package com.example.mediaservice;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.*;
import java.nio.file.*;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.*;
import java.util.Properties;

public class ProducerClient {
    private final ManagedChannel channel;
    private final Logger logger;
    private final String clientId;
    private final ExecutorService threadPool;
    private final int streamingChunkSize;
    private final long fileStabilityCheckInterval;
    private final long fileStabilityCheckDuration;
    private final Set<Path> processedFiles;
    private final Set<Path> filesInProgress;
    
    // Robustness enhancements
    private final AtomicBoolean isShuttingDown;
    private final AtomicInteger activeUploads;
    private final CountDownLatch shutdownLatch;
    private final String host;
    private final int port;
    private volatile MediaServiceGrpc.MediaServiceBlockingStub blockingStub;
    private volatile MediaServiceGrpc.MediaServiceStub asyncStub;
    
    // Configuration values
    private final int maxRetryAttempts;
    private final long retryBackoffInitialMs;
    private final double retryBackoffMultiplier;
    private final long retryBackoffMaxMs;
    private final long gracefulShutdownTimeoutSeconds;
    private final long forceShutdownTimeoutSeconds;
    private final int queueCheckRetryMaxAttempts;
    private final long queueCheckRetryDelayMs;
    private final long queueWaitTimeoutSeconds;
    private final long reconnectBackoffInitialMs;
    private final long reconnectBackoffMaxMs;
    
    // Metrics
    private final AtomicLong uploadSuccessCount;
    private final AtomicLong uploadFailureCount;
    private final AtomicLong retryCount;
    private final AtomicInteger connectionStateChanges;
    
    // WatchService recovery
    private final Map<String, Thread> watchThreads;
    private final Map<String, AtomicBoolean> watchActiveFlags;

    public ProducerClient(String host, int port, String clientId, int producerThreads) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.threadPool = Executors.newFixedThreadPool(producerThreads);
        this.processedFiles = ConcurrentHashMap.newKeySet();
        this.filesInProgress = ConcurrentHashMap.newKeySet();
        this.isShuttingDown = new AtomicBoolean(false);
        this.activeUploads = new AtomicInteger(0);
        this.shutdownLatch = new CountDownLatch(1);
        this.watchThreads = new ConcurrentHashMap<>();
        this.watchActiveFlags = new ConcurrentHashMap<>();
        
        // Load configuration
        Properties config = loadConfig();
        this.streamingChunkSize = Integer.parseInt(config.getProperty("streaming.chunk.size", "1048576"));
        this.fileStabilityCheckInterval = Long.parseLong(config.getProperty("file.stability.check.interval", "500"));
        this.fileStabilityCheckDuration = Long.parseLong(config.getProperty("file.stability.check.duration", "2000"));
        
        // Retry configuration
        this.maxRetryAttempts = Integer.parseInt(config.getProperty("upload.retry.max.attempts", "3"));
        this.retryBackoffInitialMs = Long.parseLong(config.getProperty("upload.retry.backoff.initial.ms", "1000"));
        this.retryBackoffMultiplier = Double.parseDouble(config.getProperty("upload.retry.backoff.multiplier", "2.0"));
        this.retryBackoffMaxMs = Long.parseLong(config.getProperty("upload.retry.backoff.max.ms", "30000"));
        
        // Shutdown configuration
        this.gracefulShutdownTimeoutSeconds = Long.parseLong(config.getProperty("shutdown.graceful.timeout.seconds", "300"));
        this.forceShutdownTimeoutSeconds = Long.parseLong(config.getProperty("shutdown.force.timeout.seconds", "30"));
        
        // Queue check configuration
        this.queueCheckRetryMaxAttempts = Integer.parseInt(config.getProperty("queue.check.retry.max.attempts", "5"));
        this.queueCheckRetryDelayMs = Long.parseLong(config.getProperty("queue.check.retry.delay.ms", "1000"));
        this.queueWaitTimeoutSeconds = Long.parseLong(config.getProperty("queue.wait.timeout.seconds", "300"));
        
        // Connection configuration
        this.reconnectBackoffInitialMs = Long.parseLong(config.getProperty("grpc.reconnect.backoff.initial.ms", "1000"));
        this.reconnectBackoffMaxMs = Long.parseLong(config.getProperty("grpc.reconnect.backoff.max.ms", "60000"));
        
        // Metrics initialization
        this.uploadSuccessCount = new AtomicLong(0);
        this.uploadFailureCount = new AtomicLong(0);
        this.retryCount = new AtomicLong(0);
        this.connectionStateChanges = new AtomicInteger(0);
        
        // Setup file logging
        this.logger = setupLogger(clientId);
        
        // Build channel with enhanced configuration
        this.channel = buildManagedChannel(host, port, config);
        this.blockingStub = MediaServiceGrpc.newBlockingStub(channel);
        this.asyncStub = MediaServiceGrpc.newStub(channel);
        
        // Setup channel state monitoring
        setupChannelStateMonitoring();
        
        // Setup graceful shutdown hook
        setupShutdownHook();
        
