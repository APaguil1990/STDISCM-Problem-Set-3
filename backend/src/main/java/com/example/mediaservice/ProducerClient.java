package com.example.mediaservice;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.*;
import java.nio.file.*;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.*;
import java.util.Properties;

public class ProducerClient {
    private final ManagedChannel channel;
    private final MediaServiceGrpc.MediaServiceBlockingStub blockingStub;
    private final MediaServiceGrpc.MediaServiceStub asyncStub;
    private final Logger logger;
    private final String clientId;
    private final ExecutorService threadPool;
    private final int streamingChunkSize;
    private final long fileStabilityCheckInterval;
    private final long fileStabilityCheckDuration;
    private final Set<Path> processedFiles;
    private final Set<Path> filesInProgress;

    public ProducerClient(String host, int port, String clientId, int producerThreads) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(100 * 1024 * 1024)
            // REMOVE keepalive settings to avoid warnings
            .build();
        this.blockingStub = MediaServiceGrpc.newBlockingStub(channel);
        this.asyncStub = MediaServiceGrpc.newStub(channel);
        this.clientId = clientId;
        this.threadPool = Executors.newFixedThreadPool(producerThreads);
        this.processedFiles = ConcurrentHashMap.newKeySet();
        this.filesInProgress = ConcurrentHashMap.newKeySet();
        
        // Load configuration
        Properties config = loadConfig();
        this.streamingChunkSize = Integer.parseInt(config.getProperty("streaming.chunk.size", "1048576"));
        this.fileStabilityCheckInterval = Long.parseLong(config.getProperty("file.stability.check.interval", "500"));
        this.fileStabilityCheckDuration = Long.parseLong(config.getProperty("file.stability.check.duration", "2000"));
        
        // Setup file logging
        this.logger = setupLogger(clientId);
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

