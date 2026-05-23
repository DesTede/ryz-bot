package com.example.yanivbot.Services;

import com.example.yanivbot.Models.IncomingMessage;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class WhatsappService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsappService.class);

    @Value("${whatsapp.access-token}")
    private String accessToken;

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.api-version:v23.0}")
    private String apiVersion;

    public WhatsappService() {
    }

    /**
     * Parse incoming message from Meta WhatsApp webhook
     */
    public IncomingMessage parseIncomingMessage(Map<String, Object> payload) {
        try {
            JSONObject json = new JSONObject(payload);
            JSONObject entry = json.getJSONArray("entry").getJSONObject(0);
            JSONObject change = entry.getJSONArray("changes").getJSONObject(0);
            JSONObject value = change.getJSONObject("value");
            JSONObject message = value.getJSONArray("messages").getJSONObject(0);

            String phone = message.getString("from");
            String messageId = message.getString("id");
            String text = "";

            // Check message type
            String messageType = message.getString("type");

            if (messageType.equals("text")) {
                text = message.getJSONObject("text").getString("body");
            } else if (messageType.equals("interactive")) {
                // Handle interactive messages (buttons, lists, etc.)
                JSONObject interactive = message.getJSONObject("interactive");
                String interactiveType = interactive.getString("type");

                if (interactiveType.equals("button_reply")) {
                    // Button click - extract button ID
                    text = interactive.getJSONObject("button_reply").getString("id");
                } else if (interactiveType.equals("list_reply")) {
                    // List selection - extract selected item ID
                    text = interactive.getJSONObject("list_reply").getString("id");
                }
            }

            return new IncomingMessage(phone, text, messageId);
        } catch (Exception e) {
            logger.error("Error parsing incoming message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Normalize phone number to international format
     */
    public String normalizePhone(String phone) {
        // Remove all non-digits
        phone = phone.replaceAll("[^0-9]", "");

        // If starts with 0 (Israeli local format), replace with 972
        if (phone.startsWith("0")) {
            phone = "972" + phone.substring(1);
        }

        // Ensure it starts with 972 (Israeli country code)
        if (!phone.startsWith("972")) {
            phone = "972" + phone;
        }

        return phone;
    }

    /**
     * Send text message
     */
    public void sendText(String phone, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", phone);
            payload.put("type", "text");

            JSONObject text = new JSONObject();
            text.put("preview_url", false);
            text.put("body", message);
            payload.put("text", text);

            sendRequest(payload);
        } catch (Exception e) {
            logger.error("Error sending text to {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Send text with safe exception handling
     */
    public void sendSafeText(String phone, String message) {
        try {
            sendText(phone, message);
        } catch (Exception e) {
            logger.error("Error in sendSafeText to {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Send interactive button message
     *
     * @param phone - Recipient phone
     * @param headerText - Header text (optional, can be null)
     * @param bodyText - Main message text
     * @param footerText - Footer text (optional, can be null)
     * @param buttons - Array of buttons (max 3)
     */
    public void sendInteractiveButtons(String phone, String headerText, String bodyText, String footerText, InteractiveButton... buttons) {
        try {
            if (buttons.length > 3) {
                logger.warn("Max 3 buttons allowed, truncating to 3");
            }

            JSONObject payload = new JSONObject();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", phone);
            payload.put("type", "interactive");

            JSONObject interactive = new JSONObject();
            interactive.put("type", "button");

            // Body (required)
            JSONObject body = new JSONObject();
            body.put("text", bodyText);
            interactive.put("body", body);

            // Header (optional)
            if (headerText != null && !headerText.isEmpty()) {
                JSONObject header = new JSONObject();
                header.put("type", "text");
                header.put("text", headerText);
                interactive.put("header", header);
            }

            // Footer (optional)
            if (footerText != null && !footerText.isEmpty()) {
                JSONObject footer = new JSONObject();
                footer.put("text", footerText);
                interactive.put("footer", footer);
            }

            // Buttons
            JSONArray buttonArray = new JSONArray();
            for (int i = 0; i < Math.min(buttons.length, 3); i++) {
                JSONObject button = new JSONObject();
                button.put("type", "reply");

                JSONObject reply = new JSONObject();
                reply.put("id", buttons[i].id);
                reply.put("title", buttons[i].title);
                button.put("reply", reply);

                buttonArray.put(button);
            }
            interactive.put("action", new JSONObject().put("buttons", buttonArray));

            payload.put("interactive", interactive);

            sendRequest(payload);
            logger.info("Sent interactive button message to {}", phone);
        } catch (Exception e) {
            logger.error("Error sending interactive buttons to {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Send interactive buttons with just body text (no header/footer)
     */
    public void sendInteractiveButtons(String phone, String bodyText, InteractiveButton... buttons) {
        sendInteractiveButtons(phone, null, bodyText, null, buttons);
    }

    /**
     * Generate Google Maps link
     */
    public String generateGoogleMapsLink(double latitude, double longitude) {
        return "https://www.google.com/maps/search/?api=1&query=" + latitude + "," + longitude;
    }

    /**
     * Notify admins about important events
     */
    public void notifyAdmins(String message) {
        // This would be populated from environment variable
        String adminPhonesStr = System.getenv("ADMIN_PHONES");
        if (adminPhonesStr != null && !adminPhonesStr.isEmpty()) {
            String[] phones = adminPhonesStr.split(",");
            for (String phone : phones) {
                sendSafeText(phone.trim(), message);
            }
        }
    }

    /**
     * Send request to WhatsApp API
     */
    private void sendRequest(JSONObject payload) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            logger.error("WhatsApp API error: {} - {}", responseCode, conn.getResponseMessage());
        }

        conn.disconnect();
    }

    /**
     * Helper class for interactive buttons
     */
    public static class InteractiveButton {
        public String id;
        public String title;

        public InteractiveButton(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}