package com.example.mediaservice;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.Properties;

public class ProducerClient {
    private final ManagedChannel channel;
    private final MediaServiceGrpc.MediaServiceBlockingStub blockingStub;
    private final Logger logger;
    private final String clientId;

    // Toggle for the "Bonus" requirement
    private static boolean useSmartSignaling = false;

    public ProducerClient(String host, int port, String clientId) {
        // Increase max message size to 100MB to allow large video uploads
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(100 * 1024 * 1024)
            .build();
        this.blockingStub = MediaServiceGrpc.newBlockingStub(channel);
        this.clientId = clientId;
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
            return Logger.getGlobal();
        }
    }

    // SYNCHRONOUS upload method - blocks until finished
    public void uploadVideo(String filePath) {
        String filename = Paths.get(filePath).getFileName().toString();

        // --- SMART SIGNALING LOGIC (Bonus) ---
        if (useSmartSignaling) {
            while (true) {
                try {
                    QueueStatus status = blockingStub.getQueueStatus(Empty.getDefaultInstance());
                    if (!status.getIsFull()) {
                        break; // Queue has space, proceed
                    }
                    // Wait if full
                    logger.info("Queue is full (" + status.getCurrentSize() + "/" + status.getMaxCapacity() + "). Waiting to upload: " + filename);
                    Thread.sleep(2000);
                } catch (Exception e) {
                    logger.warning("Failed to check queue status: " + e.getMessage());
                    break;
                }
            }
        }
        // -------------------------------------

        logger.info("File: " + filename + " Status: Uploading");

        try (FileInputStream fis = new FileInputStream(filePath); BufferedInputStream bis = new BufferedInputStream(fis)) {
            byte[] buffer = new byte[1024 * 1024];
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

            UploadResponse response;
            try {
                // Set a deadline/timeout for the upload request
                response = blockingStub
                    .withDeadlineAfter(60, TimeUnit.SECONDS)
                    .uploadVideo(request);
            } catch (Exception e) {
                logger.info("File: " + filename + " Status: Connection/Upload Error - " + e.getMessage());
                return;
            }

            String status = response.getStatus();

            if ("QUEUED".equals(status)) {
                logger.info("File: " + filename + " Status: Successfully queued (ID: " + response.getVideoId() + ")");
            } else if ("DROPPED".equals(status)) {
                logger.info("File: " + filename + " Status: Dropped - " + response.getMessage());
            } else {
                logger.info("File: " + filename + " Status: " + status + " - " + response.getMessage());
            }

        } catch (IOException e) {
            logger.info("File: " + filename + " Status: Failed to read local file - " + e.getMessage());
        }
    }

    public void cleanupDuplicateVideos() {
        try {
            Path videosDir = Paths.get("./videos");
            if (!Files.exists(videosDir)) {
                return;
            }

            Map<String, List<Path>> fileGroups = new HashMap<>();

            Files.list(videosDir)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    String filename = file.getFileName().toString();
                    String baseName = getBaseFileName(filename);
                    fileGroups.computeIfAbsent(baseName, k -> new ArrayList<>()).add(file);
                });

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

    private static String getBaseFileName(String filename) {
        String nameWithoutExt = filename.replaceFirst("[.][^.]+$", "");
        String baseName = nameWithoutExt.replaceAll("\\s*\\(\\d+\\)$", "");
        return baseName.toLowerCase();
    }

    private static List<Path> scanFolderForVideos(String folderPath, String[] extensions) throws IOException {
        List<Path> videoFiles = new ArrayList<>();
        Path folder = Paths.get(folderPath);
        if (!Files.exists(folder)) throw new IOException("Invalid folder: " + folderPath);

        Files.walk(folder).filter(Files::isRegularFile).forEach(path -> {
            String name = path.getFileName().toString().toLowerCase();
            if (Arrays.stream(extensions).anyMatch(ext -> name.endsWith(ext.toLowerCase()))) {
                videoFiles.add(path);
            }
        });
        return videoFiles;
    }

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
        channel.shutdown();
    }

    public static void main(String[] args) {
        String defaultTargetIp = "localhost";
        int defaultTargetPort = 9090;

        // Try to load config properties
        Properties config = new Properties();
        try {
            String configPath = "./producer-config.properties";
            if (Files.exists(Paths.get(configPath))) {
                try (FileInputStream fis = new FileInputStream(configPath)) {
                    config.load(fis);
                }
            }
        } catch (Exception e) {
            // Ignore config error
        }

        defaultTargetIp = config.getProperty("target.server.ip", defaultTargetIp);
        defaultTargetPort = Integer.parseInt(config.getProperty("target.server.port", String.valueOf(defaultTargetPort)));

        Scanner scanner = new Scanner(System.in);
        System.out.println("=== Producer Client ===");

        System.out.println("1 - Upload videos");
        System.out.println("2 - Clean up duplicates");
        System.out.print("Enter choice: ");
        String choice = scanner.nextLine().trim();

        if ("2".equals(choice)) {
            ProducerClient cleanup = new ProducerClient(defaultTargetIp, defaultTargetPort, "cleanup");
            cleanup.cleanupDuplicateVideos();
            cleanup.shutdown();
            return;
        }

        // 1. Ask for Producer Threads
        int producerThreads = getValidatedInput(scanner, "Enter number of producer threads (p): ", 1);

        // 2. Ask for Smart Signaling (The Toggle)
        System.out.print("Enable 'Smart Queue' Signaling (No Drops)? (y/n): ");
        String signalInput = scanner.nextLine().trim();
        if (signalInput.equalsIgnoreCase("y")) {
            useSmartSignaling = true;
            System.out.println("Smart Signaling ENABLED (Producer will wait).");
        } else {
            useSmartSignaling = false;
            System.out.println("Smart Signaling DISABLED (Producer will flood; expect DROPPED videos).");
        }

        System.out.println("Enter target IP address (default: " + defaultTargetIp + "):");
        String targetIp = scanner.nextLine().trim();
        if (targetIp.isEmpty()) targetIp = defaultTargetIp;

        // 3. Ask for Folders
        System.out.println("Enter folder paths (comma separated):");
        String foldersInput = scanner.nextLine().trim();
        String[] folderPaths = foldersInput.split(",");

        // Scan files
        List<Path> allFiles = new ArrayList<>();
        String[] videoExtensions = {".mp4", ".avi", ".mov", ".mkv"};

        for (String folderPath : folderPaths) {
            try {
                allFiles.addAll(scanFolderForVideos(folderPath.trim(), videoExtensions));
            } catch (IOException e) {
                System.out.println("Skipping invalid folder: " + folderPath);
            }
        }

        // De-duplication logic (You can comment this out if you want to upload dupes)
        allFiles = removeDuplicatesByBaseName(allFiles);

        if (allFiles.isEmpty()) {
            System.out.println("No videos found to upload.");
            return;
        }

        System.out.println("Total unique videos to upload: " + allFiles.size());

        // Shared Queue
        BlockingQueue<Path> fileQueue = new LinkedBlockingQueue<>(allFiles);
        List<ProducerClient> producers = new ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(producerThreads);

        System.out.println("\n=== STARTING UPLOADS ===");

        for (int i = 0; i < producerThreads; i++) {
            // Create a client for each thread
            ProducerClient producer = new ProducerClient(targetIp, defaultTargetPort, "producer-" + (i + 1));
            producers.add(producer);

            // Create the thread logic
            new Thread(() -> {
                String threadName = Thread.currentThread().getName();
                try {
                    while (true) {
                        // Poll with timeout to ensure threads eventually die if queue is stuck
                        Path file = fileQueue.poll(1, TimeUnit.SECONDS);
                        if (file == null) {
                            break; // Queue is empty, work done
                        }
                        // This blocks until upload is done or timeout occurs
                        producer.uploadVideo(file.toString());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    System.out.println(threadName + " finished.");
                    completionLatch.countDown();
                }
            }, "Producer-" + (i + 1)).start();
        }

        // WAIT for all threads to finish
        try {
            System.out.println("Waiting for all uploads to complete...");
            completionLatch.await();
            System.out.println("✓ All uploads completed!");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Cleanup
        producers.forEach(ProducerClient::shutdown);
        scanner.close();
        System.out.println("Exiting application.");
    }

    private static int getValidatedInput(Scanner scanner, String prompt, int minValue) {
        while (true) {
            System.out.print(prompt);
            try {
                int value = Integer.parseInt(scanner.nextLine());
                if (value >= minValue) return value;
            } catch (NumberFormatException e) {
                System.out.println("Invalid number");
            }
        }
    }
}