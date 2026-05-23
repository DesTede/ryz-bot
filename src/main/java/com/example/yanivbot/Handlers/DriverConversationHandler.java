package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DriverService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles driver-specific commands with interactive buttons:
 * - התחל משמרת (Start shift with location confirmation button)
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

        // Show confirmation with INTERACTIVE BUTTONS
        showShiftStartConfirmation(message.getPhone());
        return null; // Already sent via WhatsApp
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
            driverService.clockIn(message.getPhone());

            convoService.updateState(convo, ConversationState.START);

            return "✅ המיקום התקבל! התחלת משמרת בהצלחה. תקבל הזמנות מעכשיו.";
        } catch (Exception e) {
            logger.error("Error processing location: {}", e.getMessage());
            return "❌ שגיאה בעיבוד המיקום. אנא נסה שוב.";
        }
    }

    /**
     * AWAITING_DRIVER_LOCATION state with INTERACTIVE BUTTONS
     *
     * Button options: "driver_share_location_start", "driver_cancel_shift_start"
     */
    private String handleLocationAwait(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        // Handle button clicks
        if (txt.equals("driver_share_location_start")) {
            // User tapped "Share Location" button - they need to share location via WhatsApp
            return "📍 אנא שתף את המיקום שלך מהקלסטר כדי להתחיל משמרת.";
        }

        if (txt.equals("driver_cancel_shift_start")) {
            convoService.updateState(convo, ConversationState.START);
            return "❌ ביטול התחלת משמרת.";
        }

        // If location is shared, process it
        if (txt.startsWith("LOCATION:")) {
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
     * Send shift start confirmation with INTERACTIVE BUTTONS
     */
    private void showShiftStartConfirmation(String phone) {
        String bodyText = "התחל משמרת?";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("driver_share_location_start", "📍 שתף מיקום והתחל"),
                new WhatsappService.InteractiveButton("driver_cancel_shift_start", "❌ ביטול")
        );
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