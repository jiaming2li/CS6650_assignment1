
# 1. Git Repository URL
- `/server` - Server implementation with deployment instructions
- `/client-part1` - Basic load testing client
- `/client-part2` - Client with performance analysis
- `/results` - Test results and analysis
- Include README files with clear running instructions

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
**Warmup Phase**: one thread for message generation(put in `warmupQueue`, 32,000 capacity) and 32 threads where each thread creates a fixed connection with a random room(1-20) and sends 1,000 messages to this room.
**Main Phase** one thread for message generation(put in `mainQueue`, 20,000 capacity). Message queues buffer messages between generation and sending, ensuring smooth pipelining. Use 2 sender threads to send 500,000 messages as consider the overhead of t2.micro switching between threads.

## WebSocket connection management strategy
**Warmup Phase**: each thread creates a fixed connection with a random room(1-20).
**Main Phase**: use connection pool where 20 rooms each maintain 5 persistent connections (100 total), avoiding repeated TCP handshake overhead. The `ConnectionManager` handles connection lifecycle: `initializeConnectionPools()` creates connections per room, `round-robin` selection distributes load via `roomCounters`. All connections are properly closed via `closeAllConnections()` after testing.

## Little's Law calculations and predictions


# 3. Test Results
## Screenshot of Part 1

![p1](client_part1.png)

## Screenshot of Part 2
![p2](client_part2.png)


## Performance analysis charts
![p1](chart.png)
## Evidence of EC2 deployment (EC2 console screenshot)
![p1](ec2.png)


