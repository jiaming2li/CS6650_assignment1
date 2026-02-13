package com.chatflow.client;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.chatflow.client.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Optimized Chat Load Test Client for t2.micro with 20 rooms
 * 
 * Threading Model:
 * - Warmup: 32 threads × 1000 messages (as per assignment)
 * - Main: 8 threads with 80 persistent connections (4 per room × 20 rooms)
 */
public class ChatLoadTestClientWithMetrics {
    
    // ==================== Assignment Requirements ====================
    private static final int TOTAL_MESSAGES = 500_000;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    
    // ==================== Room Configuration ====================
    private static final int TOTAL_ROOMS = 20;
    private static final int CONNECTIONS_PER_ROOM = 1; 
    private static final int TOTAL_CONNECTIONS = TOTAL_ROOMS * CONNECTIONS_PER_ROOM; // 100

    // ==================== t2.micro Optimized Configuration ====================
    // For 1 vCPU: threads = CPU cores = 4 for optimal throughput
    // More connections = more parallelism potential when server can handle
    private static final int MAIN_THREADS = 2;

    // Retry configuration
    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 1; 
    private static final long MAX_BACKOFF_MS = 50;     // Maximum 50ms backoff
    
    // ==================== Server Configuration ====================
    // Use environment variable or default to localhost for local testing
    private static final String SERVER_HOST = System.getenv("SERVER_HOST") != null ? 
        System.getenv("SERVER_HOST") : "localhost";
    private static final int SERVER_PORT = 8080;
    private static final String SERVER_URL = "ws://" + SERVER_HOST + ":" + SERVER_PORT + "/chat/";
    
    // ==================== Message Queues (Separate for Warmup and Main) ====================
    // Warmup queue: 32,000 messages (32 threads × 1000)
    private final BlockingQueue<ChatMessage> warmupQueue = 
            new LinkedBlockingQueue<>(32000);
    
    // Main queue: 500,000 messages
    private final BlockingQueue<ChatMessage> mainQueue = 
            new LinkedBlockingQueue<>(20000);
    
    // ==================== Metrics ====================
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnectionCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Long> latencyTracker = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String[]> latencyRecords = new ConcurrentLinkedQueue<>();
    private volatile boolean warmupProducerFinished = false;
    private volatile boolean mainProducerFinished = false;
    
    private static final Gson gson = new Gson();
    private final AtomicInteger nonceGenerator = new AtomicInteger(0);
    
    // ==================== Connection Pool (Room-aware) ====================
    // Map<roomId, List<WebSocketClient>> for room-specific connection pooling
    private final ConcurrentHashMap<Integer, List<WebSocketClient>> roomConnectionPools = 
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> roomConnectionCounters = 
            new ConcurrentHashMap<>();
    
    // ==================== Message Pool (50 predefined messages) ====================
    private static final List<String> MESSAGE_POOL = Arrays.asList(
        "Hello everyone!",
        "How are you doing?",
        "Great weather today!",
        "Nice to meet you!",
        "What's up?",
        "Having a great day!",
        "Thanks for the help!",
        "See you later!",
        "Good morning!",
        "Good evening!",
        "How's your project going?",
        "Any updates?",
        "Let's schedule a meeting",
        "Can you review this?",
        "Great work team!",
        "Welcome to the channel!",
        "Happy Friday!",
        "Weekend plans?",
        "Lunch time!",
        "Coffee break?",
        "Nice presentation!",
        "Thanks for sharing",
        "Interesting approach",
        "Good point!",
        "I agree with you",
        "Let me think about it",
        "Sounds good!",
        "I'll handle it",
        "Can you help me?",
        "Sure thing!",
        "No problem at all",
        "What time is the meeting?",
        "Where are we meeting?",
        "The meeting is at 3pm",
        "Don't forget the deadline",
        "Great job on the demo!",
        "Excellent work!",
        "Thanks for your hard work",
        "Welcome aboard!",
        "Congratulations!",
        "That's fantastic news!",
        "I'm excited about this",
        "Looking forward to it",
        "Sounds interesting",
        "Let me know when you're free",
        "Quick question:",
        "Just following up",
        "Thanks for the update",
        "Got it, thanks!"
    );
    
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("ChatFlow Load Test Client - Optimized for t2.micro");
        System.out.println("=".repeat(60));
        System.out.println("Configuration:");
        System.out.println("  - Warmup: " + WARMUP_THREADS + " threads × " + WARMUP_MESSAGES_PER_THREAD + " msgs");
        System.out.println("  - Main: " + TOTAL_MESSAGES + " msgs, " + TOTAL_ROOMS + " rooms, " + TOTAL_CONNECTIONS + " connections");
        System.out.println("  - Main threads: " + MAIN_THREADS);
        System.out.println("=".repeat(60));
        
