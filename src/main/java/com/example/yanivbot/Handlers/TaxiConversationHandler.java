package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.TaxiOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles all taxi order conversation flows.
 * 
 * Manages states:
 * - TAXI_SERVICE: Customer selecting service
 * - TAXI_PICKUP: Customer entering pickup location
 * - TAXI_DESTINATION: Customer entering destination
 * - TAXI_NOTES: Customer entering optional notes
 * - AWAITING_TAXI_ORDER_CONFIRMATION: Waiting for customer confirmation
 * 
 * Also handles:
 * - Driver claiming taxi orders: "מונית {id}"
 * - Driver completing taxi orders: "הסתיים {id}"
 */
@Component
public class TaxiConversationHandler implements ConversationHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(TaxiConversationHandler.class);
    
    private final TaxiOrderService taxiOrderService;
    private final ConversationService convoService;
    
    public TaxiConversationHandler(TaxiOrderService taxiOrderService, ConversationService convoService) {
        this.taxiOrderService = taxiOrderService;
        this.convoService = convoService;
    }
    
    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();
        
        // Check for customer requesting driver location: "מיקום"
        if (txt.equals("מיקום")) {
            return taxiOrderService.getDriverLocation(message.getPhone());
        }
        
        // Check for driver claiming taxi order: "מונית {id}"
        if (txt.matches("^מונית\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return taxiOrderService.claimTaxiOrder(orderId, message.getPhone());
        }
        
        // Check for driver completing taxi order: "הסתיים {id}"
        if (txt.matches("^הסתיים\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return taxiOrderService.completeOrder(orderId, message.getPhone());
        }
        
        // Handle state-based flows
        switch (state) {
            case TAXI_SERVICE:
                return handleTaxiService(convo, message);
                
            case TAXI_PICKUP:
                return handleTaxiPickup(convo, message);
                
            case TAXI_DESTINATION:
                return handleTaxiDestination(convo, message);
                
            case TAXI_NOTES:
                return handleTaxiNotes(convo, message);
                
            case AWAITING_TAXI_ORDER_CONFIRMATION:
                return handleTaxiConfirmation(convo, message);
                
            default:
                // This handler doesn't handle other states
                return null;
        }
    }
    
    /**
     * TAXI_SERVICE state: Customer choosing to order taxi
     * 
     * Expected input: "1" to confirm taxi order
     */
    private String handleTaxiService(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        
        if (txt.equals("1")) {
            convoService.updateState(convo, ConversationState.TAXI_PICKUP);
            return "מאיפה לאסוף אותך? (לא לשכוח עיר) 📍";
        }
        
        return "בחר שירות:\nעבור מונית לחץ - 1";
    }
    
    /**
     * TAXI_PICKUP state: Customer entering pickup location
     */
    private String handleTaxiPickup(Conversation convo, IncomingMessage message) {
        String pickupLocation = message.getText().trim();
        
        // Save pickup location as temp data
        convoService.saveTempData(convo, pickupLocation);
        convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
        
        return "לאן תרצה ללכת? 🎯";
    }
    
    /**
     * TAXI_DESTINATION state: Customer entering destination
     */
    private String handleTaxiDestination(Conversation convo, IncomingMessage message) {
        String destination = message.getText().trim();
        
        // Get the pickup location from temp data
        String pickupLocation = convo.getTempData();
        
        // Save both in temp data as "pickup|destination"
        String orderData = pickupLocation + "|" + destination;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.TAXI_NOTES);
        
        return "הערות נוספות? (או הקלד 'לא')";
    }
    
    /**
     * TAXI_NOTES state: Customer optionally entering notes
     */
    private String handleTaxiNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();
        
        if (notes.equals("לא")) {
            notes = "";
        }
        
        // Get the order data (pickup|destination)
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String pickupLocation = parts[0];
        String destination = parts[1];
        
        // Move to confirmation state
        convoService.saveTempData(convo, orderData + "|" + notes);
        convoService.updateState(convo, ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION);
        
        return "אנא אשר את הפרטים:\n" +
                "📍 מאיפה: " + pickupLocation + "\n" +
                "🎯 לאן: " + destination + "\n" +
                "📝 הערות: " + (notes.isEmpty() ? "אין" : notes) + "\n\n" +
                "הקלד 'כן' לאישור או 'לא' לביטול";
    }
    
    /**
     * AWAITING_TAXI_ORDER_CONFIRMATION state: Waiting for customer to confirm order
     */
    private String handleTaxiConfirmation(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        
        if (!txt.equals("כן")) {
            // User cancelled the order
            convoService.updateState(convo, ConversationState.START);
            return "❌ ההזמנה בוטלה.\n\nשלום 👋 בחר שירות:\nעבור מונית לחץ - 1";
        }
        
        // User confirmed - create the taxi order
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String pickupLocation = parts[0];
        String destination = parts[1];
        String notes = parts.length > 2 ? parts[2] : "";
        
        try {
            taxiOrderService.createTaxiOrder(message.getPhone(), pickupLocation, destination, notes);
            
            // Reset conversation state
            convoService.updateState(convo, ConversationState.START);
            
            return "✅ ההזמנה נוצרה בהצלחה! נחפש לך נהג קרוב...";
        } catch (Exception e) {
            logger.error("Failed to create taxi order for {}: {}", message.getPhone(), e.getMessage());
            convoService.updateState(convo, ConversationState.START);
            return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
        }
    }
}
