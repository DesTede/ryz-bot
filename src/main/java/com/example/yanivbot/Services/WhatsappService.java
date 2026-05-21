package com.example.yanivbot.Services;

import com.example.yanivbot.Models.IncomingMessage;
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
 * Updated WhatsappService with support for both Twilio and Meta WhatsApp.
 * 
 * Routes messages to the appropriate provider based on configuration.
 * For production: Uses Meta WhatsApp Cloud API
 * For testing: Can still use Twilio if needed
 */
@Service
public class WhatsappService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsappService.class);

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    @Value("${whatsapp.api-version:v18.0}")
    private String apiVersion;

    @Value("${whatsapp.provider:meta}")
    private String provider; // "meta" or "twilio"

    @Value("${twilio.account-sid:}")
    private String twilioAccountSid;

    @Value("${twilio.auth-token:}")
    private String twilioAuthToken;

    @Value("${twilio.whatsapp-number:}")
    private String twilioWhatsappNumber;

    @Value("${admin.phones}")
    private String adminPhones;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final String META_API_BASE = "https://graph.facebook.com";

    /**
     * Send text message via configured provider (Meta or Twilio)
     */
    public void sendText(String to, String message) {
        try {
            if ("meta".equalsIgnoreCase(provider)) {
                sendTextViaMeta(to, message);
            } else if ("twilio".equalsIgnoreCase(provider)) {
                sendTextViaTwilio(to, message);
            } else {
                logger.error("Unknown provider: {}", provider);
            }
        } catch (Exception e) {
            logger.error("Failed to send text message to {}: {}", to, e.getMessage(), e);
        }
    }

    /**
     * Send text message without throwing exceptions
     */
    public void sendSafeText(String to, String message) {
        try {
            sendText(to, message);
        } catch (Exception e) {
            logger.warn("Safe send failed to {}: {}", to, e.getMessage());
        }
    }

    /**
     * Send text message via Meta WhatsApp
     */
    private void sendTextViaMeta(String to, String message) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);

        Map<String, Object> textObj = new HashMap<>();
        // REMOVE this line: textObj.put("preview_url", "false");
        textObj.put("body", message);
        payload.put("text", textObj);

        sendRequestToMeta(payload);
    }

    /**
     * Send text message via Twilio (legacy, for testing)
     */
    private void sendTextViaTwilio(String to, String message) throws Exception {
        String accountSid = twilioAccountSid;
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";

        String body = "From=whatsapp:" + twilioWhatsappNumber.replace("+", "") +
                "&To=whatsapp:%2B" + to.replace("+", "") +
                "&Body=" + java.net.URLEncoder.encode(message, StandardCharsets.UTF_8);

        // ... implement Twilio request (your existing code)
        logger.info("Twilio send: {}", body);
    }

    /**
     * Parse incoming message from Meta webhook
     */
    public IncomingMessage parseIncomingMessage(Map<String, Object> payload) {
        try {
            // Meta webhook structure: entry[0].changes[0].value.messages[0]
            List<Map<String, Object>> entry = (List<Map<String, Object>>) payload.get("entry");
            if (entry == null || entry.isEmpty()) return null;

            Map<String, Object> changes = ((List<Map<String, Object>>) entry.get(0).get("changes")).get(0);
            Map<String, Object> value = (Map<String, Object>) changes.get("value");
            List<Map<String, Object>> messages = (List<Map<String, Object>>) value.get("messages");

            if (messages == null || messages.isEmpty()) return null;

            Map<String, Object> msg = messages.get(0);
            String from = (String) msg.get("from");
            
            Map<String, Object> textObj = (Map<String, Object>) msg.get("text");
            String text = textObj != null ? (String) textObj.get("body") : null;

            IncomingMessage message = new IncomingMessage();
            message.setPhone(from);
            message.setText(text);

            return message;
        } catch (Exception e) {
            logger.error("Failed to parse incoming message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Normalize phone number to Meta format (972512345678, 972549711059)
     */
    public String normalizePhone(String phone) {
        phone = phone.replaceAll("[\\s\\-]", "");
        
        if (phone.startsWith("+")) {
            phone = phone.substring(1);
        }
        
        if (phone.startsWith("0")) {
            phone = "972" + phone.substring(1);
        }
        
        if (phone.startsWith("5")) {
            phone = "972" + phone;
        }
        
        return phone; // Meta doesn't need the +
    }

    /**
     * Get admin phone numbers
     */
    public List<String> getAdminPhones() {
        return List.of(adminPhones.split(","));
    }

    /**
     * Notify all admins
     */
    public void notifyAdmins(String message) {
        for (String phone : getAdminPhones()) {
            sendSafeText(phone.trim(), message);
        }
    }

    /**
     * Send request to Meta WhatsApp API
     */
    private boolean sendRequestToMeta(Map<String, Object> payload) throws Exception {
        String url = String.format("%s/%s/%s/messages",
                META_API_BASE, apiVersion, phoneNumberId);

        String jsonPayload = objectMapper.writeValueAsString(payload);

        // FIX: Strip all whitespace from token
        String cleanToken = accessToken.replaceAll("\\s+", "");

        System.out.println("Token:" + accessToken );
        System.out.println("Clean Token:" + cleanToken);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + cleanToken)  // Use cleanToken here
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            logger.info("Message sent successfully via Meta. Status: {}", response.statusCode());
            return true;
        } else {
            logger.error("Failed to send message via Meta. Status: {}, Response: {}",
                    response.statusCode(), response.body());
            return false;
        }
    }
}
