package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.IncomingMessage;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    private final RestTemplate restTemplate = new RestTemplate();
    private final TwilioSmsService twilioSmsService;

    // Retry config for WhatsApp Cloud API sends
    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public WhatsappService(TwilioSmsService twilioSmsService) {
        this.twilioSmsService = twilioSmsService;
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

            // Check if this payload is a status update (sent, delivered, read) instead of a message
            if (!value.has("messages")) {
                if (value.has("statuses")) {
                    logger.debug("Received a WhatsApp status update (sent/delivered/read). Skipping parsing.");
                } else {
                    logger.warn("Received unknown webhook event type. Missing 'messages' field.");
                }
                return null;
            }

            JSONObject message = value.getJSONArray("messages").getJSONObject(0);

            String phone = message.getString("from");
            String messageId = message.getString("id");
            String text = "";
            Double latitude = null;
            Double longitude = null;

            // Check message type
            String messageType = message.getString("type");

            switch (messageType) {
                case "text" -> text = message.getJSONObject("text").getString("body");
                case "location" -> {
                    // Handle location message
                    JSONObject locationObj = message.getJSONObject("location");
                    latitude = locationObj.getDouble("latitude");
                    longitude = locationObj.getDouble("longitude");
                    logger.info("Location received from {}: lat={}, lon={}", phone, latitude, longitude);
                }
                case "interactive" -> {
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
            }

            IncomingMessage incomingMessage = new IncomingMessage(phone, text, messageId);
            incomingMessage.setLatitude(latitude);
            incomingMessage.setLongitude(longitude);

            return incomingMessage;
        } catch (Exception e) {
            logger.error("Error parsing incoming message: {}", e.getMessage());
            return null;
        }
    }


    /**
     * Send text message.
     * @return true if WhatsApp accepted the message, false otherwise
     */
    public boolean sendText(String phone, String message) {
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

            return sendRequest(payload);
        } catch (Exception e) {
            logger.error("Error sending text to {}: {}", phone, e.getMessage());
            return false;
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
     * Send a critical text message: try WhatsApp (with retries), and if that
     * fails, fall back to Twilio SMS. Use only for delivery-critical alerts
     * (driver dispatch, stale-location, auto clock-out) - not for chat replies.
     */
    public void sendCriticalText(String phone, String message) {
        boolean sent = sendText(phone, message);
        if (!sent) {
            logger.warn("WhatsApp delivery failed for critical message to {} - falling back to SMS",
                    PhoneNumberUtil.maskPhoneNumber(phone));
            twilioSmsService.sendSms(phone, message);
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
            logger.info("Sent interactive button message to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        } catch (Exception e) {
            logger.error("Error sending interactive buttons to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage());
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
     * Send request to WhatsApp API, with retries on transient failures.
     * Retries on connection errors and 5xx/429 responses; 4xx (other than 429)
     * are treated as permanent and not retried.
     *
     * @return true if a 200 response was received, false otherwise
     */
    private boolean sendRequest(JSONObject payload) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages";

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                if (responseCode == 200) {
                    return true;
                }

                logger.error("WhatsApp API error: {} (attempt {}/{})", responseCode, attempt, MAX_SEND_ATTEMPTS);

                // 4xx (except 429 rate-limit) won't fix itself - stop retrying
                if (responseCode < 500 && responseCode != 429) {
                    return false;
                }
            } catch (Exception e) {
                lastError = e;
                logger.warn("WhatsApp send attempt {}/{} failed: {}", attempt, MAX_SEND_ATTEMPTS, e.getMessage());
            }

            if (attempt < MAX_SEND_ATTEMPTS) {
                Thread.sleep(RETRY_DELAY_MS);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return false;
    }
    
    /**
     * Helper class for interactive items (Buttons or List Rows)
     */
    public static class InteractiveButton {
        public String id;
        public String title;
        public String description; // Added field

        // Keep your original constructor so your buttons don't break
        public InteractiveButton(String id, String title) {
            this.id = id;
            this.title = title;
            this.description = null;
        }

        // Overloaded constructor specifically for list rows with descriptions
        public InteractiveButton(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }
    }

    /**
     * Send interactive buttons safely - catches exceptions to prevent error messages
     * to the user
     */
    public void sendInteractiveButtonsSafe(String phone, String bodyText, InteractiveButton... buttons) {
        try {
            logger.info("Sending interactive buttons to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), bodyText);
            sendInteractiveButtons(phone, bodyText, buttons);
            logger.info("Interactive buttons sent successfully to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        } catch (Exception e) {
            logger.error("Error sending interactive buttons to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage(), e);
            logger.warn("Falling back to text-only message");
            sendSafeText(phone, bodyText);
        }
    }

    /**
     * Send an interactive list message (dropdown picker).
     * Now supports up to 72 characters via the row description field.
     */
    public void sendInteractiveList(String phone, String bodyText, String buttonText, String sectionTitle, List<InteractiveButton> items) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", phone);
            payload.put("type", "interactive");

            JSONObject interactive = new JSONObject();
            interactive.put("type", "list");

            JSONObject body = new JSONObject();
            body.put("text", bodyText);
            interactive.put("body", body);

            JSONArray rowsArray = new JSONArray();
            for (InteractiveButton item : items) {
                JSONObject row = new JSONObject();
                row.put("id", item.id);

                // Title is hard-capped at 24 chars by WhatsApp
                row.put("title", item.title.length() > 24 ? item.title.substring(0, 24) : item.title);

                // Append the description if it exists (WhatsApp description field caps at 72 chars)
                if (item.description != null && !item.description.isEmpty()) {
                    row.put("description", item.description.length() > 72 ? item.description.substring(0, 72) : item.description);
                }

                rowsArray.put(row);
            }

            JSONObject section = new JSONObject();
            section.put("title", sectionTitle);
            section.put("rows", rowsArray);

            JSONArray sections = new JSONArray();
            sections.put(section);

            JSONObject action = new JSONObject();
            action.put("button", buttonText);
            action.put("sections", sections);
            interactive.put("action", action);

            payload.put("interactive", interactive);

            sendRequest(payload);
            logger.info("Sent interactive list message to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        } catch (Exception e) {
            logger.error("Error sending interactive list to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage());
        }
    }
    
    /**
     * Send a message using WhatsApp Message Template
     * Templates support dynamic variables ({{1}}, {{2}}, etc) that are replaced
     * with actual values at runtime.
     * <p>
     * Supported templates:
     * - delivery_status_delivering: Sent to customer when order is picked up
     *   Variables: {{1}}=Customer Name, {{2}}=Business Name, {{3}}=Driver Phone,
     *              {{4}}=Delivery Address, {{5}}=Google Maps Link
     * <p>
     * - delivery_status_completed: Sent to customer when delivery is complete
     *   Variables: {{1}}=Business Name, {{2}}=Delivery Address
     *
     * @param phone Recipient phone number (format: 972XXXXXXXXX)
     * @param templateName Template name registered in WhatsApp Business API
     * @param variables List of variable values in order ({{1}}, {{2}}, etc)
     */
    public void sendTemplateMessage(String phone, String templateName, List<String> variables) {
        logger.info("Preparing template message for {}: {}",
                PhoneNumberUtil.maskPhoneNumber(phone), templateName);
        
        try {
            // Validate inputs
            if (phone == null || phone.trim().isEmpty()) {
                throw new IllegalArgumentException("Phone number cannot be empty");
            }

            if (templateName == null || templateName.trim().isEmpty()) {
                throw new IllegalArgumentException("Template name cannot be empty");
            }

            if (variables == null || variables.isEmpty()) {
                throw new IllegalArgumentException("Template variables cannot be empty");
            }

            logger.debug("Template variables count: {}", variables.size());
            for (int i = 0; i < variables.size(); i++) {
                logger.debug("  {{{}}} = {}", i + 1, variables.get(i));
            }

            // Build template parameters
            List<Map<String, String>> parameters = new ArrayList<>();

            for (String variable : variables) {
                Map<String, String> param = new HashMap<>();
                param.put("type", "text");
                param.put("text", variable);
                parameters.add(param);
            }

            // Build template object
            Map<String, Object> bodyComponent = new HashMap<>();
            bodyComponent.put("type", "body");
            bodyComponent.put("parameters", parameters);

            
            List<Map<String, Object>> components = new ArrayList<>();
            components.add(bodyComponent);

            Map<String, Object> template = new HashMap<>();
            template.put("name", templateName);
            template.put("language", Map.of("code", "he"));
            template.put("components", components);
            
            // Build message object
            Map<String, Object> message = new HashMap<>();
            message.put("messaging_product", "whatsapp");
            message.put("to", PhoneNumberUtil.normalizePhone(phone));
            message.put("type", "template");
            message.put("template", template);

            // Log the message structure
            
            sendMessageToWhatsAppAPI(message);

            logger.info("✅ Template message sent successfully to {}",
                    PhoneNumberUtil.maskPhoneNumber(phone));
            logger.info("   Template: {}", templateName);
            logger.info("   Variables: {}", variables.size());

        } catch (IllegalArgumentException e) {
            logger.error("❌ Invalid template message parameters for {}: {}",
                    PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage());
            throw e;

        } catch (Exception e) {
            logger.error("❌ Error sending template message to {}: {}",
                    PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage(), e);
            throw new RuntimeException("Failed to send WhatsApp template message", e);
        }
    }

    /**
     * Helper method to send message to WhatsApp API
     * <p>
     * Replace this implementation based on your actual WhatsApp client library
     *
     * @param message Message object to send
     */
    private void sendMessageToWhatsAppAPI(Map<String, Object> message) {
//            String url = "https://graph.facebook.com/v18.0/" + phoneNumberId + "/messages";
        String url = "https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages";
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

                if (response.getStatusCode().isError()) {
                    throw new RuntimeException("WhatsApp API returned error: " + response.getBody());
                }

                logger.info("✅ Message sent to WhatsApp API successfully");
                return;

            } catch (Exception e) {
                lastError = new RuntimeException("Failed to send message to WhatsApp API", e);
                logger.warn("WhatsApp template send attempt {}/{} failed: {}", attempt, MAX_SEND_ATTEMPTS, e.getMessage());
                if (attempt < MAX_SEND_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        logger.error("❌ API call failed after {} attempts", MAX_SEND_ATTEMPTS);
        throw lastError;
    }

    /**
     * Notify admins smartly: template if outside 24-hour window, 
     * regular text if inside (free)
     * Smart delivery to multiple admins based on their 24-hour window status
     *
     * @param regularMessage Regular text message content
     * @param templateName Template name for outside window
     * @param templateVariables Variables for template
     */
    public void notifyAdminsSmartMessage(String regularMessage, String templateName,
                                         List<String> templateVariables,
                                         ConversationService convoService) {
        String adminPhonesStr = System.getenv("ADMIN_PHONES");
        if (adminPhonesStr != null && !adminPhonesStr.isEmpty()) {
            String[] phones = adminPhonesStr.split(",");
            for (String phone : phones) {
                sendSmartCustomerMessage(phone.trim(), regularMessage, templateName,
                        templateVariables, convoService);
            }
        }
    }

    /**
     * Sends an interactive-button message to all admins who are within the 24-hour window.
     * Used to surface action buttons (e.g. stop-redispatch) alongside template alerts.
     */
    public void notifyAdminsInteractiveButtons(String bodyText,
                                               ConversationService convoService,
                                               InteractiveButton... buttons) {
        String adminPhonesStr = System.getenv("ADMIN_PHONES");
        if (adminPhonesStr == null || adminPhonesStr.isEmpty()) return;
        for (String phone : adminPhonesStr.split(",")) {
            String trimmed = phone.trim();
            if (convoService.isWithin24HourWindow(trimmed)) {
                sendInteractiveButtonsSafe(trimmed, bodyText, buttons);
            }
        }
    }

    /**
     * Same as above but for admins
     * Caller explicitly chooses: template or regular message
     */
    public void notifyAdminsWithOption(String regularMessage, String templateName,
                                       List<String> templateVariables,
                                       boolean useTemplate) {
        String adminPhonesStr = System.getenv("ADMIN_PHONES");
        if (adminPhonesStr != null && !adminPhonesStr.isEmpty()) {
            String[] phones = adminPhonesStr.split(",");
            for (String phone : phones) {
                sendMessageWithOption(phone.trim(), regularMessage, templateName,
                        templateVariables, useTemplate);
            }
        }
    }

    /**
     * Smart send: free regular text if within 24-hour window, paid template if outside
     */
    public void sendSmartCustomerMessage(String phone, String regularMessage, String templateName,
                                         List<String> templateVariables,
                                         ConversationService convoService) {
        boolean inWindow = convoService.isWithin24HourWindow(phone);

        if (inWindow) {
            logger.info("Within 24-hour window - sending free text to {}", PhoneNumberUtil.maskPhoneNumber(phone));
            sendSafeText(phone, regularMessage);
        } else {
            logger.info("Outside 24-hour window - sending template to {}", PhoneNumberUtil.maskPhoneNumber(phone));
            sendTemplateMessage(PhoneNumberUtil.normalizePhone(phone), templateName, templateVariables);
        }
    }

    /**
     * Explicit choice: caller decides whether to use template or regular text
     */
    public void sendMessageWithOption(String phone, String regularMessage, String templateName,
                                      List<String> templateVariables, boolean useTemplate) {
        if (useTemplate) {
            logger.info("📋 Sending template (forced) to {}", PhoneNumberUtil.maskPhoneNumber(phone));
            sendTemplateMessage(PhoneNumberUtil.normalizePhone(phone), templateName, templateVariables);
        } else {
            logger.info("📱 Sending regular text (forced) to {}", PhoneNumberUtil.maskPhoneNumber(phone));
            sendSafeText(phone, regularMessage);
        }
    }

    /**
     * Send an interactive location request message.
     * Displays a body text and a "Send Location" button the user can tap.
     */
    public void sendLocationRequestMessage(String phone, String bodyText) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", phone);
            payload.put("type", "interactive");

            JSONObject interactive = new JSONObject();
            interactive.put("type", "location_request_message");

            JSONObject body = new JSONObject();
            body.put("text", bodyText);
            interactive.put("body", body);

            JSONObject action = new JSONObject();
            action.put("name", "send_location");
            interactive.put("action", action);

            payload.put("interactive", interactive);

            sendRequest(payload);
        } catch (Exception e) {
            logger.error("Error sending location request to {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Send WhatsApp authentication OTP template.
     * Authentication templates use a button component, not body variables.
     */
    public void sendOtpTemplate(String phone, String code, String templateName) {
        try {
            Map<String, Object> bodyParam = new HashMap<>();
            bodyParam.put("type", "text");
            bodyParam.put("text", code);

            Map<String, Object> bodyComponent = new HashMap<>();
            bodyComponent.put("type", "body");
            bodyComponent.put("parameters", List.of(bodyParam));

            Map<String, Object> buttonParam = new HashMap<>();
            buttonParam.put("type", "text");
            buttonParam.put("text", code);

            Map<String, Object> buttonComponent = new HashMap<>();
            buttonComponent.put("type", "button");
            buttonComponent.put("sub_type", "url");
            buttonComponent.put("index", "0");
            buttonComponent.put("parameters", List.of(buttonParam));

            Map<String, Object> template = new HashMap<>();
            template.put("name", templateName);
            template.put("language", Map.of("code", "he"));
            template.put("components", List.of(bodyComponent, buttonComponent));

            Map<String, Object> message = new HashMap<>();
            message.put("messaging_product", "whatsapp");
            message.put("to", PhoneNumberUtil.normalizePhone(phone));
            message.put("type", "template");
            message.put("template", template);

            sendMessageToWhatsAppAPI(message);
            logger.info("✅ OTP template sent to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        } catch (Exception e) {
            logger.error("❌ Error sending OTP template to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage(), e);
            boolean smsSent = twilioSmsService.sendSms(phone, "קוד הכניסה שלך ל-RYZ: " + code);
            if (!smsSent) {
                throw new RuntimeException("Failed to send OTP template", e);
            }
            logger.info("✅ OTP delivered via SMS fallback to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        }
    }

    /**
     * Sends shift started confirmation to driver via WhatsApp template.
     * Uses smart routing - free text if within 24hr window, template otherwise.
     */
    public void sendDriverShiftStartedTemplate(String phone, String driverName, boolean inWindow) {
        if (inWindow) {
            sendSafeText(phone, "🟢 הכל מוכן " + driverName + "!\n🟢 המשמרת התחילה\n📍 המיקום התקבל בהצלחה\nנסיעות חדשות בדרך אליך 🚖");
            return;
        }

        try {
            Map<String, Object> bodyParam = new HashMap<>();
            bodyParam.put("type", "text");
            bodyParam.put("text", driverName);

            Map<String, Object> bodyComponent = new HashMap<>();
            bodyComponent.put("type", "body");
            bodyComponent.put("parameters", List.of(bodyParam));

            Map<String, Object> template = new HashMap<>();
            template.put("name", "driver_shift_started");
            template.put("language", Map.of("code", "he"));
            template.put("components", List.of(bodyComponent));

            Map<String, Object> message = new HashMap<>();
            message.put("messaging_product", "whatsapp");
            message.put("to", PhoneNumberUtil.normalizePhone(phone));
            message.put("type", "template");
            message.put("template", template);

            sendMessageToWhatsAppAPI(message);
            logger.info("✅ Shift started template sent to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        } catch (Exception e) {
            logger.error("❌ Error sending shift started template to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage(), e);
            boolean smsSent = twilioSmsService.sendSms(phone,
                    "🟢 הכל מוכן " + driverName + "! המשמרת התחילה. נסיעות חדשות בדרך אליך 🚖");
            if (!smsSent) {
                throw new RuntimeException("Failed to send shift started template", e);
            }
            logger.info("✅ Shift-started delivered via SMS fallback to {}", PhoneNumberUtil.maskPhoneNumber(phone));
        }
    }
}