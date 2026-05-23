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
                return handleCustomerPhone(convo, message);
            case DELIVERY_ADDRESS:
                return handleAddress(convo, message);
            case DELIVERY_READY_TIME:
                return handleReadyTime(convo, message);
            case DELIVERY_PRICE:
                return handlePrice(convo, message);
            case DELIVERY_NOTES:
                return handleNotes(convo, message);
            default:
                return null;
        }
    }

    private String handleCustomerPhone(Conversation convo, IncomingMessage message) {
        String phone = message.getText().trim();
        convoService.saveTempData(convo, phone);
        convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
        return "📞 מה מספר הטלפון של הלקוח?";
    }

    private String handleAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        String tempData = convo.getTempData() + "|" + address;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);

        showReadyTimeButton(message.getPhone());
        return null;
    }

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

    private String handlePrice(Conversation convo, IncomingMessage message) {
        String price = message.getText().trim();
        String tempData = convo.getTempData() + "|" + price;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

        return "📝 יש הערות נוספות לשליח?\n(קומה, קוד כניסה, הערה להזמנה וכו׳)\nאם אין הערות רשמו: לא";
    }

    private String handleNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();
        if (notes.equals("לא")) {
            notes = "";
        }

        String tempData = convo.getTempData() + "|" + notes;
        String[] parts = tempData.split("\\|");

        if (parts.length >= 5) {
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
            return "✅ אנא אשרו את פרטי המשלוח:\n📞 שם לקוח: [CUSTOMER_NAME]\n📞 טלפון לקוח: [CUSTOMER_PHONE]\n📍 כתובת מסירה: [DELIVERY_ADDRESS]\n⏱️ זמן הכנה: [READY_TIME]\n💰 סכום לתשלום: [PRICE]\n📝 הערות: [NOTES / אין]";
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