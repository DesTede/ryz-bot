package com.example.yanivbot.Controllers;

import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.WhatsappService;
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
 * Routes messages to MessageController for processing.
 */
@RestController
@RequestMapping("/webhook")
public class WhatsAppWebhookController {
    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    
    @Autowired
    private WhatsappService whatsappService;
    
    @Autowired
    private MessageController messageController;
    
    @Value("${whatsapp.webhook-verify-token:yanivbot_verify}")
    private String webhookVerifyToken;
    
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
    public ResponseEntity<Void> handleWebhook(@RequestBody Map<String, Object> payload) {

        System.out.println("===== WEBHOOK CALLED =====");
        System.out.println("Payload: " + payload);
                
        try {
            logger.debug("Webhook payload received from Meta");
            
            // Parse incoming message using WhatsappService
            IncomingMessage incomingMessage = whatsappService.parseIncomingMessage(payload);
            
            if (incomingMessage == null) {
                logger.debug("No valid message in webhook payload");
                return ResponseEntity.ok().build();
            }
            
            String phoneNumber = incomingMessage.getPhone();
            String messageText = incomingMessage.getText();
            
            logger.info("Message received from {}: {}", phoneNumber, messageText);
            
            // Route to MessageController to process the message
            // MessageController.processMessage handles all the bot logic
            messageController.handleMetaMessage(incomingMessage);
            
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok().build();
        }
    }

    
}
