package com.chatflow.client;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.chatflow.client.model.ChatMessage;

/**
 * Basic Chat Load Test Client
 * 
 * Required Metrics (Assignment 1):
 * 1. Number of successful messages sent
 * 2. Number of failed messages
 * 3. Total runtime (wall time)
 * 4. Overall throughput (messages/second)
 * 5. Connection statistics (total connections, reconnections)
 */
public class ChatLoadTestClient {
    
    // Configuration
    private static final int TOTAL_MESSAGES = 500_000;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_MSGS_PER_THREAD = 1000;
    private static final int TOTAL_ROOMS = 20;
    private static final int CONNECTIONS_PER_ROOM = 1;
    private static final int MAIN_THREADS = 2;
    private static final String SERVER_HOST = System.getenv("SERVER_HOST") != null ? 
        System.getenv("SERVER_HOST") : "localhost";
    private static final int SERVER_PORT = 8080;
    private static final String SERVER_URL = "ws://" + SERVER_HOST + ":" + SERVER_PORT + "/chat/";
    
    // Message queues
    private final BlockingQueue<ChatMessage> warmupQueue = new LinkedBlockingQueue<>(32000);
    private final BlockingQueue<ChatMessage> mainQueue = new LinkedBlockingQueue<>(20000);

    // Metrics (Assignment 1 Requirements)
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnectionCount = new AtomicInteger(0);

    // Connection pools
    private final ConcurrentHashMap<Integer, List<WebSocketClient>> roomPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> roomCounters = new ConcurrentHashMap<>();
    
    // Track main test duration
    private long mainTestStart = 0;
    private long mainTestEnd = 0;

