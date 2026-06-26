package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Handlers.MessageRouter;
import com.example.yanivbot.Entities.IncomingMessage;
import com.example.yanivbot.Entities.ProcessedMessage;
import com.example.yanivbot.Repositories.ProcessedMessageRepository;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.CustomerService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
    private CustomerService customerService;

    @Autowired
    private MessageRouter messageRouter;

    @Autowired
    private ProcessedMessageRepository processedMessageRepo;

    /**
     * Called by WhatsAppWebhookController when Meta sends a message.
     *
     * This is the new simplified message handler that delegates to MessageRouter.
     *
     * NOTE: Some handlers send messages directly via WhatsApp and return null.
     * Only send a reply if the handler returns a non-null/non-empty string.
     */
    public void handleMetaMessage(IncomingMessage message) {
        if (message == null) {
            return;
        }

        // Allow location messages even if text is empty. Only skip if there's no text AND no location data
        if ((message.getText() == null || message.getText().isBlank()) && !message.hasLocation()) {
            return;
        }

        // Idempotency: Meta delivers webhooks at-least-once and retries on slow/failed
        // responses. Insert-first on the message id; a duplicate PK means we've already
        // handled this message, so skip it (prevents duplicate orders/claims).
        String messageId = message.getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            try {
                processedMessageRepo.saveAndFlush(new ProcessedMessage(messageId));
            } catch (DataIntegrityViolationException e) {
                logger.info("Duplicate webhook message {} ignored", messageId);
                return;
            }
        }

        try {
            // Get or create conversation
            Conversation convo = convoService.getOrCreate(message.getPhone());
            customerService.updateLastMessageAt(message.getPhone());

            logger.info("Handling message from {}: '{}' | State: {}", message.getPhone(), message.getText(), convo.getState());

            // Route to appropriate handler
            String reply = messageRouter.route(convo, message);
            convo.setLastMessageTime(System.currentTimeMillis());
            convo.setNudgedAt(0);
            convoService.save(convo);

            // Send response only if handler returned something
            // Some handlers (like those sending interactive buttons) return null because
            // they already sent the message directly via WhatsApp
            if (reply != null && !reply.isEmpty()) {
                logger.info("Sending reply to {}: {}", message.getPhone(), reply);
                whatsappService.sendText(message.getPhone(), reply);
            } else {
                logger.info("Handler returned null/empty - message already sent via WhatsApp");
            }
        } catch (Exception e) {
            logger.error("Error handling message from {}: {}", message.getPhone(), e.getMessage(), e);
            whatsappService.sendSafeText(message.getPhone(), "❌ משהו השתבש. אנא נסה שוב.");
        }
    }
}