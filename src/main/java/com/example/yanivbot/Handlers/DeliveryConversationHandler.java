package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DeliveryOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles all delivery order conversation flows.
 * 
 * Manages states:
 * - DELIVERY_CUSTOMER_PHONE: Business owner entering customer phone
 * - DELIVERY_ADDRESS: Business owner entering delivery address
 * - DELIVERY_READY_TIME: Business owner entering when order will be ready
 * - DELIVERY_PRICE: Business owner entering delivery price
 * - DELIVERY_NOTES: Business owner entering optional notes
 * 
 * Also handles:
 * - Customer requesting driver location: "מיקום"
 * - Dispatch delivery immediately: "מוכן עכשיו"
 * - Driver claiming delivery: "משלוח {id}"
 * - Driver marking picked up: "איסוף {id}"
 * - Driver marking delivered: "נמסר {id}"
 */
@Component
public class DeliveryConversationHandler implements ConversationHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(DeliveryConversationHandler.class);
    
    private final DeliveryOrderService deliveryOrderService;
    private final ConversationService convoService;
    
    public DeliveryConversationHandler(DeliveryOrderService deliveryOrderService, ConversationService convoService) {
        this.deliveryOrderService = deliveryOrderService;
        this.convoService = convoService;
    }
    
    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();
        
        // Check for customer requesting driver location: "מיקום"
        if (txt.equals("מיקום")) {
            return deliveryOrderService.getDriverLocation(message.getPhone());
        }
        
        // Check for dispatching delivery immediately: "מוכן עכשיו"
        if (txt.equals("מוכן עכשיו")) {
            return deliveryOrderService.dispatchNow(message.getPhone());
        }
        
        // Check for driver claiming delivery: "משלוח {id}"
        if (txt.matches("^משלוח\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.claimOrder(orderId, message.getPhone());
        }
        
        // Check for driver marking picked up: "איסוף {id}"
        if (txt.matches("^איסוף\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markPickedUp(orderId, message.getPhone());
        }
        
        // Check for driver marking delivered: "נמסר {id}"
        if (txt.matches("^נמסר\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markDelivered(orderId, message.getPhone());
        }
        
        // Handle state-based flows
        switch (state) {
            case DELIVERY_CUSTOMER_PHONE:
                return handleDeliveryCustomerPhone(convo, message);
                
            case DELIVERY_ADDRESS:
                return handleDeliveryAddress(convo, message);
                
            case DELIVERY_READY_TIME:
                return handleDeliveryReadyTime(convo, message);
                
            case DELIVERY_PRICE:
                return handleDeliveryPrice(convo, message);
                
            case DELIVERY_NOTES:
                return handleDeliveryNotes(convo, message);
                
            default:
                // This handler doesn't handle other states
                return null;
        }
    }
    
    /**
     * DELIVERY_CUSTOMER_PHONE state: Business owner entering customer phone
     */
    private String handleDeliveryCustomerPhone(Conversation convo, IncomingMessage message) {
        String customerPhone = message.getText().trim();
        
        // Save customer phone as temp data
        convoService.saveTempData(convo, customerPhone);
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
        
        return "כתובת המסירה 📍";
    }
    
    /**
     * DELIVERY_ADDRESS state: Business owner entering delivery address
     */
    private String handleDeliveryAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        
        // Get customer phone from temp data and append address
        String customerPhone = convo.getTempData();
        String orderData = customerPhone + "|" + address;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);
        
        return "בכמה דקות ההזמנה תהיה מוכנה? (או 'עכשיו')";
    }
    
    /**
     * DELIVERY_READY_TIME state: Business owner entering when order will be ready
     */
    private String handleDeliveryReadyTime(Conversation convo, IncomingMessage message) {
        String readyTime = message.getText().trim();
        
        // Get the previous data and append ready time
        String orderData = convo.getTempData();
        orderData = orderData + "|" + readyTime;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.DELIVERY_PRICE);
        
        return "מחיר המשלוח 💰";
    }
    
    /**
     * DELIVERY_PRICE state: Business owner entering delivery price
     */
    private String handleDeliveryPrice(Conversation convo, IncomingMessage message) {
        String price = message.getText().trim();
        
        // Get the previous data and append price
        String orderData = convo.getTempData();
        orderData = orderData + "|" + price;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);
        
        return "הערות נוספות? (או הקלד 'לא')";
    }
    
    /**
     * DELIVERY_NOTES state: Business owner entering optional notes
     */
    private String handleDeliveryNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();
        
        if (notes.equals("לא")) {
            notes = "";
        }
        
        // Get all the order data
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String customerPhone = parts[0];
        String address = parts[1];
        String readyTime = parts[2];
        String price = parts[3];
        
        try {
            // Create the delivery order
            deliveryOrderService.createDelivery(convo,
                message.getPhone(),  // business owner phone
//                customerPhone,
//                address,
//                readyTime,
//                price,
                notes
            );
            
            // Reset conversation state
            convoService.updateState(convo, ConversationState.START);
            
            return "✅ ההזמנה נוצרה בהצלחה! המשלוח ישודרו לנהגים כשהסחורה תהיה מוכנה.";
        } catch (Exception e) {
            logger.error("Failed to create delivery order for {}: {}", message.getPhone(), e.getMessage());
            convoService.updateState(convo, ConversationState.START);
            return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
        }
    }
}
