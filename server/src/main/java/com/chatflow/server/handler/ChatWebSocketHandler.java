package com.chatflow.server.handler;

import com.chatflow.server.model.ChatMessage;
import com.chatflow.server.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = extractRoomId(session);
        sessions.put(session.getId(), session);
        System.out.println("New connection: " + session.getId() + " to room: " + roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            // 1. Parse and validate message
            ChatMessage chatMsg = parseAndValidate(message.getPayload());

            // 2. Validate
            if (!isValid(chatMsg)) {
                System.err.println("Validation failed for session " + session.getId());
                sendError(session, "Invalid message format");
                return;
            }

            // 3. Echo back with server timestamp
            ChatResponse response = new ChatResponse(chatMsg, "SUCCESS");
            String jsonResponse = toJson(response);
            session.sendMessage(new TextMessage(jsonResponse));

        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            sendError(session, "Error processing message: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        System.out.println("Connection closed: " + session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        System.err.println("Transport error for session " + session.getId() + ": " + exception.getMessage());
        sessions.remove(session.getId());
    }

    // Helper methods

    private String extractRoomId(WebSocketSession session) {
        String uri = session.getUri().toString();
        String[] parts = uri.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : "default";
    }

    private ChatMessage parseAndValidate(String payload) throws Exception {
        return objectMapper.readValue(payload, ChatMessage.class);
    }

    private boolean isValid(ChatMessage msg) {
        try {
            // userId: 1-100000
            int userId = Integer.parseInt(msg.getUserId());
            if (userId < 1 || userId > 100000) {
                return false;
            }

            // username: 3-20 alphanumeric
            String username = msg.getUsername();
            if (username == null || username.length() < 3 || username.length() > 20) {
                return false;
            }
            if (!username.matches("[a-zA-Z0-9]+")) {
                return false;
            }

            // message: 1-500 chars
            String message = msg.getMessage();
            if (message == null || message.length() < 1 || message.length() > 500) {
                return false;
            }

            // timestamp: ISO-8601
            if (msg.getTimestamp() == null) {
                return false;
            }
            Instant.parse(msg.getTimestamp()); // This will throw if invalid

            // messageType: TEXT|JOIN|LEAVE
            if (!Arrays.asList("TEXT", "JOIN", "LEAVE").contains(msg.getMessageType())) {
                return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        try {
            ErrorResponse error = new ErrorResponse(errorMessage);
            String json = objectMapper.writeValueAsString(error);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            System.err.println("Failed to send error message: " + e.getMessage());
        }
    }

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    // Inner class for error responses
    private static class ErrorResponse {
        private String error;
        private String timestamp;

        public ErrorResponse(String error) {
            this.error = error;
            this.timestamp = Instant.now().toString();
        }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }
}