
# 1. Git Repository URL
https://github.com/jiaming2li/CS6650_assignment1.git

# 2. Design Document
## Architecture diagram
![arc](arc.png)
## Major classes and their relationships

```mermaid
classDiagram
    %% ============================================
    %% Client-Part1: Basic Load Testing Client
    %% ============================================
    class ChatLoadTestClient {
        <<Main Orchestrator (Basic)>>
        -int TOTAL_MESSAGES = 500_000
        -int WARMUP_THREADS = 32
        -int WARMUP_MSGS_PER_THREAD = 1000
        -int TOTAL_ROOMS = 20
        -int CONNECTIONS_PER_ROOM = 5
        -int MAIN_THREADS = 2
        -BlockingQueue~ChatMessage~ warmupQueue
        -BlockingQueue~ChatMessage~ mainQueue
        -AtomicInteger successCount
        -AtomicInteger failureCount
        -AtomicInteger totalConnections
        -AtomicInteger reconnectionCount
        -ConcurrentHashMap~Integer, List~WebSocketClient~~ roomPools
        -ConcurrentHashMap~Integer, AtomicInteger~ roomCounters
        +run()
        +runWarmup()
        +runMainTest()
        +generateWarmupMessages()
        +sendToRoom(ChatMessage)
        +sendWithRetry(WebSocketClient, ChatMessage)
        +createWarmupClient(int, CountDownLatch)
        +createConnection(int)
    }

    %% ============================================
    %% Client-Part2: Performance Analysis Client
    %% ============================================
    class ChatLoadTestClientWithMetrics {
        <<Main Orchestrator (Metrics)>>
        -int TOTAL_MESSAGES = 500_000
        -int WARMUP_THREADS = 32
        -int WARMUP_MESSAGES_PER_THREAD = 1000
        -int TOTAL_ROOMS = 20
        -int CONNECTIONS_PER_ROOM = 5
        -int MAIN_THREADS = 2
        -BlockingQueue~ChatMessage~ warmupQueue
        -BlockingQueue~ChatMessage~ mainQueue
        -AtomicInteger successCount
        -AtomicInteger failureCount
        -AtomicInteger totalConnections
        -AtomicInteger reconnectionCount
        -ConcurrentHashMap~String, Long~ latencyTracker
        -ConcurrentLinkedQueue~String[]~ latencyRecords
        -ConcurrentHashMap~Integer, List~WebSocketClient~~ roomConnectionPools
        -ConcurrentHashMap~Integer, AtomicInteger~ roomConnectionCounters
        -List~ThroughputBucket~ throughputBuckets
        +run()
        +runWarmupPhase()
        +runMainPhase()
        +initializeConnectionPools()
        +startWarmupProducer()
        +startMainProducer()
        +sendToRoom(ChatMessage)
        +sendWithRetry(WebSocketClient, ChatMessage)
        +createWarmupClient(int, AtomicInteger)
        +createConnection(int)
        +calculateLatencyStats()
        +writeToCsv()
    }

    %% ============================================
    %% Message Classes
    %% ============================================
    class ChatMessage {
        <<Entity>>
        -String userId
        -String username
        -String message
        -String timestamp
        -String messageType
        -String nonce
        -int roomId
        +toJson() String
    }

    class ChatResponse {
        <<Entity>>
        -String status
        -ChatMessage message
        -String serverTimestamp
    }

    class ThroughputBucket {
        <<Metrics>>
        -long startTime
        -long endTime
        -AtomicInteger messageCount
    }

    %% ============================================
    %% WebSocket Client
    %% ============================================
    class WebSocketClient {
        <<External>>
        -URI serverUri
        -boolean isOpen
        -int roomId
        +connect()
        +send(String)
        +onMessage(String)
    }

    %% ============================================
    %% Server Classes
    %% ============================================
    class ChatWebSocketHandler {
        <<Spring Handler>>
        -Map~String, WebSocketSession~ sessions
        -ObjectMapper objectMapper
        +handleTextMessage(WebSocketSession, TextMessage)
    }

    class ServerApplication {
        <<Spring Boot>>
        +main(String[] args)
    }

    %% ============================================
    %% Relationships
    %% ============================================

    %% Client Inheritance
    ChatLoadTestClientWithMetrics --|> ChatLoadTestClient : Extends

    %% Message Flow
    ChatLoadTestClient --> ChatMessage : Creates/Sends
    ChatLoadTestClientWithMetrics --> ChatMessage : Creates/Sends
    ChatLoadTestClient --> BlockingQueue : Uses
    ChatLoadTestClientWithMetrics --> BlockingQueue : Uses

    %% Connection Pooling
    ChatLoadTestClient o-- WebSocketClient : Aggregation
    ChatLoadTestClient --> roomPools : Manages
    ChatLoadTestClientWithMetrics o-- WebSocketClient : Aggregation
    ChatLoadTestClientWithMetrics --> roomConnectionPools : Manages

    %% Metrics
    ChatLoadTestClient --> AtomicInteger : Counts
    ChatLoadTestClientWithMetrics --> AtomicInteger : Counts
    ChatLoadTestClientWithMetrics --> ConcurrentHashMap : Tracks Latency
    ChatLoadTestClientWithMetrics --> ConcurrentLinkedQueue : Records

    %% Server Relationships
    ChatWebSocketHandler o-- "*" WebSocketSession : Aggregation
    ChatWebSocketHandler --> ChatMessage : Processes
    ChatWebSocketHandler --> ChatResponse : Creates
    ServerApplication *-- ChatWebSocketHandler : Composition

```

## Threading model explanation
Main thread, running `main()`.  

**Warmup Phase**: one thread for message generation(put in `warmupQueue`, 32,000 capacity) and 32 threads(threadpool) where each thread creates a fixed connection with a random room(1-20) and sends 1,000 messages to this room.  

**Main Phase** one thread for message generation(put in `mainQueue`, 20,000 capacity). Message queues buffer messages between generation and sending, ensuring smooth pipelining. Use 2 sender threads(threadpool) to send 500,000 messages as consider the overhead of t2.micro switching between threads. I tried 3/4/32 threads and can not achieve ideal throughput, showing that the bottleneck is 1 vCPU.

## WebSocket connection management strategy 

**Warmup Phase**: each thread creates a fixed connection with a random room(1-20).  

**Main Phase**: use connection pool where 20 rooms each maintain 1 persistent connections (20 total). Sender picks corresponding connection according to message's roomId and sends through the connection. I tried 2/3/5 connections per room and can not achieve ideal throughput. I assumed that CPU can not handle more concurrent messages.

## Little's Law calculations and predictions
![p1](RTT.png)  

I tested mean single message RTT(0.68ms), meaning the theoritical throughput is 20/0.68=29411. But as the cost of CPU context switching, lock contention, network queue/CPU limitation and extra data processing, the real throughput is a little bit lower than 29411(24000+ to 26000+). 


# 3. Test Results
## Screenshot of Part 1

![client1](client_1.png)

## Screenshot of Part 2
![client2](client_2_1.png)
![client2](client_2_2.png)


## Performance analysis charts
![p1](chart.png)
## Evidence of EC2 deployment (EC2 console screenshot)
![p1](ec2.png)


