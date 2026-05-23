package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.CarType;
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
 * Updated to include car type selection.
 * 
 * Manages states:
 * - TAXI_SERVICE: Customer selecting service
 * - TAXI_CAR_TYPE: Customer selecting car type (NEW)
 * - TAXI_PICKUP: Customer entering pickup location
 * - TAXI_DESTINATION: Customer entering destination
 * - TAXI_NOTES: Customer entering optional notes
 * - AWAITING_TAXI_ORDER_CONFIRMATION: Waiting for customer confirmation
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
                
            case TAXI_CAR_TYPE:
                return handleTaxiCarType(convo, message);
                
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
            convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);
            return "בחר סוג כלי רכב:\n\n" +
                    "1️⃣ אופנוע\n" +
                    "2️⃣ מכונית פרטית\n" +
                    "3️⃣ מיניוואן";
        }
        
        return "בחר שירות:\nעבור מונית לחץ - 1";
    }
    
    /**
     * TAXI_CAR_TYPE state: Customer selecting car type
     * 
     * Options: 1 = Motorcycle, 2 = Private Car, 3 = Minivan
     */
    private String handleTaxiCarType(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        CarType selectedCarType = null;
        
        if (txt.equals("1")) {
            selectedCarType = CarType.MOTORCYCLE;
        } else if (txt.equals("2")) {
            selectedCarType = CarType.PRIVATE_CAR;
        } else if (txt.equals("3")) {
            selectedCarType = CarType.MINIVAN;
        } else {
            return "בחירה לא חוקית. בחר 1, 2 או 3";
        }
        
        // Save car type to temp data
        convoService.saveTempData(convo, selectedCarType.name());
        convoService.updateState(convo, ConversationState.TAXI_PICKUP);
        
        return "מאיפה לאסוף אותך? (לא לשכוח עיר) 📍";
    }
    
    /**
     * TAXI_PICKUP state: Customer entering pickup location
     */
    private String handleTaxiPickup(Conversation convo, IncomingMessage message) {
        String pickupLocation = message.getText().trim();
        
        // Get car type from temp data and append pickup location
        String carType = convo.getTempData();
        String orderData = carType + "|" + pickupLocation;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
        
        return "לאן תרצה ללכת? 🎯";
    }
    
    /**
     * TAXI_DESTINATION state: Customer entering destination
     */
    private String handleTaxiDestination(Conversation convo, IncomingMessage message) {
        String destination = message.getText().trim();
        
        // Get the order data (carType|pickup) from temp data
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];
        
        // Save both in temp data as "carType|pickup|destination"
        orderData = carType + "|" + pickupLocation + "|" + destination;
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
        
        // Get the order data (carType|pickup|destination)
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];
        
        // Move to confirmation state
        convoService.saveTempData(convo, orderData + "|" + notes);
        convoService.updateState(convo, ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION);
        
        return "אנא אשר את הפרטים:\n" +
                "🚗 כלי רכב: " + CarType.valueOf(carType).getHebrewName() + "\n" +
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
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];
        String notes = parts.length > 3 ? parts[3] : "";
        
        try {
            taxiOrderService.createTaxiOrder(message.getPhone(), pickupLocation, destination, notes, CarType.valueOf(carType));
            
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
