package com.example.mediaservice;

import io.grpc.stub.StreamObserver;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class MediaServiceImpl extends MediaServiceGrpc.MediaServiceImplBase {
    private final BoundedQueue<VideoMetadata> videoQueue;
    private final Map<String, VideoInfo> videoStore;
    private final Path storageDir;
    private final int consumerThreads;
    private final ExecutorService consumerExecutor;
    private final Logger logger; 

    public Map<String, VideoInfo> getVideoStore() {
        return videoStore; 
    } 

    // public int getQueueSize() {
    //     return videoQueue.size();
    // } 

    // public int getMaxQueueSize() {
    //     return videoQueue.getCapacity(); 
    // } 

    // public int getDroppedCount() {
    //     return videoQueue.getDroppedCount();
    // }

    public MediaServiceImpl(int maxQueueSize, int consumerThreads, String storagePath) {
        this.videoQueue = new BoundedQueue<>(maxQueueSize);
        this.videoStore = new ConcurrentHashMap<>();
        this.consumerThreads = consumerThreads;
        this.storageDir = Paths.get(storagePath);
        this.consumerExecutor = Executors.newFixedThreadPool(consumerThreads);

        // Setup logging
        this.logger = setupLogger();

        try {
            Files.createDirectories(storageDir);
            Files.createDirectories(storageDir.resolve("previews"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        startConsumers();
        setupShutdownHook();
    }

    private Logger setupLogger() {
        try {
            Logger logger = Logger.getLogger("MediaServiceImpl");
            FileHandler fileHandler = new FileHandler("consumer_log.txt");
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
            return logger;
        } catch (IOException e) {
            return Logger.getGlobal();
        }
    }

    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown initiated - closing resources");
            consumerExecutor.shutdown();
            try {
                if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    consumerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                consumerExecutor.shutdownNow();
            }
            logger.info("Shutdown completed");
        }));
    }

    private void startConsumers() {
        for (int i = 0; i < consumerThreads; i++) {
            final int consumerId = i;
            consumerExecutor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        VideoMetadata metadata = videoQueue.dequeue();
                        processVideo(metadata, consumerId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        logger.warning("Consumer " + consumerId + " error: " + e.getMessage());
                    }
                }
            });
        }
    }

    @Override
    public void uploadVideo(VideoChunk request, StreamObserver<UploadResponse> responseObserver) {
        VideoMetadata metadata = new VideoMetadata(
            UUID.randomUUID().toString(),
            request.getFilename(),
            request.getClientId(),
            request.getData().toByteArray()
        );

        boolean queued = videoQueue.enqueue(metadata);
        
        if (queued) {
            responseObserver.onNext(UploadResponse.newBuilder()
                .setStatus("QUEUED")
                .setMessage("Video added to queue")
                .setVideoId(metadata.getId())
                .build());
            logger.info("Video queued: " + metadata.getFilename());
        } else {
            responseObserver.onNext(UploadResponse.newBuilder()
                .setStatus("DROPPED")
                .setMessage("Queue is full")
                .build());
            logger.info("Video dropped (queue full): " + metadata.getFilename());
        }
        responseObserver.onCompleted();
    }

    private void processVideo(VideoMetadata metadata, int consumerId) {
        try {
            // Handle filename collisions
            String safeFilename = getSafeFilename(metadata.getFilename());
            Path filePath = storageDir.resolve(safeFilename);
            
            // Write video file
            Files.write(filePath, metadata.getData());
            
            // Generate preview using FFmpeg
            generatePreview(filePath, consumerId);
            compressVideo(filePath, consumerId);

            // Store metadata
            VideoInfo videoInfo = VideoInfo.newBuilder()
                .setId(metadata.getId())
                .setFilename(safeFilename)
                .setUploadTime(new Date().toString())
                .setSize(metadata.getData().length)
                .setClientId(metadata.getClientId())
                .build();
            
            videoStore.put(metadata.getId(), videoInfo);
            logger.info("Consumer " + consumerId + " processed: " + safeFilename);
            
        } catch (Exception e) {
            logger.warning("Consumer " + consumerId + " failed to process: " + metadata.getFilename() + " - " + e.getMessage());
        }
    }


    private void compressVideo(Path inputPath, int consumerId) {
        try {
            String filename = inputPath.getFileName().toString();
            // Create a filename for compressed version
            String compressedName = "compressed_" + filename;
            Path compressedPath = storageDir.resolve(compressedName);

           List<String> ffmpegCommand = new ArrayList<>();
                   ffmpegCommand.add("ffmpeg");
                   ffmpegCommand.add("-i");
                   ffmpegCommand.add(inputPath.toAbsolutePath().toString());
                   ffmpegCommand.add("-vcodec");
                   ffmpegCommand.add("libx264");
                   ffmpegCommand.add("-crf");
                   ffmpegCommand.add("28"); // Higher number = more compression (lower quality)
                   ffmpegCommand.add("-preset");
                   ffmpegCommand.add("fast"); // Compress fast
                   ffmpegCommand.add("-y");   // Overwrite if file exists
                   ffmpegCommand.add(compressedPath.toAbsolutePath().toString());

           ProcessBuilder pb = new ProcessBuilder(ffmpegCommand);
           pb.redirectErrorStream(true);

           Process process = pb.start();

           BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
           StringBuilder output = new StringBuilder();
           String line;
           while ((line = reader.readLine()) != null) {
               output.append(line).append("\n");
           }

           int exitCode = process.waitFor();

           if (exitCode == 0) {
               logger.info("Consumer " + consumerId + " compressed video: " + compressedFilename);
           } else {
               logger.warning("Consumer " + consumerId + " Compression failed with code " + exitCode + "\nOutput: " + output.toString());
           }
        } catch (Exception e) {
            logger.warning("Consumer " + consumerId + " compression exception: " + e.getMessage());
        }
    }
    private String getSafeFilename(String filename) {
        String baseName = filename.replaceAll("[^a-zA-Z0-9.-]", "_");
        Path filePath = storageDir.resolve(baseName);
        
        if (!Files.exists(filePath)) {
            return baseName;
        }
        
        // Handle collisions
        String nameWithoutExt = baseName.substring(0, baseName.lastIndexOf('.'));
        String extension = baseName.substring(baseName.lastIndexOf('.'));
        int counter = 1;
        
        while (Files.exists(storageDir.resolve(nameWithoutExt + "(" + counter + ")" + extension))) {
            counter++;
        }
        
        return nameWithoutExt + "(" + counter + ")" + extension;
    }

    private void generatePreview(Path videoPath, int consumerId) {
        try {
            String filename = videoPath.getFileName().toString();
            String previewName = filename.substring(0, filename.lastIndexOf('.')) + "_preview.mp4";
            Path previewsDir = storageDir.resolve("previews");
            Path previewPath = storageDir.resolve("previews").resolve(previewName); 

            // Ensure previews directory exists 
            Files.createDirectories(previewsDir);

            // FFmpeg comand to generate 10-second preview 
            List<String> ffmpegCommand = new ArrayList<>(); 
            ffmpegCommand.add("ffmpeg");
            ffmpegCommand.add("-i");
            ffmpegCommand.add(videoPath.toAbsolutePath().toString());
            ffmpegCommand.add("-ss");
            ffmpegCommand.add("00:00:00");
            ffmpegCommand.add("-t");
            ffmpegCommand.add("10");
            ffmpegCommand.add("-c:v");
            ffmpegCommand.add("libx264");
            ffmpegCommand.add("-c:a");
            ffmpegCommand.add("aac");
            ffmpegCommand.add("-y"); 
            ffmpegCommand.add(previewPath.toAbsolutePath().toString()); 

            ProcessBuilder pb = new ProcessBuilder(ffmpegCommand); 
            pb.redirectErrorStream(true); 

            Process process = pb.start(); 

            // Read process output for dubug 
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())); 
            StringBuilder output = new StringBuilder(); 
            String line; 
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            } 

            int exitCode = process.waitFor(); 

            if (exitCode == 0) {
                logger.info("Consumer " + consumerId + " generated preview: " + previewName);
            } else {
                logger.warning("Consumer " + consumerId + " FFmpeg failed for: " + filename + " with exit code " + exitCode + "\nOutput: " + output.toString());
                createFallbackPreview(videoPath, previewPath, consumerId);
            }
            
            // // FFmpeg command to generate 10-second preview
            // ProcessBuilder pb = new ProcessBuilder(
            //     "ffmpeg", "-i", videoPath.toString(),
            //     "-ss", "0", "-t", "10", // 10-second preview from start
            //     "-c", "copy", // Copy streams without re-encoding if possible
            //     previewPath.toString(),
            //     "-y" // Overwrite output file
            // );
            
            // Process process = pb.start();
            // int exitCode = process.waitFor();
            
            // if (exitCode == 0) {
            //     logger.info("Consumer " + consumerId + " generated preview: " + previewName);
            // } else {
            //     logger.warning("Consumer " + consumerId + " FFmpeg failed for: " + filename);
            // }
            
        } catch (Exception e) {
            logger.warning("Consumer " + consumerId + " preview generation failed: " + e.getMessage());
        }
    }

    private void createFallbackPreview(Path videoPath, Path previewPath, int consumerId) {
        try {
            Files.copy(videoPath, previewPath, StandardCopyOption.REPLACE_EXISTING); 
            logger.info("Consumer " + consumerId + " created fallback preview: " + previewPath.getFileName());
        } catch (IOException e) {
            logger.warning("Consumer " + consumerId + " fallback preview also failed: " + e.getMessage());
        }
    }

    // Add getters for queue stats
    public int getQueueSize() { return videoQueue.size(); }
    public int getMaxQueueSize() { return videoQueue.getCapacity(); }
    public int getDroppedCount() { return videoQueue.getDroppedCount(); }

    // Rest of existing methods (getVideoList, getVideo) remain the same
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
                Path filePath = storageDir.resolve(videoInfo.getFilename()); 
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

// Add this class for video metadata
class VideoMetadata {
    private final String id;
    private final String filename;
    private final String clientId;
    private final byte[] data;
    
    public VideoMetadata(String id, String filename, String clientId, byte[] data) {
        this.id = id;
        this.filename = filename;
        this.clientId = clientId;
        this.data = data;
    }
    
    // Getters
    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getClientId() { return clientId; }
    public byte[] getData() { return data; }
}