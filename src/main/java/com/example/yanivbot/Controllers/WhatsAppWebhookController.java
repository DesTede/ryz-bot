package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.IncomingMessage;
import com.example.yanivbot.Services.WhatsappService;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook endpoint for receiving messages from Meta WhatsApp Business API.
 * Routes messages to MessageController for processing.
 */
@RestController
@RequestMapping("/webhook")
public class WhatsAppWebhookController {
    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    // Cache to track processed messages (Message ID -> Timestamp) to avoid race conditions
    private final java.util.concurrent.ConcurrentHashMap<String, Long> processedMessageIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEBOUNCE_WINDOW_MS = 6000; // 6 seconds window
    
    @Autowired
    private WhatsappService whatsappService;
    
    @Autowired
    private MessageController messageController;
    
    @Value("${whatsapp.webhook-verify-token:yanivbot_verify}")
    private String webhookVerifyToken;

    @Value("${whatsapp.app-secret}")
    private String appSecret;
    
    /**
     * Webhook GET endpoint for Meta verification during setup.
     * Meta sends: ?hub.mode=subscribe&hub.challenge=xxx&hub.verify_token=yyy
     */
    @GetMapping("/whatsapp")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.challenge") String challenge,
            @RequestParam("hub.verify_token") String verifyToken) {
        
        logger.info("Webhook verification request received");
        
        if (!"subscribe".equals(mode)) {
            logger.error("Invalid hub.mode: {}", mode);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        if (!webhookVerifyToken.equals(verifyToken)) {
            logger.error("Invalid verify token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        logger.info("Webhook verified successfully");
        return ResponseEntity.ok(challenge);
    }
    
    /**
     * Webhook POST endpoint for receiving messages from Meta.
     * Parses the message and routes to MessageController for processing.
     */
    @PostMapping("/whatsapp")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody) {

        if (rawBody.contains("\"statuses\"") && !rawBody.contains("\"messages\"")) {
            // Status-only webhook (sent/delivered/read) — log a single compact line
            String status = rawBody.contains("\"read\"") ? "read"
                    : rawBody.contains("\"delivered\"") ? "delivered"
                    : rawBody.contains("\"sent\"") ? "sent" : "status";
            logger.debug("Webhook [{}] — status update only, skipping", status);
        } else {
            logger.debug("Webhook received — processing message");
        }

        if (!isValidSignature(signature, rawBody)) {
            logger.warn("Invalid webhook signature — request rejected");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            logger.debug("Webhook payload received from Meta");

            Map<String, Object> payload = OBJECT_MAPPER
                    .readValue(rawBody, new TypeReference<>() {});

            // Parse incoming message using WhatsappService
            IncomingMessage incomingMessage = whatsappService.parseIncomingMessage(payload);

            if (incomingMessage == null) {
                logger.debug("No valid message in webhook payload");
                return ResponseEntity.ok().build();
            }

            String phoneNumber = incomingMessage.getPhone();
            String messageText = incomingMessage.getText();

            logger.info("Message received from {}: {}", PhoneNumberUtil.maskPhoneNumber(phoneNumber), messageText);

            // Extract unique message ID from the WhatsApp payload to deduplicate concurrent requests
            String msgId = incomingMessage.getMessageId();
            if (msgId != null) {
                long now = System.currentTimeMillis();

                // Atomically attempt to add the ID. Returns the existing timestamp if it was already there.
                Long previousTimestamp = processedMessageIds.putIfAbsent(msgId, now);

                if (previousTimestamp != null) {
                    long elapsed = now - previousTimestamp;
                    if (elapsed < DEBOUNCE_WINDOW_MS) {
                        logger.warn("[WEBHOOK] Ignored duplicate concurrent request for message ID: {} (elapsed: {}ms)", msgId, elapsed);
                        return ResponseEntity.ok().build(); // Return 200 OK so Meta stops retrying
                    }
                    // If it somehow bypassed the window (e.g., long-term retry), update the timestamp
                    processedMessageIds.put(msgId, now);
                }
            }
            
            // Route to MessageController to process the message
            // MessageController.processMessage handles all the bot logic
            messageController.handleMetaMessage(incomingMessage);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok().build();
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30000)
    public void cleanExpiredMessageIds() {
        long now = System.currentTimeMillis();
        processedMessageIds.entrySet().removeIf(entry -> (now - entry.getValue()) > DEBOUNCE_WINDOW_MS);
    }
    
    private boolean isValidSignature(String signature, String rawBody) {
        if (signature == null || !signature.startsWith("sha256=")) {
            logger.warn("Missing or malformed X-Hub-Signature-256 header");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            String expected = "sha256=" + hex;
            return java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }
    
}
