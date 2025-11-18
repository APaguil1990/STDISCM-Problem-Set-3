package com.example.mediaservice; 

import io.grpc.stub.StreamObserver; 
import java.io.IOException; 
import java.nio.file.Files; 
import java.nio.file.Path; 
import java.nio.file.Paths; 
import java.util.*; 
import java.util.concurrent.*; 

public class MediaServiceImpl extends MediaServiceGrpc.MediaServiceImplBase {
    private final BlockingQueue<VideoChunk> videoQueue; 
    private final Map<String, VideoInfo> videoStore = new ConcurrentHashMap<>(); 
    private final Path storageDir; 
    private final int maxQueueSize; 
    private final int consumerThreads; 

    public MediaServiceImpl(int maxQueueSize, int consumerThreads, String storagePath) {
        this.maxQueueSize = maxQueueSize; 
        this.consumerThreads = consumerThreads; 
        this.videoQueue = new ArrayBlockingQueue<>(maxQueueSize); 
        this.storageDir = Paths.get(storagePath); 

        try {
            Files.createDirectories(storageDir); 
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Start consumer threads 
        startConsumers(); 
    } 

    private void startConsumers() {
        for (int i = 0; i < consumerThreads; i++) {
            new Thread(() -> {
                while (true) {
                    try {
                        VideoChunk chunk = videoQueue.take(); 
                        processVideo(chunk);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); 
                        break;
                    }
                }
            }).start();
        }
    }

    @Override 
    public void uploadVideo(VideoChunk request, StreamObserver<UploadResponse> responseObserver) {
        if (videoQueue.size() >= maxQueueSize) {
            responseObserver.onNext(UploadResponse.newBuilder()
                .setStatus("DROPPED")
                .setMessage("Queue is full") 
                .build()); 
                responseObserver.onCompleted(); 
                return;
        }

        String videoId = UUID.randomUUID().toString(); 
        VideoChunk enhancedChunk = VideoChunk.newBuilder(request)
            .setFilename(videoId + "_" + request.getFilename()) 
            .build(); 

        boolean offered = videoQueue.offer(enhancedChunk); 

        if (offered) {
            responseObserver.onNext(UploadResponse.newBuilder() 
                .setStatus("QUEUED") 
                .setMessage("Video added to queue") 
                .setVideoId(videoId) 
                .build());
        } else {
            responseObserver.onNext(UploadResponse.newBuilder() 
                .setStatus("DROPPED") 
                .setMessage("Queue is full") 
                .build());
        } 
        responseObserver.onCompleted();
    } 

    private void processVideo(VideoChunk chunk) {
        try {
            Path filePath = storageDir.resolve(chunk.getFilename()); 
            Files.write(filePath, chunk.getData().toByteArray()); 

            VideoInfo videoInfo = VideoInfo.newBuilder() 
                .setId(chunk.getFilename()) 
                .setFilename(chunk.getFilename()) 
                .setUploadTime(new Date().toString()) 
                .setSize(chunk.getData().size()) 
                .build();
            
            videoStore.put(chunk.getFilename(), videoInfo); 
            System.out.println("Processed video: " + chunk.getFilename());
        } catch (IOException e) {
            e.printStackTrace();
        }
    } 

    @Override
    public void getVideoList(Empty request, StreamObserver<VideoList> responseObserver) {
        VideoList.Builder videoList = VideoList.newBuilder();
        videoStore.values().forEach(videoList::addVideos);
        responseObserver.onNext(videoList.build());
        responseObserver.onCompleted();
    }

    @Override 
    public void getVideo(VideoRequest request, StreamObserver<VideoResponse> responseObserver) {
        try {
            VideoInfo videoInfo = videoStore.get(request.getVideoId()); 
            if (videoInfo != null) {
                Path filePath = storageDir.resolve(request.getVideoId()); 
                byte[] data = Files.readAllBytes(filePath); 

                responseObserver.onNext(VideoResponse.newBuilder() 
                    .setFilename(videoInfo.getFilename())
                    .setData(com.google.protobuf.ByteString.copyFrom(data)) 
                    .setStatus("SUCCESS")
                    .build());
            } else {
                responseObserver.onNext(VideoResponse.newBuilder() 
                    .setStatus("NOT_FOUND") 
                    .build());
            }
        } catch (IOException e) {
            responseObserver.onNext(VideoResponse.newBuilder() 
                .setStatus("ERROR") 
                .build());
        } 
        responseObserver.onCompleted();
    }
}