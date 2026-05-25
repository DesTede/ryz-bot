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
            return deliveryOrderService.markDelivered(orderId, message.getPhone());
        }

        // Handle confirmation buttons
        if (txt.equals("delivery_confirm_yes") || txt.equals("delivery_confirm_no")) {
            return handleConfirmationButton(convo, message);
        }

        // Route based on CURRENT STATE, not pipe count
        // This way we know exactly which question we're on
        logger.info("Delivery flow - state: {}, tempData: {}", state, convo.getTempData());

        switch (state) {
            case DELIVERY_CUSTOMER_PHONE:
                // State: DELIVERY_CUSTOMER_PHONE -> asking for CUSTOMER NAME (first question)
                return handleCustomerName(convo, message);
            case DELIVERY_ADDRESS:
                // State: DELIVERY_ADDRESS -> asking for CUSTOMER PHONE (second question)
                return handleCustomerPhone(convo, message);
            case DELIVERY_READY_TIME:
                // State: DELIVERY_READY_TIME -> asking for ADDRESS (third question)
                return handleAddress(convo, message);
            case DELIVERY_PRICE:
                // State: DELIVERY_PRICE -> asking for READY TIME (fourth question)
                return handleReadyTime(convo, message);
            case DELIVERY_NOTES:
                // State: DELIVERY_NOTES -> check if asking for PRICE or NOTES
                String tempData = convo.getTempData();
                int pipeCount = tempData == null || tempData.isEmpty() ? 0 : tempData.split("\\|", -1).length - 1;

                if (pipeCount == 3) {
                    // Have 3 pipes (name|phone|address|readyTime) -> asking for PRICE
                    return handlePrice(convo, message);
                } else if (pipeCount == 4) {
                    // Have 4 pipes (name|phone|address|readyTime|price) -> asking for NOTES
                    return handleNotes(convo, message);
                }
                break;
            default:
                break;
        }

        return null;
    }

    /**
     * Stage 1: Customer Name
     */
    private String handleCustomerName(Conversation convo, IncomingMessage message) {
        String customerName = message.getText().trim();
        convoService.saveTempData(convo, customerName);
        convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
        return "📞 מה מספר הטלפון של הלקוח?";
    }

    /**
     * Stage 2: Customer Phone with validation
     */
    private String handleCustomerPhone(Conversation convo, IncomingMessage message) {
        String phone = message.getText().trim();

        // Validate: must be 10 digits
        if (!phone.matches("\\d{10}")) {
            logger.warn("Invalid phone: {}", phone);
            // Don't update tempData or state - just return error
            return "❌ מספר הטלפון לא תקין. אנא הקלידו מספר טלפון בן 10 ספרות.";
        }

        // Valid phone - now update and proceed
        String tempData = convo.getTempData() + "|" + phone;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
        return "📍 מה כתובת המסירה?";
    }

    /**
     * Stage 3: Delivery Address
     */
    private String handleAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        String tempData = convo.getTempData() + "|" + address;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);

        showReadyTimeButton(message.getPhone());
        return null;
    }

    /**
     * Stage 4: Ready Time
     */
    private String handleReadyTime(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        int minutes = 0;

        if (txt.equals("delivery_ready_now") || txt.equals("עכשיו")) {
            minutes = 0;
        } else {
            try {
                minutes = Integer.parseInt(txt);
            } catch (NumberFormatException e) {
                return "⏱️ בעוד כמה דקות ההזמנה תהיה מוכנה?\nאם ההזמנה מוכנה עכשיו לחצו: עכשיו\nאו הקלידו זמן הכנה";
            }
        }

        String tempData = convo.getTempData() + "|" + minutes;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_PRICE);
        return "מה סכום המשלוח לתשלום?\nאם ההזמנה כבר שולמה רשמו: 0";
    }

    /**
     * Stage 5: Price
     */
    private String handlePrice(Conversation convo, IncomingMessage message) {
        String price = message.getText().trim();
        String tempData = convo.getTempData() + "|" + price;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

        return "📝 יש הערות נוספות לשליח?\n(קוד כניסה, הערה להזמנה וכו׳)\nאם אין הערות רשמו: לא";
    }

    /**
     * Stage 6: Notes - then show confirmation
     */
    private String handleNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();
        if (notes.equals("לא")) {
            notes = "";
        }

        String tempData = convo.getTempData() + "|" + notes;
        String[] parts = tempData.split("\\|");

        // We need exactly 6 parts: name, phone, address, readyTime, price, notes
        if (parts.length >= 6) {
            String customerName = parts[0];
            String customerPhone = parts[1];
            String address = parts[2];
            int readyInMinutes = Integer.parseInt(parts[3]);
            double price = Double.parseDouble(parts[4]);
            String notesStr = parts.length > 5 ? parts[5] : "";

            // Show confirmation with actual values
            String confirmationMessage = "✅ אנא אשרו את פרטי המשלוח:\n" +
                    "📞 שם לקוח: " + customerName + "\n" +
                    "📞 טלפון לקוח: " + customerPhone + "\n" +
                    "📍 כתובת מסירה: " + address + "\n" +
                    "⏱️ זמן הכנה: " + (readyInMinutes == 0 ? "עכשיו" : readyInMinutes + " דקות") + "\n" +
                    "💰 סכום לתשלום: " + price + "₪\n" +
                    "📝 הערות: " + (notesStr.isEmpty() ? "אין" : notesStr);

            // Save complete tempData for confirmation
            convoService.saveTempData(convo, tempData);

            // Send confirmation with yes/no buttons
            whatsappService.sendInteractiveButtons(
                    message.getPhone(),
                    confirmationMessage,
                    new WhatsappService.InteractiveButton("delivery_confirm_yes", "כן ✅"),
                    new WhatsappService.InteractiveButton("delivery_confirm_no", "לא ❌")
            );

            return null;
        }

        return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
    }

    /**
     * Handle confirmation buttons (yes/no)
     */
    private String handleConfirmationButton(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String tempData = convo.getTempData();

        if (txt.equals("delivery_confirm_yes")) {
            // Create the order
            String[] parts = tempData.split("\\|");
            if (parts.length >= 6) {
                String businessPhone = message.getPhone();
                String customerName = parts[0];
                String customerPhone = parts[1];
                String address = parts[2];
                int readyInMinutes = Integer.parseInt(parts[3]);
                double price = Double.parseDouble(parts[4]);
                String notesStr = parts.length > 5 ? parts[5] : "";

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
                return "✅ ההזמנה נוצרה בהצלחה והתפזרה לנהגים!";
            }
        } else if (txt.equals("delivery_confirm_no")) {
            // Cancel order
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            return "❌ ההזמנה בוטלה. בואו נתחיל מחדש!";
        }

        return null;
    }

    private void showReadyTimeButton(String phone) {
        String bodyText = "⏱️ בעוד כמה דקות ההזמנה תהיה מוכנה?\nאם ההזמנה מוכנה עכשיו לחצו: עכשיו\nאו הקלידו זמן הכנה";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("delivery_ready_now", "⏱️ עכשיו")
        );
    }
}