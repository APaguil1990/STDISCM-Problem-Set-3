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

## Multi-VM Deployment

The system can be deployed across multiple virtual machines for distributed operation. Each component can run on a separate VM.

### VM Setup Overview
- **VM 1**: MediaServer (gRPC + HTTP server)
- **VM 2**: ProducerClient (video upload client)
- **VM 3**: React Frontend (web application)

### Configuration Files

The system uses configuration files to specify IP addresses and ports for multi-VM deployment:

#### 1. MediaServer Configuration (`backend/config.properties`)
```properties
# gRPC server port (default: 9090)
grpc.port=9090

# HTTP server port (default: 8080)
http.port=8080

# Maximum queue size (default: 10)
queue.size=10

# Number of consumer threads (default: 3)
consumer.threads=3

# Bind address - use 0.0.0.0 to accept connections from any IP
bind.address=0.0.0.0
```

**Important**: Set `bind.address=0.0.0.0` to allow connections from other VMs. For security, you can restrict to specific IPs.

#### 2. ProducerClient Configuration (`backend/producer-config.properties`)
```properties
# Target MediaServer IP address
# Replace with the IP address of the VM running MediaServer
target.server.ip=192.168.1.100

# Target MediaServer gRPC port (default: 9090)
target.server.port=9090
```

**Important**: Replace `192.168.1.100` with the actual IP address of the VM running MediaServer.

#### 3. Frontend Configuration (`frontend/config.json`)
```json
{
  "SERVER_IP": "192.168.1.100",
  "SERVER_PORT": "8080"
}
```

**Important**: Replace `192.168.1.100` with the actual IP address of the VM running MediaServer.

### Multi-VM Deployment Steps

#### Step 1: Setup MediaServer VM
1. Copy the entire project to VM 1
2. Edit `backend/config.properties`:
   - Set `bind.address=0.0.0.0` (to accept connections from other VMs)
   - Configure ports as needed
3. Start MediaServer:
   ```bash
   cd backend
   mvn clean compile
   mvn exec:java -D"exec.mainClass=com.example.mediaservice.MediaServer"
   ```
4. Note the VM's IP address (e.g., `192.168.1.100`)

#### Step 2: Setup ProducerClient VM
1. Copy the entire project to VM 2
2. Edit `backend/producer-config.properties`:
   - Set `target.server.ip` to the MediaServer VM's IP address
   - Set `target.server.port` to match MediaServer's gRPC port
3. Start ProducerClient:
   ```bash
   cd backend
   mvn clean compile
   mvn exec:java -D"exec.mainClass=com.example.mediaservice.ProducerClient"
   ```
4. When prompted for target IP, you can press Enter to use the config file value, or override it

#### Step 3: Setup Frontend VM
1. Copy the entire project to VM 3
2. Edit `frontend/config.json`:
   - Set `SERVER_IP` to the MediaServer VM's IP address
   - Set `SERVER_PORT` to match MediaServer's HTTP port
3. Start the frontend:
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
4. Access the web application from any browser

### Command-Line Overrides

All components support command-line arguments that override configuration file values:

**MediaServer**:
```bash
mvn exec:java -D"exec.mainClass=com.example.mediaservice.MediaServer" -Dexec.args="9090 8080 10 3 0.0.0.0"
# Arguments: [grpcPort] [httpPort] [queueSize] [consumerThreads] [bindAddress]
```

**ProducerClient**: The target IP can be overridden when prompted during execution.

### Network Requirements

- All VMs must be on the same network or have network connectivity
- Firewall rules must allow:
  - MediaServer VM: Incoming connections on gRPC port (default: 9090) and HTTP port (default: 8080)
  - ProducerClient VM: Outgoing connections to MediaServer gRPC port
  - Frontend VM: Outgoing connections to MediaServer HTTP port
- For cloud deployments, ensure security groups/network rules are configured appropriately

### Testing Multi-VM Setup

1. Verify MediaServer is accessible:
   ```bash
   # From ProducerClient VM or Frontend VM
   curl http://[MEDIA_SERVER_IP]:8080/api/stats
   ```

2. Check network connectivity:
   ```bash
   # From ProducerClient VM
   ping [MEDIA_SERVER_IP]
   
   # Test gRPC port (if netcat/telnet available)
   telnet [MEDIA_SERVER_IP] 9090
   ```

## Project Structure 
```
Problem Set 3/
├── backend/
│   ├── pom.xml                          # Maven dependencies
│   ├── config.properties                # MediaServer configuration
│   ├── producer-config.properties       # ProducerClient configuration
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
│   ├── config.json                      # Frontend configuration
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
Configuration is managed via `backend/config.properties`:
- **grpc.port**: gRPC port (default: 9090) 
- **http.port**: HTTP port (default: 8080) 
- **queue.size**: Max queue capacity (default: 10) 
- **consumer.threads**: Number of processing threads (default: 3)
- **bind.address**: Bind address for servers (default: 0.0.0.0 for multi-VM)

Command-line arguments can override config file values:
```bash
mvn exec:java -D"exec.mainClass=com.example.mediaservice.MediaServer" -Dexec.args="[grpcPort] [httpPort] [queueSize] [consumerThreads] [bindAddress]"
```

### Producer Configuration 
Configuration is managed via `backend/producer-config.properties`:
- **target.server.ip**: Target MediaServer IP address (default: localhost)
- **target.server.port**: Target MediaServer gRPC port (default: 9090)

Runtime configuration:
- **Producer Threads**: Number of parallel uploaders (entered at runtime)
- **Consumer Threads**: Server-side processing threads (entered at runtime)
- **Queue Size**: Server queue capacity (entered at runtime)
- **Target IP**: Can be overridden when prompted (uses config file as default)

### Frontend Configuration
Configuration is managed via `frontend/config.json`:
- **SERVER_IP**: MediaServer IP address (default: localhost)
- **SERVER_PORT**: MediaServer HTTP port (default: 8080) 

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