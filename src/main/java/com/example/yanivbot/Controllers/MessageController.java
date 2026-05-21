package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Handlers.MessageRouter;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simplified MessageController using MessageRouter.
 * 
 * Responsibilities:
 * 1. Receive incoming messages from WhatsAppWebhookController
 * 2. Parse messages
 * 3. Route to appropriate handler via MessageRouter
 * 4. Send response back to user
 * 
 * All business logic has been moved to Handlers in the com.example.yanivbot.Handlers package.
 * 
 * The old 500+ line MessageController with switch statements is now a clean 50-line controller.
 */
@RestController
@RequestMapping("/message")
public class MessageController {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageController.class);
    
    @Autowired
    private WhatsappService whatsappService;
    
    @Autowired
    private ConversationService convoService;
    
    @Autowired
    private MessageRouter messageRouter;
    
    /**
     * Called by WhatsAppWebhookController when Meta sends a message.
     * 
     * This is the new simplified message handler that delegates to MessageRouter.
     */
    public void handleMetaMessage(IncomingMessage message) {
        if (message == null || message.getText() == null || message.getText().isBlank()) {
            return;
        }
        
        try {
            // Get or create conversation
            Conversation convo = convoService.getOrCreate(message.getPhone());
            
            // Route to appropriate handler
            String reply = messageRouter.route(convo, message);
            
            // Send response
            if (reply != null && !reply.isEmpty()) {
                whatsappService.sendText(message.getPhone(), reply);
            }
        } catch (Exception e) {
            logger.error("Error handling message from {}: {}", message.getPhone(), e.getMessage(), e);
            whatsappService.sendSafeText(message.getPhone(), "❌ משהו השתבש. אנא נסה שוב.");
        }
    }
}
