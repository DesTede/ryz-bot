package com.example.yanivbot.Controllers;

import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook endpoint for receiving messages from Meta WhatsApp Business API.
 * Replaces Twilio webhook with Meta's production API.
 */
@RestController
@RequestMapping("/webhook")
public class WhatsAppWebhookController {
    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    
    @Autowired
    private WhatsappService whatsappService;
    
    @Autowired
    private ConversationService conversationService;
    
    @Value("${whatsapp.webhook-verify-token:yanivbot_verify_token}")
    private String webhookVerifyToken;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
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
     * Routes message through ConversationService state machine.
     */
    @PostMapping("/whatsapp")
    public ResponseEntity<Void> handleWebhook(@RequestBody Map<String, Object> payload) {
        try {
            logger.debug("Webhook payload received from Meta");
            
            // Parse incoming message using your existing WhatsappService
            IncomingMessage incomingMessage = whatsappService.parseIncomingMessage(payload);
            
            if (incomingMessage == null) {
                logger.debug("No valid message in webhook payload");
                return ResponseEntity.ok().build();
            }
            
            String phoneNumber = incomingMessage.getPhone();
            String messageText = incomingMessage.getText();
            
            logger.info("Message received from {}: {}", phoneNumber, messageText);
            
            // Route through your conversation state machine
            conversationService.handleUserMessage(phoneNumber, messageText);
            
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error processing webhook: {}", e.getMessage(), e);
            // Return 200 OK to Meta even on error to prevent retries
            return ResponseEntity.ok().build();
        }
    }
}
