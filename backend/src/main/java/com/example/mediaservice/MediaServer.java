package com.example.mediaservice;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
                    .maxInboundMessageSize(50 * 1024 * 1024) // Allow 50MB uploads
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
                    
                    // Get videos and filter out previews ONLY
                    List<VideoInfo> videoList = new ArrayList<>(mediaService.getVideoStore().values());
                    
                    // Remove only previews (keep compressed videos)
                    videoList.removeIf(video -> 
                        video.getFilename().contains("_preview")
                        // REMOVE this line: || video.getFilename().startsWith("compressed_")
                    );
                    
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
                
                // Get compressed size, but if it's 0, check if there's a compressed file
                long compressedSize = video.getCompressedSize();
                if (compressedSize == 0) {
                    // Check if compressed file exists
                    String compressedFilename = "compressed_" + video.getFilename();
                    Path compressedPath = Paths.get("./videos", compressedFilename);
                    if (Files.exists(compressedPath)) {
                        try {
                            compressedSize = Files.size(compressedPath);
                        } catch (IOException e) {
                            // Keep as 0
                        }
                    }
                }
                
                String jsonEntry = String.format(
                    "{\"id\":\"%s\",\"filename\":\"%s\",\"upload_time\":\"%s\",\"size\":%d,\"client_id\":\"%s\",\"compressed_size\":%d}",
                    video.getId(),
                    video.getFilename(),
                    video.getUploadTime(),
                    video.getSize(),
                    video.getClientId(),
                    compressedSize  // Use actual compressed size
                );
                
                json.append(jsonEntry);
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
                
                // Allow three paths now
                if (!path.startsWith("/content/videos/") && 
                    !path.startsWith("/content/previews/") &&
                    !path.startsWith("/content/compressed/")) {
                    String response = "Invalid path. Use /content/videos/, /content/previews/, or /content/compressed/";
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.close();
                    return;
                }
                
                String filename = path.replace("/content/videos/", "")
                                    .replace("/content/previews/", "")
                                    .replace("/content/compressed/", "");
                
                java.nio.file.Path filePath;
                
                if (path.startsWith("/content/previews/")) {
                    // Serve previews
                    filePath = Paths.get("./videos/previews", filename);
                } else if (path.startsWith("/content/compressed/")) {
                    // Serve compressed videos
                    filePath = Paths.get("./videos", filename);
                    // Don't block compressed files anymore
                } else {
                    // Serve regular videos
                    filePath = Paths.get("./videos", filename);
                    
                    // Only block previews from main videos directory
                    if (filename.contains("_preview")) {
                        String response = "Access denied - this is a preview file";
                        exchange.getResponseHeaders().set("Content-Type", "text/plain");
                        exchange.sendResponseHeaders(403, response.getBytes().length);
                        exchange.getResponseBody().write(response.getBytes());
                        exchange.close();
                        return;
                    }
                }
                
                // Rest of the method remains the same...
                if (Files.exists(filePath)) {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Content-Type", getContentType(filename));
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    
                    long fileSize = Files.size(filePath);
                    exchange.sendResponseHeaders(200, fileSize);
                    
                    try (OutputStream os = exchange.getResponseBody();
                        InputStream is = Files.newInputStream(filePath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                } else {
                    String response = "File not found: " + filename;
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(404, response.getBytes().length);
                    exchange.getResponseBody().write(response.getBytes());
                }
            } catch (IOException e) {
                System.err.println("Error serving static content: " + e.getMessage());
            } finally {
                exchange.close();
            }
        }
    
        
        private String getContentType(String filename) {
            if (filename.endsWith(".mp4")) return "video/mp4";
            if (filename.endsWith(".avi")) return "video/x-msvideo";
            if (filename.endsWith(".mov")) return "video/quicktime";
            if (filename.endsWith(".mkv")) return "video/x-matroska";
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