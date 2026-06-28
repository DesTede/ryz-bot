package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Entities.IncomingMessage;
import com.example.yanivbot.Services.BusinessOwnerService;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import org.springframework.stereotype.Component;

@Component
public class BusinessConversationHandler implements ConversationHandler {

    private final ConversationService convoService;
    private final WhatsappService whatsappService;
    private final BusinessOwnerService businessOwnerService;


    public BusinessConversationHandler(ConversationService convoService, WhatsappService whatsappService, BusinessOwnerService businessOwnerService) {
        this.convoService = convoService;
        this.whatsappService = whatsappService;
        this.businessOwnerService = businessOwnerService;
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
            whatsappService.sendInteractiveButtonsSafe(
                    message.getPhone(),
                    "מעולה 👍\nעכשיו בחרו את סוג הרכב:",
//                    new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "אופנוע 🏍️"),
                    new WhatsappService.InteractiveButton("taxi_car_type_private_car", "מכונית 🚗"),
                    new WhatsappService.InteractiveButton("taxi_car_type_minivan", "רכב גדול +6 🚐")
            );
            return null;
        }

        if (txt.equals("business_delivery_option") || txt.equals("2")) {
            // Business owner creating delivery - start with customer phone
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
            return "📞 מה מספר הטלפון של הלקוח?";
        }

        // Invalid option - show menu again
        showBusinessMenuButtons(message.getPhone());
        return null;
    }

    public void showBusinessMenuButtons(String phone) {
        String businessName = businessOwnerService.getBusinessName(phone);
        String greeting = (businessName != null && !businessName.isEmpty())
                ? "🚀 שלום " + businessName + "!\nברוכים הבאים ל־RYZ Business"
                : "🚀 שלום וברוכים הבאים\nל־RYZ Business";
        
        String bodyText = greeting + "\n" +
                "ניהול משלוחים ונסיעות בקלות ובמהירות ⚡" +
                "\nמה תרצו לעשות? 👇\n" +
                "(לחץ 00 לאתחול מחדש)";
        
        whatsappService.sendInteractiveButtonsSafe(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("business_delivery_option", "🛵 1️⃣ יצירת משלוח"),
                new WhatsappService.InteractiveButton("business_taxi_option", "🚗 2️⃣ מונית")
        );
    }
}