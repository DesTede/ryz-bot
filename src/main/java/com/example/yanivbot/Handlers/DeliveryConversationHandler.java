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

        // Route based on tempData pipe count
        String tempData = convo.getTempData();
        int pipeCount = tempData == null || tempData.isEmpty() ? 0 : tempData.split("\\|", -1).length - 1;

        logger.info("Delivery flow - pipeCount: {}, tempData: {}", pipeCount, tempData);

        return switch (pipeCount) {
            case 0 ->
                // No pipes yet -> ask for customer name
                    handleCustomerName(convo, message);
            case 1 ->
                // 1 pipe (name|phone) -> ask for phone
                    handleCustomerPhone(convo, message);
            case 2 ->
                // 2 pipes (name|phone|address) -> ask for address
                    handleAddress(convo, message);
            case 3 ->
                // 3 pipes (name|phone|address|readyTime) -> ask for ready time
                    handleReadyTime(convo, message);
            case 4 ->
                // 4 pipes (name|phone|address|readyTime|price) -> ask for price
                    handlePrice(convo, message);
            case 5 ->
                // 5 pipes (all data entered) -> ask for notes, then show confirmation
                    handleNotes(convo, message);
            default -> {
                logger.warn("Unexpected pipe count: {}", pipeCount);
                yield null;
            }
        };
    }

    /**
     * Stage 1: Customer Name
     */
    private String handleCustomerName(Conversation convo, IncomingMessage message) {
        String customerName = message.getText().trim();
        convoService.saveTempData(convo, customerName);
        // Change state IMMEDIATELY after saving name
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
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
        // Change state IMMEDIATELY after saving phone
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);
        return "📍 מה כתובת המסירה?";
    }

    /**
     * Stage 3: Delivery Address
     */
    private String handleAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        String tempData = convo.getTempData() + "|" + address;
        convoService.saveTempData(convo, tempData);
        // Change state IMMEDIATELY after saving address
        convoService.updateState(convo, ConversationState.DELIVERY_PRICE);

        showReadyTimeButton(message.getPhone());
        return null;
    }

    /**
     * Stage 4: Ready Time
     */
    private String handleReadyTime(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        int minutes;

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
        // Change state IMMEDIATELY after saving ready time
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

        return "מה סכום המשלוח לתשלום?\nאם ההזמנה כבר שולמה רשמו: 0";
    }

    /**
     * Stage 5: Price
     */
    private String handlePrice(Conversation convo, IncomingMessage message) {
        String price = message.getText().trim();
        String tempData = convo.getTempData() + "|" + price;
        convoService.saveTempData(convo, tempData);
        // State stays at DELIVERY_NOTES for next question (notes)

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
            String confirmationMessage = getConfirmationMessage(parts);

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

    private static String getConfirmationMessage(String[] parts) {
        String customerName = parts[0];
        String customerPhone = parts[1];
        String address = parts[2];
        int readyInMinutes = Integer.parseInt(parts[3]);
        double price = Double.parseDouble(parts[4]);
        String notesStr = parts.length > 5 ? parts[5] : "";

        // Show confirmation with actual values
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