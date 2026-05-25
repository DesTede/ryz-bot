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

        if (txt.matches("^איסוף\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markPickedUp(orderId, message.getPhone());
        }

        if (txt.matches("^נמסר\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markDelivered(orderId, message.getPhone());
        }

        switch (state) {
            case DELIVERY_CUSTOMER_PHONE:
                // This state is misnamed - it's actually for CUSTOMER NAME (first question)
                return handleCustomerName(convo, message);
            case DELIVERY_ADDRESS:
                // This state is for CUSTOMER PHONE (second question)
                return handleCustomerPhone(convo, message);
            case DELIVERY_READY_TIME:
                // Actually for delivery ADDRESS (third question)
                return handleAddress(convo, message);
            case DELIVERY_PRICE:
                return handleReadyTime(convo, message);
            case DELIVERY_NOTES:
                return handlePrice(convo, message);
            default:
                // Handle confirmation if in START state after capturing notes
                if (state == ConversationState.START && convo.getTempData() != null && convo.getTempData().contains("|")) {
                    return handleConfirmation(convo, message);
                }
                return null;
        }
    }

    /**
     * First question: Customer Name
     */
    private String handleCustomerName(Conversation convo, IncomingMessage message) {
        String customerName = message.getText().trim();
        convoService.saveTempData(convo, customerName);
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
        return "📞 מה מספר הטלפון של הלקוח?";
    }

    /**
     * Second question: Customer Phone with validation
     */
    private String handleCustomerPhone(Conversation convo, IncomingMessage message) {
        String phone = message.getText().trim();

        // Validate: must be 10 digits
        if (!phone.matches("\\d{10}")) {
            return "❌ מספר הטלפון לא תקין. אנא הקלידו מספר טלפון בן 10 ספרות (ללא קידומת 0).\nדוגמה: 0527123456 -> הקלידו: 527123456";
        }

        String tempData = convo.getTempData() + "|" + phone;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);

        return "📍 מה כתובת המסירה?";
    }

    /**
     * Third question: Delivery Address
     */
    private String handleAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        String tempData = convo.getTempData() + "|" + address;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_PRICE);

        showReadyTimeButton(message.getPhone());
        return null;
    }

    /**
     * Fourth question: Ready Time
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
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

        return "מה סכום המשלוח לתשלום?\nאם ההזמנה כבר שולמה רשמו: 0";
    }

    /**
     * Fifth question: Price
     */
    private String handlePrice(Conversation convo, IncomingMessage message) {
        String price = message.getText().trim();
        String tempData = convo.getTempData() + "|" + price;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.START);

        return "📝 יש הערות נוספות לשליח?\n(קומה, קוד כניסה, הערה להזמנה וכו׳)\nאם אין הערות רשמו: לא";
    }

    /**
     * Handle confirmation (after notes)
     */
    private String handleConfirmation(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();
        if (notes.equals("לא")) {
            notes = "";
        }

        String tempData = convo.getTempData() + "|" + notes;
        String[] parts = tempData.split("\\|");

        if (parts.length >= 6) {
            String businessPhone = message.getPhone();
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

            // Send confirmation with yes/no buttons
            whatsappService.sendInteractiveButtons(
                    message.getPhone(),
                    confirmationMessage,
                    new WhatsappService.InteractiveButton("delivery_confirm_yes", "כן ✅"),
                    new WhatsappService.InteractiveButton("delivery_confirm_no", "לא ❌")
            );

            // Store order details for confirmation
            convoService.saveTempData(convo, tempData);
            return null;
        }

        return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
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