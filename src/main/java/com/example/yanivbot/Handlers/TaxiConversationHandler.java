package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Entities.IncomingMessage;
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
        return switch (state) {
            case TAXI_CAR_TYPE -> handleTaxiCarType(convo, message);
            case TAXI_PICKUP -> handleTaxiPickup(convo, message);
            case AWAITING_PICKUP_SELECTION -> handlePickupSelection(convo, message);
            case TAXI_DESTINATION -> handleTaxiDestination(convo, message);
            case AWAITING_DESTINATION_SELECTION -> handleDestinationSelection(convo, message);
            case TAXI_NOTES -> handleTaxiNotes(convo, message);
            case AWAITING_TAXI_ORDER_CONFIRMATION -> handleTaxiConfirmation(convo, message);
            default -> {
                logger.debug("No handler for state: {}", state);
                yield null;
            }
        };
    }

    private String handleTaxiCarType(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        CarType selectedCarType;

        switch (txt) {
            case "taxi_car_type_motorcycle", "1" -> selectedCarType = CarType.MOTORCYCLE;
            case "taxi_car_type_private_car", "2" -> selectedCarType = CarType.PRIVATE_CAR;
            case "taxi_car_type_minivan", "3" -> selectedCarType = CarType.MINIVAN;
            default -> {
                return "🚫 אופס… נראה שנבחרה אפשרות שלא קיימת\nבחרו אפשרות מהרשימה כדי להמשיך 🚀";
            }
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

        List<WhatsappService.InteractiveButton> items = new ArrayList<>();
        StringBuilder tempData = new StringBuilder(carType + "|PICKUP_PENDING");
        for (int i = 0; i < Math.min(9, suggestions.size()); i++) {
            String fullAddress = suggestions.get(i).description;

            // Try splitting by comma to extract only the street for the title line
            String titleText = fullAddress.contains(",") ? fullAddress.split(",")[0].trim() : fullAddress;

            // If the street name itself is still > 24 characters, cleanly substring it
            if (titleText.length() > 24) {
                titleText = titleText.substring(0, 21) + "...";
            }
            // Provide row: id, display title (max 24), city subtitle description (max 72)
            String descriptionText = fullAddress.contains(",")
                    ? fullAddress.substring(fullAddress.indexOf(',') + 1).trim()
                    : null;
            items.add(new WhatsappService.InteractiveButton("pickup_" + i, titleText, descriptionText));
            tempData.append("|").append(fullAddress).append("|").append(suggestions.get(i).placeId);
        }
        items.add(new WhatsappService.InteractiveButton("pickup_manual", "✏️ הזן ידנית"));

        convoService.saveTempData(convo, tempData.toString());
        convoService.updateState(convo, ConversationState.AWAITING_PICKUP_SELECTION);

        whatsappService.sendInteractiveList(message.getPhone(), "📍 בחר כתובת איסוף:", "בחר כתובת", "תוצאות חיפוש", items);
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
        String pickupLocation;
        String pickupPlaceId;
        try {
            int index = Integer.parseInt(txt.replace("pickup_", ""));
            pickupLocation = parts[2 + (index * 2)];
            pickupPlaceId = parts[3 + (index * 2)];
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            logger.warn("Invalid pickup selection '{}' ({} tempData parts)", txt, parts.length);
            return "📍 אנא בחר כתובת מהרשימה, או לחץ על ✏️ הזן ידנית";
        }
        
        convoService.saveTempData(convo, carType + "|" + pickupLocation + "|" + pickupPlaceId);
        convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
        return "📍 נקודת האיסוף נקלטה ✅\nשלחו יעד נסיעה 👇\n(לא לשכוח עיר)";
    }

    private String handleTaxiDestination(Conversation convo, IncomingMessage message) {
        String input = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];
        String pickupPlaceId = parts[2];

        List<GooglePlacesService.PlaceSuggestion> suggestions = placesService.getSuggestions(input);

        if (suggestions.isEmpty()) {
            return "🔍 לא נמצאה כתובת תואמת, נסו לכתוב בצורה אחרת (לא לשכוח עיר)";
        }

        List<WhatsappService.InteractiveButton> items = new ArrayList<>();
        StringBuilder tempData = new StringBuilder(carType + "|" + pickupLocation + "|" + pickupPlaceId + "|DEST_PENDING");
        for (int i = 0; i < Math.min(9, suggestions.size()); i++) {
            String fullAddress = suggestions.get(i).description;

            // Try splitting by comma to extract only the street for the title line
            String titleText = fullAddress.contains(",") ? fullAddress.split(",")[0].trim() : fullAddress;

            // If the street name itself is still > 24 characters, cleanly substring it
            if (titleText.length() > 24) {
                titleText = titleText.substring(0, 21) + "...";
            }
            // Provide row: id, display title (max 24), city subtitle description (max 72)
            String descriptionText = fullAddress.contains(",")
                    ? fullAddress.substring(fullAddress.indexOf(',') + 1).trim()
                    : null;
            items.add(new WhatsappService.InteractiveButton("dest_" + i, titleText, descriptionText));
            tempData.append("|").append(fullAddress)
                    .append("|").append(suggestions.get(i).placeId);
        }
        items.add(new WhatsappService.InteractiveButton("dest_manual", "✏️ הזן ידנית"));

        convoService.saveTempData(convo, tempData.toString());
        convoService.updateState(convo, ConversationState.AWAITING_DESTINATION_SELECTION);

        whatsappService.sendInteractiveList(message.getPhone(), "🎯 בחר יעד נסיעה:", "בחר יעד", "תוצאות חיפוש", items);
        return null;
    }

    private String handleDestinationSelection(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];
        String pickupPlaceId = parts[2];

        if (txt.equals("dest_manual")) {
            convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
            return "🎯 הזן יעד נסיעה ידנית:";
        }

        if (!txt.startsWith("dest_")) {
            return "🎯 אנא בחר יעד מהרשימה, או לחץ על ✏️ הזן ידנית";
        }

        String destination;
        String destinationPlaceId;
        try {
            int index = Integer.parseInt(txt.replace("dest_", ""));
            destination = parts[4 + (index * 2)];
            destinationPlaceId = parts[5 + (index * 2)];
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            logger.warn("Invalid destination selection '{}' ({} tempData parts)", txt, parts.length);
            return "🎯 אנא בחר יעד מהרשימה, או לחץ על ✏️ הזן ידנית";
        }
        
        convoService.saveTempData(convo, carType + "|" + pickupLocation + "|" + pickupPlaceId + "|" + destination + "|" + destinationPlaceId);
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
        String pickupPlaceId = parts[2];
        String destination = parts[3];
        String destinationPlaceId = parts[4];

        
        logger.info("Taxi Fare Calculation | Pickup: '{}' | Destination: '{}' | CarType: '{}'", pickupLocation, destination, carType);

        Double estimatedFare = null;
        try {


            GeoCodingService.TripInfo tripInfo = geoCodingService.getTripInfo(pickupPlaceId, destinationPlaceId);

            double basePrice = botConfigService.getTaxiBasePrice();
            double pricePerKm = botConfigService.getTaxiPricePerKm();
            double pricePerMinute = botConfigService.getTaxiPricePerMinute();
            double vat = botConfigService.getTaxiVat();

            double carTypeModifier = switch (carType) {
                case "PRIVATE_CAR" -> 1.0;
                case "MOTORCYCLE" -> 0.8;
                case "MINIVAN" -> 1.4;
                default -> {
                    logger.warn("Unknown car type '{}', defaulting modifier to 1.0", carType);
                    yield 1.0;
                }
            };

            if (tripInfo != null && tripInfo.distanceKm > 0) {
                estimatedFare = (basePrice + (tripInfo.distanceKm * pricePerKm) + 
                        (tripInfo.durationMinutes * pricePerMinute)) * carTypeModifier * (1 + vat);
                logger.info("Fare calculated: ₪{} for {}km / {}min (Vehicle Type: {})",
                        String.format("%.2f", estimatedFare),
                        String.format("%.1f", tripInfo.distanceKm),
                        String.format("%.1f", tripInfo.durationMinutes),
                        carType);
            } else {
                logger.warn("TripInfo returned null or 0 distance. Fare unavailable.");
            }
        } catch (Exception e) {
            logger.error("Fare calculation failed: {}", e.getMessage(), e);
        }

        String fareStr = estimatedFare != null ? String.format("%.2f", estimatedFare) : "";
        convoService.saveTempData(convo, carType + "|" + pickupLocation + "|" + pickupPlaceId + 
                "|" + destination + "|" + destinationPlaceId + "|" + notes + "|" + fareStr);
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

        // Normalize typed input so small variations (spaces, punctuation, case) still match
        String normalized = txt.trim().toLowerCase().replaceAll("[!.,]", "");

        boolean isNo = txt.equals("order_confirm_no")
                || normalized.equals("לא")
                || normalized.equals("בטל")
                || normalized.equals("no");

        if (isNo) {
            convoService.updateState(convo, ConversationState.START);
            whatsappService.sendSafeText(message.getPhone(), "\n❌ ההזמנה בוטלה בהצלחה.\nנשמח לעמוד לשירותכם שוב ב־RYZ💙\nלהתחלת הזמנה חדשה שלחו הודעה 🚀");
            convoService.saveTempData(convo,"");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return null;
        }

        boolean isYes = txt.equals("order_confirm_yes")
                || normalized.equals("כן")
                || normalized.equals("אשר")
                || normalized.equals("yes")
                || normalized.equals("ok");

        if (!isYes) {
            logger.warn("User typed free text '{}' instead of pressing a confirmation button", txt);
            String[] parts = convo.getTempData().split("\\|", -1);
            String carType = parts[0];
            String pickupLocation = parts[1];
            String destination = parts[3];
            String notes = parts.length > 5 ? parts[5] : "";
            Double estimatedFare = (parts.length > 6 && !parts[6].isEmpty()) ? Double.parseDouble(parts[6]) : null;
            whatsappService.sendSafeText(message.getPhone(),
                    "לא זיהיתי את התשובה 🤔\nאנא אשרו או בטלו את ההזמנה עם הכפתורים למטה\n(או שלחו \"התחל מחדש\" לאיפוס השיחה)");
            showConfirmationButtons(message.getPhone(), carType, pickupLocation, destination, notes, estimatedFare);
            return null;
        }

        String orderData = convo.getTempData();
        String[] parts = orderData.split("\\|", -1);
        String carType = parts[0];
        String pickupLocation = parts[1];
        String pickupPlaceId = parts[2];
        String destination = parts[3];
        String notes = parts.length > 5 ? parts[5] : "";
        Double estimatedFare = (parts.length > 6 && !parts[6].isEmpty()) ? Double.parseDouble(parts[6]) : null;

        try {
            logger.info("Creating taxi order for customer {}", message.getPhone());
            boolean created = taxiOrderService.createTaxiOrder(message.getPhone(), pickupLocation, pickupPlaceId, destination, notes, CarType.valueOf(carType), estimatedFare);

            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo,"");
            convo.setNudgedAt(0);
            convoService.save(convo);

            if (!created) {
                // Duplicate active order — createTaxiOrder already sent the "you already have an order" notice
                logger.info("Taxi order not created (customer already has an active order)");
                return null;
            }

            logger.info("Taxi order created successfully");
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