package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DriverService;
import com.example.yanivbot.Services.TaxiOrderService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DriverConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(DriverConversationHandler.class);

    private final DriverService driverService;
    private final ConversationService convoService;
    private final WhatsappService whatsappService;
    private final TaxiOrderService taxiOrderService;

    @Value("${admin.phones}")
    private String adminPhones;

    public DriverConversationHandler(DriverService driverService, ConversationService convoService, WhatsappService whatsappService, TaxiOrderService taxiOrderService) {
        this.driverService = driverService;
        this.convoService = convoService;
        this.whatsappService = whatsappService;
        this.taxiOrderService = taxiOrderService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();

        if (txt.equals("התחל משמרת") || txt.equals("driver_start_shift")) {
            return handleStartShift(convo, message);
        }

        if (txt.equals("סיים משמרת") || txt.equals("driver_end_shift")) {
            return handleEndShift(convo, message);
        }

        // Handle taxi order claim button (taxi_claim_123)
        if (txt.startsWith("taxi_claim_")) {
            return handleTaxiOrderClaim(message);
        }

        // Handle taxi order completion button (taxi_complete_123)
        if (txt.startsWith("taxi_complete_")) {
            return handleTaxiOrderCompletion(message);
        }

        // Handle location in AWAITING_DRIVER_LOCATION state
        if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
            // Check if message has location data
            if (message.hasLocation()) {
                return handleLocationShare(convo, message);
            }

            // Handle cancel button
            if (txt.equals("driver_cancel_shift_start")) {
                return handleCancelShiftStart(convo, message);
            }

            // Waiting for location
            return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
        }

        // Driver typed something else - treat as customer
        // This happens after סיים משמרת or if driver is somehow in START state
        logger.info("Driver {} typed non-shift message: '{}' - treating as customer", message.getPhone(), txt);
        return null; // Return null to let MessageRouter treat them as customer
    }

    private String handleTaxiOrderClaim(IncomingMessage message) {
        String txt = message.getText().trim();
        try {
            long orderId = Long.parseLong(txt.replace("taxi_claim_", ""));
            return taxiOrderService.claimTaxiOrder(orderId, message.getPhone());
        } catch (Exception e) {
            logger.error("Error claiming taxi order: {}", e.getMessage(), e);
            return "❌ שגיאה בקבלת הנסיעה. אנא נסה שוב.";
        }
    }

    private String handleTaxiOrderCompletion(IncomingMessage message) {
        String txt = message.getText().trim();
        try {
            long orderId = Long.parseLong(txt.replace("taxi_complete_", ""));
            return taxiOrderService.completeOrder(orderId, message.getPhone());
        } catch (Exception e) {
            logger.error("Error completing taxi order: {}", e.getMessage(), e);
            return "❌ שגיאה בסיום הנסיעה. אנא נסה שוב.";
        }
    }

    private String handleStartShift(Conversation convo, IncomingMessage message) {
        if (!isDriver(message.getPhone())) {
            return "❌ הטלפון שלך לא רשום במערכת כנהג. צור קשר עם תמיכה.";
        }

        convoService.updateState(convo, ConversationState.AWAITING_DRIVER_LOCATION);
        showShiftStartConfirmation(message.getPhone());
        return null;
    }

    private String handleLocationShare(Conversation convo, IncomingMessage message) {
        try {
            double latitude = message.getLatitude();
            double longitude = message.getLongitude();

            logger.info("Driver {} shared location: {}, {}", message.getPhone(), latitude, longitude);

            driverService.updateDriverLocation(message.getPhone(), latitude, longitude);
            driverService.clockIn(message.getPhone());

            convoService.updateState(convo, ConversationState.START);

            return "🟢 הכול מוכן!\n🟢 המשמרת התחילה\n📍 המיקום התקבל בהצלחה\nנסיעות חדשות בדרך אליך 🚖";
        } catch (Exception e) {
            logger.error("Error processing location: {}", e.getMessage(), e);
            return "❌ שגיאה בעיבוד המיקום. אנא נסה שוב.";
        }
    }

    private String handleEndShift(Conversation convo, IncomingMessage message) {
        driverService.clockOut(message.getPhone());
        convoService.updateState(convo, ConversationState.START);
        convoService.saveTempData(convo, "END_SHIFT"); // Flag that driver ended shift

        String bodyText = "✅ המשמרת נסגרה בהצלחה\nנשמח לראות אותך שוב בהמשך 🙌";

        whatsappService.sendInteractiveButtonsSafe(
                message.getPhone(),
                bodyText,
                new WhatsappService.InteractiveButton("driver_start_shift", "🟢 התחל משמרת")
        );

        return null; // Message already sent via button
    }

    private String handleCancelShiftStart(Conversation convo, IncomingMessage message) {
        // Same as end shift - flag as ended
        convoService.updateState(convo, ConversationState.START);
        convoService.saveTempData(convo, "END_SHIFT"); // Flag that shift was cancelled

        String bodyText = "✅ ביטול התחלת משמרת\nנשמח לראות אותך שוב בהמשך 🙌";

        whatsappService.sendInteractiveButtonsSafe(
                message.getPhone(),
                bodyText,
                new WhatsappService.InteractiveButton("driver_start_shift", "🟢 התחל משמרת")
        );

        return null; // Message already sent via button
    }

    private void showShiftStartConfirmation(String phone) {
        String bodyText = "📍 כדי להתחיל משמרת עליך לשלוח מיקום נוכחי.\nלאחר מכן תוכל להתחיל לקבל הזמנות חדשות 🚀\n\nלשליחת מיקום, לחץ על + בתפריט ובחר 📍 מיקום";

        whatsappService.sendInteractiveButtonsSafe(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("driver_cancel_shift_start", "❌ ביטול")
        );
    }

    public boolean isDriver(String phone) {
        return driverService.findByPhone(phone) != null;
    }

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