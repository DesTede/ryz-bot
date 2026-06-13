package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.*;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles all taxi order conversation flows with interactive buttons and updated messages.
 */
@Component
public class TaxiConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(TaxiConversationHandler.class);

    private final TaxiOrderService taxiOrderService;
    private final ConversationService convoService;
    private final WhatsappService whatsappService;
    private final GooglePlacesService placesService;
    private final GeoCodingService geoCodingService;
    private final BotConfigService botConfigService;

    public TaxiConversationHandler(TaxiOrderService taxiOrderService, ConversationService convoService, WhatsappService whatsappService, GooglePlacesService googlePlacesService, GeoCodingService geoCodingService, BotConfigService botConfigService) {
        this.taxiOrderService = taxiOrderService;
        this.convoService = convoService;
        this.whatsappService = whatsappService;
        this.placesService = googlePlacesService;
        this.geoCodingService = geoCodingService;
        this.botConfigService = botConfigService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();

        logger.info("TaxiConversationHandler | State: {} | Message: '{}'", state, txt);

        // Handle state-based flows
        switch (state) {
            case TAXI_CAR_TYPE:
                return handleTaxiCarType(convo, message);

            case TAXI_PICKUP:
                return handleTaxiPickup(convo, message);

            case AWAITING_PICKUP_SELECTION:
                return handlePickupSelection(convo, message);

            case TAXI_DESTINATION:
                return handleTaxiDestination(convo, message);

            case AWAITING_DESTINATION_SELECTION:
                return handleDestinationSelection(convo, message);

            case TAXI_NOTES:
                return handleTaxiNotes(convo, message);

            case AWAITING_TAXI_ORDER_CONFIRMATION:
                return handleTaxiConfirmation(convo, message);

            default:
                logger.debug("No handler for state: {}", state);
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

        return """
                🚗 מעולה!
                 שלחו את כתובת האיסוף שלכם📍
                (לא לשכוח עיר)\s""";
    }

    private String handleTaxiPickup(Conversation convo, IncomingMessage message) {
        String input = message.getText().trim();
        String carType = convo.getTempData().split("\\|", -1)[0];

        List<GooglePlacesService.PlaceSuggestion> suggestions = placesService.getSuggestions(input);

        if (suggestions.isEmpty()) {
                return "🔍 לא נמצאה כתובת תואמת, נסו לכתוב בצורה אחרת (לא לשכוח עיר)";
            }

        List<WhatsappService.InteractiveButton> buttons = new ArrayList<>();
        StringBuilder tempData = new StringBuilder(carType + "|PICKUP_PENDING");
        for (int i = 0; i < Math.min(2, suggestions.size()); i++) {
            String shortDesc = suggestions.get(i).description.length() > 20
                    ? suggestions.get(i).description.substring(0, 20)
                    : suggestions.get(i).description;
            buttons.add(new WhatsappService.InteractiveButton("pickup_" + i, shortDesc));
            tempData.append("|").append(suggestions.get(i).description);
        }
        buttons.add(new WhatsappService.InteractiveButton("pickup_manual", "✏️ הזן ידנית"));

        convoService.saveTempData(convo, tempData.toString());
        convoService.updateState(convo, ConversationState.AWAITING_PICKUP_SELECTION);

        whatsappService.sendInteractiveButtonsSafe(message.getPhone(), "📍 בחר כתובת איסוף:", buttons.toArray(new WhatsappService.InteractiveButton[0]));
        return null;
    }

    private String handlePickupSelection(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String carType = parts[0];

        if (txt.equals("pickup_manual")) {
            convoService.updateState(convo, ConversationState.TAXI_PICKUP);
            return "📍 הזן כתובת איסוף ידנית:";
        }

        if (!txt.startsWith("pickup_")) {
            return "📍 אנא בחר כתובת מהרשימה, או לחץ על ✏️ הזן ידנית";
        }

        logger.info("TempData: {}", convo.getTempData());
        int index = Integer.parseInt(txt.replace("pickup_", ""));
        String pickupLocation = parts[2 + index];

        convoService.saveTempData(convo, carType + "|" + pickupLocation);
        convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
        return "📍 נקודת האיסוף נקלטה ✅\nשלחו יעד נסיעה 👇\n(לא לשכוח עיר)";
    }

    private String handleTaxiDestination(Conversation convo, IncomingMessage message) {
        String input = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];

        List<GooglePlacesService.PlaceSuggestion> suggestions = placesService.getSuggestions(input);

        if (suggestions.isEmpty()) {
            return "🔍 לא נמצאה כתובת תואמת, נסו לכתוב בצורה אחרת (לא לשכוח עיר)";
        }

            List<WhatsappService.InteractiveButton> buttons = new ArrayList<>();
        StringBuilder tempData = new StringBuilder(carType + "|" + pickupLocation + "|DEST_PENDING");
        for (int i = 0; i < Math.min(2, suggestions.size()); i++) {
            String shortDesc = suggestions.get(i).description.length() > 20
                    ? suggestions.get(i).description.substring(0, 20)
                    : suggestions.get(i).description;
            buttons.add(new WhatsappService.InteractiveButton("dest_" + i, shortDesc));
            tempData.append("|").append(suggestions.get(i).description);
        }
        buttons.add(new WhatsappService.InteractiveButton("dest_manual", "✏️ הזן ידנית"));

        convoService.saveTempData(convo, tempData.toString());
        convoService.updateState(convo, ConversationState.AWAITING_DESTINATION_SELECTION);

        whatsappService.sendInteractiveButtonsSafe(message.getPhone(), "🎯 בחר יעד נסיעה:", buttons.toArray(new WhatsappService.InteractiveButton[0]));
        return null;
    }

    private String handleDestinationSelection(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];

        if (txt.equals("dest_manual")) {
            convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
            return "🎯 הזן יעד נסיעה ידנית:";
        }

        if (!txt.startsWith("dest_")) {
            return "🎯 אנא בחר יעד מהרשימה, או לחץ על ✏️ הזן ידנית";
        }

        logger.info("TempData: {}", convo.getTempData());
        int index = Integer.parseInt(txt.replace("dest_", ""));
        String destination = parts[3 + index];

        convoService.saveTempData(convo, carType + "|" + pickupLocation + "|" + destination);
        convoService.updateState(convo, ConversationState.TAXI_NOTES);
        return "💬 רוצים להוסיף משהו לנהג?\nכתבו את ההערה כאן 👇\nאם אין הערות, השיבו 'אין'";
    }

    private String handleTaxiNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();

        if (notes.equals("אין")) {
            notes = "";
        }

        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];

        // הדפסת לוג כדי לוודא שהכתובות לא התהפכו או נשמרו בצורה שגויה
        logger.info("Taxi Fare Calculation | Pickup: '{}' | Destination: '{}' | CarType: '{}'", pickupLocation, destination, carType);

        Double estimatedFare = null;
        try {
            // שליחת הכתובות המקוריות ישירות ל-Service
            Double distanceKm = geoCodingService.getDistanceKm(pickupLocation, destination);

            // שליחת הגדרות המחיר
            double basePrice = botConfigService.getTaxiBasePrice();
            if (basePrice <= 0) basePrice = 15.0; // DEFAULT_TAXI_BASE_PRICE

            double pricePerKm = botConfigService.getTaxiPricePerKm();
            if (pricePerKm <= 0) pricePerKm = 4.5; // DEFAULT_TAXI_PRICE_PER_KM

            double carTypeModifier = 1.0;
            switch (carType) {
                case "PRIVATE_CAR":
                    carTypeModifier = 1.0;
                    break;
                case "MOTORCYCLE":
                    carTypeModifier = 0.8;
                    break;
                case "MINIVAN":
                    carTypeModifier = 1.4;
                    break;
                default:
                    logger.warn("Unknown car type '{}', defaulting modifier to 1.0", carType);
                    carTypeModifier = 1.0;
                    break;
            }

//             בדיקה: אם המרחק תקין וגדול מ-0, נחשב לפי הנוסחה המלאה
            if (distanceKm != null && distanceKm > 0) {
                estimatedFare = (basePrice + (distanceKm * pricePerKm)) * carTypeModifier * 1.18;
                logger.info("Fare calculated successfully: ₪{} for {} km (Vehicle Type: {})",
                        String.format("%.2f", estimatedFare), String.format("%.1f", distanceKm), carType);
            } else {
                // אם גוגל החזיר null או 0, נבצע חישוב לפי מרחק מינימלי מוגדר כברירת מחדל (למשל 3 ק"מ) כדי שלא יציג תמיד 15
                logger.warn("Distance Matrix returned 0 or null (Distance: {}). Using fallback calculation.", distanceKm);
                double fallbackDistance = 4.0; // מרחק ברירת מחדל קצר לפקקים/נסיעה עירונית ממוצעת
                estimatedFare = (basePrice + (fallbackDistance * pricePerKm)) * carTypeModifier * 1.18;
            }
        } catch (Exception e) {
            logger.error("Fare calculation failed: {}", e.getMessage(), e);
            estimatedFare = 15.0; // מחיר גיבוי מוחלט למניעת קריסה
        }

        // שמירת הנתונים והמשך זרימת השיחה הרגילה שלך
        String fareStr = String.format("%.2f", estimatedFare);
        convoService.saveTempData(convo, orderData + "|" + notes + "|" + fareStr);
        convoService.updateState(convo, ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION);

        try {
            logger.info("Showing confirmation buttons for customer {}", PhoneNumberUtil.maskPhoneNumber(message.getPhone()));
            showConfirmationButtons(message.getPhone(), carType, pickupLocation, destination, notes, estimatedFare);
            logger.info("Confirmation buttons sent successfully");
            return null;
        } catch (Exception e) {
            logger.error("Error sending confirmation buttons: {}", e.getMessage(), e);
            return buildConfirmationText(carType, pickupLocation, destination, notes, estimatedFare);
        }
    }

    private String handleTaxiConfirmation(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        logger.info("TaxiConfirmation | Message: '{}'", txt);

        if (txt.equals("order_confirm_no")) {
            convoService.updateState(convo, ConversationState.START);
            whatsappService.sendSafeText(message.getPhone(), "\n❌ ההזמנה בוטלה בהצלחה.\nנשמח לעמוד לשירותכם שוב ב־Movez💙\nלהתחלת הזמנה חדשה שלחו הודעה 🚀");
            convoService.saveTempData(convo,"");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return null;
        }

        if (!txt.equals("order_confirm_yes") && !txt.equals("כן")) {
            logger.warn("Invalid confirmation response: '{}'", txt);
            return "אנא בחר: אשר (כן) או בטל (בטל)";
        }

        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];
        String destination = parts[2];
        String notes = parts.length > 3 ? parts[3] : "";
        Double estimatedFare = (parts.length > 4 && !parts[4].isEmpty()) ? Double.parseDouble(parts[4]) : null;
        
        try {
            logger.info("Creating taxi order for customer {}", message.getPhone());
            taxiOrderService.createTaxiOrder(message.getPhone(), pickupLocation, destination, notes, CarType.valueOf(carType), estimatedFare);
            convoService.updateState(convo, ConversationState.START);
            logger.info("Taxi order created successfully");
            convoService.saveTempData(convo,"");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return "✅ ההזמנה אושרה! מחפשים נהג קרוב אליך";
        } catch (Exception e) {
            logger.error("Failed to create taxi order for {}: {}", message.getPhone(), e.getMessage(), e);
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo,"");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
        }
    }

    /**
     * Send confirmation buttons with order summary
     */
    private void showConfirmationButtons(String phone, String carType, String pickupLocation, String destination, String notes, Double estimatedFare) {
        String bodyText = buildConfirmationText(carType, pickupLocation, destination, notes, estimatedFare);

        logger.info("Sending confirmation buttons to {}", phone);

        // Use the SAFE method that catches exceptions internally
        whatsappService.sendInteractiveButtonsSafe(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("order_confirm_yes", "✅ כן - אשר"),
                new WhatsappService.InteractiveButton("order_confirm_no", "❌ לא - בטל")
        );
    }

    /**
     * Build confirmation summary text
     */
    private String buildConfirmationText(String carType, String pickupLocation, String destination, String notes, Double estimatedFare) {
        String fareText = (estimatedFare != null)
                ? String.format("₪%.0f", estimatedFare)
                : "מחיר לא זמין כרגע";
        return "🚀 הנה סיכום הנסיעה שלכם:\n" +
                "🚘 רכב: " + CarType.valueOf(carType).getHebrewName() + "\n" +
                "📍 איסוף: " + pickupLocation + "\n" +
                "🎯 יעד: " + destination + "\n" +
                "📝 הערות: " + (notes.isEmpty() ? "אין" : notes) + "\n" +
                "💰 מחיר משוער: " + fareText + "\n\n" +
                "אם הכל נראה טוב — בחרו כן ✅";
    }
}