    // Message pool
    private static final String[] MESSAGES = {
        "Hello!", "How are you?", "Test message", "Hi there", "Good morning",
        "Thanks!", "See you", "OK", "Great!", "Nice"
    };
    
    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();
        new ChatLoadTestClient().run();
        long endTime = System.currentTimeMillis();
        System.out.println("\n" + "=".repeat(50));
        System.out.println("Total runtime: " + (endTime - startTime) / 1000.0 + "s");
    }

    public void run() throws Exception {
        System.out.println("=== ChatLoadTestClient (Basic) ===");
        //System.out.println("Messages: " + TOTAL_MESSAGES + ", Rooms: " + TOTAL_ROOMS + 
        //                  ", Connections: " + (TOTAL_ROOMS * CONNECTIONS_PER_ROOM));

        // Generate warmup messages
        generateWarmupMessages();

        // Warmup
        runWarmup();

        // Main test
        runMainTest();

        // Print results
        printResults();
    }
    
    private void generateWarmupMessages() {
        System.out.println("Generating warmup messages...");
        for (int i = 0; i < WARMUP_THREADS * WARMUP_MSGS_PER_THREAD; i++) {
            warmupQueue.offer(createRandomMessage());
        }
        System.out.println("Warmup queue: " + warmupQueue.size() + " messages");
    }

    private void runWarmup() throws Exception {
        System.out.println("\n=== Warmup Phase ===");
        System.out.println("Messages: " + (WARMUP_THREADS * WARMUP_MSGS_PER_THREAD) + 
                          " (" + WARMUP_THREADS + " threads × " + WARMUP_MSGS_PER_THREAD + "), Rooms: 1-" + TOTAL_ROOMS);
        long warmupStart = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(WARMUP_THREADS);
        CountDownLatch allThreadsLatch = new CountDownLatch(WARMUP_THREADS);

        for (int i = 0; i < WARMUP_THREADS; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                // Each thread has its own latch for responses
                CountDownLatch threadLatch = new CountDownLatch(WARMUP_MSGS_PER_THREAD);
                
                try {
                    // Random room for this warmup thread
                    int roomId = new Random().nextInt(TOTAL_ROOMS) + 1;
                    
                    // Create client with latch
                    WebSocketClient client = createWarmupClient(roomId, threadLatch);
                    
                    int messagesSent = 0;
                    
                    for (int j = 0; j < WARMUP_MSGS_PER_THREAD; j++) {
                        try {
                            ChatMessage msg = warmupQueue.take();
                            msg.setRoomId(roomId);
                            client.send(toJson(msg));
                            messagesSent++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    boolean completed = threadLatch.await(30, TimeUnit.SECONDS);
                    
                    client.closeBlocking();
                    
                    //Only log first 3 threads to reduce output
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
        long warmupEnd = System.currentTimeMillis();
        double warmupTime = (warmupEnd - warmupStart) / 1000.0;
        int warmupSuccess = successCount.get();
        System.out.println("...");
        System.out.println("Warmup complete: " + warmupSuccess + " success, " + failureCount.get() + " failed");
        System.out.printf("Warmup throughput: %.0f msgs/s (%.2fs)%n", warmupSuccess / warmupTime, warmupTime);
        successCount.set(0);
        failureCount.set(0);
    }
    
    // Create WebSocketClient for warmup with latch
    private WebSocketClient createWarmupClient(int roomId, CountDownLatch threadLatch) throws Exception {
        URI uri = new URI(SERVER_URL + roomId);
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake h) {}
            
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
            public void onClose(int c, String r, boolean remote) {}
            
            @Override
            public void onError(Exception e) {
                System.err.println("WebSocket error in warmup client: " + e.getMessage());
            }
        };
        client.connectBlocking();
        return client;
    }
    
    private void runMainTest() throws Exception {
        System.out.println("\n=== Main Phase ===");
        
        // Initialize connection pools
        System.out.println("Initializing " + (TOTAL_ROOMS * CONNECTIONS_PER_ROOM) + " connections...");
        for (int roomId = 1; roomId <= TOTAL_ROOMS; roomId++) {
            roomPools.put(roomId, new CopyOnWriteArrayList<>());
            roomCounters.put(roomId, new AtomicInteger(0));
            for (int c = 0; c < CONNECTIONS_PER_ROOM; c++) {
                WebSocketClient client = createConnection(roomId);
                roomPools.get(roomId).add(client);
                totalConnections.incrementAndGet();
                Thread.sleep(10);
            }
            // Only log rooms 1-3 and last room
            if (roomId <= 3 || roomId == TOTAL_ROOMS) {
                System.out.println("Room " + roomId + ": " + CONNECTIONS_PER_ROOM + " connections established");
            }
        }
        System.out.println("...");
        System.out.println("All connections established!");
        
        // Start message generator in background
        Thread generator = startMainMessageGenerator();

        // Start sender threads
        ExecutorService executor = Executors.newFixedThreadPool(MAIN_THREADS);
        CountDownLatch latch = new CountDownLatch(MAIN_THREADS);
        long testStart = System.currentTimeMillis();
        mainTestStart = testStart;
        
        for (int i = 0; i < MAIN_THREADS; i++) {
            executor.submit(() -> {
                try {
                    while (!mainQueue.isEmpty() || !Thread.currentThread().isInterrupted()) {
                        ChatMessage msg = mainQueue.poll(1, TimeUnit.SECONDS);
                        if (msg != null) {
                            sendToRoom(msg);
                        }
                        // Check if generator is done and queue is empty
                        if (mainQueue.isEmpty() && !generator.isAlive()) {
                            break;
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
        executor.shutdown();
        generator.join();
        
        long testEnd = System.currentTimeMillis();
        mainTestEnd = testEnd;
        System.out.println("Main phase completed in " + (testEnd - testStart) / 1000.0 + "s");
    }

    private Thread startMainMessageGenerator() {
        Thread generator = new Thread(() -> {
            System.out.println("Generating main messages...");
            for (int i = 0; i < TOTAL_MESSAGES; i++) {
                try {
                    mainQueue.put(createRandomMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("Message generation complete!");
        });
        generator.start();
        return generator;
    }

    private void sendToRoom(ChatMessage msg) {
        int roomId = msg.getRoomId();
        List<WebSocketClient> pool = roomPools.get(roomId);
        if (pool == null || pool.isEmpty()) {
            failureCount.incrementAndGet();
            return;
        }
        
        int index = roomCounters.get(roomId).getAndIncrement() % pool.size();
        WebSocketClient client = pool.get(index);
        
        if (client != null && client.isOpen()) {
            sendWithRetry(client, msg);
        } else {
            failureCount.incrementAndGet();
        }
    }
    
    private void sendWithRetry(WebSocketClient client, ChatMessage msg) {
        try {
            client.send(toJson(msg));
                        successCount.incrementAndGet();
                } catch (Exception e) {
            reconnectionCount.incrementAndGet();
            failureCount.incrementAndGet();
        }
    }

    private void printResults() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("RESULTS");
        System.out.println("=".repeat(50));
        System.out.println("1. Successful messages: " + successCount.get());
        System.out.println("2. Failed messages: " + failureCount.get());
        System.out.println("5. Total connections: " + totalConnections.get());
        System.out.println("   Reconnections: " + reconnectionCount.get());
        
        int total = successCount.get() + failureCount.get();
        System.out.println("3. Total sent: " + total);
        
        // Calculate throughput using actual test duration
        double actualDuration = (mainTestEnd - mainTestStart) / 1000.0;
        double throughput = total / actualDuration;
        System.out.println("4. Throughput: " + String.format("%.0f", throughput) + " msgs/s (" + String.format("%.2f", actualDuration) + "s actual)");
    }

    // ============ Helper Methods ============

    private ChatMessage createRandomMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(String.valueOf(new Random().nextInt(100000) + 1)); // 数字 1-100000
        msg.setUsername("User" + new Random().nextInt(100000));
        msg.setMessage(MESSAGES[new Random().nextInt(MESSAGES.length)]);
        msg.setRoomId(new Random().nextInt(TOTAL_ROOMS) + 1);
        msg.setMessageType("TEXT");
        msg.setTimestamp(Instant.now().toString());
        msg.setNonce(UUID.randomUUID().toString());
        return msg;
    }
    
    private ChatMessage createRandomMessage(Random random) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(String.valueOf(random.nextInt(100000) + 1)); // 数字 1-100000
        msg.setUsername("User" + random.nextInt(100000));
        msg.setMessage(MESSAGES[random.nextInt(MESSAGES.length)]);
        msg.setRoomId(random.nextInt(TOTAL_ROOMS) + 1);
        msg.setMessageType("TEXT");
        msg.setTimestamp(Instant.now().toString());
        msg.setNonce(UUID.randomUUID().toString());
        return msg;
    }

    private WebSocketClient createConnection(int roomId) throws Exception {
        URI uri = new URI(SERVER_URL + roomId);
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake h) {}
            @Override
            public void onMessage(String m) {}
            @Override
            public void onClose(int c, String r, boolean remote) {}
            @Override
            public void onError(Exception e) {}
        };
        client.connectBlocking();
        return client;
    }
    
    private String toJson(ChatMessage msg) {
        return String.format(
            "{\"userId\":\"%s\",\"username\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"messageType\":\"%s\",\"roomId\":%d,\"nonce\":\"%s\"}",
            msg.getUserId(), msg.getUsername(), msg.getMessage(), msg.getTimestamp(),
            msg.getMessageType(), msg.getRoomId(), msg.getNonce()
        );
    }
}
