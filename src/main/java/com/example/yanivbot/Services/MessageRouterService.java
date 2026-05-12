package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Routes incoming WhatsApp messages through conversation state machine.
 * Handles the logic that was previously in MessageController.
 * 
 * NOTE: This is a placeholder/template for future refactoring.
 * Currently, MessageController still handles all the logic.
 * This service will be fully implemented when you're ready to refactor.
 */
@Service
public class MessageRouterService {
    private static final Logger logger = LoggerFactory.getLogger(MessageRouterService.class);
    
    @Autowired
    private ConversationService conversationService;
    
    @Autowired
    private WhatsappService whatsappService;
    
    @Autowired
    private TaxiOrderService taxiOrderService;
    
    @Autowired
    private DeliveryOrderService deliveryOrderService;
    
    @Autowired
    private BusinessOwnerService businessOwnerService;
    
    @Autowired
    private DriverService driverService;
    
    @Autowired
    private BotConfigService botConfigService;
    
    /**
     * Main entry point for handling user messages.
     * Routes based on conversation state.
     * 
     * This is a STUB implementation.
     * For now, all logic remains in MessageController.
     * This service is here for future refactoring when the code is ready.
     */
    public void handleUserMessage(String phoneNumber, String messageText) {
        try {
            if (messageText == null || messageText.trim().isEmpty()) {
                logger.warn("Received empty message from {}", phoneNumber);
                return;
            }
            
            logger.info("Processing message from {} : {}", phoneNumber, messageText);
            
            // Get or create conversation state
            Conversation conversation = conversationService.getOrCreate(phoneNumber);
            ConversationState currentState = conversation.getState();
            
            logger.debug("User {} in state: {}", phoneNumber, currentState);
            
            // TODO: Implement full message routing here
            // For now, this is a stub. All logic is still in MessageController.
            // This service will be completed during the refactoring phase.
            
            logger.warn("MessageRouterService.handleUserMessage called but not yet implemented. " +
                    "Please use MessageController for now.");
            
        } catch (Exception e) {
            logger.error("Error handling message from {}: {}", phoneNumber, e.getMessage(), e);
        }
    }
}
