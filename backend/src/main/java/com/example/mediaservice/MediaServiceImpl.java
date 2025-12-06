package com.example.mediaservice;

import io.grpc.stub.StreamObserver;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class MediaServiceImpl extends MediaServiceGrpc.MediaServiceImplBase {
    // Changed generic type to QueuedVideo
    private final BoundedQueue<QueuedVideo> videoQueue;
    private final Map<String, VideoInfo> videoStore;
    private final Path storageDir;
    private final int consumerThreads;
    private final ExecutorService consumerExecutor;
    private final Logger logger;

    public Map<String, VideoInfo> getVideoStore() {
        return videoStore;
    }

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
                        // Changed to QueuedVideo
                        QueuedVideo videoItem = videoQueue.dequeue();
                        processVideo(videoItem, consumerId);
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
        // Changed to QueuedVideo
        QueuedVideo videoItem = new QueuedVideo(
            UUID.randomUUID().toString(),
            request.getFilename(),
            request.getClientId(),
            request.getData().toByteArray()
        );
        String videoId = UUID.randomUUID().toString();
        String processedFilename = videoId + "_" + request.getFilename();

        try {
            VideoMetadata metadata = new VideoMetadata(
                UUID.randomUUID().toString(),
                request.getFilename(),
                request.getClientId(),
                request.getData().toByteArray()
            );

        boolean queued = videoQueue.enqueue(videoItem);

        if (queued) {
            responseObserver.onNext(UploadResponse.newBuilder()
                .setStatus("QUEUED")
                .setMessage("Video added to queue")
                .setVideoId(videoItem.getId())
                .build());
            logger.info("Video queued: " + videoItem.getFilename());
        } else {
            responseObserver.onNext(UploadResponse.newBuilder()
                .setStatus("DROPPED")
                .setMessage("Queue is full")
            boolean queued = videoQueue.enqueue(metadata);

            if (queued) {
                // Send response BEFORE processing video
                responseObserver.onNext(UploadResponse.newBuilder()
                    .setStatus("QUEUED")
                    .setMessage("Video added to queue")
                    .setVideoId(videoId)
                    .build());
                responseObserver.onCompleted();
                logger.info("Video queued: " + request.getFilename() + " from client: " + request.getClientId());
            } else {
                // Queue is full - send immediate response
                responseObserver.onNext(UploadResponse.newBuilder()
                    .setStatus("DROPPED")
                    .setMessage("Queue is full")
                    .build());
                responseObserver.onCompleted();
                logger.info("Video dropped (queue full): " + request.getFilename());
            }
        } catch (Exception e) {
            // Send error response if something goes wrong
            responseObserver.onNext(UploadResponse.newBuilder()
                .setStatus("ERROR")
                .setMessage("Server error: " + e.getMessage())
                .build());
            logger.info("Video dropped (queue full): " + videoItem.getFilename());
        }
        responseObserver.onCompleted();
            responseObserver.onCompleted();
            logger.severe("Error processing uploads " + e.getMessage());
        }
    }

    // Changed parameter to QueuedVideo
    private void processVideo(QueuedVideo videoItem, int consumerId) {
        try {
            // Handle filename collisions
            String safeFilename = getSafeFilename(videoItem.getFilename());
            Path filePath = storageDir.resolve(safeFilename);

            // Write video file
            Files.write(filePath, videoItem.getData());

            // 1. Generate preview
            generatePreview(filePath, consumerId);

            // 2. Compress Video (Bonus Feature)
            compressVideo(filePath, consumerId);
            long compressedSize = 0;
            try {
                Path compressedPath = storageDir.resolve("compressed_" + safeFilename);
                if (Files.exists(compressedPath)) {
                    compressedSize = Files.size(compressedPath);
                }
            } catch (IOException e) {
                logger.warning("Could not determine compressed size");
            }
            // Store metadata
            VideoInfo videoInfo = VideoInfo.newBuilder()
                .setId(videoItem.getId())
                .setFilename(safeFilename)
                .setUploadTime(new Date().toString())
                .setSize(videoItem.getData().length)
                .setClientId(videoItem.getClientId())
                .setCompressedSize(compressedSize)
                .build();

            videoStore.put(videoItem.getId(), videoInfo);
            logger.info("Consumer " + consumerId + " processed: " + safeFilename);

        } catch (Exception e) {
            logger.warning("Consumer " + consumerId + " failed to process: " + videoItem.getFilename() + " - " + e.getMessage());
        }
    }

    private void compressVideo(Path inputPath, int consumerId) {
        try {
            String filename = inputPath.getFileName().toString();
            String compressedFilename = "compressed_" + filename;
            Path compressedPath = storageDir.resolve(compressedFilename);

            List<String> ffmpegCommand = new ArrayList<>();
            ffmpegCommand.add("ffmpeg");
            ffmpegCommand.add("-i");
            ffmpegCommand.add(inputPath.toAbsolutePath().toString());
            ffmpegCommand.add("-vcodec");
            ffmpegCommand.add("libx264");
            ffmpegCommand.add("-crf");
            ffmpegCommand.add("28");
            ffmpegCommand.add("-preset");
            ffmpegCommand.add("fast");
            ffmpegCommand.add("-y");
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
                logger.warning("Consumer " + consumerId + " Compression failed with code " + exitCode);
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

            Files.createDirectories(previewsDir);

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
                logger.warning("Consumer " + consumerId + " FFmpeg preview failed: " + filename);
                createFallbackPreview(videoPath, previewPath, consumerId);
            }
        } catch (Exception e) {
            logger.warning("Consumer " + consumerId + " preview generation failed: " + e.getMessage());
        }
    }

    private void createFallbackPreview(Path videoPath, Path previewPath, int consumerId) {
        try {
            Files.copy(videoPath, previewPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Consumer " + consumerId + " created fallback preview");
        } catch (IOException e) {
            logger.warning("Consumer " + consumerId + " fallback preview failed: " + e.getMessage());
        }
    }

    public int getQueueSize() { return videoQueue.size(); }
    public int getMaxQueueSize() { return videoQueue.getCapacity(); }
    public int getDroppedCount() { return videoQueue.getDroppedCount(); }

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

// Renamed class to avoid collision with Protobuf generated class
class QueuedVideo {
    private final String id;
    private final String filename;
    private final String clientId;
    private final byte[] data;

    public QueuedVideo(String id, String filename, String clientId, byte[] data) {
        this.id = id;
        this.filename = filename;
        this.clientId = clientId;
        this.data = data;
    }

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getClientId() { return clientId; }
    public byte[] getData() { return data; }
}