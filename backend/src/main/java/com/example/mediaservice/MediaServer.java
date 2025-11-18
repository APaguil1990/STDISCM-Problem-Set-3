package com.example.mediaservice;

import io.grpc.Server; 
import io.grpc.ServerBuilder; 
import java.io.IOException;

public class MediaServer {
    private final int port; 
    private final Server server; 

    public MediaServer(int port, int maxQueueSize, int consumerThreads) {
        this.port = port; 
        this.server = ServerBuilder.forPort(port)
            .addService(new MediaServiceImpl(maxQueueSize, consumerThreads, "./videos")) 
            .build();
    } 

    public void start() throws IOException {
        server.start(); 
        System.out.println("Media Server started on port " + port); 

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down gRPC server"); 
            MediaServer.this.stop(); 
            System.err.println("Server shut down");
        }));
    } 

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    } 

    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    } 

    public static void main(String[] args) throws Exception {
        int port = 9090; 
        int maxQueueSize = 10; 
        int consumerThreads = 3; 

        if (args.length >= 1) {
            port = Integer.parseInt(args[0]); 
        } 
        if (args.length >= 2) {
            maxQueueSize = Integer.parseInt(args[1]); 
        } 
        if (args.length >= 3) {
            consumerThreads = Integer.parseInt(args[2]);
        }

        MediaServer server = new MediaServer(port, maxQueueSize, consumerThreads); 
        server.start(); 
        server.blockUntilShutdown();
    }
}
