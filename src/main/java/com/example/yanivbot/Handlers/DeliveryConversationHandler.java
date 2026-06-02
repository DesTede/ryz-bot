package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DeliveryOrderService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeliveryConversationHandler implements ConversationHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryConversationHandler.class);

    private final ConversationService convoService;
    private final DeliveryOrderService deliveryOrderService;
    private final WhatsappService whatsappService;

    public DeliveryConversationHandler(ConversationService convoService, DeliveryOrderService deliveryOrderService, WhatsappService whatsappService) {
        this.convoService = convoService;
        this.deliveryOrderService = deliveryOrderService;
        this.whatsappService = whatsappService;
    }

    @Override
    public String handleMessage(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        ConversationState state = convo.getState();

        // Handle pickup/delivery commands
        if (txt.matches("^איסוף\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markPickedUp(orderId, message.getPhone());
        }

        if (txt.matches("^נמסר\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.completeDelivery(orderId, message.getPhone());
        }

        // Handle confirmation buttons
        if (txt.equals("delivery_confirm_yes") || txt.equals("delivery_confirm_no")) {
            return handleConfirmationButton(convo, message);
        }

        // ===== DELIVERY FLOW LOG =====
        logger.info("[DELIVERY] Phone: {} | Input: '{}' | State: {} | TempData: '{}'",
                message.getPhone(), txt, state, convo.getTempData());

        // Route based on CURRENT STATE (which changes immediately after each answer)
        String stageInfo = getDeliveryStageFromState(state);
        logger.info("[DELIVERY] State: {} -> {}", state, stageInfo);

        return switch (state) {
            case DELIVERY_CUSTOMER_NAME -> {
                logger.info("[DELIVERY] -> handleCustomerName");
                yield handleCustomerName(convo, message);
            }
            case DELIVERY_CUSTOMER_PHONE -> {
                logger.info("[DELIVERY] -> handleCustomerPhone");
                yield handleCustomerPhone(convo, message);
            }
            case DELIVERY_ADDRESS -> {
                logger.info("[DELIVERY] -> handleAddress");
                yield handleAddress(convo, message);
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
            case DELIVERY_CUSTOMER_NAME -> "1/6 - Asking for CUSTOMER NAME";
            case DELIVERY_CUSTOMER_PHONE -> "2/6 - Asking for CUSTOMER PHONE";
            case DELIVERY_ADDRESS -> "3/6 - Asking for ADDRESS";
            case DELIVERY_READY_TIME -> "4/6 - Asking for READY TIME";
            case DELIVERY_PRICE -> "5/6 - Asking for PRICE";
            case DELIVERY_NOTES -> "6/6 - Asking for NOTES then CONFIRMATION";
            default -> "UNKNOWN";
        };
    }

    /**
     * Stage 1: Customer Name
     */
    private String handleCustomerName(Conversation convo, IncomingMessage message) {
        String customerName = message.getText().trim();
        logger.info("[DELIVERY] Stage 1: Saving customer name: '{}'", customerName);

        convoService.saveTempData(convo, customerName);
        convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);

        logger.info("[DELIVERY] ✅ Name saved | Moving to Stage 2 (Phone)");
        return "📞 מה מספר הטלפון של הלקוח?";
    }

    /**
     * Stage 2: Customer Phone with validation
     */
    private String handleCustomerPhone(Conversation convo, IncomingMessage message) {
        String phone = message.getText().trim();
        logger.info("[DELIVERY] Stage 2: Validating phone: '{}'", phone);

        // Validate: must be 10 digits
        if (!phone.matches("\\d{10}")) {
            logger.warn("[DELIVERY] ❌ Phone validation FAILED: '{}' (not 10 digits)", phone);
            return "❌ מספר הטלפון לא תקין. אנא הקלידו מספר טלפון בן 10 ספרות.";
        }

        // Valid phone - now update and proceed
        String tempData = convo.getTempData() + "|" + phone;
        logger.info("[DELIVERY] ✅ Phone validated | TempData: '{}'", tempData);

        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);

        logger.info("[DELIVERY] Moving to Stage 3 (Address)");
        return "📍 מה כתובת המסירה?";
    }

    /**
     * Stage 3: Delivery Address
     */
    private String handleAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        String tempData = convo.getTempData() + "|" + address;
        logger.info("[DELIVERY] Stage 3: Saving address: '{}' | TempData: '{}'", address, tempData);

        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);

        logger.info("[DELIVERY] ✅ Address saved | Moving to Stage 4 (Ready Time)");
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
                return "⏱️ בעוד כמה דקות ההזמנה תהיה מוכנה?\nאם ההזמנה מוכנה עכשיו לחצו:\nאו הקלידו זמן הכנה";
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
            String errorMsg = "❌ מחיר לא תקין\n" +
                    "אנא הקלד מחיר בספרות בלבד\n" +
                    "דוגמה: 45.90 או 50";
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

        // Simply store the notes without parsing
