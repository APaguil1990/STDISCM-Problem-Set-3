package com.example.mediaservice;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class ProducerClient {
    private final ManagedChannel channel;
    private final MediaServiceGrpc.MediaServiceBlockingStub blockingStub;
    private final Logger logger;
    private final String clientId;
    private final ExecutorService threadPool;

    public ProducerClient(String host, int port, String clientId, int producerThreads) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(100 * 1024 * 1024)
            // .keepAliveTime(30, TimeUnit.SECONDS)
            // .keepAliveTimeout(10, TimeUnit.SECONDS)
            // .keepAliveWithoutCalls(true)
            // .idleTimeout(5, TimeUnit.MINUTES)
            .build();
        this.blockingStub = MediaServiceGrpc.newBlockingStub(channel);
        this.clientId = clientId;
        this.threadPool = Executors.newFixedThreadPool(producerThreads);
        
        // Setup file logging
        this.logger = setupLogger(clientId);
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

                if (removedCount > 0) {
                    System.out.println("Removed " + removedCount + " duplicate files"); 
                }
            }
        } catch (IOException e) {
            System.err.println("Error cleaning up duplicates: " + e.getMessage());
        }
    }

    // Get base filename 
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
        Scanner scanner = new Scanner(System.in);
        System.out.println("Choose operation:"); 
        System.out.println("1 - Upload videos with multiple producers");
        System.out.println("2 - Clean up duplicate videos in storage");
        System.out.println("3 - Clean up duplicates THEN upload videos");
        System.out.println("Enter choice (1, 2, or 3): "); 

        String choice = scanner.nextLine().trim();

        // Handle cleanup operations first 
        if ("2".equals(choice) || "3".equals(choice)) {
            ProducerClient cleanupClient = new ProducerClient("localhost", 9090, "cleanup-client", 1); 
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
        int consumerThreads = getValidatedInput(scanner, "Enter number of consumer threads (c): ", 1);
        int queueSize = getValidatedInput(scanner, "Enter queue size (q): ", 1);
        
        System.out.println("Enter target IP address:");
        String targetIp = scanner.nextLine().trim();
        if (targetIp.isEmpty()) {
            targetIp = "localhost";
        }
        
        // *** CHANGED: Ask for MULTIPLE folders ***
        System.out.println("Enter folder paths (comma separated, e.g., folder1, folder2, folder3):");
        scanner.nextLine(); // Clear buffer
        String foldersInput = scanner.nextLine().trim();
        String[] folderPaths = foldersInput.split(",");
        
        // *** CHANGED: Use shared queue approach ***
        List<Path> allFiles = new ArrayList<>();
        String[] videoExtensions = {".mp4", ".avi", ".mov", ".mkv"};
        
        // Scan ALL folders once
        for (String folderPath : folderPaths) {
            folderPath = folderPath.trim();
            try {
                List<Path> folderFiles = scanFolderForVideos(folderPath, videoExtensions);
                allFiles.addAll(folderFiles);
                System.out.println("Found " + folderFiles.size() + " videos in " + folderPath);
            } catch (IOException e) {
                System.err.println("Error scanning " + folderPath + ": " + e.getMessage());
            }
        }
        
        // Remove duplicates across ALL folders
        allFiles = removeDuplicatesByBaseName(allFiles);
        System.out.println("Total unique videos to upload: " + allFiles.size());
        
        if (allFiles.isEmpty()) {
            System.out.println("No videos found. Exiting.");
            scanner.close();
            return;
        }
        
        // Create shared queue
        BlockingQueue<Path> fileQueue = new LinkedBlockingQueue<>(allFiles);
        
        // Create producers with shared queue
        List<ProducerClient> producers = new ArrayList<>();
        List<Thread> producerThreadsList = new ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(producerThreads);
        
        for (int i = 0; i < producerThreads; i++) {
            ProducerClient producer = new ProducerClient(targetIp, 9090, "producer-" + (i + 1), 1);
            producers.add(producer);
            
            Thread producerThread = new Thread(() -> {
                try {
                    while (true) {
                        // Get file from shared queue (waits if empty)
                        Path file = fileQueue.poll(1, TimeUnit.SECONDS);
                        if (file == null) {
                            // No more files
                            break;
                        }
                        System.out.println(Thread.currentThread().getName() + 
                                        " uploading: " + file.getFileName());
                        producer.uploadVideo(file.toString());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            }, "Producer-" + (i + 1));
            
            producerThreadsList.add(producerThread);
            producerThread.start();
        }
        
        System.out.println("\nProducers started. Uploading " + allFiles.size() + " videos...");
        System.out.println("Press Enter to quit or wait for completion.");
        
        // Wait for user input or completion
        try {
            // Wait for all producers to finish OR user presses Enter
            boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
            if (completed) {
                System.out.println("All uploads completed!");
            } else {
                System.out.println("Timeout or user interruption.");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // Cleanup
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