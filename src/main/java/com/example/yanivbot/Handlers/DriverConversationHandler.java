package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Services.BusinessOwnerService;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles driver-specific commands.
 *
 * Commands:
 * - "התחל משמרת": Driver starting shift (requires location)
 * - "סיים משמרת": Driver ending shift
 * - Location sharing: Updates driver location
 *
 * State:
 * - AWAITING_DRIVER_LOCATION: Waiting for driver to share location after starting shift
 */
@Component
public class DriverConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(DriverConversationHandler.class);

    private final DriverService driverService;
    private final ConversationService convoService;
    private final BusinessOwnerService businessOwnerService;

    public DriverConversationHandler(DriverService driverService, ConversationService convoService, BusinessOwnerService businessOwnerService) {
        this.driverService = driverService;
        this.convoService = convoService;
        this.businessOwnerService = businessOwnerService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        logger.info("DriverConversationHandler: txt='{}', state={}", txt, convo.getState());

        // Check for driver starting shift: "התחל משמרת"
        if (txt.equals("התחל משמרת")) {
            logger.info("Matched: התחל משמרת");
            return handleStartShift(convo, message);
        }

        // Check for driver ending shift: "סיים משמרת"
        if (txt.equals("סיים משמרת")) {
            logger.info("Matched: סיים משמרת");
            return handleEndShift(convo, message);
        }

        // Check for location message during shift start
        if (txt.startsWith("LOCATION:")) {
            if (convo.getState() == ConversationState.AWAITING_DRIVER_LOCATION) {
                return handleLocationForShiftStart(convo, message, txt);
            }
        }

        // This handler doesn't handle other states
        logger.debug("DriverConversationHandler: not handling this message");
        return null;
    }

    /**
     * Handle driver starting shift: "התחל משמרת"
     *
     * Checks if driver exists, then requests location
     */
    private String handleStartShift(Conversation convo, IncomingMessage message) {
        Driver driver = driverService.findByPhone(message.getPhone());

        if (driver == null) {
            return "❌ הטלפון שלך לא רשום במערכת כנהג.";
        }

        // Update state to wait for location
        convoService.updateState(convo, ConversationState.AWAITING_DRIVER_LOCATION);

        return "📍 כדי להתחיל משמרת עליך לשתף מיקום בזמן אמת.\n" +
                "שתף מיקום חי ואז תירשם כזמין לקבל הזמנות.";
    }

    /**
     * Handle driver ending shift: "סיים משמרת"
     *
     * Clock out driver and show appropriate menu
     */
    private String handleEndShift(Conversation convo, IncomingMessage message) {
        logger.info("handleEndShift: Clocking out driver {}", message.getPhone());

        driverService.clockOut(message.getPhone());

        // Check if user is also a business owner
        if (businessOwnerService.isBusinessOwner(message.getPhone())) {
            convoService.updateState(convo, ConversationState.BUSINESS_MENU);
            logger.info("handleEndShift: User is business owner, showing business menu");
            return "👋 סיימת משמרת!\n\n" +
                    "שלום 👋\n" +
                    "בחר שירות:\n\n" +
                    "עבור מונית - 1\n\n" +
                    "עבור יצירת משלוח - 2\n\n" +
                    "(שלח 00 בכל עת לחזרה לתפריט הראשי)";
        } else {
            // Regular customer menu
            convoService.updateState(convo, ConversationState.TAXI_SERVICE);
            logger.info("handleEndShift: User is regular customer, showing customer menu");
            return "👋 סיימת משמרת!\n\n" +
                    "שלום 👋\n" +
                    "בחר שירות:\n\n" +
                    "עבור מונית לחץ - 1\n\n" +
                    "(שלח 00 בכל עת לחזרה לתפריט הראשי)";
        }
    }

    /**
     * Handle location message when driver is starting shift
     *
     * Parses location coordinates and updates driver location in database
     */
    private String handleLocationForShiftStart(Conversation convo, IncomingMessage message, String locationText) {
        try {
            // Parse location: "LOCATION:latitude,longitude"
            String[] coords = locationText.substring(9).split(",");
            double latitude = Double.parseDouble(coords[0]);
            double longitude = Double.parseDouble(coords[1]);

            logger.info("Driver {} clocking in at lat: {}, lng: {}",
                    message.getPhone(), latitude, longitude);

            // Update driver location and clock in
            driverService.updateDriverLocation(message.getPhone(), latitude, longitude);
            driverService.clockIn(message.getPhone());

            // Reset state to START
            convoService.updateState(convo, ConversationState.START);

            return "✅ המיקום התקבל! התחלת משמרת בהצלחה. תקבל הזמנות מעכשיו.";
        } catch (Exception e) {
            logger.error("Failed to process location for driver {}: {}", message.getPhone(), e.getMessage());
            convoService.updateState(convo, ConversationState.START);
            return "❌ שגיאה בעיבוד המיקום. אנא נסה שוב.";
        }
    }
}