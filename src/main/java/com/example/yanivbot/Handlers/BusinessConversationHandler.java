package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import org.springframework.stereotype.Component;

@Component
public class BusinessConversationHandler implements ConversationHandler {

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

        if (state == ConversationState.BUSINESS_MENU) {
            return handleBusinessMenu(convo, message);
        }

        return null;
    }

    private String handleBusinessMenu(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        if (txt.equals("business_taxi_option") || txt.equals("1")) {
            // Business owner ordering taxi - treat as customer
            convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);
            // Send car type selection buttons
            whatsappService.sendInteractiveButtons(
                    message.getPhone(),
                    "מעולה 👍\nעכשיו בחרו את סוג הרכב:",
                    new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "אופנוע 🏍️"),
                    new WhatsappService.InteractiveButton("taxi_car_type_private_car", "מכונית 🚗"),
                    new WhatsappService.InteractiveButton("taxi_car_type_minivan", "הסעות +6 🚐")
            );
            return null;
        }

        if (txt.equals("business_delivery_option") || txt.equals("2")) {
            // Business owner creating delivery - start with customer name
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_NAME);
            return "מה שם הלקוח?";
        }

        // Invalid option - show menu again
        showBusinessMenuButtons(message.getPhone());
        return null;
    }

    public void showBusinessMenuButtons(String phone) {
        String bodyText = "🚀 שלום וברוכים הבאים ל־Moovez Business\nניהול משלוחים ונסיעות בקלות ובמהירות ⚡\nמה תרצו לעשות? 👇";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("business_delivery_option", "🛵 1️⃣ יצירת משלוח"),
                new WhatsappService.InteractiveButton("business_taxi_option", "🚗 2️⃣ מונית")
        );
    }
}