    public void uploadVideo(String filePath) {
        threadPool.submit(() -> {
            String filename = Paths.get(filePath).getFileName().toString();

            // Check queue status before uploading
            while (true) {
                try {
                    QueueStatus status = blockingStub.getQueueStatus(Empty.getDefaultInstance());
                    if (!status.getIsFull()) {
                        break; // Queue has space, proceed to upload
                    }
                    // If full, wait 2 seconds and try again
                    logger.info("Queue is full (" + status.getCurrentSize() + "/" + status.getMaxCapacity() + "). Waiting to upload: " + filename);
                    Thread.sleep(2000);
                } catch (Exception e) {
                    logger.warning("Failed to check queue status: " + e.getMessage());
                    break; // If check fails, try uploading anyway or exit
                }
            }
            
            logger.info("File: " + filename + " Status: Uploading");
            
            try (FileInputStream fis = new FileInputStream(filePath); BufferedInputStream bis = new BufferedInputStream(fis)) {
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

                // Add deadline and better error handling
                UploadResponse response;
                try {
                    response = blockingStub
                        .withDeadlineAfter(60, TimeUnit.SECONDS)  // 60 second timeout
                        .uploadVideo(request);
                } catch (io.grpc.StatusRuntimeException e) {
                    // Check if it's a timeout or other gRPC error
                    if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                        logger.info("File: " + filename + " Status: Upload timed out (check if video was stored)");
                    } else if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
                        logger.info("File: " + filename + " Status: Server unavailable");
                    } else {
                        logger.info("File: " + filename + " Status: gRPC error - " + e.getStatus());
                    }
                    return;
                }

                String status = response.getStatus();
                
                if ("QUEUED".equals(status)) {
                    logger.info("File: " + filename + " Status: Successfully queued (ID: " + response.getVideoId() + ")");
                } else if ("DROPPED".equals(status)) {
                    logger.info("File: " + filename + " Status: Dropped - " + response.getMessage());
                } else if ("ERROR".equals(status)) {
                    logger.info("File: " + filename + " Status: Server error - " + response.getMessage());
                } else {
                    logger.info("File: " + filename + " Status: Server returned - " + status + " - " + response.getMessage());
                }
                
            } catch (IOException e) {
                logger.info("File: " + filename + " Status: Failed to read file - " + e.getMessage());
            } catch (Exception e) {
                logger.info("File: " + filename + " Status: Unexpected error - " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
        threadPool.submit(() -> {
            Path path = Paths.get(filePath);
            String filename = path.getFileName().toString();

            // Check if already processed
            if (processedFiles.contains(path.toAbsolutePath())) {
                logger.info("File already processed, skipping: " + filename);
                return;
            }

            // Check queue status before uploading
            while (true) {
                try {
                    QueueStatus status = blockingStub.getQueueStatus(Empty.getDefaultInstance());
                    if (!status.getIsFull()) {
                        break; // Queue has space, proceed to upload
                    }
                    logger.info("Queue is full (" + status.getCurrentSize() + "/" + status.getMaxCapacity() + "). Waiting to upload: " + filename);
                    Thread.sleep(2000);
                } catch (Exception e) {
                    logger.warning("Failed to check queue status: " + e.getMessage());
                    break;
                }
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
                        logger.info("File: " + filename + " Status: Successfully queued via streaming");
                    } else if ("DROPPED".equals(uploadStatus.get())) {
                        logger.info("File: " + filename + " Status: Dropped - " + uploadMessage.get());
                    } else {
                        logger.info("File: " + filename + " Status: " + uploadStatus.get() + " - " + uploadMessage.get());
                    }
                }

            } catch (IOException e) {
                logger.warning("File: " + filename + " Status: Failed to read file - " + e.getMessage());
                filesInProgress.remove(path.toAbsolutePath());
                requestObserver.onError(io.grpc.Status.INTERNAL.withDescription("File read error: " + e.getMessage()).asException());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("File: " + filename + " Status: Upload interrupted");
                filesInProgress.remove(path.toAbsolutePath());
                requestObserver.onError(io.grpc.Status.CANCELLED.withDescription("Upload interrupted").asException());
            } catch (Exception e) {
                logger.warning("File: " + filename + " Status: Unexpected error - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                filesInProgress.remove(path.toAbsolutePath());
                requestObserver.onError(io.grpc.Status.INTERNAL.withDescription("Unexpected error: " + e.getMessage()).asException());
            }
        });
    }

    /**
     * Watch a folder for new files and upload them using streaming
     */
    public void watchFolder(String folderPath, String[] extensions) {
        Path folder = Paths.get(folderPath);
        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            logger.warning("Invalid folder for watching: " + folderPath);
            return;
        }

        threadPool.submit(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                folder.register(watchService, 
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

                logger.info("Started watching folder: " + folderPath);

                while (!Thread.currentThread().isInterrupted()) {
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
                        logger.warning("Watch key no longer valid for: " + folderPath);
                        break;
                    }
                }

                watchService.close();
                logger.info("Stopped watching folder: " + folderPath);
            } catch (IOException e) {
                logger.severe("Error watching folder " + folderPath + ": " + e.getMessage());
            }
        });
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

    public void shutdown() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(60, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        channel.shutdown();
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
            System.out.println("Press 'q' and Enter to stop monitoring");
            
            // Start watching each folder
            for (String folderPath : validFolders) {
                producer.watchFolder(folderPath, videoExtensions);
            }
            
            // Wait for user to quit
            Thread monitorThread = new Thread(() -> {
                Scanner inputScanner = new Scanner(System.in);
                while (true) {
                    String input = inputScanner.nextLine().trim();
                    if ("q".equalsIgnoreCase(input)) {
                        System.out.println("Stopping monitoring...");
                        producer.shutdown();
                        System.exit(0);
                    }
                }
            });
            monitorThread.setDaemon(true);
            monitorThread.start();
            
            // Keep main thread alive
            try {
                monitorThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
            System.out.println("No videos found in any folder. Exiting.");
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