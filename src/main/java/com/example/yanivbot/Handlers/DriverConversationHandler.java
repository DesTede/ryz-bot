package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.*;
import com.example.yanivbot.Utils.PhoneNumberUtil;
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
    private final DeliveryOrderService deliveryOrderService;

    @Value("${admin.phones}")
    private String adminPhones;

    public DriverConversationHandler(DriverService driverService, ConversationService convoService, WhatsappService whatsappService, TaxiOrderService taxiOrderService, DeliveryOrderService deliveryOrderService) {
        this.driverService = driverService;
        this.convoService = convoService;
        this.whatsappService = whatsappService;
        this.taxiOrderService = taxiOrderService;
        this.deliveryOrderService = deliveryOrderService;
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

        // Handle delivery claim button (delivery_claim_123)                
        if (txt.startsWith("delivery_claim_")) {                           
            return handleDeliveryOrderClaim(message);                       
        }

        // Handle location sharing - can happen in any state while driver is active
        if (message.hasLocation()) {
            if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
                return handleLocationShare(convo, message);
            } else if (state == ConversationState.START) {
                // Driver updating location during shift
                return handleLocationUpdateDuringShift(convo, message);
            }
        }

        // Handle location in AWAITING_DRIVER_LOCATION state
        if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
            // Handle cancel button
            if (txt.equals("driver_cancel_shift_start")) {
                return handleCancelShiftStart(convo, message);
            }

            // Waiting for location
            return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
        }
//        // Handle location in AWAITING_DRIVER_LOCATION state
//        if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
//            // Check if message has location data
//            if (message.hasLocation()) {
//                return handleLocationShare(convo, message);
//            }
//
//            // Handle cancel button
//            if (txt.equals("driver_cancel_shift_start")) {
//                return handleCancelShiftStart(convo, message);
//            }
//
//            // Waiting for location
//            return "📍 אנא שתף את המיקום שלך כדי להתחיל משמרת.";
//        }

        // Driver typed something else - treat as customer
        // This happens after סיים משמרת or if driver is somehow in START state
        logger.info("Driver {} typed non-shift message: '{}' - treating as customer", message.getPhone(), txt);
        return ""; // Return empty string - don't pass to customer flow
    }

    private String handleTaxiOrderClaim(IncomingMessage message) {
        String txt = message.getText().trim();
        try {
            logger.info("Driver {} attempting to claim taxi order from message: {}", message.getPhone(), txt);
            long orderId = Long.parseLong(txt.replace("taxi_claim_", ""));
            logger.info("Extracted order ID: {}", orderId);
            String result = taxiOrderService.claimTaxiOrder(orderId, message.getPhone());
            logger.info("Claim result: {}", result);

            // תיקון: אם הסרביס החזיר null, זה אומר שההודעה כבר נשלחה ידנית בתוך הסרביס.
            // נחזיר מחרוזת ריקה כדי למנוע מהראוטר לשלוח הודעת כפל/שגיאה.
            if (result == null) {
                return "";
            }

            return result;
        } catch (NumberFormatException e) {
            logger.error("Error parsing order ID from message: {}", txt, e);
            return "❌ שגיאה בפורמט הזמנה. אנא נסה שוב.";
        } catch (Exception e) {
            logger.error("Error claiming taxi order for driver {} from message {}: {}",
                    message.getPhone(), txt, e.getMessage(), e);
            return "❌ שגיאה בקבלת הנסיעה. אנא נסה שוב.";
        }
    }

    private String handleTaxiOrderCompletion(IncomingMessage message) {
        String txt = message.getText().trim();
        try {
            long orderId = Long.parseLong(txt.replace("taxi_complete_", ""));
            String result = taxiOrderService.completeOrder(orderId, message.getPhone());

            // avoid dual messages even if service is null
            if (result == null) {
                return "";
            }

            return result;
        } catch (Exception e) {
            logger.error("Error completing taxi order: {}", e.getMessage(), e);
            return "❌ שגיאה בסיום הנסיעה. אנא נסה שוב.";
        }
    }

    private String handleDeliveryOrderClaim(IncomingMessage message) {
        String txt = message.getText().trim();
        try {
            logger.info("Driver {} attempting to claim delivery order from message: {}", message.getPhone(), txt);
            long orderId = Long.parseLong(txt.replace("delivery_claim_", ""));
            logger.info("Extracted delivery order ID: {}", orderId);
            String result = deliveryOrderService.claimDeliveryOrder(orderId, message.getPhone());
            logger.info("Claim result: {}", result);

            // If the service sent the message directly, return empty string to avoid duplicate
            if (result == null) {
                return "";
            }

            return result;
        } catch (NumberFormatException e) {
            logger.error("Error parsing delivery order ID from message: {}", txt, e);
            return "❌ שגיאה בפורמט הזמנה. אנא נסה שוב.";
        } catch (Exception e) {
            logger.error("Error claiming delivery order for driver {} from message {}: {}",
                    message.getPhone(), txt, e.getMessage(), e);
            return "❌ שגיאה בקבלת המשלוח. אנא נסה שוב.";
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

    private String handleLocationUpdateDuringShift(Conversation convo, IncomingMessage message) {
        try {
            double latitude = message.getLatitude();
            double longitude = message.getLongitude();

            logger.info("Driver {} updated location during shift: {}, {}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(message.getPhone()), latitude, longitude);

            driverService.updateDriverLocation(message.getPhone(), latitude, longitude);

            return "✅ המיקום שלך עודכן בהצלחה📍 ";
        } catch (Exception e) {
            logger.error("Error updating location during shift: {}", e.getMessage(), e);
            return "❌ שגיאה בעדכון המיקום. אנא נסה שוב.";
        }
    }
    
    private String handleLocationShare(Conversation convo, IncomingMessage message) {
        try {
            double latitude = message.getLatitude();
            double longitude = message.getLongitude();

            logger.info("Driver {} shared location: {}, {}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(message.getPhone()), latitude, longitude);

            driverService.clockIn(message.getPhone());
            driverService.updateDriverLocation(message.getPhone(), latitude, longitude);
            
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "DRIVER_ACTIVE");
            
            return "🟢 הכל מוכן!\n🟢 המשמרת התחילה\n📍 המיקום התקבל בהצלחה\nנסיעות חדשות בדרך אליך 🚖";
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
        convoService.saveTempData(convo, "END_SHIFT"); // Flag that shift was canceled

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