        long totalStartTime = System.currentTimeMillis();
        
        ChatLoadTestClientWithMetrics instance = new ChatLoadTestClientWithMetrics();
        
        // Phase 1: Generate Warmup Messages (32,000) - runs in background
        System.out.println("\n[Phase 1] Generating " + (WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD) + " warmup messages...");
        Thread warmupGenerator = new Thread(instance::generateWarmupMessages);
        warmupGenerator.start();
        
        // Wait for warmup messages to be generated
        warmupGenerator.join();
        
        // Phase 2: Generate Main Messages (500,000) - runs in background
        System.out.println("\n[Phase 2] Generating " + TOTAL_MESSAGES + " main messages...");
        Thread mainGenerator = new Thread(instance::generateMainMessages);
        mainGenerator.start();
        
        // Phase 3: Warmup (32 threads × 1000 messages)
        System.out.println("\n[Phase 3] Running warmup phase...");
        long warmupStart = System.currentTimeMillis();
        instance.runWarmupPhase();
        long warmupEnd = System.currentTimeMillis();
        System.out.println("[Phase 3] Warmup completed in " + (warmupEnd - warmupStart) / 1000.0 + "s");
        
        // Phase 4: Main Load Test
        System.out.println("\n[Phase 4] Running main load test...");
        long mainStart = System.currentTimeMillis();
        instance.runMainPhase();
        long mainEnd = System.currentTimeMillis();
        
        // Wait for main message generation to finish
        mainGenerator.join();
        
        // Wait for all responses
        instance.waitForCompletion();

        // Close all connections after receiving all responses
        instance.closeAllConnections();

        long totalEnd = System.currentTimeMillis();
        