//        String notes = txt.isEmpty() ? "" : txt;
        String notes = (txt.isEmpty() || txt.equals("אין")) ? "" : txt;
        // Build complete tempData: name|phone|address|readyTime|price|notes
        String tempData = convo.getTempData();
        String[] parts = tempData.split("\\|");

        if (parts.length >= 5) {
            // Add notes to the end
            String completeTempData = tempData + "|" + notes;

            // Save updated tempData
            convoService.saveTempData(convo, completeTempData);

            logger.info("[DELIVERY] ✅ All data collected:");
            logger.info("  - Customer Name: {}", parts[0]);
            logger.info("  - Address: {}", parts[2]);
            logger.info("  - Ready Time: {} minutes", parts[3]);
            logger.info("  - Price: {} ₪", parts[4]);
            logger.info("  - Notes: {}", notes.isEmpty() ? "(none)" : notes);

            // ✅ CONVERT TO CORRECT TYPES
            String customerName = parts[0];
            String customerPhone = parts[1];
            String address = parts[2];
            int readyMinutes = Integer.parseInt(parts[3]);  // ✅ Convert to int
            double price = Double.parseDouble(parts[4]);

            // Show confirmation with all details
            String confirmation = buildConfirmationMessage(customerName, customerPhone, address,
                    readyMinutes, price, notes);

            convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

            // Send confirmation with YES/NO buttons
            whatsappService.sendInteractiveButtons(
                    message.getPhone(),
                    confirmation,
                    new WhatsappService.InteractiveButton("delivery_confirm_yes", "אישור משלוח ✅"),
                    new WhatsappService.InteractiveButton("delivery_confirm_no", "ביטול ❌")
            );

            return null; // Message sent via buttons
        }

        return "❌ שגיאה בעיבוד הנתונים. אנא נסה שנית.";
    }
//    /**
//     * Stage 6: Notes - then show confirmation
//     */
//    private String handleNotes(Conversation convo, IncomingMessage message) {
//        String notes = message.getText().trim();
//        if (notes.equals("אין")) {
//            notes = "";
//        }
//        logger.info("[DELIVERY] Stage 6: Saving notes: '{}' (empty={})", notes, notes.isEmpty());
//
//        String tempData = convo.getTempData() + "|" + notes;
//        String[] parts = tempData.split("\\|", -1);  // Use -1 to NOT discard trailing empty strings
//
//        logger.info("[DELIVERY] Complete tempData: '{}' | Parts count: {}", tempData, parts.length);
//
//        // We need exactly 6 parts: name, phone, address, readyTime, price, notes
//        if (parts.length >= 6) {
//            String customerName = parts[0];
//            String customerPhone = parts[1];
//            String address = parts[2];
//            int readyInMinutes = Integer.parseInt(parts[3]);
//            double price = Double.parseDouble(parts[4]);
//            String notesStr = parts[5];
//
//            logger.info("[DELIVERY] ✅ All data collected:");
//            logger.info("  - Customer Name: {}", customerName);
//            logger.info("  - Customer Phone: {}", customerPhone);
//            logger.info("  - Address: {}", address);
//            logger.info("  - Ready Time: {} minutes", readyInMinutes);
//            logger.info("  - Price: {} ₪", price);
//            logger.info("  - Notes: {}", notesStr.isEmpty() ? "(none)" : notesStr);
//
//            // Show confirmation with actual values
//            String confirmationMessage = buildConfirmationMessage(customerName, customerPhone, address, readyInMinutes, price, notesStr);
//
//            // Save complete tempData for confirmation
//            convoService.saveTempData(convo, tempData);
//
//            logger.info("[DELIVERY] 📤 Sending confirmation message with YES/NO buttons");
//
//            // Send confirmation with yes/no buttons
//            whatsappService.sendInteractiveButtons(
//                    message.getPhone(),
//                    confirmationMessage,
//                    new WhatsappService.InteractiveButton("delivery_confirm_yes", "אישור משלוח ✅"),
//                    new WhatsappService.InteractiveButton("delivery_confirm_no", "ביטול משלוח ❌")
//            );
//
//            return null;
//        }
//
//        logger.error("[DELIVERY] ❌ Error: Not enough parts. Expected 6+, got {}", parts.length);
//        return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
//    }

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

        if (txt.equals("delivery_confirm_yes")) {
            // Create the order
            String[] parts = tempData.split("\\|", -1);
            if (parts.length >= 6) {
                String businessPhone = message.getPhone();
                String customerName = parts[0];
                String customerPhone = parts[1];
                String address = parts[2];
                int readyInMinutes = Integer.parseInt(parts[3]);
                double price = Double.parseDouble(parts[4]);
                String notesStr = parts[5];

                logger.info("[DELIVERY] ✅ User confirmed order - creating delivery order...");

                deliveryOrderService.createDeliveryOrder(
                        businessPhone,
                        customerName,
                        customerPhone,
                        address,
                        readyInMinutes,
                        price,
                        notesStr
                );

                convoService.updateState(convo, ConversationState.START);
                convoService.saveTempData(convo, "");

                logger.info("[DELIVERY] ✅ Order created successfully - DeliveryOrderService will send confirmation");
                return null;
            }
        } else if (txt.equals("delivery_confirm_no")) {
            // Cancel order
            logger.info("[DELIVERY] ❌ User cancelled order");
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            return """
                    ❌ ההזמנה בוטלה בהצלחה.
                    נשמח
                    לעמוד לשירותכם שוב ב־Movez\uD83D\uDC99
                    בשביל
                    להתחיל מחדש, פשוט שלחו הודעה\uD83D\uDE80""";
        }

        return null;
    }

    private void showReadyTimeButton(String phone) {
        String bodyText = "⏱️ בעוד כמה דקות ההזמנה תהיה מוכנה?\nאם ההזמנה מוכנה עכשיו לחצו: \nאו הקלידו זמן הכנה";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("delivery_ready_now", "⏱️ מוכנה")
        );
    }
}