        logger.info("ProducerClient initialized: " + clientId + " connecting to " + host + ":" + port);
    }

    private Properties loadConfig() {
        Properties config = new Properties();
        try {
            String configPath = "./producer-config.properties";
            if (Files.exists(Paths.get(configPath))) {
                try (FileInputStream fis = new FileInputStream(configPath)) {
                    config.load(fis);
                }
            }
        } catch (Exception e) {
            // Use defaults if config file not found
        }
        return config;
    }

    private Logger setupLogger(String clientId) {
        try {
            Logger logger = Logger.getLogger("ProducerClient-" + clientId);
            FileHandler fileHandler = new FileHandler("producer_" + clientId + "_log.txt");
            fileHandler.setFormatter(new SimpleFormatter() {
                @Override
                public String format(LogRecord record) {
                    return String.format("[%1$tF %1$tT] [%2$s] %3$s %4$s%n",
                            new Date(record.getMillis()),
                            Thread.currentThread().getName(),
                            record.getMessage(),
                            record.getThrown() != null ? " - Error: " + record.getThrown().getMessage() : "");
                }
            });
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
            return logger;
        } catch (IOException e) {
            System.err.println("Failed to setup logger: " + e.getMessage());
            return Logger.getGlobal();
        }
    }
    
    private ManagedChannel buildManagedChannel(String host, int port, Properties config) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(100 * 1024 * 1024);
        
        // Configure keepalive if specified
        try {
            long keepaliveTime = Long.parseLong(config.getProperty("grpc.keepalive.time.sec", "30"));
            long keepaliveTimeout = Long.parseLong(config.getProperty("grpc.keepalive.timeout.sec", "5"));
            boolean keepaliveWithoutCalls = Boolean.parseBoolean(config.getProperty("grpc.keepalive.without.calls.enabled", "true"));
            
            builder.keepAliveTime(keepaliveTime, TimeUnit.SECONDS)
                   .keepAliveTimeout(keepaliveTimeout, TimeUnit.SECONDS)
                   .keepAliveWithoutCalls(keepaliveWithoutCalls);
        } catch (Exception e) {
            logger.warning("Failed to configure keepalive settings: " + e.getMessage());
        }
        
        return builder.build();
    }
    
    private void setupChannelStateMonitoring() {
        // Monitor channel state changes in a background thread
        Thread monitorThread = new Thread(() -> {
            ConnectivityState lastState = null;
            while (!isShuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    ConnectivityState currentState = channel.getState(false);
                    if (lastState != null && !currentState.equals(lastState)) {
                        connectionStateChanges.incrementAndGet();
                        logger.info("Channel state changed from " + lastState + " to " + currentState);
                        
                        if (currentState == ConnectivityState.TRANSIENT_FAILURE || 
                            currentState == ConnectivityState.SHUTDOWN) {
                            logger.warning("Channel in failure state: " + currentState + ". Attempting recovery...");
                            attemptReconnection();
                        }
                    }
                    lastState = currentState;
                    Thread.sleep(5000); // Check every 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("Error monitoring channel state: " + e.getMessage());
                }
            }
        }, "ChannelMonitor-" + clientId);
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
    
    private void attemptReconnection() {
        logger.info("Attempting to reconnect channel...");
        // gRPC channel should automatically reconnect, but we can trigger it
        if (channel.getState(false) == ConnectivityState.TRANSIENT_FAILURE) {
            channel.resetConnectBackoff();
        }
    }
    
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook triggered - initiating graceful shutdown");
            shutdown();
        }, "ShutdownHook-" + clientId));
    }
    
    private void waitForQueueAvailable(String filename, long timeoutSeconds) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;
        int attempt = 0;
        
        while (!isShuttingDown.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new InterruptedException("Timeout waiting for queue to become available");
            }
            
            boolean success = false;
            for (int i = 0; i < queueCheckRetryMaxAttempts; i++) {
                try {
                    QueueStatus status = blockingStub.getQueueStatus(Empty.getDefaultInstance());
                    if (!status.getIsFull()) {
                        success = true;
                        break;
                    }
                    logger.info("Queue is full (" + status.getCurrentSize() + "/" + status.getMaxCapacity() + 
                               "). Waiting to upload: " + filename);
                } catch (Exception e) {
                    logger.warning("Failed to check queue status (attempt " + (i + 1) + "/" + 
                                 queueCheckRetryMaxAttempts + "): " + e.getMessage());
                    if (i < queueCheckRetryMaxAttempts - 1) {
                        Thread.sleep(queueCheckRetryDelayMs);
                    }
                }
            }
            
            if (success) {
                break;
            }
            
            attempt++;
            long backoffMs = Math.min(retryBackoffInitialMs * (long)Math.pow(retryBackoffMultiplier, attempt), retryBackoffMaxMs);
            Thread.sleep(Math.min(backoffMs, 2000)); // Cap at 2 seconds for queue checks
        }
    }
    
    private <T> T executeWithRetry(String operationName, java.util.function.Supplier<T> operation, 
                                   java.util.function.Predicate<T> isSuccess) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < maxRetryAttempts && !isShuttingDown.get()) {
            try {
                T result = operation.get();
                if (isSuccess == null || isSuccess.test(result)) {
                    if (attempt > 0) {
                        logger.info(operationName + " succeeded after " + attempt + " retry attempts");
                    }
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
                retryCount.incrementAndGet();
                attempt++;
                
                if (attempt < maxRetryAttempts && isRetryableError(e)) {
                    long backoffMs = calculateBackoff(attempt);
                    logger.warning(operationName + " failed (attempt " + attempt + "/" + maxRetryAttempts + 
                                 "): " + e.getMessage() + ". Retrying in " + backoffMs + "ms");
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Operation interrupted", ie);
                    }
                } else {
                    break;
                }
            }
        }
        
        uploadFailureCount.incrementAndGet();
        if (lastException != null) {
            logger.severe(operationName + " failed after " + attempt + " attempts: " + lastException.getMessage());
        }
        return null;
    }
    
    private boolean isRetryableError(Exception e) {
        if (e instanceof StatusRuntimeException) {
            StatusRuntimeException sre = (StatusRuntimeException) e;
            Status.Code code = sre.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || 
                   code == Status.Code.DEADLINE_EXCEEDED ||
                   code == Status.Code.RESOURCE_EXHAUSTED ||
                   code == Status.Code.INTERNAL;
        }
        return e instanceof IOException || e instanceof InterruptedException;
    }
    
    private long calculateBackoff(int attempt) {
        long backoff = (long)(retryBackoffInitialMs * Math.pow(retryBackoffMultiplier, attempt - 1));
        return Math.min(backoff, retryBackoffMaxMs);
    }

    public void uploadVideo(String filePath) {
        if (isShuttingDown.get()) {
            logger.warning("Shutdown in progress, skipping upload: " + filePath);
            return;
        }
        
        threadPool.submit(() -> {
            activeUploads.incrementAndGet();
            try {
                String filename = Paths.get(filePath).getFileName().toString();
                
                // Wait for queue to become available with timeout
                try {
                    waitForQueueAvailable(filename, queueWaitTimeoutSeconds);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warning("Interrupted while waiting for queue: " + filename);
                    return;
                }
                
                if (isShuttingDown.get()) {
                    logger.warning("Shutdown in progress, aborting upload: " + filename);
                    return;
                }
                
                logger.info("File: " + filename + " Status: Uploading");
                
                // Use retry wrapper for upload
                UploadResponse response = executeWithRetry(
                    "Upload " + filename,
                    () -> {
                        try (FileInputStream fis = new FileInputStream(filePath); 
                             BufferedInputStream bis = new BufferedInputStream(fis)) {
                            byte[] buffer = new byte[1024 * 1024]; // 1MB chunks
                            int bytesRead;
                            ByteArrayOutputStream fileData = new ByteArrayOutputStream();
                            
                            while ((bytesRead = bis.read(buffer)) != -1) {
                                fileData.write(buffer, 0, bytesRead);
                            }
                            
                            VideoChunk request = VideoChunk.newBuilder()
                                .setFilename(filename)
                                .setData(com.google.protobuf.ByteString.copyFrom(fileData.toByteArray()))
                                .setClientId(clientId)
                                .build();
                            
                            return blockingStub
                                .withDeadlineAfter(60, TimeUnit.SECONDS)
                                .uploadVideo(request);
                        }
                    },
                    (r) -> r != null && ("QUEUED".equals(r.getStatus()) || "DROPPED".equals(r.getStatus()))
                );
                
                if (response != null) {
                    String status = response.getStatus();
                    
                    if ("QUEUED".equals(status)) {
                        uploadSuccessCount.incrementAndGet();
                        logger.info("File: " + filename + " Status: Successfully queued (ID: " + response.getVideoId() + ")");
                    } else if ("DROPPED".equals(status)) {
                        logger.warning("File: " + filename + " Status: Dropped - " + response.getMessage());
                    } else if ("ERROR".equals(status)) {
                        uploadFailureCount.incrementAndGet();
                        logger.warning("File: " + filename + " Status: Server error - " + response.getMessage());
                    } else {
                        logger.info("File: " + filename + " Status: Server returned - " + status + " - " + response.getMessage());
                    }
                } else {
                    uploadFailureCount.incrementAndGet();
                    logger.warning("File: " + filename + " Status: Upload failed after retries");
                }
                
            } catch (Exception e) {
                uploadFailureCount.incrementAndGet();
                logger.severe("File: " + filePath + " Status: Unexpected error - " + 
                            e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                activeUploads.decrementAndGet();
            }
        });
    }

    /**
     * Wait for file to be completely written by checking if file size stabilizes
     */
    private boolean waitForFileComplete(Path filePath) {
        try {
            if (!Files.exists(filePath)) {
                return false;
            }

            long lastSize = Files.size(filePath);
            long stableStartTime = System.currentTimeMillis();
            long checkInterval = fileStabilityCheckInterval;
            long requiredStableDuration = fileStabilityCheckDuration;

            while (true) {
                Thread.sleep(checkInterval);

                if (!Files.exists(filePath)) {
                    logger.warning("File disappeared during stability check: " + filePath);
                    return false;
                }

                long currentSize = Files.size(filePath);

                if (currentSize != lastSize) {
                    // Size changed, reset stability timer
                    lastSize = currentSize;
                    stableStartTime = System.currentTimeMillis();
                } else {
                    // Size unchanged, check if stable long enough
                    long stableDuration = System.currentTimeMillis() - stableStartTime;
                    if (stableDuration >= requiredStableDuration) {
                        logger.info("File size stabilized: " + filePath.getFileName() + " (" + currentSize + " bytes)");
                        return true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            logger.warning("Error checking file stability: " + filePath + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * Upload video using gRPC client-side streaming
     */
    public void uploadVideoStreaming(String filePath) {
        if (isShuttingDown.get()) {
            logger.warning("Shutdown in progress, skipping streaming upload: " + filePath);
            return;
        }
        
        threadPool.submit(() -> {
            activeUploads.incrementAndGet();
            Path path = Paths.get(filePath);
            String filename = path.getFileName().toString();

            // Check if already processed
            if (processedFiles.contains(path.toAbsolutePath())) {
                logger.info("File already processed, skipping: " + filename);
                activeUploads.decrementAndGet();
                return;
            }

            // Check queue status before uploading with robust waiting
            try {
                waitForQueueAvailable(filename, queueWaitTimeoutSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("Interrupted while waiting for queue: " + filename);
                return;
            }
            
            if (isShuttingDown.get()) {
                logger.warning("Shutdown in progress, aborting streaming upload: " + filename);
                filesInProgress.remove(path.toAbsolutePath());
                return;
            }

            logger.info("File: " + filename + " Status: Streaming upload started");

            final CountDownLatch finishLatch = new CountDownLatch(1);
            final AtomicReference<String> uploadStatus = new AtomicReference<>("UNKNOWN");
            final AtomicReference<String> uploadMessage = new AtomicReference<>("");

            StreamObserver<VideoChunk> requestObserver = asyncStub
                .withDeadlineAfter(300, TimeUnit.SECONDS)
                .streamUploadVideo(new StreamObserver<UploadResponse>() {
                    @Override
                    public void onNext(UploadResponse response) {
                        uploadStatus.set(response.getStatus());
                        uploadMessage.set(response.getMessage());
                        logger.info("File: " + filename + " Status: " + response.getStatus() + " - " + response.getMessage());
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.warning("File: " + filename + " Streaming error: " + t.getMessage());
                        uploadStatus.set("ERROR");
                        uploadMessage.set(t.getMessage());
                        finishLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        finishLatch.countDown();
                    }
                });

            try (FileInputStream fis = new FileInputStream(filePath);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                byte[] buffer = new byte[streamingChunkSize];
                int bytesRead;
                boolean firstChunk = true;

                while ((bytesRead = bis.read(buffer)) != -1) {
                    VideoChunk.Builder chunkBuilder = VideoChunk.newBuilder();

                    if (firstChunk) {
                        // First chunk includes metadata
                        chunkBuilder.setFilename(filename)
                                   .setClientId(clientId)
                                   .setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, bytesRead));
                        firstChunk = false;
                    } else {
                        // Subsequent chunks only have data
                        chunkBuilder.setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, bytesRead));
                    }

                    requestObserver.onNext(chunkBuilder.build());
                }

                requestObserver.onCompleted();

                // Wait for response
                if (!finishLatch.await(60, TimeUnit.SECONDS)) {
                    logger.warning("File: " + filename + " Streaming upload timeout");
                } else {
                    Path absolutePath = path.toAbsolutePath();
                    filesInProgress.remove(absolutePath); // Remove from in-progress set
                    
                    if ("QUEUED".equals(uploadStatus.get())) {
                        processedFiles.add(absolutePath);
                        uploadSuccessCount.incrementAndGet();
                        logger.info("File: " + filename + " Status: Successfully queued via streaming");
                    } else if ("DROPPED".equals(uploadStatus.get())) {
                        logger.warning("File: " + filename + " Status: Dropped - " + uploadMessage.get());
                    } else {
                        uploadFailureCount.incrementAndGet();
                        logger.warning("File: " + filename + " Status: " + uploadStatus.get() + " - " + uploadMessage.get());
                    }
                }

            } catch (IOException e) {
                uploadFailureCount.incrementAndGet();
                logger.warning("File: " + filename + " Status: Failed to read file - " + e.getMessage());
                filesInProgress.remove(path.toAbsolutePath());
                requestObserver.onError(io.grpc.Status.INTERNAL.withDescription("File read error: " + e.getMessage()).asException());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("File: " + filename + " Status: Upload interrupted");
                filesInProgress.remove(path.toAbsolutePath());
                requestObserver.onError(io.grpc.Status.CANCELLED.withDescription("Upload interrupted").asException());
            } catch (Exception e) {
                uploadFailureCount.incrementAndGet();
                logger.warning("File: " + filename + " Status: Unexpected error - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                filesInProgress.remove(path.toAbsolutePath());
                requestObserver.onError(io.grpc.Status.INTERNAL.withDescription("Unexpected error: " + e.getMessage()).asException());
            } finally {
                activeUploads.decrementAndGet();
            }
        });
    }

    /**
     * Watch a folder for new files and upload them using streaming with recovery
     */
    public void watchFolder(String folderPath, String[] extensions) {
        Path folder = Paths.get(folderPath);
        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            logger.warning("Invalid folder for watching: " + folderPath);
            return;
        }

        AtomicBoolean watchActive = new AtomicBoolean(true);
        watchActiveFlags.put(folderPath, watchActive);
        
        Thread watchThread = new Thread(() -> {
            logger.info("Started watching folder: " + folderPath);
            int consecutiveFailures = 0;
            long reconnectBackoff = reconnectBackoffInitialMs;
            
            while (watchActive.get() && !isShuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // Verify folder still exists
                    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                        logger.warning("Folder no longer exists or is not a directory: " + folderPath);
                        // Wait and retry - folder might be temporarily unavailable
                        Thread.sleep(reconnectBackoff);
                        reconnectBackoff = Math.min(reconnectBackoff * 2, reconnectBackoffMaxMs);
                        continue;
                    }
                    
                    reconnectBackoff = reconnectBackoffInitialMs; // Reset on success
                    consecutiveFailures = 0;
                    
                    WatchService watchService = FileSystems.getDefault().newWatchService();
                    folder.register(watchService, 
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                    logger.info("Watching folder: " + folderPath + " (registered with WatchService)");

                    while (watchActive.get() && !isShuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                        WatchKey key;
                        try {
                            key = watchService.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }

                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();

                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                logger.warning("WatchService overflow for folder: " + folderPath);
                                continue;
                            }

                            @SuppressWarnings("unchecked")
                            WatchEvent<Path> ev = (WatchEvent<Path>) event;
                            Path fileName = ev.context();
                            Path fullPath = folder.resolve(fileName);

                            // Check if it's a video file
                            String filename = fileName.toString().toLowerCase();
                            boolean isVideoFile = Arrays.stream(extensions)
                                .anyMatch(ext -> filename.endsWith(ext.toLowerCase()));

                            if (isVideoFile && Files.isRegularFile(fullPath)) {
                                Path absolutePath = fullPath.toAbsolutePath();
                                
                                // Skip if already processed or in progress
                                if (processedFiles.contains(absolutePath) || filesInProgress.contains(absolutePath)) {
                                    logger.info("File already processed or in progress, skipping: " + fileName);
                                    continue;
                                }
                                
                                // Wait for file to be complete
                                if (waitForFileComplete(fullPath)) {
                                    // Double-check after waiting (file might have been processed by another event)
                                    if (!processedFiles.contains(absolutePath) && !filesInProgress.contains(absolutePath)) {
                                        filesInProgress.add(absolutePath);
                                        logger.info("New file detected: " + fileName);
                                        uploadVideoStreaming(fullPath.toString());
                                    } else {
                                        logger.info("File was processed while waiting, skipping: " + fileName);
                                    }
                                } else {
                                    logger.warning("File did not stabilize, skipping: " + fileName);
                                }
                            }
                        }

                        boolean valid = key.reset();
                        if (!valid) {
                            logger.warning("Watch key no longer valid for: " + folderPath + ". Re-registering...");
                            break; // Break inner loop to re-register
                        }
                    }

                    watchService.close();
                    
                    if (!watchActive.get() || isShuttingDown.get()) {
                        break; // Exit outer loop
                    }
                    
                    // Watch key became invalid, wait before re-registering
                    logger.info("Re-registering watch for folder: " + folderPath);
                    Thread.sleep(1000);
                    
                } catch (IOException e) {
                    consecutiveFailures++;
                    logger.warning("Error watching folder " + folderPath + " (failure " + consecutiveFailures + "): " + e.getMessage());
                    
                    if (consecutiveFailures >= 5) {
                        logger.severe("Too many consecutive failures watching folder " + folderPath + ". Backing off...");
                        try {
                            Thread.sleep(reconnectBackoff);
                            reconnectBackoff = Math.min(reconnectBackoff * 2, reconnectBackoffMaxMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    consecutiveFailures++;
                    logger.severe("Unexpected error watching folder " + folderPath + ": " + e.getMessage());
                    try {
                        Thread.sleep(reconnectBackoff);
                        reconnectBackoff = Math.min(reconnectBackoff * 2, reconnectBackoffMaxMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            logger.info("Stopped watching folder: " + folderPath);
        }, "FolderWatcher-" + folderPath);
        
        watchThread.setDaemon(true);
        watchThread.start();
        watchThreads.put(folderPath, watchThread);
    }

    public void scanAndUploadFolder(String folderPath, String[] extensions) {
        try {
            Path folder = Paths.get(folderPath);
            if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                System.out.println("Invalid folder: " + folderPath);
                return;
            }

            Map<String,Path> uniqueFiles = new HashMap<>(); 

            Files.walk(folder) 
                .filter(Files::isRegularFile) 
                .filter(path -> {
                    String filename = path.getFileName().toString().toLowerCase(); 
                    return Arrays.stream(extensions).anyMatch(ext -> filename.endsWith(ext.toLowerCase()));
                }) 
                .forEach(path -> {
                    String filename = path.getFileName().toString(); 
                    String baseName = getBaseFileName(filename); 

                    // Check if we already have file with base name 
                    if (uniqueFiles.containsKey(baseName)) {
                        System.out.println("Skipping duplicate: " + filename + " (duplicate of " + uniqueFiles.get(baseName).getFileName() + ")");
                    } else {
                        uniqueFiles.put(baseName, path); 
                        uploadVideo(path.toString());
                    }
                });

                System.out.println("Found " + uniqueFiles.size() + " unique videos in " + folderPath);
                
        } catch (IOException e) {
            System.err.println("Error scanning folder: " + e.getMessage());
        }
    } 

    public void cleanupDuplicateVideos() {
        try {
            Path videosDir = Paths.get("./videos"); 
            if (!Files.exists(videosDir)) {
                return; 
            } 

            Map<String, List<Path>> fileGroups = new HashMap<>(); 

            // Group files by base name 
            Files.list(videosDir) 
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    String filename = file.getFileName().toString(); 
                    String baseName = getBaseFileName(filename); 
                    fileGroups.computeIfAbsent(baseName, k -> new ArrayList<>()).add(file);
                }); 
            
            // Remove duplicates, only keep original 
            int removedCount = 0; 
            for (Map.Entry<String, List<Path>> entry : fileGroups.entrySet()) {
                List<Path> duplicates = entry.getValue(); 
                if (duplicates.size() > 1) {
                    duplicates.sort((p1, p2) -> {
                        try {
                            return Files.getLastModifiedTime(p1).compareTo(Files.getLastModifiedTime(p2)); 
                        } catch (IOException e) {
                            return 0; 
                        }
                    }); 

                    // Keep the first (oldest file), remove rest 
                    for (int i = 1; i < duplicates.size(); i++) {
                        try {
                            Files.delete(duplicates.get(i)); 
                            removedCount++; 
                            System.out.println("Removed duplicate: " + duplicates.get(i).getFileName());
                        } catch (IOException e) {
                            System.err.println("Failed to remove duplicate: " + duplicates.get(i).getFileName());
                        }
                    }
                }
            }
            
            if (removedCount > 0) {
                System.out.println("Removed " + removedCount + " duplicate files"); 
            }
        } catch (IOException e) {
            System.err.println("Error cleaning up duplicates: " + e.getMessage());
        }
    }

    // Get base filename - MUST BE STATIC
    private static String getBaseFileName(String filename) {
        String nameWithoutExt = filename.replaceFirst("[.][^.]+$", ""); 
        String baseName = nameWithoutExt.replaceAll("\\s*\\(\\d+\\)$", ""); 
        return baseName.toLowerCase();
    }

    // Helper method to scan a single folder
    private static List<Path> scanFolderForVideos(String folderPath, String[] extensions) throws IOException {
        List<Path> videoFiles = new ArrayList<>();
        Path folder = Paths.get(folderPath);
        
        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            throw new IOException("Invalid folder: " + folderPath);
        }
        
        Files.walk(folder)
            .filter(Files::isRegularFile)
            .filter(path -> {
                String filename = path.getFileName().toString().toLowerCase();
                return Arrays.stream(extensions).anyMatch(ext -> filename.endsWith(ext.toLowerCase()));
            })
            .forEach(videoFiles::add);
        
        return videoFiles;
    }

    // Helper method to remove duplicates across multiple folders
    private static List<Path> removeDuplicatesByBaseName(List<Path> files) {
        Map<String, Path> uniqueFiles = new HashMap<>();
        
        for (Path file : files) {
            String baseName = getBaseFileName(file.getFileName().toString());
            if (!uniqueFiles.containsKey(baseName)) {
                uniqueFiles.put(baseName, file);
            } else {
                System.out.println("Skipping duplicate across folders: " + file.getFileName());
            }
        }
        
        return new ArrayList<>(uniqueFiles.values());
    }

    public boolean isShuttingDown() {
        return isShuttingDown.get();
    }
    
    public void shutdown() {
        if (isShuttingDown.getAndSet(true)) {
            logger.info("Shutdown already in progress");
            return;
        }
        
        logger.info("Initiating graceful shutdown...");
        logger.info("Active uploads: " + activeUploads.get());
        logger.info("Metrics - Success: " + uploadSuccessCount.get() + 
                   ", Failures: " + uploadFailureCount.get() + 
                   ", Retries: " + retryCount.get() +
                   ", Connection state changes: " + connectionStateChanges.get());
        
        // Stop all watch threads
        for (Map.Entry<String, AtomicBoolean> entry : watchActiveFlags.entrySet()) {
            entry.getValue().set(false);
        }
        
        // Wait for active uploads to complete (graceful period)
        long gracefulTimeoutMs = gracefulShutdownTimeoutSeconds * 1000;
        long startTime = System.currentTimeMillis();
        
        while (activeUploads.get() > 0 && (System.currentTimeMillis() - startTime) < gracefulTimeoutMs) {
            try {
                logger.info("Waiting for " + activeUploads.get() + " active upload(s) to complete...");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (activeUploads.get() > 0) {
            logger.warning("Graceful shutdown timeout reached. " + activeUploads.get() + " upload(s) still in progress.");
        } else {
            logger.info("All uploads completed gracefully");
        }
        
        // Shutdown thread pool gracefully
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(forceShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("Thread pool did not terminate gracefully, forcing shutdown");
                threadPool.shutdownNow();
                if (!threadPool.awaitTermination(forceShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    logger.severe("Thread pool did not terminate after force shutdown");
                }
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown gRPC channel gracefully
        channel.shutdown();
        try {
            if (!channel.awaitTermination(forceShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("Channel did not terminate gracefully, forcing shutdown");
                channel.shutdownNow();
                if (!channel.awaitTermination(forceShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    logger.severe("Channel did not terminate after force shutdown");
                }
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        shutdownLatch.countDown();
        logger.info("Shutdown completed");
    }

    public static void main(String[] args) {
        // Load configuration from producer-config.properties
        String defaultTargetIp = "localhost";
        int defaultTargetPort = 9090;
        
        Properties config = new Properties();
        try {
            String configPath = "./producer-config.properties";
            if (Files.exists(Paths.get(configPath))) {
                try (FileInputStream fis = new FileInputStream(configPath)) {
                    config.load(fis);
                    System.out.println("Loaded configuration from producer-config.properties");
                }
            } else {
                System.out.println("producer-config.properties not found, using defaults");
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load producer-config.properties: " + e.getMessage());
            System.err.println("Using default values");
        }
        
        // Read from config file with defaults
        defaultTargetIp = config.getProperty("target.server.ip", defaultTargetIp);
        defaultTargetPort = Integer.parseInt(config.getProperty("target.server.port", String.valueOf(defaultTargetPort)));
        
        Scanner scanner = new Scanner(System.in);
        
        // ========== DEBUG INFO ==========
        System.out.println("=== DEBUG INFO ===");
        System.out.println("Current Working Directory: " + System.getProperty("user.dir"));
        System.out.println("Java Class Location: " + ProducerClient.class.getProtectionDomain().getCodeSource().getLocation());
        
        // Test some common paths
        System.out.println("\n--- Testing Common Paths ---");
        String[] testPaths = {
            "../test-videos1",
            "../test-videos2", 
            "../test-videos3",
            "../test-videos4",
            "../test-videos5",
            "test-videos1",
            "test-videos2",
            "C:\\Users\\arche\\OneDrive\\Documents\\DLSU\\4th Year\\1st Term\\STDISCM\\Problem Set 3\\test-videos1"
        };
        
        for (String path : testPaths) {
            File f = new File(path);
            System.out.println("Path: " + path);
            System.out.println("  Absolute: " + f.getAbsolutePath());
            System.out.println("  Exists: " + f.exists());
            System.out.println("  Is Directory: " + f.isDirectory());
            if (f.exists() && f.isDirectory()) {
                String[] files = f.list((dir, name) -> 
                    name.toLowerCase().endsWith(".mp4") || 
                    name.toLowerCase().endsWith(".avi") || 
                    name.toLowerCase().endsWith(".mov") || 
                    name.toLowerCase().endsWith(".mkv"));
                System.out.println("  Video files found: " + (files != null ? files.length : 0));
            }
            System.out.println();
        }
        System.out.println("==================");
        // ================================

        System.out.println("Choose operation:"); 
        System.out.println("1 - Upload videos with multiple producers");
        System.out.println("2 - Clean up duplicate videos in storage");
        System.out.println("3 - Clean up duplicates THEN upload videos");
        System.out.println("4 - Continuous folder monitoring with streaming upload");
        System.out.println("Enter choice (1, 2, 3, or 4): "); 

        String choice = scanner.nextLine().trim();

        // Handle option 4 - Continuous monitoring
        if ("4".equals(choice)) {
            System.out.println("Enter target IP address (default: " + defaultTargetIp + "):");
            String targetIp = scanner.nextLine().trim();
            if (targetIp.isEmpty()) {
                targetIp = defaultTargetIp;
            }

            System.out.println("\n=== CONTINUOUS MONITORING MODE ===");
            System.out.println("Enter folder paths to monitor (comma separated, e.g., test-videos1, test-videos2):");
            System.out.println("Note: Use '../folder' to go up one level, or full path like 'C:\\path\\to\\folder'");
            
            String foldersInput = scanner.nextLine().trim();
            String[] folderPaths = foldersInput.split(",");
            
            List<String> validFolders = new ArrayList<>();
            for (String folderPath : folderPaths) {
                folderPath = folderPath.trim();
                Path folder = Paths.get(folderPath);
                if (Files.exists(folder) && Files.isDirectory(folder)) {
                    validFolders.add(folderPath);
                    System.out.println("Valid folder: " + folderPath);
                } else {
                    System.out.println("Invalid folder (skipping): " + folderPath);
                }
            }
            
            if (validFolders.isEmpty()) {
                System.out.println("No valid folders found. Exiting.");
                scanner.close();
                return;
            }
            
            String[] videoExtensions = {".mp4", ".avi", ".mov", ".mkv"};
            // Create producer with enough threads for watching folders + uploads
            // Each folder needs 1 thread for watching, plus some for concurrent uploads
            int threadsNeeded = validFolders.size() + 2; // folders + 2 upload threads
            ProducerClient producer = new ProducerClient(targetIp, defaultTargetPort, "monitor-client", threadsNeeded);
            
            System.out.println("\n=== STARTING CONTINUOUS MONITORING ===");
            System.out.println("Monitoring " + validFolders.size() + " folder(s) for new video files...");
            System.out.println("Press 'q' and Enter to stop monitoring gracefully");
            
            // Start watching each folder
            for (String folderPath : validFolders) {
                producer.watchFolder(folderPath, videoExtensions);
            }
            
            // Wait for user to quit
            Thread monitorThread = new Thread(() -> {
                Scanner inputScanner = new Scanner(System.in);
                while (!producer.isShuttingDown()) {
                    try {
                        String input = inputScanner.nextLine().trim();
                        if ("q".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                            System.out.println("Stopping monitoring gracefully...");
                            producer.shutdown();
                            break;
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            });
            monitorThread.setDaemon(true);
            monitorThread.start();
            
            // Keep main thread alive and wait for shutdown
            try {
                while (!producer.isShuttingDown()) {
                    Thread.sleep(1000);
                }
                producer.shutdownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Interrupted, shutting down...");
                producer.shutdown();
            }
            
            scanner.close();
            return;
        }

        // Handle cleanup operations first 
        if ("2".equals(choice) || "3".equals(choice)) {
            ProducerClient cleanupClient = new ProducerClient(defaultTargetIp, defaultTargetPort, "cleanup-client", 1); 
            cleanupClient.cleanupDuplicateVideos(); 

            if ("2".equals(choice)) {
                cleanupClient.shutdown(); 
                scanner.close(); 
                return; 
            } 
            cleanupClient.shutdown();
        }
        
        // Input validation
        int producerThreads = getValidatedInput(scanner, "Enter number of producer threads (p): ", 1);

        System.out.println("Enter target IP address (default: " + defaultTargetIp + "):");
        String targetIp = scanner.nextLine().trim();
        if (targetIp.isEmpty()) {
            targetIp = defaultTargetIp;
        }
        
        // *** ENHANCED INPUT HANDLING WITH DEBUGGING ***
        System.out.println("\n=== FOLDER INPUT ===");
        System.out.println("Enter folder paths (comma separated, e.g., test-videos1, test-videos2, test-videos3):");
        System.out.println("Note: Use '../folder' to go up one level, or full path like 'C:\\path\\to\\folder'");
        
        // Clear scanner buffer if needed
        // if (scanner.hasNextLine()) {
        //     scanner.nextLine(); // Clear any leftover input
        // }
        
        String foldersInput = scanner.nextLine().trim();
        
        System.out.println("\n=== DEBUG: Processing Input ===");
        System.out.println("Raw input received: '" + foldersInput + "'");
        
        // Split by comma and trim each folder path
        String[] folderPaths = foldersInput.split(",");
        System.out.println("Found " + folderPaths.length + " folder paths");
        
        // Enhanced path debugging
        List<String> validFolders = new ArrayList<>();
        for (int i = 0; i < folderPaths.length; i++) {
            String folderPath = folderPaths[i].trim();
            folderPaths[i] = folderPath;
            
            System.out.println("\n--- Analyzing Folder " + (i+1) + " ---");
            System.out.println("Input: '" + folderPath + "'");
            
            // Try to resolve the path
            File folder = new File(folderPath);
            System.out.println("Resolved to: " + folder.getAbsolutePath());
            System.out.println("Exists: " + folder.exists());
            System.out.println("Is directory: " + folder.isDirectory());
            
            if (folder.exists() && folder.isDirectory()) {
                validFolders.add(folderPath);
                
                // List all video files in the directory
                System.out.println("Video files in directory:");
                File[] allFiles = folder.listFiles();
                int videoCount = 0;
                if (allFiles != null) {
                    for (File f : allFiles) {
                        if (f.isFile()) {
                            String name = f.getName().toLowerCase();
                            if (name.endsWith(".mp4") || name.endsWith(".avi") || 
                                name.endsWith(".mov") || name.endsWith(".mkv")) {
                                System.out.println("  ✓ " + f.getName());
                                videoCount++;
                            } else {
                                System.out.println("  ✗ " + f.getName() + " (not a video)");
                            }
                        }
                    }
                }
                System.out.println("Total video files: " + videoCount);
            } else {
                System.out.println("⚠ WARNING: Folder does not exist or is not a directory!");
            }
        }
        
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Valid folders found: " + validFolders.size() + "/" + folderPaths.length);
        if (validFolders.isEmpty()) {
            System.out.println("No valid folders found. Exiting.");
            scanner.close();
            return;
        }
        
        // Convert list back to array
        folderPaths = validFolders.toArray(new String[0]);
        // =============================================
        
        // Use shared queue approach
        List<Path> allFiles = new ArrayList<>();
        String[] videoExtensions = {".mp4", ".avi", ".mov", ".mkv"};
        
        System.out.println("\n=== SCANNING FOLDERS ===");
        // Scan ALL valid folders once
        for (String folderPath : folderPaths) {
            try {
                System.out.println("\nScanning folder: " + folderPath);
                List<Path> folderFiles = scanFolderForVideos(folderPath, videoExtensions);
                allFiles.addAll(folderFiles);
                System.out.println("Found " + folderFiles.size() + " videos in " + folderPath);
                
                // Debug: List the files found
                if (!folderFiles.isEmpty()) {
                    System.out.println("Files found:");
                    for (Path file : folderFiles) {
                        System.out.println("  - " + file.getFileName());
                    }
                }
            } catch (IOException e) {
                System.err.println("Error scanning " + folderPath + ": " + e.getMessage());
            }
        }
        
        if (allFiles.isEmpty()) {
            System.out.println("No videos found in any folder.");
            System.out.println("The producer will continue running and monitor for new videos.");
            System.out.println("Press 'q' and Enter to exit, or wait for new videos to appear.");
            
            // Create a minimal producer to continue monitoring
            ProducerClient producer = new ProducerClient(targetIp, defaultTargetPort, "monitor-client", 2);
            String[] videoExtensions = {".mp4", ".avi", ".mov", ".mkv"};
            
            // Start watching all valid folders
            for (String folderPath : folderPaths) {
                producer.watchFolder(folderPath, videoExtensions);
            }
            
            System.out.println("\n=== CONTINUOUS MONITORING MODE (Empty folders) ===");
            System.out.println("Monitoring folders for new video files...");
            System.out.println("Press 'q' and Enter to stop monitoring gracefully");
            
            // Wait for user to quit or new files
            Thread monitorThread = new Thread(() -> {
                Scanner inputScanner = new Scanner(System.in);
                while (!producer.isShuttingDown()) {
                    try {
                        String input = inputScanner.nextLine().trim();
                        if ("q".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                            System.out.println("Stopping monitoring gracefully...");
                            producer.shutdown();
                            break;
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            });
            monitorThread.setDaemon(true);
            monitorThread.start();
            
            // Wait for shutdown
            try {
                producer.shutdownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                producer.shutdown();
            }
            
            scanner.close();
            return;
        }
        
        // Remove duplicates across ALL folders
        allFiles = removeDuplicatesByBaseName(allFiles);
        System.out.println("\n=== FINAL FILE LIST ===");
        System.out.println("Total unique videos to upload: " + allFiles.size());
        
        // Debug: Show final list
        System.out.println("Files to upload:");
        for (Path file : allFiles) {
            System.out.println("  - " + file.getFileName() + " (from: " + file.getParent() + ")");
        }
        
        // Create shared queue
        BlockingQueue<Path> fileQueue = new LinkedBlockingQueue<>(allFiles);
        
        // Create producers with shared queue
        List<ProducerClient> producers = new ArrayList<>();
        List<Thread> producerThreadsList = new ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(producerThreads);
        
        System.out.println("\n=== STARTING UPLOADS ===");
        System.out.println("Creating " + producerThreads + " producer threads...");
        
        for (int i = 0; i < producerThreads; i++) {
            ProducerClient producer = new ProducerClient(targetIp, defaultTargetPort, "producer-" + (i + 1), 1);
            producers.add(producer);
            
            Thread producerThread = new Thread(() -> {
                String threadName = Thread.currentThread().getName();
                try {
                    System.out.println(threadName + " started");
                    while (true) {
                        // Get file from shared queue (waits if empty)
                        Path file = fileQueue.poll(1, TimeUnit.SECONDS);
                        if (file == null) {
                            // No more files
                            System.out.println(threadName + " finished - no more files");
                            break;
                        }
                        System.out.println(threadName + " uploading: " + file.getFileName());
                        producer.uploadVideo(file.toString());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println(threadName + " interrupted");
                } finally {
                    completionLatch.countDown();
                    System.out.println(threadName + " completed");
                }
            }, "Producer-" + (i + 1));
            
            producerThreadsList.add(producerThread);
            producerThread.start();
        }
        
        System.out.println("\n" + producerThreads + " producers started. Uploading " + allFiles.size() + " videos...");
        System.out.println("Waiting for uploads to complete...");
        
        // Wait for all producers to finish
        try {
            boolean completed = completionLatch.await(30, TimeUnit.MINUTES); // 30 minute timeout
            if (completed) {
                System.out.println("✓ All uploads completed!");
            } else {
                System.out.println("⚠ Timeout - some uploads may not have completed");
            }
        } catch (InterruptedException e) {
            System.err.println("⚠ Upload waiting interrupted");
            e.printStackTrace();
        }
        
        // Cleanup
        System.out.println("\n=== CLEANUP ===");
        producers.forEach(ProducerClient::shutdown);
        scanner.close();
        System.out.println("All producers stopped.");
    }
    
    private static int getValidatedInput(Scanner scanner, String prompt, int minValue) {
        while (true) {
            System.out.print(prompt);
            try {
                int value = Integer.parseInt(scanner.nextLine());
                if (value >= minValue) {
                    return value;
                } else {
                    System.out.println("Value must be at least " + minValue);
                }
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid number");
            }
        }
    }
}