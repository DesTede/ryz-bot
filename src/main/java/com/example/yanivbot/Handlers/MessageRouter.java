package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessageRouter {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);

    private final ConversationService convoService;
    private final TaxiConversationHandler taxiHandler;
    private final DeliveryConversationHandler deliveryHandler;
    private final DriverConversationHandler driverHandler;
    private final BusinessConversationHandler businessHandler;
    private final WhatsappService whatsappService;

    public MessageRouter(ConversationService convoService,
                         TaxiConversationHandler taxiHandler,
                         DeliveryConversationHandler deliveryHandler,
                         DriverConversationHandler driverHandler,
                         BusinessConversationHandler businessHandler,
                         WhatsappService whatsappService) {
        this.convoService = convoService;
        this.taxiHandler = taxiHandler;
        this.deliveryHandler = deliveryHandler;
        this.driverHandler = driverHandler;
        this.businessHandler = businessHandler;
        this.whatsappService = whatsappService;
    }

    public String route(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        logger.info("Routing message for {}: '{}' | State: {}", message.getPhone(), txt, convo.getState());

        if (txt.equals("00")) {
            convoService.updateState(convo, ConversationState.START);
            return handleStart(message.getPhone());
        }

        String driverResponse = driverHandler.handleMessage(convo, message);
        if (driverResponse != null) {
            logger.info("Driver handler returned response");
            return driverResponse;
        }

        if (isStartMenuButton(txt)) {
            logger.info("Processing START menu button: {}", txt);
            return handleStartMenuButton(convo, message);
        }

        ConversationState state = convo.getState();

        switch (state) {
            case START:
                return handleStart(message.getPhone());

            case BUSINESS_MENU:
                String businessResponse = businessHandler.handleMessage(convo, message);
                if (businessResponse != null) {
                    return businessResponse;
                }
                break;

            case TAXI_SERVICE:
            case TAXI_CAR_TYPE:
            case TAXI_PICKUP:
            case TAXI_DESTINATION:
            case TAXI_NOTES:
            case AWAITING_TAXI_ORDER_CONFIRMATION:
                String taxiResponse = taxiHandler.handleMessage(convo, message);
                if (taxiResponse != null) {
                    return taxiResponse;
                }
                break;

            case DELIVERY_CUSTOMER_PHONE:
            case DELIVERY_ADDRESS:
            case DELIVERY_READY_TIME:
            case DELIVERY_PRICE:
            case DELIVERY_NOTES:
                String deliveryResponse = deliveryHandler.handleMessage(convo, message);
                if (deliveryResponse != null) {
                    return deliveryResponse;
                }
                break;
        }

        return "❌ משהו השתבש. אנא נסה שוב.";
    }

    private boolean isStartMenuButton(String txt) {
        return txt.equals("start_service_taxi") ||
                txt.equals("start_service_delivery") ||
                txt.equals("start_contact_us");
    }

    private String handleStartMenuButton(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        if (txt.equals("start_service_taxi")) {
            convoService.updateState(convo, ConversationState.TAXI_SERVICE);
            logger.info("Customer {} chosen taxi", message.getPhone());
            showCarTypeSelection(message.getPhone());
            return null;
        }

        if (txt.equals("start_service_delivery")) {
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
            logger.info("Business owner {} chosen delivery", message.getPhone());
            return "מה שם הלקוח?";
        }

        if (txt.equals("start_contact_us")) {
            logger.info("Customer {} requested contact info", message.getPhone());
            return "📞 יצור קשר: +972-527-718199";
        }

        return null;
    }

    private String handleStart(String phone) {
        if (driverHandler.isDriver(phone)) {
            if (driverHandler.isBusinessOwner(phone)) {
                showBusinessMenu(phone);
            } else {
                showDriverMenu(phone);
            }
            return null;
        }

        showCustomerMenu(phone);
        return null;
    }

    private void showCustomerMenu(String phone) {
        String bodyText = "ברוכים הבאים ל־Movez — מזמינים נסיעה תוך שניות בוואטסאפ ⚡\nאז איך קוראים לך?";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("start_service_taxi", "🚕 מונית"),
                new WhatsappService.InteractiveButton("start_service_delivery", "🚚 משלוח")
        );
    }

    private void showDriverMenu(String phone) {
        String bodyText = "ברוך הבא למערכת הנהגים של Moovez\nכדי להתחיל לקבל נסיעות לחץ על";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("driver_start_shift", "🟢 התחל משמרת"),
                new WhatsappService.InteractiveButton("driver_end_shift", "🔴 סיים משמרת")
        );
    }

    private void showBusinessMenu(String phone) {
        businessHandler.showBusinessMenuButtons(phone);
    }

    private void showCarTypeSelection(String phone) {
        String bodyText = "מעולה 👍\nעכשיו בחרו את סוג הרכב:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "1️⃣ אופנוע – לעקוף את כל הפקקים"),
                new WhatsappService.InteractiveButton("taxi_car_type_private_car", "2️⃣ מכונית פרטית – פשוט ולעניין"),
                new WhatsappService.InteractiveButton("taxi_car_type_minivan", "3️⃣ הסעות גדולות +6")
        );
    }
}