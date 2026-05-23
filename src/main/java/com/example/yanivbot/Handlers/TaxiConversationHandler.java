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
 * Handles all taxi order conversation flows with interactive buttons.
 *
 * Updated to use interactive buttons for:
 * - Car type selection
 * - Order confirmation (Yes/No)
 *
 * Manages states:
 * - TAXI_SERVICE: Customer selecting service
 * - TAXI_CAR_TYPE: Customer selecting car type (WITH BUTTONS)
 * - TAXI_PICKUP: Customer entering pickup location
 * - TAXI_DESTINATION: Customer entering destination
 * - TAXI_NOTES: Customer entering optional notes
 * - AWAITING_TAXI_ORDER_CONFIRMATION: Waiting for customer confirmation (WITH BUTTONS)
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
            case TAXI_SERVICE:
                return handleTaxiService(convo, message);

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
                // This handler doesn't handle other states
                return null;
        }
    }

    /**
     * TAXI_SERVICE state: Customer choosing to order taxi
     *
     * Button click: "start_service_taxi" or "business_taxi"
     */
    private String handleTaxiService(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        // Check if it's a button click for taxi service
        if (txt.equals("start_service_taxi") || txt.equals("business_taxi") || txt.equals("1")) {
            convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);
            showCarTypeButtons(message.getPhone());
            return null; // Already sent via WhatsApp
        }

        return "בחר שירות:\nעבור מונית לחץ - 1";
    }

    /**
     * TAXI_CAR_TYPE state: Customer selecting car type with INTERACTIVE BUTTONS
     *
     * Button options: "taxi_car_type_motorcycle", "taxi_car_type_private_car", "taxi_car_type_minivan"
     */
    private String handleTaxiCarType(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        CarType selectedCarType = null;

        // Handle button clicks
        if (txt.equals("taxi_car_type_motorcycle")) {
            selectedCarType = CarType.MOTORCYCLE;
        } else if (txt.equals("taxi_car_type_private_car")) {
            selectedCarType = CarType.PRIVATE_CAR;
        } else if (txt.equals("taxi_car_type_minivan")) {
            selectedCarType = CarType.MINIVAN;
        }
        // Fallback: support old numeric input "1", "2", "3"
        else if (txt.equals("1")) {
            selectedCarType = CarType.MOTORCYCLE;
        } else if (txt.equals("2")) {
            selectedCarType = CarType.PRIVATE_CAR;
        } else if (txt.equals("3")) {
            selectedCarType = CarType.MINIVAN;
        } else {
            return "בחירה לא חוקית. בחר אופנוע, מכונית פרטית, או מיניוואן";
        }

        // Save car type to temp data
        convoService.saveTempData(convo, selectedCarType.name());
        convoService.updateState(convo, ConversationState.TAXI_PICKUP);

        return "מאיפה לאסוף אותך? (לא לשכוח עיר) 📍";
    }

    /**
     * TAXI_PICKUP state: Customer entering pickup location
     */
    private String handleTaxiPickup(Conversation convo, IncomingMessage message) {
        String pickupLocation = message.getText().trim();

        // Get car type from temp data and append pickup location
        String carType = convo.getTempData();
        String orderData = carType + "|" + pickupLocation;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.TAXI_DESTINATION);

        return "לאן תרצה ללכת? 🎯";
    }

    /**
     * TAXI_DESTINATION state: Customer entering destination
     */
    private String handleTaxiDestination(Conversation convo, IncomingMessage message) {
        String destination = message.getText().trim();

        // Get the order data (carType|pickup) from temp data
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];

        // Save both in temp data as "carType|pickup|destination"
        orderData = carType + "|" + pickupLocation + "|" + destination;
        convoService.saveTempData(convo, orderData);
        convoService.updateState(convo, ConversationState.TAXI_NOTES);

        return "הערות נוספות? (או הקלד 'לא')";
    }

    /**
     * TAXI_NOTES state: Customer optionally entering notes
     */
    private String handleTaxiNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();

        if (notes.equals("לא")) {
            notes = "";
        }

        // Get the order data (carType|pickup|destination)
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];

        // Move to confirmation state
        convoService.saveTempData(convo, orderData + "|" + notes);
        convoService.updateState(convo, ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION);

        // Show confirmation with INTERACTIVE BUTTONS
        showConfirmationButtons(message.getPhone(), carType, pickupLocation, destination, notes);
        return null; // Already sent via WhatsApp
    }

    /**
     * AWAITING_TAXI_ORDER_CONFIRMATION state: Waiting for customer to confirm order with BUTTONS
     *
     * Button options: "order_confirm_yes", "order_confirm_no"
     */
    private String handleTaxiConfirmation(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        // Handle button clicks
        if (txt.equals("order_confirm_no") || txt.equals("לא")) {
            // User cancelled the order
            convoService.updateState(convo, ConversationState.START);
            whatsappService.sendSafeText(message.getPhone(), "❌ ההזמנה בוטלה.");
            return null; // Menu will be shown by router
        }

        if (!txt.equals("order_confirm_yes") && !txt.equals("כן")) {
            return "אנא בחר: אשר (כן) או בטל (לא)";
        }

        // User confirmed - create the taxi order
        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|");
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];
        String notes = parts.length > 3 ? parts[3] : "";

        try {
            taxiOrderService.createTaxiOrder(message.getPhone(), pickupLocation, destination, notes, CarType.valueOf(carType));

            // Reset conversation state
            convoService.updateState(convo, ConversationState.START);

            return "✅ ההזמנה נוצרה בהצלחה! נחפש לך נהג קרוב...";
        } catch (Exception e) {
            logger.error("Failed to create taxi order for {}: {}", message.getPhone(), e.getMessage());
            convoService.updateState(convo, ConversationState.START);
            return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
        }
    }

    /**
     * Send car type selection with INTERACTIVE BUTTONS
     */
    private void showCarTypeButtons(String phone) {
        String bodyText = "בחר סוג כלי רכב:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "🏍️ אופנוע"),
                new WhatsappService.InteractiveButton("taxi_car_type_private_car", "🚗 מכונית פרטית"),
                new WhatsappService.InteractiveButton("taxi_car_type_minivan", "🚐 מיניוואן")
        );
    }

    /**
     * Send order confirmation with INTERACTIVE BUTTONS
     */
    private void showConfirmationButtons(String phone, String carType, String pickupLocation, String destination, String notes) {
        String bodyText = "אנא אשר את הפרטים:\n" +
                "🚗 כלי רכב: " + CarType.valueOf(carType).getHebrewName() + "\n" +
                "📍 מאיפה: " + pickupLocation + "\n" +
                "🎯 לאן: " + destination + "\n" +
                "📝 הערות: " + (notes.isEmpty() ? "אין" : notes);

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("order_confirm_yes", "✅ כן - אשר"),
                new WhatsappService.InteractiveButton("order_confirm_no", "❌ לא - בטל")
        );
    }
}