package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Routes incoming WhatsApp messages to appropriate service based on conversation state.
 * Acts as the main message dispatcher for the bot.
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
    
    /**
     * Main entry point for handling user messages.
     * Routes based on conversation state and message content.
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
            
            // Route based on current conversation state
            switch (currentState) {
                case START:
                    handleStartState(phoneNumber, messageText, conversation);
                    break;
                case AWAITING_TAXI_LOCATION:
                    handleTaxiLocation(phoneNumber, messageText, conversation);
                    break;
                case AWAITING_DELIVERY_PICKUP:
                    handleDeliveryPickup(phoneNumber, messageText, conversation);
                    break;
                case AWAITING_DELIVERY_DROPOFF:
                    handleDeliveryDropoff(phoneNumber, messageText, conversation);
                    break;
                case AWAITING_TAXI_DESTINATION:
                    handleTaxiDestination(phoneNumber, messageText, conversation);
                    break;
                // Add other states as needed
                default:
                    logger.warn("Unknown conversation state: {}", currentState);
                    whatsappService.sendSafeText(phoneNumber, "מצטער, קרתה שגיאה. אנא נסה שוב.");
            }
        } catch (Exception e) {
            logger.error("Error handling message from {}: {}", phoneNumber, e.getMessage(), e);
            whatsappService.sendSafeText(phoneNumber, "מצטער, קרתה שגיאה. אנא נסה שוב.");
        }
    }
    
    /**
     * START state: User chooses between taxi, delivery, or is a driver/business owner
     */
    private void handleStartState(String phoneNumber, String messageText, Conversation conversation) {
        logger.debug("Handling START state for {}", phoneNumber);
        
        String text = messageText.toLowerCase().trim();
        
        // Check for taxi order keyword: "מונית"
        if (text.contains("מונית")) {
            conversationService.updateState(conversation, ConversationState.AWAITING_TAXI_LOCATION);
            whatsappService.sendSafeText(phoneNumber, "אנא שלח את כתובת ההתחלה (מאיפה תרצה להתחיל?)");
            return;
        }
        
        // Check for delivery order keyword: "משלוח"
        if (text.contains("משלוח")) {
            conversationService.updateState(conversation, ConversationState.AWAITING_DELIVERY_PICKUP);
            whatsappService.sendSafeText(phoneNumber, "אנא שלח את כתובת ההאיסוף");
            return;
        }
        
        // Check if user is a driver claiming orders (e.g., "מונית 123")
        if (text.matches("^מונית\\s+\\d+$")) {
            handleDriverTaxiClaim(phoneNumber, messageText, conversation);
            return;
        }
        
        // Check if user is a driver claiming delivery (e.g., "משלוח 456")
        if (text.matches("^משלוח\\s+\\d+$")) {
            handleDriverDeliveryClaim(phoneNumber, messageText, conversation);
            return;
        }
        
        // Check if order completion (e.g., "הסתיים 123")
        if (text.matches("^הסתיים\\s+\\d+$")) {
            handleTaxiCompletion(phoneNumber, messageText, conversation);
            return;
        }
        
        // Check if user is a business owner
        if (businessOwnerService.isBusinessOwner(phoneNumber)) {
            handleBusinessOwnerMenu(phoneNumber, messageText, conversation);
            return;
        }
        
        // Default: show main menu
        showMainMenu(phoneNumber);
    }
    
    /**
     * Driver claiming a taxi order (message format: "מונית {orderId}")
     */
    private void handleDriverTaxiClaim(String phoneNumber, String messageText, Conversation conversation) {
        try {
            long orderId = Long.parseLong(messageText.replaceAll("[^0-9]", ""));
            String response = taxiOrderService.claimTaxiOrder(orderId, phoneNumber);
            
            if (response != null) {
                whatsappService.sendSafeText(phoneNumber, response);
            }
            
            conversationService.updateState(conversation, ConversationState.START);
        } catch (NumberFormatException e) {
            logger.error("Failed to parse taxi order ID from: {}", messageText);
            whatsappService.sendSafeText(phoneNumber, "❌ מספר הזמנה לא תקין");
        }
    }
    
    /**
     * Driver claiming a delivery order (message format: "משלוח {orderId}")
     */
    private void handleDriverDeliveryClaim(String phoneNumber, String messageText, Conversation conversation) {
        try {
            long orderId = Long.parseLong(messageText.replaceAll("[^0-9]", ""));
            String response = deliveryOrderService.claimDeliveryOrder(orderId, phoneNumber);
            
            if (response != null) {
                whatsappService.sendSafeText(phoneNumber, response);
            }
            
            conversationService.updateState(conversation, ConversationState.START);
        } catch (NumberFormatException e) {
            logger.error("Failed to parse delivery order ID from: {}", messageText);
            whatsappService.sendSafeText(phoneNumber, "❌ מספר הזמנה לא תקין");
        }
    }
    
    /**
     * Driver completing a taxi order (message format: "הסתיים {orderId}")
     */
    private void handleTaxiCompletion(String phoneNumber, String messageText, Conversation conversation) {
        try {
            long orderId = Long.parseLong(messageText.replaceAll("[^0-9]", ""));
            String response = taxiOrderService.completeOrder(orderId, phoneNumber);
            whatsappService.sendSafeText(phoneNumber, response);
            conversationService.updateState(conversation, ConversationState.START);
        } catch (NumberFormatException e) {
            logger.error("Failed to parse order ID from: {}", messageText);
            whatsappService.sendSafeText(phoneNumber, "❌ מספר הזמנה לא תקין");
        }
    }
    
    /**
     * AWAITING_TAXI_LOCATION: User sent pickup location for taxi order
     */
    private void handleTaxiLocation(String phoneNumber, String messageText, Conversation conversation) {
        logger.debug("Handling AWAITING_TAXI_LOCATION for {}", phoneNumber);
        
        // Save pickup location as temp data
        conversationService.saveTempData(conversation, messageText);
        
        // Move to next state: ask for destination
        conversationService.updateState(conversation, ConversationState.AWAITING_TAXI_DESTINATION);
        whatsappService.sendSafeText(phoneNumber, "אנא שלח את כתובת היעד (לאן תרצה ללכת?)");
    }
    
    /**
     * AWAITING_TAXI_DESTINATION: User sent destination for taxi order
     */
    private void handleTaxiDestination(String phoneNumber, String messageText, Conversation conversation) {
        logger.debug("Handling AWAITING_TAXI_DESTINATION for {}", phoneNumber);
        
        String pickupLocation = conversation.getTempData(); // Retrieved from previous state
        String destination = messageText;
        String notes = ""; // Could be optional
        
        // Create the taxi order
        taxiOrderService.createTaxiOrder(phoneNumber, pickupLocation, destination, notes);
        
        // Reset conversation state
        conversationService.updateState(conversation, ConversationState.START);
        conversationService.saveTempData(conversation, ""); // Clear temp data
    }
    
    /**
     * AWAITING_DELIVERY_PICKUP: User sent pickup address for delivery
     */
    private void handleDeliveryPickup(String phoneNumber, String messageText, Conversation conversation) {
        logger.debug("Handling AWAITING_DELIVERY_PICKUP for {}", phoneNumber);
        
        // Save pickup address as temp data
        conversationService.saveTempData(conversation, messageText);
        
        // Move to next state: ask for dropoff
        conversationService.updateState(conversation, ConversationState.AWAITING_DELIVERY_DROPOFF);
        whatsappService.sendSafeText(phoneNumber, "אנא שלח את כתובת ההסעה (לאן צריך להסיע את החבילה?)");
    }
    
    /**
     * AWAITING_DELIVERY_DROPOFF: User sent dropoff address for delivery
     */
    private void handleDeliveryDropoff(String phoneNumber, String messageText, Conversation conversation) {
        logger.debug("Handling AWAITING_DELIVERY_DROPOFF for {}", phoneNumber);
        
        String pickupAddress = conversation.getTempData();
        String dropoffAddress = messageText;
        
        // Create the delivery order
        deliveryOrderService.createDeliveryOrder(phoneNumber, pickupAddress, dropoffAddress);
        
        // Reset conversation state
        conversationService.updateState(conversation, ConversationState.START);
        conversationService.saveTempData(conversation, ""); // Clear temp data
    }
    
    /**
     * Business owner menu (if registered as business owner)
     */
    private void handleBusinessOwnerMenu(String phoneNumber, String messageText, Conversation conversation) {
        logger.debug("Handling BUSINESS_OWNER menu for {}", phoneNumber);
        
        // TODO: Implement business owner specific logic
        // Examples: create delivery orders, view orders, manage account
        
        whatsappService.sendSafeText(phoneNumber, "ברוכים הבאים! אתה רשום כעסק. האפשרויות שלך:");
    }
    
    /**
     * Show main menu to user
     */
    private void showMainMenu(String phoneNumber) {
        String menu = """
            👋 ברוכים הבאים ל-YanivBot!
            
            בחר אחת מהאפשרויות:
            
            🚕 מונית - הזמן מונית
            📦 משלוח - שלח חבילה
            
            (או חזור לתפריט זה בכל עת)
            """;
        
        whatsappService.sendSafeText(phoneNumber, menu);
    }
}
