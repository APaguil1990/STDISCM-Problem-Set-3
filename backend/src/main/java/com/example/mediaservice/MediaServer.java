package com.example.mediaservice;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class MediaServer {
    private final int grpcPort;
    private final int httpPort;
    private final Server grpcServer;
    private final HttpServer httpServer;
    private final MediaServiceImpl mediaService;

    public MediaServer(int grpcPort, int httpPort, int maxQueueSize, int consumerThreads) {
        this.grpcPort = grpcPort;
        this.httpPort = httpPort;
        this.mediaService = new MediaServiceImpl(maxQueueSize, consumerThreads, "./videos");
        this.grpcServer = ServerBuilder.forPort(grpcPort)
            .addService(mediaService)
            .build();
        this.httpServer = createHttpServer();
    }

    private HttpServer createHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            
            // API endpoints
            server.createContext("/api/stats", new StatsHandler());
            server.createContext("/api/videos", new VideosHandler());
            
            // Static content serving
            server.createContext("/content/", new StaticContentHandler());
            
            server.setExecutor(null);
            return server;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create HTTP server", e);
        }
    }

    public void start() throws IOException {
        grpcServer.start();
        httpServer.start();
        
        System.out.println("Media Server started:");
        System.out.println("gRPC Server on port: " + grpcPort + " (0.0.0.0)");
        System.out.println("HTTP Server on port: " + httpPort);
        System.out.println("Static content available at: http://[SERVER_IP]:" + httpPort + "/content/");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down servers...");
            MediaServer.this.stop();
            System.err.println("Servers shut down");
        }));
    }

    public void stop() {
        if (grpcServer != null) {
            grpcServer.shutdown();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private void blockUntilShutdown() throws InterruptedException {
        if (grpcServer != null) {
            grpcServer.awaitTermination();
        }
    }

    // HTTP Handlers
    class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if ("GET".equals(exchange.getRequestMethod())) {
                    // Set CORS headers
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    
                    String response = String.format(
                        "{\"queueSize\": %d, \"maxQueue\": %d, \"droppedCount\": %d}",
                        mediaService.getQueueSize(),
                        mediaService.getMaxQueueSize(),
                        mediaService.getDroppedCount()
                    );
                    
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    class VideosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if ("GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    
                    // Get actual videos from MediaService using the getter
                    List<VideoInfo> videoList = new ArrayList<>(mediaService.getVideoStore().values());
                    String response = convertVideoListToJson(videoList);
                    
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        private String convertVideoListToJson(List<VideoInfo> videos) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < videos.size(); i++) {
                VideoInfo video = videos.get(i);
                json.append(String.format(
                    "{\"id\":\"%s\",\"filename\":\"%s\",\"upload_time\":\"%s\",\"size\":%d,\"client_id\":\"%s\"}",
                    video.getId(), 
                    video.getFilename(),  // Fixed: getFilename() not getFileName()
                    video.getUploadTime(), 
                    video.getSize(),
                    video.getClientId()
                ));
                if (i < videos.size() - 1) json.append(",");
            }
            json.append("]");
            return json.toString();
        }
    }

    class StaticContentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                String filename = path.substring("/content/".length());
                
                // Serve from videos directory
                java.nio.file.Path filePath = Paths.get("./videos", filename);

                if (filename.startsWith("previews/")) {
                    String previewFilename = filename.substring("previews/".length()); 
                    filePath = Paths.get("./videos/previews", previewFilename);
                } else {
                    filePath = Paths.get("./videos", filename);
                }
                
                if (Files.exists(filePath)) {
                    exchange.getResponseHeaders().set("Content-Type", getContentType(filename));
                    exchange.sendResponseHeaders(200, Files.size(filePath));
                    Files.copy(filePath, exchange.getResponseBody());
                } else {
                    String response = "File not found: " + filename;
                    exchange.sendResponseHeaders(404, response.getBytes().length);
                    exchange.getResponseBody().write(response.getBytes());
                }
                exchange.getResponseBody().close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        private String getContentType(String filename) {
            if (filename.endsWith(".mp4")) return "video/mp4";
            if (filename.endsWith(".avi")) return "video/x-msvideo";
            if (filename.endsWith(".mov")) return "video/quicktime";
            return "application/octet-stream";
        }
    }

    public static void main(String[] args) throws Exception {
        int grpcPort = 9090;
        int httpPort = 8080;
        int maxQueueSize = 10;
        int consumerThreads = 3;

        if (args.length >= 1) grpcPort = Integer.parseInt(args[0]);
        if (args.length >= 2) httpPort = Integer.parseInt(args[1]);
        if (args.length >= 3) maxQueueSize = Integer.parseInt(args[2]);
        if (args.length >= 4) consumerThreads = Integer.parseInt(args[3]);

        MediaServer server = new MediaServer(grpcPort, httpPort, maxQueueSize, consumerThreads);
        server.start();
        server.blockUntilShutdown();
    }
}