package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Entities.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DeliveryOrderService;
import com.example.yanivbot.Services.GooglePlacesService;
import com.example.yanivbot.Services.WhatsappService;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class DeliveryConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryConversationHandler.class);

    private final ConversationService convoService;
    private final DeliveryOrderService deliveryOrderService;
    private final WhatsappService whatsappService;
    private final GooglePlacesService placesService;

    public DeliveryConversationHandler(ConversationService convoService, DeliveryOrderService deliveryOrderService, WhatsappService whatsappService, GooglePlacesService placesService) {
        this.convoService = convoService;
        this.deliveryOrderService = deliveryOrderService;
        this.whatsappService = whatsappService;
        this.placesService = placesService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();
        
        // ===== DELIVERY FLOW LOG =====
        logger.info("[DELIVERY] Phone: {} | Input: '{}' | State: {} | TempData: '{}'",
                message.getPhone(), txt, state, convo.getTempData());

        // Route based on CURRENT STATE (which changes immediately after each answer)
        String stageInfo = getDeliveryStageFromState(state);
        logger.info("[DELIVERY] State: {} -> {}", state, stageInfo);

        return switch (state) {
            case DELIVERY_CUSTOMER_PHONE -> {
                logger.info("[DELIVERY] -> handleCustomerPhone");
                yield handleCustomerPhone(convo, message);
            }
            case DELIVERY_AWAITING_CUSTOMER_CONFIRM -> {
                logger.info("[DELIVERY] -> handleCustomerConfirm");
                yield handleCustomerConfirm(convo, message);
            }
            case DELIVERY_CUSTOMER_NAME -> {
                logger.info("[DELIVERY] -> handleCustomerName");
                yield handleCustomerName(convo, message);
            }
            case DELIVERY_ADDRESS -> {
                logger.info("[DELIVERY] -> handleAddress");
                yield handleAddress(convo, message);
            }
            case AWAITING_DELIVERY_ADDRESS_SELECTION -> {
                logger.info("[DELIVERY] -> handleAddressSelection");
                yield handleAddressSelection(convo, message);
            }
            case DELIVERY_READY_TIME -> {
                logger.info("[DELIVERY] -> handleReadyTime");
                yield handleReadyTime(convo, message);
            }
            case DELIVERY_PRICE -> {
                logger.info("[DELIVERY] -> handlePrice");
                yield handlePrice(convo, message);
            }
            case DELIVERY_NOTES -> {
                logger.info("[DELIVERY] -> handleNotes");
                yield handleNotes(convo, message);
            }
            case DELIVERY_AWAITING_CONFIRMATION -> {
                logger.info("[DELIVERY] -> handleConfirmationButton");
                yield handleConfirmationButton(convo, message);
            }
            
            default -> {
                logger.warn("[DELIVERY] Unexpected state: {}", state);
                yield null;
            }
        };
    }

    /**
     * Helper to show human-readable stage name from state
     */
    private String getDeliveryStageFromState(ConversationState state) {
        return switch (state) {
            case DELIVERY_CUSTOMER_PHONE -> "1/6 - Asking for CUSTOMER PHONE";
            case DELIVERY_AWAITING_CUSTOMER_CONFIRM -> "1b/6 - Awaiting CUSTOMER CONFIRM";
            case DELIVERY_CUSTOMER_NAME -> "2/6 - Asking for CUSTOMER NAME";
            case DELIVERY_ADDRESS -> "3/6 - Asking for ADDRESS";
            case AWAITING_DELIVERY_ADDRESS_SELECTION -> "3b/6 - Awaiting ADDRESS SELECTION";
            case DELIVERY_READY_TIME -> "4/6 - Asking for READY TIME";
            case DELIVERY_PRICE -> "5/6 - Asking for PRICE";
            case DELIVERY_NOTES -> "6/6 - Asking for NOTES then CONFIRMATION";
            default -> "UNKNOWN";
        };
    }

    /**
     * Stage 1: Customer Phone (now first step) - validate and look up previous order
     */
    private String handleCustomerPhone(Conversation convo, IncomingMessage message) {
        String rawInput = message.getText().trim();
        logger.info("[DELIVERY] Stage 1: Validating phone: '{}'", PhoneNumberUtil.maskPhoneNumber(rawInput));

        // Validate: must be 10 digits
        if (!PhoneNumberUtil.isValidPhoneNumber(rawInput)) {
            logger.warn("[DELIVERY] ❌ Phone validation FAILED: '{}'", rawInput);
            return "❌ מספר הטלפון לא תקין. אנא הקלידו מספר טלפון בן 10 ספרות.";
        }
        
//        if (!customerPhone.matches("\\d{10}")) {
//            logger.warn("[DELIVERY] ❌ Phone validation FAILED: '{}' (not 10 digits)", PhoneNumberUtil.maskPhoneNumber(customerPhone));
//            return "❌ מספר הטלפון לא תקין. אנא הקלידו מספר טלפון בן 10 ספרות.";
//        }

        String customerPhone = PhoneNumberUtil.normalizePhone(rawInput);
        logger.info("[DELIVERY] Normalized phone to: '{}'", PhoneNumberUtil.maskPhoneNumber(customerPhone));
        
        // Look up previous order details for this customer at this business
        String businessPhone = message.getPhone();
        String[] prevDetails = deliveryOrderService.getPreviousCustomerDetails(businessPhone, customerPhone);

        if (prevDetails != null) {
            // Returning customer - show details for confirmation
            String prevName = prevDetails[0];
            String prevAddress = prevDetails[1];
            String prevPlaceId = prevDetails.length > 2 ? prevDetails[2] : "";
            logger.info("[DELIVERY] ✅ Returning customer found: '{}' | Address: '{}'", prevName, prevAddress);
            convoService.saveTempData(convo, customerPhone + "|" + prevName + "|" + prevAddress + "|" + prevPlaceId);
            convoService.updateState(convo, ConversationState.DELIVERY_AWAITING_CUSTOMER_CONFIRM);
            whatsappService.sendInteractiveButtonsSafe(
                    message.getPhone(),
                    "📋 הלקוח הזה הזמין אצלכם בעבר:\n👤 שם: " + prevName + "\n📍 כתובת: " + prevAddress + "\nהפרטים עדיין נכונים?",
                    new WhatsappService.InteractiveButton("customer_confirm_yes", "✅ כן, נכון"),
                    new WhatsappService.InteractiveButton("customer_confirm_no", "❌ לא, שנה")
            );
            return null;
        }

        // New customer - ask for name
        logger.info("[DELIVERY] New customer - moving to name capture");
        convoService.saveTempData(convo, customerPhone);
        convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_NAME);
        return "👤 מה שם הלקוח?";
    }

    /**
     * Stage 2: Customer Name (entered after phone in the new flow)
     * TempData at this point contains just the customer phone
     */
    private String handleCustomerName(Conversation convo, IncomingMessage message) {
        String customerName = message.getText().trim();
        String customerPhone = convo.getTempData(); // phone was stored in previous step
        logger.info("[DELIVERY] Stage 2: Saving customer name: '{}' | Phone: '{}'", customerName, customerPhone);

        convoService.saveTempData(convo, customerName + "|" + customerPhone);
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);

        logger.info("[DELIVERY] ✅ Name saved | Moving to Stage 3 (Address)");
        return "📍 מה כתובת המסירה?";
    }

    /**
     * Stage 1b: Confirm returning customer details (name + address)
     * TempData: {customerPhone}|{prevName}|{prevAddress}|{prevPlaceId}
     */
    private String handleCustomerConfirm(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", 4);
        String customerPhone = parts[0];
        String prevName = parts.length > 1 ? parts[1] : "";
        String prevAddress = parts.length > 2 ? parts[2] : "";
        String prevPlaceId = parts.length > 3 ? parts[3] : "";

        // Normalize typed input so small variations (spaces, punctuation, case) still match
        String normalized = txt.trim().toLowerCase().replaceAll("[!.,]", "");

        boolean isNo = txt.equals("customer_confirm_no")
                || normalized.equals("לא")
                || normalized.equals("בטל")
                || normalized.equals("no");

        if (isNo) {
            // Details wrong - ask for new name (phone stays)
            logger.info("[DELIVERY] ❌ Customer details rejected - asking for new name");
            convoService.saveTempData(convo, customerPhone);
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_NAME);
            return "👤 מה שם הלקוח?";
        }

        boolean isYes = txt.equals("customer_confirm_yes")
                || normalized.equals("כן")
                || normalized.equals("אשר")
                || normalized.equals("yes")
                || normalized.equals("ok");

        if (!isYes) {
            logger.warn("[DELIVERY] User typed free text '{}' instead of pressing a confirmation button", txt);
            whatsappService.sendSafeText(message.getPhone(),
                    "לא זיהיתי את התשובה 🤔\nאנא אשרו או שנו את הפרטים עם הכפתורים למטה\n(או שלחו \"התחל מחדש\" לאיפוס השיחה)");
            whatsappService.sendInteractiveButtonsSafe(
                    message.getPhone(),
                    "📋 הלקוח הזה הזמין אצלכם בעבר:\n👤 שם: " + prevName + "\n📍 כתובת: " + prevAddress + "\nהפרטים עדיין נכונים?",
                    new WhatsappService.InteractiveButton("customer_confirm_yes", "✅ כן, נכון"),
                    new WhatsappService.InteractiveButton("customer_confirm_no", "❌ לא, שנה")
            );
            return null;
        }

        logger.info("[DELIVERY] ✅ Customer confirmed previous details");
        convoService.saveTempData(convo, prevName + "|" + customerPhone + "|" + prevAddress + "|" + prevPlaceId);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);
        showReadyTimeButton(message.getPhone());
        return null;
    }
    
    /**
     * Stage 3: Delivery Address - show Places suggestions instead of taking raw free text
     */
    private String handleAddress(Conversation convo, IncomingMessage message) {
        String input = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String customerName = parts[0];
        String customerPhone = parts[1];

        List<GooglePlacesService.PlaceSuggestion> suggestions = placesService.getSuggestions(input);

        if (suggestions.isEmpty()) {
            return "🔍 לא נמצאה כתובת תואמת, נסו לכתוב בצורה אחרת (לא לשכוח עיר)";
        }

        List<WhatsappService.InteractiveButton> items = new ArrayList<>();
        StringBuilder tempData = new StringBuilder(customerName + "|" + customerPhone + "|ADDRESS_PENDING");
        for (int i = 0; i < Math.min(9, suggestions.size()); i++) {
            String fullAddress = suggestions.get(i).description;

            String titleText = fullAddress.contains(",") ? fullAddress.split(",")[0].trim() : fullAddress;
            if (titleText.length() > 24) {
                titleText = titleText.substring(0, 21) + "...";
            }
            String descriptionText = fullAddress.contains(",")
                    ? fullAddress.substring(fullAddress.indexOf(',') + 1).trim()
                    : null;
            items.add(new WhatsappService.InteractiveButton("delivery_addr_" + i, titleText, descriptionText));
            tempData.append("|").append(fullAddress).append("|").append(suggestions.get(i).placeId);
        }
        items.add(new WhatsappService.InteractiveButton("delivery_addr_manual", "✏️ הזן ידנית"));

        convoService.saveTempData(convo, tempData.toString());
        convoService.updateState(convo, ConversationState.AWAITING_DELIVERY_ADDRESS_SELECTION);

        logger.info("[DELIVERY] Stage 3: Showing address suggestions | TempData: '{}'", tempData);
        whatsappService.sendInteractiveList(message.getPhone(), "📍 בחר כתובת מסירה:", "בחר כתובת", "תוצאות חיפוש", items);
        return null;
    }

    /**
     * Stage 3b: Customer picked an address from the suggestion list (or asked to enter manually)
     */
    private String handleAddressSelection(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String[] parts = convo.getTempData().split("\\|", -1);
        String customerName = parts[0];
        String customerPhone = parts[1];

        if (txt.equals("delivery_addr_manual")) {
            convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
            return "📍 הזן כתובת מסירה ידנית:";
        }

        if (!txt.startsWith("delivery_addr_")) {
            return "📍 אנא בחר כתובת מהרשימה, או לחץ על ✏️ הזן ידנית";
        }

        String address;
        String addressPlaceId;
        try {
            int index = Integer.parseInt(txt.replace("delivery_addr_", ""));
            address = parts[3 + (index * 2)];
            addressPlaceId = parts[4 + (index * 2)];
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            logger.warn("[DELIVERY] Invalid address selection '{}' ({} tempData parts)", txt, parts.length);
            return "📍 אנא בחר כתובת מהרשימה, או לחץ על ✏️ הזן ידנית";
        }

        String tempData = customerName + "|" + customerPhone + "|" + address + "|" + addressPlaceId;
        logger.info("[DELIVERY] ✅ Address selected: '{}' | TempData: '{}'", address, tempData);

        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);

        logger.info("[DELIVERY] Moving to Stage 4 (Ready Time)");
        showReadyTimeButton(message.getPhone());
        return null;
    }

    /**
     * Stage 4: Ready Time
     */
    private String handleReadyTime(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        int minutes;
        logger.info("[DELIVERY] Stage 4: Parsing ready time: '{}'", txt);

        if (txt.equals("delivery_ready_now") || txt.equals("עכשיו")) {
            minutes = 0;
            logger.info("[DELIVERY] Ready time: NOW (0 minutes)");
        } else {
            try {
                minutes = Integer.parseInt(txt);
                logger.info("[DELIVERY] Ready time: {} minutes", minutes);
            } catch (NumberFormatException e) {
                logger.warn("[DELIVERY] Invalid ready time input: '{}'", txt);
                return "⏱️ בעוד כמה דקות ההזמנה תהיה מוכנה?\nאם ההזמנה מוכנה עכשיו לחצו:\nאו הקלידו זמן הכנה(הקלד מספר בלבד)";
            }
        }

        String tempData = convo.getTempData() + "|" + minutes;
        logger.info("[DELIVERY] ✅ Ready time saved | TempData: '{}'", tempData);

        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_PRICE);

        logger.info("[DELIVERY] Moving to Stage 5 (Price)");
        return "מה סכום המשלוח לתשלום?\nאם ההזמנה כבר שולמה רשמו: 0";
    }

    /**
     * Stage 5: Price
     */
    private String handlePrice(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        if (!txt.matches("^\\d+(\\.\\d{1,2})?$")) {
            String errorMsg = """
                    ❌ מחיר לא תקין
                    אנא הקלד מחיר בספרות בלבד
                    דוגמה: 45.90 או 50""";
            whatsappService.sendSafeText(message.getPhone(), errorMsg);
            return null; // Stay in same state, ask again
        }

        double price = Double.parseDouble(txt);
        
        if (price < 0) {
            whatsappService.sendSafeText(message.getPhone(),
                    "❌ המחיר חייב להיות גדול מ-0");
            return null;
        }
        if (price > 10000) {
            whatsappService.sendSafeText(message.getPhone(),
                    "❌ המחיר גבוה מדי (מקסימום 10,000₪)");
            return null;
        }
        
        String tempData = convo.getTempData() + "|" + price;
        logger.info("[DELIVERY] Stage 5: Saving price: '{}' | TempData: '{}'", price, tempData);

        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

        logger.info("[DELIVERY] ✅ Price saved | Moving to Stage 6 (Notes)");
        
        
        return "📝 יש הערות נוספות לשליח?\n(קומה, מספר דירה, קוד כניסה, הערה להזמנה וכו׳)\nאם אין הערות רשמו: אין";
    }

    /**
     * Handle delivery notes (Stage 6)
     * User types: Notes or "אין" (no notes)
     * Next: Show confirmation with all details
     */
    private String handleNotes(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        // store the notes without parsing
        String notes = (txt.isEmpty() || txt.equals("אין")) ? "" : txt;
        
        // Build complete tempData: name|phone|address|placeId|readyTime|price|notes
        String tempData = convo.getTempData();
        String[] parts = tempData.split("\\|", -1);

        if (parts.length >= 6) {
            String completeTempData = tempData + "|" + notes;
            convoService.saveTempData(convo, completeTempData);

            logger.info("[DELIVERY] ✅ All data collected:");
            logger.info("  - Customer Name: {}", parts[0]);
            logger.info("  - Address: {}", parts[2]);
            logger.info("  - Ready Time: {} minutes", parts[4]);
            logger.info("  - Price: {} ₪", parts[5]);
            logger.info("  - Notes: {}", notes.isEmpty() ? "(none)" : notes);

            String customerName = parts[0];
            String customerPhone = parts[1];
            String address = parts[2];
            int readyMinutes = Integer.parseInt(parts[4]);
            double price = Double.parseDouble(parts[5]);

            // Show confirmation with all details
            String confirmation = buildConfirmationMessage(customerName, customerPhone, address,
                    readyMinutes, price, notes);

            convoService.updateState(convo, ConversationState.DELIVERY_AWAITING_CONFIRMATION);

            // Send confirmation with YES/NO buttons
            whatsappService.sendInteractiveButtonsSafe(
                    message.getPhone(),
                    confirmation,
                    new WhatsappService.InteractiveButton("delivery_confirm_yes", "אישור משלוח ✅"),
                    new WhatsappService.InteractiveButton("delivery_confirm_no", "ביטול ❌")
            );

            return null; 
        }

        // NEW
        convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
        convoService.saveTempData(convo, "");
        return "❌ שגיאה בעיבוד הנתונים.\nאנא התחילו מחדש - מה מספר הטלפון של הלקוח?";
    }

    /**
     * Build confirmation message string
     */
    private String buildConfirmationMessage(String customerName, String customerPhone, String address,
                                            int readyInMinutes, double price, String notesStr) {
        return "✅ אנא אשרו את פרטי המשלוח:\n" +
                "📞 שם לקוח: " + customerName + "\n" +
                "📞 טלפון לקוח: " + customerPhone + "\n" +
                "📍 כתובת מסירה: " + address + "\n" +
                "⏱️ זמן הכנה: " + (readyInMinutes == 0 ? "עכשיו" : readyInMinutes + " דקות") + "\n" +
                "💰 סכום לתשלום: " + price + "₪\n" +
                "📝 הערות: " + (notesStr.isEmpty() ? "אין" : notesStr);
    }

    /**
     * Handle confirmation buttons (yes/no)
     */
    private String handleConfirmationButton(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String tempData = convo.getTempData();

        // Normalize typed input so small variations (spaces, punctuation, case) still match
        String normalized = txt.trim().toLowerCase().replaceAll("[!.,]", "");

        boolean isNo = txt.equals("delivery_confirm_no")
                || normalized.equals("לא")
                || normalized.equals("בטל")
                || normalized.equals("no");

        if (isNo) {
            // Cancel order
            logger.info("[DELIVERY] ❌ User cancelled order");
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return """
                    ❌ ההזמנה בוטלה בהצלחה.
                    נשמח
                    לעמוד לשירותכם שוב ב־RYZ💙
                    בשביל
                    להתחיל מחדש, פשוט שלחו הודעה🚀""";
        }

        boolean isYes = txt.equals("delivery_confirm_yes")
                || normalized.equals("כן")
                || normalized.equals("אשר")
                || normalized.equals("yes")
                || normalized.equals("ok");

        if (!isYes) {
            logger.warn("[DELIVERY] User typed free text '{}' instead of pressing a confirmation button", txt);
            String[] parts = tempData.split("\\|", -1);
            if (parts.length >= 7) {
                String customerName = parts[0];
                String customerPhone = parts[1];
                String address = parts[2];
                int readyInMinutes = Integer.parseInt(parts[4]);
                double price = Double.parseDouble(parts[5]);
                String notesStr = parts[6];
                String confirmation = buildConfirmationMessage(customerName, customerPhone, address, readyInMinutes, price, notesStr);
                whatsappService.sendSafeText(message.getPhone(),
                        "לא זיהיתי את התשובה 🤔\nאנא אשרו או בטלו את ההזמנה עם הכפתורים למטה\n(או שלחו \"התחל מחדש\" לאיפוס השיחה)");
                whatsappService.sendInteractiveButtonsSafe(
                        message.getPhone(),
                        confirmation,
                        new WhatsappService.InteractiveButton("delivery_confirm_yes", "אישור משלוח ✅"),
                        new WhatsappService.InteractiveButton("delivery_confirm_no", "ביטול ❌")
                );
            } else {
                whatsappService.sendSafeText(message.getPhone(),
                        "לא זיהיתי את התשובה 🤔\nאנא לחצו על אחד הכפתורים: ✅ אישור משלוח או ❌ ביטול\n(או שלחו \"התחל מחדש\" לאיפוס השיחה)");
            }
            return null;
        }

        String[] parts = tempData.split("\\|", -1);
        if (parts.length >= 7) {
            String businessPhone = message.getPhone();
            String customerName = parts[0];
            String customerPhone = parts[1];
            String address = parts[2];
            String addressPlaceId = parts[3];
            int readyInMinutes = Integer.parseInt(parts[4]);
            double price = Double.parseDouble(parts[5]);
            String notesStr = parts[6];

            logger.info("[DELIVERY] ✅ User confirmed order - creating delivery order...");

            deliveryOrderService.createDeliveryOrder(
                    businessPhone,
                    customerName,
                    customerPhone,
                    address,
                    addressPlaceId,
                    readyInMinutes,
                    price,
                    notesStr
            );

            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            convo.setNudgedAt(0);
            convoService.save(convo);

            logger.info("[DELIVERY] ✅ Order created successfully - DeliveryOrderService will send confirmation");
            return null;
        } else {
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
        }
    }

    private void showReadyTimeButton(String phone) {
        String bodyText = "⏱️ בעוד כמה דקות ההזמנה תהיה מוכנה?\nאם ההזמנה מוכנה עכשיו לחצו: \nאו הקלידו זמן הכנה(הקלד מספר בלבד)";

        whatsappService.sendInteractiveButtonsSafe(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("delivery_ready_now", "⏱️ מוכנה")
        );
    }
}