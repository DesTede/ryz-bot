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

/**
 * Handles delivery order flows with interactive buttons
 *
 * Updated to use interactive buttons for:
 * - Delivery ready time selection (Now/5min/10min/15min/20min/Other)
 * - Driver order actions (Pick Up/Delivered/Call)
 */
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

        // Check for driver delivery actions: "איסוף {id}" or "נמסר {id}"
        if (txt.matches("^איסוף\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markPickedUp(orderId, message.getPhone());
        }

        if (txt.matches("^נמסר\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markDelivered(orderId, message.getPhone());
        }

        // Handle state-based flows
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
        return "כתובת המסירה 📍";
    }

    private String handleAddress(Conversation convo, IncomingMessage message) {
        String address = message.getText().trim();
        String tempData = convo.getTempData() + "|" + address;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);

        // Show ready time with INTERACTIVE BUTTONS
        showReadyTimeButtons(message.getPhone());
        return null; // Already sent via WhatsApp
    }

    /**
     * DELIVERY_READY_TIME state: Show ready time with INTERACTIVE BUTTONS
     *
     * Button options: "delivery_ready_now", "delivery_ready_5min", "delivery_ready_10min", 
     *                  "delivery_ready_15min", "delivery_ready_20min", "delivery_ready_other"
     */
    private String handleReadyTime(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        int minutes = 0;

        // Handle button clicks
        if (txt.equals("delivery_ready_now")) {
            minutes = 0;
        } else if (txt.equals("delivery_ready_5min")) {
            minutes = 5;
        } else if (txt.equals("delivery_ready_10min")) {
            minutes = 10;
        } else if (txt.equals("delivery_ready_15min")) {
            minutes = 15;
        } else if (txt.equals("delivery_ready_20min")) {
            minutes = 20;
        } else if (txt.equals("delivery_ready_other")) {
            return "הזן מספר דקות:";
        }
        // Fallback: support old numeric input
        else if (txt.equals("עכשיו")) {
            minutes = 0;
        } else {
            try {
                minutes = Integer.parseInt(txt);
            } catch (NumberFormatException e) {
                return "בחר זמן מוכן או הזן מספר דקות";
            }
        }

        String tempData = convo.getTempData() + "|" + minutes;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_PRICE);

        return "מחיר המשלוח 💰";
    }

    private String handlePrice(Conversation convo, IncomingMessage message) {
        String price = message.getText().trim();
        String tempData = convo.getTempData() + "|" + price;
        convoService.saveTempData(convo, tempData);
        convoService.updateState(convo, ConversationState.DELIVERY_NOTES);

        return "הערות נוספות? (או הקלד 'לא')";
    }

    private String handleNotes(Conversation convo, IncomingMessage message) {
        String notes = message.getText().trim();
        if (notes.equals("לא")) {
            notes = "";
        }

        String tempData = convo.getTempData() + "|" + notes;
        String[] parts = tempData.split("\\|");

        if (parts.length >= 5) {
            String businessPhone = message.getPhone();  // The business owner who created the order
            String customerPhone = parts[0];
            String address = parts[1];
            String readyInMinutes = parts[2];  // Keep as String
            String price = parts[3];           // Keep as String
            String notesStr = parts.length > 4 ? parts[4] : "";

            // Call with all String parameters
            deliveryOrderService.createDeliveryOrder(
                    businessPhone,
                    customerPhone,
                    address,
                    readyInMinutes,
                    price,
                    notesStr
            );

            convoService.updateState(convo, ConversationState.START);
            return "✅ ההזמנה נוצרה בהצלחה! המשלוח ישודר לנהגים.";
        }

        return "❌ שגיאה בעת יצירת ההזמנה. אנא נסה שוב.";
    }

    /**
     * Send ready time selection with INTERACTIVE BUTTONS
     * Note: Max 3 buttons per message in WhatsApp, so we show top 3 options + "Other"
     */
    private void showReadyTimeButtons(String phone) {
        String bodyText = "בכמה דקות ההזמנה תהיה מוכנה?";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("delivery_ready_now", "⏱️ עכשיו"),
                new WhatsappService.InteractiveButton("delivery_ready_5min", "5 דקות"),
                new WhatsappService.InteractiveButton("delivery_ready_10min", "10 דקות")
        );

        // Send second message with more options
        whatsappService.sendInteractiveButtons(
                phone,
                "",
                new WhatsappService.InteractiveButton("delivery_ready_15min", "15 דקות"),
                new WhatsappService.InteractiveButton("delivery_ready_20min", "20 דקות"),
                new WhatsappService.InteractiveButton("delivery_ready_other", "אחר")
        );
    }
}