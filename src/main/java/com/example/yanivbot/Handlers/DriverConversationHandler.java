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

            // Handle button clicks
            if (txt.equals("driver_cancel_shift_start")) {
                convoService.updateState(convo, ConversationState.START);
                return "❌ ביטול התחלת משמרת.";
            }

            // Waiting for location
            return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
        }

        return null;
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

        if (isBusinessOwner(message.getPhone())) {
            return "✅ המשמרת נסגרה בהצלחה\nנשמח לראות אותך שוב בהמשך 🙌\nכדי לחזור לקבל נסיעות לחץ על \"התחל משמרת\" 🚖";
        } else {
            return "✅ המשמרת נסגרה בהצלחה\nנשמח לראות אותך שוב בהמשך 🙌\nכדי לחזור לקבל נסיעות לחץ על \"התחל משמרת\" 🚖";
        }
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