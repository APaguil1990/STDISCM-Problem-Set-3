package com.example.mediaservice;

import io.grpc.ManagedChannel; 
import io.grpc.ManagedChannelBuilder; 
import java.io.IOException; 
import java.nio.file.Files; 
import java.nio.file.Path; 
import java.nio.file.Paths; 
import java.util.Scanner; 

public class ProducerClient {
    private final ManagedChannel channel; 
    private final MediaServiceGrpc.MediaServiceBlockingStub blockingStub; 

    public ProducerClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port) 
            .usePlaintext() 
            .build(); 
        this.blockingStub = MediaServiceGrpc.newBlockingStub(channel);
    } 

    public void uploadVideo(String filePath, String clientId) {
        try {
            Path path = Paths.get(filePath); 
            byte[] data = Files.readAllBytes(path); 
            String filename = path.getFileName().toString(); 

            VideoChunk request = VideoChunk.newBuilder() 
                .setFilename(filename) 
                .setData(com.google.protobuf.ByteString.copyFrom(data)) 
                .setClientId(clientId) 
                .build(); 

            UploadResponse response = blockingStub.uploadVideo(request); 
            System.out.println("Upload response: " + response.getStatus() + " - " + response.getMessage());
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        ProducerClient client = new ProducerClient("localhost", 9090); 
        Scanner scanner = new Scanner(System.in); 
        System.out.println("Enter client ID:"); 
        String clientId = scanner.nextLine(); 

        try {
            while (true) {
                System.out.println("Enter video file path (or 'quit' to exit):"); 
                String filePath = scanner.nextLine(); 

                if ("quit".equalsIgnoreCase(filePath)) {
                    break;
                }
                client.uploadVideo(filePath, clientId);
            }
        } catch (Exception e) {
            System.out.println("Program terminated.");
        } finally {
            scanner.close(); 
            client.channel.shutdown(); 
        }

        System.out.println("Producer client stopped.");
    }
}