        // Print Results
        instance.printResults(totalStartTime, warmupStart, warmupEnd, mainStart, mainEnd);
        instance.writeToCsv();
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Test completed!");
        System.out.println("=".repeat(60));
    }
    
    // ==================== Message Generation ====================
    
    private void generateWarmupMessages() {
        Random random = new Random();
        try {
            for (int i = 0; i < WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD; i++) {
                ChatMessage msg = createRandomMessage(random);
                warmupQueue.put(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            warmupProducerFinished = true;
        }
    }
    
    private void generateMainMessages() {
        Random random = new Random();
        try {
            for (int i = 0; i < TOTAL_MESSAGES; i++) {
                ChatMessage msg = createRandomMessage(random);
                mainQueue.put(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mainProducerFinished = true;
            System.out.println("[Phase 2] Main message generation complete! Queue has " + mainQueue.size() + " messages remaining");
        }
    }
    
    private ChatMessage createRandomMessage(Random random) {
        int userId = random.nextInt(100000) + 1;
        String username = "user" + userId;
        String message = MESSAGE_POOL.get(random.nextInt(MESSAGE_POOL.size()));
        int roomId = random.nextInt(TOTAL_ROOMS) + 1;
        
        String messageType;
        int typeRand = random.nextInt(100);
        if (typeRand < 90) messageType = "TEXT";
        else if (typeRand < 95) messageType = "JOIN";
        else messageType = "LEAVE";
        
        String nonce = String.valueOf(nonceGenerator.incrementAndGet());
        
        ChatMessage msg = new ChatMessage(
                String.valueOf(userId),
                username,
                message,
                Instant.now().toString(),
                messageType,
                roomId
        );
        msg.setNonce(nonce);
        return msg;
    }
    
    // ==================== Warmup Phase (32 threads × 1000 messages) ====================
    
    private void runWarmupPhase() throws InterruptedException {
        int actualWarmupThreads = WARMUP_THREADS;  // 32 threads for warmup as per assignment
        ExecutorService executor = Executors.newFixedThreadPool(actualWarmupThreads);
        CountDownLatch allThreadsLatch = new CountDownLatch(actualWarmupThreads);
        System.out.println("Starting warmup with " + actualWarmupThreads + " threads ("
                          + actualWarmupThreads + " × " + WARMUP_MESSAGES_PER_THREAD + " = "
                          + (actualWarmupThreads * WARMUP_MESSAGES_PER_THREAD) + " messages)...");
        
        for (int i = 0; i < actualWarmupThreads; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                // Each thread has its own latch for responses
                CountDownLatch threadLatch = new CountDownLatch(WARMUP_MESSAGES_PER_THREAD);
                
                try {
                    // Random room for this warmup thread
                    int roomId = new Random().nextInt(TOTAL_ROOMS) + 1;
                    
                    // Create client with latch
                    WebSocketClient client = createWarmupClient(roomId, threadLatch);
                    
                    int messagesSent = 0;
                    
                    for (int j = 0; j < WARMUP_MESSAGES_PER_THREAD; j++) {
                        try {
                            ChatMessage msg = warmupQueue.take();
                            msg.setRoomId(roomId);
                            client.send(gson.toJson(msg));
                            messagesSent++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    boolean completed = threadLatch.await(30, TimeUnit.SECONDS);
                    
                    client.closeBlocking();
                    
                    // Only log first 3 threads to avoid too much output
                    if (threadIdx < 3) {
                        System.out.println("Warmup thread " + threadIdx + " finished. Sent: " + messagesSent);
                    }
                } catch (Exception e) {
                    System.err.println("Warmup error: " + e.getMessage());
                } finally {
                    allThreadsLatch.countDown();
                }
            });
        }
        
        allThreadsLatch.await();
        executor.shutdown();
        
        System.out.println("Warmup completed!");
    }
    
    // Create WebSocketClient for warmup with latch
    private WebSocketClient createWarmupClient(int roomId, CountDownLatch threadLatch) throws Exception {
        URI uri = new URI(SERVER_URL + roomId);
        
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onMessage(String message) {
                try {
                    if (message != null && message.contains("SUCCESS")) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    threadLatch.countDown();
                }
            }
            
            @Override
            public void onOpen(ServerHandshake handshake) {}
            
            @Override
            public void onClose(int code, String reason, boolean remote) {}
            
            @Override
            public void onError(Exception ex) {
                System.err.println("WebSocket error in warmup client: " + ex.getMessage());
            }
        };
        
        client.connectBlocking();
        return client;
    }
    
    // ==================== Main Phase (Optimized for t2.micro) ====================
    
    private void runMainPhase() throws Exception {
        // Step 1: Initialize connection pools for all rooms (4 connections per room)
        System.out.println("Initializing " + TOTAL_CONNECTIONS + " connections (" + CONNECTIONS_PER_ROOM + " per room × " + TOTAL_ROOMS + " rooms)...");
        initializeConnectionPools();
        System.out.println("All connections established!");
        
        // Step 2: Start sender threads (reusing connections)
        ExecutorService senderExecutor = Executors.newFixedThreadPool(MAIN_THREADS);
        CountDownLatch latch = new CountDownLatch(MAIN_THREADS);
        
        for (int i = 0; i < MAIN_THREADS; i++) {
            //final int threadId = i;
            senderExecutor.submit(() -> {
                try {
                    //System.out.println("Sender thread " + threadId + " started");
                    // Continue until producer is finished AND queue is empty
                    while (!mainProducerFinished || !mainQueue.isEmpty()) {
                        ChatMessage msg = mainQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (msg != null) {
                            sendMessageToRoom(msg);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        senderExecutor.shutdown();
    }
    
    private void initializeConnectionPools() throws Exception {
        System.out.println("Initializing connections with timeout...");
        
        for (int roomId = 1; roomId <= TOTAL_ROOMS; roomId++) {
            // Initialize per-room structures
            roomConnectionPools.putIfAbsent(roomId, Collections.synchronizedList(new ArrayList<>()));
            roomConnectionCounters.putIfAbsent(roomId, new AtomicInteger(0));
            
            int connCount = 0;
            for (int conn = 0; conn < CONNECTIONS_PER_ROOM; conn++) {
                try {
                    // Use timeout for connection
                    WebSocketClient client = createConnectionWithTimeout(roomId, 10);  // 10s timeout
                    if (client != null && client.isOpen()) {
                        roomConnectionPools.get(roomId).add(client);
                        connCount++;
                        totalConnections.incrementAndGet();
                    } else {
                        System.err.println("Connection not open for room " + roomId);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to create connection for room " + roomId + ": " + e.getMessage());
                }
            }
            //Only log rooms 1-3 and the last room to reduce output
            if (roomId <= 3 || roomId == TOTAL_ROOMS) {
                System.out.println("Room " + roomId + ": " + connCount + " connections established");
            }
        }
        
        System.out.println("...");
        System.out.println("Total connections: " + totalConnections.get());
    }
    
    // ==================== Room-aware Message Sending ====================
    
    private void sendMessageToRoom(ChatMessage msg) {
        int roomId = msg.getRoomId();
        List<WebSocketClient> connections = roomConnectionPools.get(roomId);
        
        if (connections == null || connections.isEmpty()) {
            // No connections available for this room, fail the message
            failureCount.incrementAndGet();
            return;
        }
        
        // Round-robin selection from available connections for this room
        int index = roomConnectionCounters.get(roomId).getAndIncrement() % connections.size();
        WebSocketClient client = connections.get(index);
        
        if (client != null && client.isOpen()) {
            sendWithRetry(client, msg);
        } else {
            // Connection is dead, try to find another
            for (WebSocketClient c : connections) {
                if (c != null && c.isOpen()) {
                    sendWithRetry(c, msg);
                    return;
                }
            }
            // All connections dead, fail the message
            failureCount.incrementAndGet();
        }
    }
    
    // ==================== Connection Management ====================
    
    private WebSocketClient createConnection(int roomId) throws Exception {
        URI uri = new URI(SERVER_URL + roomId);
        
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onMessage(String message) {
                long endTime = System.currentTimeMillis();
                
                try {
                    JsonObject res = gson.fromJson(message, JsonObject.class);
                    
                    if (res == null || !res.has("status")) {
                        // Unknown response format - count as failure
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    String status = res.get("status").getAsString();
                    
                    // If status is not SUCCESS, count as failure
                    if (!"SUCCESS".equals(status)) {
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    // Check if message object exists and has nonce
                    if (!res.has("message") || res.get("message").isJsonNull()) {
                        // No message object - but SUCCESS, count as success
                        successCount.incrementAndGet();
                        return;
                    }
                    
                    JsonObject msgObj = res.getAsJsonObject("message");
                    if (!msgObj.has("nonce")) {
                        // No nonce - count as success
                        successCount.incrementAndGet();
                        return;
                    }
                    
                    String msgId = msgObj.get("nonce").getAsString();
                    Long startTime = latencyTracker.remove(msgId);
                    
                    if (startTime != null) {
                        successCount.incrementAndGet();
                        
                        long latency = endTime - startTime;
                        String messageType = msgObj.has("messageType") ? 
                            msgObj.get("messageType").getAsString() : "TEXT";
                        
                        int respRoomId = msgObj.has("roomId") ?
                            msgObj.get("roomId").getAsInt() : 0;
                        
                        String[] record = {
                                String.valueOf(startTime),
                                messageType,
                                String.valueOf(latency),
                                status,
                                String.valueOf(respRoomId)
                        };
                        latencyRecords.add(record);
                    } else {
                        // Nonce not found, count as success
                        successCount.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    System.err.println("Failed to parse message: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    // Print first 200 chars of raw message for debugging
                    if (message != null && message.length() > 200) {
                        System.err.println("  Raw (first 200): " + message.substring(0, 200));
                    } else {
                        System.err.println("  Raw: " + message);
                    }
                }
            }
            
            @Override
            public void onOpen(ServerHandshake handshake) {
                // Connection opened
            }
            
            @Override
            public void onClose(int code, String reason, boolean remote) {
                // Connection closed
            }
            
            @Override
            public void onError(Exception ex) {
                // Error occurred
            }
        };
        
        client.connectBlocking();
        return client;
    }
    
    // Connection with timeout (for warmup phase)
    private WebSocketClient createConnectionWithTimeout(int roomId, int timeoutSeconds) throws Exception {
        URI uri = new URI(SERVER_URL + roomId);
        
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onMessage(String message) {
                // Same as createConnection - delegate to shared handler
                long endTime = System.currentTimeMillis();
                
                try {
                    JsonObject res = gson.fromJson(message, JsonObject.class);
                    
                    if (res == null || !res.has("status")) {
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    String status = res.get("status").getAsString();
                    
                    if (!"SUCCESS".equals(status)) {
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    if (!res.has("message") || res.get("message").isJsonNull()) {
                        successCount.incrementAndGet();
                        return;
                    }
                    
                    JsonObject msgObj = res.getAsJsonObject("message");
                    if (!msgObj.has("nonce")) {
                        successCount.incrementAndGet();
                        return;
                    }
                    
                    String msgId = msgObj.get("nonce").getAsString();
                    Long startTime = latencyTracker.remove(msgId);
                    
                    if (startTime != null) {
                        successCount.incrementAndGet();
                        
                        long latency = endTime - startTime;
                        String messageType = msgObj.has("messageType") ? 
                            msgObj.get("messageType").getAsString() : "TEXT";
                        
                        int respRoomId = msgObj.has("roomId") ?
                            msgObj.get("roomId").getAsInt() : 0;
                        
                        String[] record = {
                                String.valueOf(startTime),
                                messageType,
                                String.valueOf(latency),
                                status,
                                String.valueOf(respRoomId)
                        };
                        latencyRecords.add(record);
                    } else {
                        // Nonce not found, count as success
                        successCount.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.err.println("Failed to parse message: " + e.getMessage());
                }
            }
            
            @Override
            public void onOpen(ServerHandshake handshake) {
                // Connection opened
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                // Connection closed
            }
            
            @Override
            public void onError(Exception ex) {
                // Error occurred
            }
        };
        
        // Connect with timeout
        client.connectBlocking(timeoutSeconds, TimeUnit.SECONDS);
        return client;
    }
    
    // ==================== Retry with Exponential Backoff (5 retries as per assignment) ====================
    
    private void sendWithRetry(WebSocketClient client, ChatMessage msg) {
        String currentNonce = msg.getNonce();
        if (currentNonce == null) {
            currentNonce = String.valueOf(nonceGenerator.incrementAndGet());
            msg.setNonce(currentNonce);
        }
        
        String json = toJson(msg);
        latencyTracker.put(currentNonce, System.currentTimeMillis());
        
        int attempt = 0;
        long backoffMs = INITIAL_BACKOFF_MS;
        
        while (attempt < MAX_RETRIES) {
            if (client != null && client.isOpen()) {
                try {
                    client.send(json);
                    return;
                } catch (Exception e) {
                    // Send failed, will retry
                }
            }
            
            // Exponential backoff with cap
            attempt++;
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);  // Cap at MAX_BACKOFF_MS
                    
                    // Try to reconnect
                    client = handleReconnection(client, msg.getRoomId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Failed after all retries
        latencyTracker.remove(currentNonce);
        failureCount.incrementAndGet();
    }
    
    private WebSocketClient handleReconnection(WebSocketClient oldClient, int roomId) {
        reconnectionCount.incrementAndGet();
        
        try {
            if (oldClient != null) {
                try {
                    oldClient.closeBlocking();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
            
            WebSocketClient newClient = createConnection(roomId);
            
            if (newClient.isOpen()) {
                // Update connection pool
                List<WebSocketClient> connections = roomConnectionPools.get(roomId);
                if (connections != null) {
                    synchronized (connections) {
                        connections.remove(oldClient);
                        connections.add(newClient);
                    }
                }
                return newClient;
            }
        } catch (Exception e) {
            System.err.println("Reconnection failed: " + e.getMessage());
        }
        return null;
    }
    
    private void closeAllConnections() {
        System.out.println("Closing all connections...");
        for (Map.Entry<Integer, List<WebSocketClient>> entry : roomConnectionPools.entrySet()) {
            List<WebSocketClient> connections = entry.getValue();
            synchronized (connections) {
                for (WebSocketClient client : connections) {
                    if (client != null && client.isOpen()) {
                        try {
                            client.closeBlocking();
                        } catch (Exception e) {
                            // Ignore close errors
                        }
                    }
                }
            }
        }
    }
    
    // ==================== JSON Conversion ====================
    
    private String toJson(ChatMessage msg) {
        JsonObject json = new JsonObject();
        json.addProperty("userId", msg.getUserId());
        json.addProperty("username", msg.getUsername());
        json.addProperty("message", msg.getMessage());
        json.addProperty("timestamp", msg.getTimestamp());
        json.addProperty("messageType", msg.getMessageType());
        json.addProperty("nonce", msg.getNonce());
        json.addProperty("roomId", msg.getRoomId());
        return json.toString();
    }
    
    // ==================== Wait for Completion ====================
    
    private void waitForCompletion() {
        System.out.println("Waiting for all responses...");
        long startTime = System.currentTimeMillis();
        long lastProgressTime = startTime;
        long lastProcessed = 0;
        long expectedTotal = TOTAL_MESSAGES + (WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD);
        int stallCount = 0;
        
        while (successCount.get() + failureCount.get() < expectedTotal) {
            try {
                Thread.sleep(5000);  // Check every 5 seconds
                
                long processed = successCount.get() + failureCount.get();
                double progress = (processed * 100.0) / expectedTotal;
                long remaining = expectedTotal - processed;
                System.out.printf("Progress: %.1f%% (%d/%d messages, remaining: %d)%n", 
                    progress, processed, expectedTotal, remaining);
                
                // Progress stagnation detection - if no progress for 600 seconds (10 min), force continue
                if (processed == lastProcessed) {
                    stallCount++;
                    if (stallCount > 12) {  // 12 * 5s = 60s of no progress
                        System.out.println("No progress for 60 seconds. Checking latency tracker...");
                        int remainingNonces = latencyTracker.size();
                        System.out.println("Nonces in tracker: " + remainingNonces);
                        if (remainingNonces > 0) {
                            // Print some remaining nonces for debugging
                            int printCount = 0;
                            for (String nonce : latencyTracker.keySet()) {
                                if (printCount++ < 5) {
                                    long waitTime = System.currentTimeMillis() - latencyTracker.get(nonce);
                                    System.out.println("  Nonce " + nonce + " waiting for " + waitTime/1000 + "s");
                                }
                            }
                        }
                        stallCount = 0;  // Reset, don't exit yet
                    }
                } else {
                    lastProgressTime = System.currentTimeMillis();
                    lastProcessed = processed;
                    stallCount = 0;
                }
                
                // Overall timeout - 15 minutes
                if (System.currentTimeMillis() - startTime > 6000) {
                    System.out.println("Timeout waiting for responses. Proceeding to results...");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("All messages processed!");
    }
    
    // ==================== Print Results ====================
    
    private void printResults(long totalStart, long warmupStart, long warmupEnd,
                             long mainStart, long mainEnd) {
        int totalSent = successCount.get() + failureCount.get();
        int expectedTotal = TOTAL_MESSAGES + (WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD);
        long totalEndTime = System.currentTimeMillis();
        long totalTimeMs = totalEndTime - totalStart;
        double totalTimeSec = totalTimeMs / 1000.0;
        
        long warmupTimeMs = warmupEnd - warmupStart;
        long mainTimeMs = mainEnd - mainStart;
        
        double warmupThroughput = (WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD) / (warmupTimeMs / 1000.0);
        double mainThroughput = TOTAL_MESSAGES / (mainTimeMs / 1000.0);
        double overallThroughput = totalSent / totalTimeSec;
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("TEST RESULTS");
        System.out.println("=".repeat(60));

        System.out.println("\n[Warmup Phase]");
        System.out.println("  Messages sent: " + (WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD));
        System.out.println("  Duration: " + warmupTimeMs / 1000.0 + "s");
        System.out.println("  Throughput: " + String.format("%.0f", warmupThroughput) + " msgs/s");
        
        System.out.println("\n[Main Phase]");
        System.out.println("  Messages sent: " + TOTAL_MESSAGES);
        System.out.println("  Duration: " + mainTimeMs / 1000.0 + "s");
        System.out.println("  Throughput: " + String.format("%.0f", mainThroughput) + " msgs/s");
        
        System.out.println("\n[Overall]");
        System.out.println("  Total messages: " + totalSent + "/" + expectedTotal);
        System.out.println("  Successful: " + successCount.get());
        System.out.println("  Failed: " + failureCount.get());
        System.out.println("  Success rate: " + String.format("%.2f%%", 
            successCount.get() * 100.0 / totalSent));
        System.out.println("  Total duration: " + String.format("%.2f", totalTimeSec) + "s");
        System.out.println("  Overall throughput: " + String.format("%.0f", overallThroughput) + " msgs/s");
        
        System.out.println("\n[Connections]");
        System.out.println("  Total connections created: " + totalConnections.get());
        System.out.println("  Reconnections: " + reconnectionCount.get());
        System.out.println("  Connections per room: " + CONNECTIONS_PER_ROOM);
        
        System.out.println("\n[Main Phase Latency Statistics]");
        calculateLatencyStats();
        
        System.out.println("=".repeat(60));
    }
    
    private void calculateLatencyStats() {
        if (latencyRecords.isEmpty()) {
            System.out.println("  No latency data available");
            return;
        }
        
        List<Long> latencies = new ArrayList<>();
        for (String[] record : latencyRecords) {
            try {
                latencies.add(Long.parseLong(record[2]));
            } catch (NumberFormatException e) {
                // Skip invalid records
        }
    }

        if (latencies.isEmpty()) {
            System.out.println("  No valid latency data");
            return;
        }
        
        Collections.sort(latencies);
        
        int n = latencies.size();
        long sum = latencies.stream().mapToLong(Long::longValue).sum();
        long min = latencies.get(0);
        long max = latencies.get(n - 1);
        long median = latencies.get(n / 2);
        double mean = (double) sum / n;
        long p95 = latencies.get((int) (n * 0.95));
        long p99 = latencies.get((int) (n * 0.99));
        
        System.out.println("  Min: " + min + "ms");
        System.out.println("  Max: " + max + "ms");
        System.out.println("  Mean: " + String.format("%.2f", mean) + "ms");
        System.out.println("  Median: " + median + "ms");
        System.out.println("  95th percentile: " + p95 + "ms");
        System.out.println("  99th percentile: " + p99 + "ms");
    }
    
    // ==================== CSV Export ====================
    
    private void writeToCsv() {
        String filename = "test_results.csv";
        System.out.println("\nWriting results to " + filename + "...");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write("StartTime,MessageType,LatencyMS,StatusCode,RoomId");
            writer.newLine();
            
            int count = 0;
            for (String[] record : latencyRecords) {
                writer.write(String.join(",", record));
                writer.newLine();
                count++;
            }
            
            System.out.println("CSV export completed. Total records: " + count);
        } catch (IOException e) {
            System.err.println("Error writing CSV: " + e.getMessage());
        }
    }
}
