package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.BusinessOwnerService;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MessageRouter orchestrates message routing to appropriate handlers.
 * 
 * Decision flow:
 * 1. Check for driver commands ("התחל משמרת", "סיים משמרת", location)
 * 2. Check for driver order claims/completions (מונית {id}, הסתיים {id}, משלוח {id}, etc.)
 * 3. Check for customer requests (מיקום, מוכן עכשיו)
 * 4. Check for special commands ("00" reset, "בוט פעיל", "בוט כבוי")
 * 5. Route to state-specific handler based on current conversation state
 * 
 * Handlers:
 * - TaxiConversationHandler: Handles taxi order flows
 * - DeliveryConversationHandler: Handles delivery order flows
 * - DriverConversationHandler: Handles driver shift management
 * - BusinessConversationHandler: Handles business owner menu
 * - StartHandler (implicit): Handles START state
 */
@Service
public class MessageRouter {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);
    
    private final TaxiConversationHandler taxiHandler;
    private final DeliveryConversationHandler deliveryHandler;
    private final DriverConversationHandler driverHandler;
    private final BusinessConversationHandler businessHandler;
    private final ConversationService convoService;
    private final DriverService driverService;
    private final BusinessOwnerService businessOwnerService;
    
    public MessageRouter(TaxiConversationHandler taxiHandler,
                        DeliveryConversationHandler deliveryHandler,
                        DriverConversationHandler driverHandler,
                        BusinessConversationHandler businessHandler,
                        ConversationService convoService,
                        DriverService driverService,
                        BusinessOwnerService businessOwnerService) {
        this.taxiHandler = taxiHandler;
        this.deliveryHandler = deliveryHandler;
        this.driverHandler = driverHandler;
        this.businessHandler = businessHandler;
        this.convoService = convoService;
        this.driverService = driverService;
        this.businessOwnerService = businessOwnerService;
    }
    
    /**
     * Route an incoming message to the appropriate handler.
     * 
     * @param convo The conversation with current state
     * @param message The incoming message
     * @return The response to send to the user, or null if no response
     */
    public String route(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        
        // Special commands that work from any state
        if (txt.equals("00")) {
            // Reset to START state
            convoService.updateState(convo, ConversationState.START);
            return handleStart(convo, message);
        }
        
        // Bot active/inactive commands (admin only)
        if (txt.equals("בוט פעיל") || txt.equals("בוט כבוי")) {
            // These should be handled by a separate BotConfigService
            // For now, just acknowledge
            return null;
        }
        
        // Route based on current state
        switch (convo.getState()) {
            case START:
                return handleStart(convo, message);
                
            case TAXI_SERVICE, TAXI_PICKUP, TAXI_DESTINATION, TAXI_NOTES, AWAITING_TAXI_ORDER_CONFIRMATION:
                // Taxi handler will process if it matches taxi-related patterns
                String taxiResult = taxiHandler.handleMessage(convo, message);
                if (taxiResult != null) {
                    return taxiResult;
                }
                // Fall through if taxi handler doesn't handle it
                break;
                
            case DELIVERY_CUSTOMER_PHONE, DELIVERY_ADDRESS, DELIVERY_READY_TIME, DELIVERY_PRICE, DELIVERY_NOTES:
                // Delivery handler will process
                String deliveryResult = deliveryHandler.handleMessage(convo, message);
                if (deliveryResult != null) {
                    return deliveryResult;
                }
                break;
                
            case AWAITING_DRIVER_LOCATION:
                // Driver handler will process
                String driverResult = driverHandler.handleMessage(convo, message);
                if (driverResult != null) {
                    return driverResult;
                }
                break;
                
            case BUSINESS_MENU:
                // Business handler will process
                String businessResult = businessHandler.handleMessage(convo, message);
                if (businessResult != null) {
                    return businessResult;
                }
                break;
        }
        
        // Try driver commands (work from any state)
        String driverResult = driverHandler.handleMessage(convo, message);
        if (driverResult != null) {
            return driverResult;
        }
        
        // Try taxi-specific commands (driver claiming/completing) (work from any state)
        String taxiResult = taxiHandler.handleMessage(convo, message);
        if (taxiResult != null) {
            return taxiResult;
        }
        
        // Try delivery-specific commands (driver claiming/completing, customer tracking) (work from any state)
        String deliveryResult = deliveryHandler.handleMessage(convo, message);
        if (deliveryResult != null) {
            return deliveryResult;
        }
        
        // No handler processed the message
        logger.warn("No handler processed message '{}' in state {} from {}", 
            txt, convo.getState(), message.getPhone());
        
        return null;
    }
    
    /**
     * Handle START state.
     * 
     * Determines user type and shows appropriate menu:
     * - Driver: Show shift start/end options
     * - Business owner: Show business menu
     * - Regular customer: Show service selection
     */
    private String handleStart(Conversation convo, IncomingMessage message) {
        // Check if user is a driver
        Driver driver = driverService.findByPhone(message.getPhone());
        if (driver != null) {
            String shiftStatus = driver.isActive() ? "🟢 במשמרת" : "🔴 לא במשמרת";
            return "שלום " + driver.getName() + " " + shiftStatus + "\n\n" +
                    "השב \"התחל משמרת\" להתחלת משמרת ולקבלת הזמנות\n" +
                    "השב \"סיים משמרת\" לסיום משמרת ולעצירת קבלת הזמנות";
        }
        
        // Check if user is a business owner
        if (businessOwnerService.isBusinessOwner(message.getPhone())) {
            convoService.updateState(convo, ConversationState.BUSINESS_MENU);
            return "שלום 👋\n" +
                    "בחר שירות:\n\n" +
                    "עבור מונית - 1\n\n" +
                    "עבור יצירת משלוח - 2\n\n" +
                    "(שלח 00 בכל עת לחזרה לתפריט הראשי)";
        }
        
        // Regular customer
        convoService.updateState(convo, ConversationState.TAXI_SERVICE);
        return "שלום 👋\n" +
                "בחר שירות:\n\n" +
                "עבור מונית לחץ - 1\n\n" +
                "(שלח 00 בכל עת לחזרה לתפריט הראשי)";
    }
}
