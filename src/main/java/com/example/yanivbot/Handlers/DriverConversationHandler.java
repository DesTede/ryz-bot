package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DriverService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles driver-specific commands:
 * - התחל משמרת (Start shift with location)
 * - סיים משמרת (End shift)
 * - Location sharing (LOCATION:lat,lng)
 */
@Component
public class DriverConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(DriverConversationHandler.class);

    private final DriverService driverService;
    private final ConversationService convoService;
    private final WhatsappService whatsappService;

    @Value("${admin.phones}")
    private String adminPhones;

    public DriverConversationHandler(DriverService driverService, ConversationService convoService, WhatsappService whatsappService) {
        this.driverService = driverService;
        this.convoService = convoService;
        this.whatsappService = whatsappService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();

        // Check for "התחל משמרת" (Start shift) command
        if (txt.equals("התחל משמרת") || txt.equals("driver_start_shift")) {
            return handleStartShift(convo, message);
        }

        // Check for "סיים משמרת" (End shift) command
        if (txt.equals("סיים משמרת") || txt.equals("driver_end_shift")) {
            return handleEndShift(convo, message);
        }

        // Check for location sharing: "LOCATION:lat,lng"
        if (txt.startsWith("LOCATION:")) {
            return handleLocationShare(convo, message);
        }

        // Handle awaiting location state
        if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
            return handleLocationAwait(convo, message);
        }

        // Not a driver command
        return null;
    }

    private String handleStartShift(Conversation convo, IncomingMessage message) {
        if (!isDriver(message.getPhone())) {
            return "❌ הטלפון שלך לא רשום במערכת כנהג.";
        }

        convoService.updateState(convo, ConversationState.AWAITING_DRIVER_LOCATION);

        return "📍 כדי להתחיל משמרת עליך לשתף מיקום בזמן אמת.\n" +
                "שתף מיקום חי ואז תירשם כזמין לקבל הזמנות.";
    }

    private String handleLocationShare(Conversation convo, IncomingMessage message) {
        try {
            String txt = message.getText().trim();
            String[] parts = txt.replace("LOCATION:", "").split(",");

            if (parts.length != 2) {
                return "❌ שגיאה בעיבוד המיקום. אנא נסה שוב.";
            }

            double latitude = Double.parseDouble(parts[0]);
            double longitude = Double.parseDouble(parts[1]);

            driverService.updateDriverLocation(message.getPhone(), latitude, longitude);
            String clockInMessage = driverService.clockIn(message.getPhone());

            convoService.updateState(convo, ConversationState.START);

            return "✅ המיקום התקבל! התחלת משמרת בהצלחה. תקבל הזמנות מעכשיו.";
        } catch (Exception e) {
            logger.error("Error processing location: {}", e.getMessage());
            return "❌ שגיאה בעיבוד המיקום. אנא נסה שוב.";
        }
    }

    private String handleLocationAwait(Conversation convo, IncomingMessage message) {
        // If we're waiting for location, any message that starts with LOCATION: should work
        if (message.getText().startsWith("LOCATION:")) {
            return handleLocationShare(convo, message);
        }

        // Otherwise, keep waiting
        return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
    }

    private String handleEndShift(Conversation convo, IncomingMessage message) {
        driverService.clockOut(message.getPhone());

        convoService.updateState(convo, ConversationState.START);

        // Check if business owner to show different menu
        if (isBusinessOwner(message.getPhone())) {
            return "👋 סיימת משמרת!\n\n" +
                    "שלום 👋\n" +
                    "בחר שירות:\n\n" +
                    "עבור מונית - 1\n\n" +
                    "עבור יצירת משלוח - 2\n\n" +
                    "(שלח 00 בכל עת לחזרה לתפריט הראשי)";
        } else {
            return "👋 סיימת משמרת!\n\n" +
                    "שלום 👋\n" +
                    "בחר שירות:\n\n" +
                    "עבור מונית לחץ - 1\n\n" +
                    "(שלח 00 בכל עת לחזרה לתפריט הראשי)";
        }
    }

    /**
     * Helper: Check if phone is registered as a driver
     */
    public boolean isDriver(String phone) {
        return driverService.findByPhone(phone) != null;
    }

    /**
     * Helper: Check if phone is a business owner
     */
    public boolean isBusinessOwner(String phone) {
        // Check if phone is in the ADMIN_PHONES list
        if (adminPhones != null && !adminPhones.isEmpty()) {
            String[] phones = adminPhones.split(",");
            for (String adminPhone : phones) {
                if (adminPhone.trim().equals(phone)) {
                    return true;
                }
            }
        }
        return false;
    }
}