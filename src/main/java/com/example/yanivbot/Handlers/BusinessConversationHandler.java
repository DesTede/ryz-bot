package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles business owner menu flows.
 * 
 * State:
 * - BUSINESS_MENU: Business owner selecting service (taxi order or create delivery)
 * 
 * This handler delegates to TaxiConversationHandler or DeliveryConversationHandler
 * based on the business owner's selection.
 */
@Component
public class BusinessConversationHandler implements ConversationHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(BusinessConversationHandler.class);
    
    private final ConversationService convoService;
    
    public BusinessConversationHandler(ConversationService convoService) {
        this.convoService = convoService;
    }
    
    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        
        // Only handle BUSINESS_MENU state
        if (convo.getState() != ConversationState.BUSINESS_MENU) {
            return null;
        }
        
        return handleBusinessMenu(convo, message);
    }
    
    /**
     * BUSINESS_MENU state: Business owner selecting service
     * 
     * Options:
     * - "1": Order taxi
     * - "2": Create delivery order
     */
    private String handleBusinessMenu(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        
        if (txt.equals("1")) {
            // Business owner ordering taxi
            convoService.updateState(convo, ConversationState.TAXI_PICKUP);
            return "מאיפה לאסוף אותך? (לא לשכוח עיר) 📍";
        } else if (txt.equals("2")) {
            // Business owner creating delivery order
            convoService.saveTempData(convo, "");
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
            return "טלפון הלקוח 📞";
        }
        
        return "אנא בחר 1 או 2";
    }
}
