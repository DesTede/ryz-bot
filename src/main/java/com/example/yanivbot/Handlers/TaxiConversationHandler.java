package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.TaxiOrderService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles all taxi order conversation flows with interactive buttons and updated messages.
 */
@Component
public class TaxiConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(TaxiConversationHandler.class);

    private final TaxiOrderService taxiOrderService;
    private final ConversationService convoService;
    private final WhatsappService whatsappService;

    public TaxiConversationHandler(TaxiOrderService taxiOrderService, ConversationService convoService, WhatsappService whatsappService) {
        this.taxiOrderService = taxiOrderService;
        this.convoService = convoService;
        this.whatsappService = whatsappService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();

        // Check for driver claiming taxi order: "מונית {id}"
        if (txt.matches("^מונית\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return taxiOrderService.claimTaxiOrder(orderId, message.getPhone());
        }

        // Check for driver completing taxi order: "הסתיים {id}"
        if (txt.matches("^הסתיים\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return taxiOrderService.completeOrder(orderId, message.getPhone());
        }

        // Handle state-based flows
        switch (state) {
            case TAXI_CAR_TYPE:
                return handleTaxiCarType(convo, message);

            case TAXI_PICKUP:
                return handleTaxiPickup(convo, message);

            case TAXI_DESTINATION:
                return handleTaxiDestination(convo, message);

            case TAXI_NOTES:
                return handleTaxiNotes(convo, message);

            case AWAITING_TAXI_ORDER_CONFIRMATION:
                return handleTaxiConfirmation(convo, message);

            default:
                return null;
        }
    }

    private String handleTaxiCarType(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        CarType selectedCarType = null;

        if (txt.equals("taxi_car_type_motorcycle")) {
            selectedCarType = CarType.MOTORCYCLE;
        } else if (txt.equals("taxi_car_type_private_car")) {
            selectedCarType = CarType.PRIVATE_CAR;
        } else if (txt.equals("taxi_car_type_minivan")) {
            selectedCarType = CarType.MINIVAN;
        } else if (txt.equals("1")) {
            selectedCarType = CarType.MOTORCYCLE;
        } else if (txt.equals("2")) {
            selectedCarType = CarType.PRIVATE_CAR;
        } else if (txt.equals("3")) {
            selectedCarType = CarType.MINIVAN;
        } else {
            return "🚫 אופס… נראה שנבחרה אפשרות שלא קיימת\nבחרו אפשרות מהרשימה כדי להמשיך 🚀";
        }

        convoService.saveTempData(convo, selectedCarType.name());
        convoService.updateState(convo, ConversationState.TAXI_PICKUP);

        return "🚗 מעולה!\nעכשיו שלחו את נקודת האיסוף שלכם 📍";
    }

    private String handleTaxiPickup(Conversation convo, IncomingMessage message) {
        String pickupLocation = message.getText().trim();

        String carType = convo.getTempData();
        String orderData = carType + "|" + pickupLocation;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.TAXI_DESTINATION);

        return "📍 נקודת האיסוף נקלטה בהצלחה ✅\nעכשיו שלחו יעד נסיעה 👇";
    }

    private String handleTaxiDestination(Conversation convo, IncomingMessage message) {
        String destination = message.getText().trim();

        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];

        orderData = carType + "|" + pickupLocation + "|" + destination;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.TAXI_NOTES);

        return "💬 רוצים להוסיף משהו לנהג?\nכתבו את ההערה כאן 👇\nאם אין הערות, רשמו: לא";
    }

    private String handleTaxiNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();

        if (notes.equals("לא")) {
            notes = "";
        }

        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];

        convoService.saveTempData(convo, orderData + "|" + notes);
        convoService.updateState(convo, ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION);

        showConfirmationButtons(message.getPhone(), carType, pickupLocation, destination, notes);
        return null;
    }

    private String handleTaxiConfirmation(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        if (txt.equals("order_confirm_no") || txt.equals("לא")) {
            convoService.updateState(convo, ConversationState.START);
            whatsappService.sendSafeText(message.getPhone(), "❗ שימו לב\nביטול ההזמנה ימחק את כל פרטי הנסיעה.\nלהמשך ביטול הקלידו: כן\nכדי לחזור להזמנה הקלידו: לא\n❌ ההזמנה בוטלה בהצלחה.\nנשמח לעמוד לשירותכם שוב ב־Movez💙\nלהתחלת הזמנה חדשה שלחו הודעה 🚀");
            return null;
        }

        if (!txt.equals("order_confirm_yes") && !txt.equals("כן")) {
            return "אנא בחר: אשר (כן) או בטל (לא)";
        }

        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];
        String notes = parts.length > 3 ? parts[3] : "";

        try {
            taxiOrderService.createTaxiOrder(message.getPhone(), pickupLocation, destination, notes, CarType.valueOf(carType));
            convoService.updateState(convo, ConversationState.START);
            return "✅ ההזמנה אושרה! מחפשים נהג קרוב אליך";
        } catch (Exception e) {
            logger.error("Failed to create taxi order for {}: {}", message.getPhone(), e.getMessage());
            convoService.updateState(convo, ConversationState.START);
            return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
        }
    }

    private void showCarTypeButtons(String phone) {
        String bodyText = "מעולה 👍\nעכשיו בחרו את סוג הרכב:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "🏍️ אופנוע"),
                new WhatsappService.InteractiveButton("taxi_car_type_private_car", "🚗 מכונית פרטית"),
                new WhatsappService.InteractiveButton("taxi_car_type_minivan", "🚐 מיניוואן")
        );
    }

    private void showConfirmationButtons(String phone, String carType, String pickupLocation, String destination, String notes) {
        String bodyText = "🚀 הנה סיכום הנסיעה שלכם:\n" +
                "🚘 רכב: " + CarType.valueOf(carType).getHebrewName() + "\n" +
                "📍 איסוף: " + pickupLocation + "\n" +
                "🎯 יעד: " + destination + "\n" +
                "📝 הערות: " + (notes.isEmpty() ? "אין" : notes) + "\n\n" +
                "אם הכול נראה טוב — בחרו ✅";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("order_confirm_yes", "✅ כן - אשר"),
                new WhatsappService.InteractiveButton("order_confirm_no", "❌ לא - בטל")
        );
    }
}