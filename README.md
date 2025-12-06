# Media Upload Service - Distributed Producer-Consumer System 
A distributed media upload platform that streams videos between producers and consumers with queue management and video previews. This project is built with Java gRPC for the backend and React for the frontend web application. 

## System Architecture 
```
┌─────────────────┐    gRPC     ┌─────────────────┐    HTTP     ┌─────────────────┐
│   Producer      │ ──────────→ │   Media Server  │ ──────────→ │  React Frontend │
│  (Java Client)  │             │   (Java gRPC)   │             │    (Web GUI)    │
└─────────────────┘             └─────────────────┘             └─────────────────┘
       ↑                               ↓                               ↓        
  Video Files                    Queue Management                Video Display 
  Folder Scanning                Preview Generation              Real-time Stats
  Duplicate Prevention           File Storage                   Hover Previews 
```

## Prerequisites 
### **Backend Dependencies** 
- Java 20 or higher 
- Maven 3.6+ 
- FFmpeg (for video preview generation) 

### **Frontend Dependencies** 
- Node.js 16+ and npm 
- React 18+ 

## Installation & Setup Instructions 
### 1. Backend Setup (Java + Maven) 
Install Java 
- Windows: Download from https://www.oracle.com/java/technologies/javase/jdk22-archive-downloads.html 

Verify Installation: 
- java --version 
- javac --version

Install Maven
- Windows: Download from https://maven.apache.org/download.cgi 

Verify Installation: 
- mvn --version 

Install FFmpeg (For previews) 
- Download from https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-full.7z 
- Extract to **C:\ffmpeg** 
- Add **C:\ffmpeg\bin** to you PATH environment variables 
- Restart 

### 2. Frontend Setup (React + Node.js) 
Install Node.js 
- Download from https://nodejs.org/en 
- Recommended version: 20 or higher 

Verify installation: 
- node --version 
- npm --version 

## Running Application 
### **Important:** Run in Separate Terminals
3 separate terminals will be needed for proper operation

#### Terminal 1: Backend Media Server 
- cd backend 
- mvn clean compile 
- mvn exec:java -D"exec.mainClass=com.example.mediaservice.MediaServer" 

#### Expected Output: 
Media Server started: 
gRPC Server on port: 9090 (0.0.0.0) 
HTTP Server on Port: 8080 
Static content available at: http://[SERVER_IP]:8080/content/ 

#### Terminal 2: Producer Client (Video Upload) 
- cd backend 
- mvn clean compile 
- mvn exec:java -D"exec.mainClass=com.example.mediaservice.ProducerClient" 

#### Producer Client Interaction: 
Choose operation:
1 - Upload videos with multiple producers
2 - Clean up duplicate videos in storage
Enter choice (1 or 2): 1

Enter number of producer threads (p): 2
Enter number of consumer threads (c): 3
Enter queue size (q): 10
Enter target IP address: localhost
Enter folder path for producer thread 1: ../test-videos
Enter folder path for producer thread 2: ../test-videos

Producers started. Press 'q' to quit.

#### Terminal 3: Frontend React Application 
- cd frontend 
- npm install 
- npm run dev 

## Project Structure 
```
Problem Set 3/
├── backend/
│   ├── pom.xml                          # Maven dependencies
│   ├── src/main/
│   │   ├── proto/media.proto            # gRPC service definition
│   │   └── java/com/example/mediaservice/
│   │       ├── MediaServer.java         # Main server (gRPC + HTTP)
│   │       ├── MediaServiceImpl.java    # Business logic
│   │       ├── ProducerClient.java      # Video upload client
│   │       └── BoundedQueue.java        # Leaky bucket queue
│   ├── videos/                          # Auto-created: stored videos
│   └── target/                          # Auto-created: compiled classes
├── frontend/
│   ├── package.json                     # React dependencies
│   ├── vite.config.js                   # Vite configuration
│   └── src/
│       ├── App.jsx                      # Main React component
│       ├── main.jsx                     # React entry point
│       ├── components/
│       │   ├── VideoGrid.jsx            # Video gallery
│       │   └── VideoPlayer.jsx          # Video player
│       └── styles/
│           └── App.css                  # Styling
└── test-videos/                         # Test videos storage
```

## Configurations 
### Backend Configuration (MediaServer) 
- **Port 1**: gRPC port (default: 9090) 
- **Port 2**: HTTP port (defautl: 8080) 
- **Queue Size**: Max queue capacity (default: 10) 
- **Consumer Threads**: Number of processing threads (default: 3) 

### Producer Configuration 
- **Producer Threads**: Number of parallel uploaders 
- **Consumer Threads**: Server-side processing threads 
- **Queue Size**: Server queue capacity 
- **Target IP**: Server address (use localhost for local testing) 

## Key Features 
### Backend Features 
- **Distributed gRPC Communication**: Efficient binary protocol 
- **Leaky Bucket Queue**: Configurable queue with drop policy 
- **Multi-threaded Processing**: Parallel video processing 
- **FFmpeg Integration**: Automatic 10-second preview generation 
- **Duplicate Prevention**: Smart filename handling 
- **REST API**: HTTP endpoints for frontend communcation 

### Frontend Features 
- **Real-time Updates**: Live queue statistics every 2 seconds 
- **Hover Previews**: 10-second video preview on mouse hover 
- **Video Player**: Full-screen video playback 
- **Duplicate Detection**: Visual indicator for duplicate files 
- **Progress Visualization**: Queue capacity progress bar 

## Troubleshooting 
### Common Issues 
1. FFmpeg not found 
- Verify FFmpege installation: **ffmpeg --version** 
- Check PATH environment variable 
- Restart 

2. Maven compilation errors 
- mvn clean 
- mvn compile 

3. Frontend dependency issues 
- rm -rf node_modules package-lock.json 
- npm install 