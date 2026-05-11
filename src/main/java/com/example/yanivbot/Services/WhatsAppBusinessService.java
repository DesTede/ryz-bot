package com.example.yanivbot.Services;

import com.example.yanivbot.Models.IncomingMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for integrating with Meta's WhatsApp Cloud API.
 * Replaces Twilio WhatsApp sandbox with production Meta API.
 */
@Service
public class WhatsAppBusinessService {
    private static final Logger logger = LoggerFactory.getLogger(WhatsAppBusinessService.class);
    
    private final String phoneNumberId;
    private final String accessToken;
    private final String apiVersion;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private static final String META_API_BASE = "https://graph.instagram.com";
    
    public WhatsAppBusinessService(
            @Value("${whatsapp.phone-number-id}") String phoneNumberId,
            @Value("${whatsapp.access-token}") String accessToken,
            @Value("${whatsapp.api-version:v18.0}") String apiVersion) {
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
        this.apiVersion = apiVersion;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Send a text message via WhatsApp Business API.
     *
     * @param to Recipient's phone number (format: 972512345678)
     * @param message Message text (supports Hebrew)
     * @return true if message sent successfully
     */
    public boolean sendText(String to, String message) {
        try {
            Map<String, Object> payload = buildTextMessagePayload(to, message);
            return sendRequest(payload);
        } catch (Exception e) {
            logger.error("Failed to send text message to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send a text message without throwing exceptions on failure.
     * Useful for non-critical notifications (matches your existing sendSafeText).
     */
    public void sendSafeText(String to, String message) {
        try {
            sendText(to, message);
        } catch (Exception e) {
            logger.warn("Safe send failed to {}: {}", to, e.getMessage());
        }
    }
    
    /**
     * Send a message with action buttons.
     */
    public boolean sendButtonMessage(String to, String headerText, 
                                     String bodyText, List<String> buttons) {
        try {
            Map<String, Object> payload = buildButtonMessagePayload(to, headerText, bodyText, buttons);
            return sendRequest(payload);
        } catch (Exception e) {
            logger.error("Failed to send button message to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send a location message.
     */
    public boolean sendLocation(String to, double latitude, 
                               double longitude, String locationName) {
        try {
            Map<String, Object> payload = buildLocationPayload(to, latitude, longitude, locationName);
            return sendRequest(payload);
        } catch (Exception e) {
            logger.error("Failed to send location to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Verify webhook token from Meta.
     */
    public boolean verifyWebhookToken(String token) {
        return true; // Token verification is handled in the controller
    }
    
    /**
     * Parse incoming message from Meta webhook.
     * Converts Meta format to your IncomingMessage model.
     */
    public IncomingMessage parseWebhookMessage(Map<String, Object> payload) {
        try {
            JsonNode json = objectMapper.valueToTree(payload);
            
            JsonNode messages = json
                    .path("entry").get(0)
                    .path("changes").get(0)
                    .path("value")
                    .path("messages");
            
            if (!messages.isArray() || messages.size() == 0) {
                return null;
            }
            
            JsonNode message = messages.get(0);
            String messageId = message.path("id").asText();
            String senderPhoneNumber = json
                    .path("entry").get(0)
                    .path("changes").get(0)
                    .path("value")
                    .path("contacts").get(0)
                    .path("wa_id").asText();
            
            String messageType = message.path("type").asText();
            String messageText = null;
            
            if ("text".equals(messageType)) {
                messageText = message.path("text").path("body").asText();
            } else if ("interactive".equals(messageType)) {
                messageText = message.path("interactive").path("button_reply")
                        .path("title").asText();
            } else if ("location".equals(messageType)) {
                double latitude = message.path("location").path("latitude").asDouble();
                double longitude = message.path("location").path("longitude").asDouble();
                messageText = latitude + "," + longitude;
            }
            
            IncomingMessage incomingMessage = new IncomingMessage();
            incomingMessage.setPhone(senderPhoneNumber);
            incomingMessage.setText(messageText);
            
            return incomingMessage;
        } catch (Exception e) {
            logger.error("Failed to parse webhook message: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Send a read receipt for a received message.
     */
    public boolean sendReadReceipt(String messageId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("messaging_product", "whatsapp");
            payload.put("status", "read");
            payload.put("message_id", messageId);
            
            return sendRequest(payload);
        } catch (Exception e) {
            logger.warn("Failed to send read receipt for {}: {}", messageId, e.getMessage());
            return false;
        }
    }
    
    // ============= Private Helper Methods =============
    
    private Map<String, Object> buildTextMessagePayload(String to, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", to);
        
        Map<String, Object> textObj = new HashMap<>();
        textObj.put("preview_url", "false");
        textObj.put("body", message);
        payload.put("text", textObj);
        
        return payload;
    }
    
    private Map<String, Object> buildButtonMessagePayload(String to, 
                                                         String headerText, String bodyText,
                                                         List<String> buttons) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);
        
        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "button");
        
        Map<String, Object> body = new HashMap<>();
        body.put("text", bodyText);
        interactive.put("body", body);
        
        if (headerText != null && !headerText.isEmpty()) {
            Map<String, Object> header = new HashMap<>();
            header.put("type", "text");
            header.put("text", headerText);
            interactive.put("header", header);
        }
        
        Map<String, Object> action = new HashMap<>();
        List<Map<String, Object>> buttonList = new java.util.ArrayList<>();
        
        for (int i = 0; i < buttons.size() && i < 3; i++) {
            Map<String, Object> button = new HashMap<>();
            button.put("type", "reply");
            Map<String, Object> reply = new HashMap<>();
            reply.put("id", "btn_" + i);
            reply.put("title", buttons.get(i));
            button.put("reply", reply);
            buttonList.add(button);
        }
        action.put("buttons", buttonList);
        interactive.put("action", action);
        
        payload.put("interactive", interactive);
        return payload;
    }
    
    private Map<String, Object> buildLocationPayload(String to,
                                                     double latitude, double longitude,
                                                     String locationName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);
        
        Map<String, Object> location = new HashMap<>();
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        location.put("name", locationName);
        
        payload.put("location", location);
        return payload;
    }
    
    private boolean sendRequest(Map<String, Object> payload) throws Exception {
        String url = String.format("%s/%s/%s/messages",
                META_API_BASE, apiVersion, phoneNumberId);
        
        String jsonPayload = objectMapper.writeValueAsString(payload);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, 
                                                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            logger.info("Message sent successfully. Status: {}", response.statusCode());
            return true;
        } else {
            logger.error("Failed to send message. Status: {}, Response: {}", 
                        response.statusCode(), response.body());
            return false;
        }
    }
}
