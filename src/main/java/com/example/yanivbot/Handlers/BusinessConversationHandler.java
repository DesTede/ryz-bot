package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles business owner flows with interactive buttons
 */
@Component
public class BusinessConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(BusinessConversationHandler.class);

    private final ConversationService convoService;
    private final WhatsappService whatsappService;

    public BusinessConversationHandler(ConversationService convoService, WhatsappService whatsappService) {
        this.convoService = convoService;
        this.whatsappService = whatsappService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();

        // Handle business menu with buttons
        if (state == ConversationState.BUSINESS_MENU) {
            return handleBusinessMenu(convo, message);
        }

        return null;
    }

    /**
     * BUSINESS_MENU state: Show menu with interactive buttons
     *
     * Button options: "business_taxi_option", "business_delivery_option", "business_end_shift_option"
     */
    private String handleBusinessMenu(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        // Handle button clicks
        if (txt.equals("business_taxi_option") || txt.equals("1")) {
            convoService.updateState(convo, ConversationState.TAXI_SERVICE);
            return null; // Taxi handler will take it from here
        }

        if (txt.equals("business_delivery_option") || txt.equals("2")) {
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
            return "טלפון הלקוח 📞";
        }

        if (txt.equals("business_end_shift_option")) {
            // Delegate to driver handler for shift end
            return null; // Driver handler will handle this
        }

        // Invalid input - show menu again
        showBusinessMenuButtons(message.getPhone());
        return null;
    }

    /**
     * Send business menu with interactive buttons
     */
    public void showBusinessMenuButtons(String phone) {
        String bodyText = "שלום בעלים 👋 בחר שירות:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("business_taxi_option", "🚕 מונית"),
                new WhatsappService.InteractiveButton("business_delivery_option", "🚚 משלוח"),
                new WhatsappService.InteractiveButton("business_end_shift_option", "🔚 סיים משמרת")
        );
    }
}