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

        if (txt.equals("התחל משמרת") || txt.equals("driver_start_shift")) {
            return handleStartShift(convo, message);
        }

        if (txt.equals("סיים משמרת") || txt.equals("driver_end_shift")) {
            return handleEndShift(convo, message);
        }

        if (txt.startsWith("LOCATION:")) {
            return handleLocationShare(convo, message);
        }

        if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
            return handleLocationAwait(convo, message);
        }

        return null;
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

            return "🟢 הכול מוכן!\n🟢 המשמרת התחילה\n📍 המיקום התקבל בהצלחה\nנסיעות חדשות בדרך אליך 🚖";
        } catch (Exception e) {
            logger.error("Error processing location: {}", e.getMessage());
            return "❌ שגיאה בעיבוד המיקום. אנא נסה שוב.";
        }
    }

    private String handleLocationAwait(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        if (txt.equals("driver_share_location_start")) {
            return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
        }

        if (txt.equals("driver_cancel_shift_start")) {
            convoService.updateState(convo, ConversationState.START);
            return "❌ ביטול התחלת משמרת.";
        }

        if (txt.startsWith("LOCATION:")) {
            return handleLocationShare(convo, message);
        }

        return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
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
        String bodyText = "📍 כדי להתחיל משמרת עליך לשלוח מיקום נוכחי.\nלאחר מכן תוכל להתחיל לקבל הזמנות חדשות 🚀";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("driver_share_location_start", "📍 שתף מיקום והתחל"),
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