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

        // Handle reset command "00"
        if (txt.equals("00")) {
            convoService.updateState(convo, ConversationState.START);
            return handleStart(message.getPhone());
        }

        // Check driver commands FIRST (before state-based routing)
        String driverResponse = driverHandler.handleMessage(convo, message);
        if (driverResponse != null) {
            logger.info("Driver handler returned response");
            return driverResponse;
        }

        // Handle button clicks for START menu
        if (isStartMenuButton(txt)) {
            logger.info("Processing START menu button: {}", txt);
            return handleStartMenuButton(convo, message);
        }

        // Route based on conversation state
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

    /**
     * Check if message is a START menu button click
     */
    private boolean isStartMenuButton(String txt) {
        return txt.equals("start_service_taxi") ||
                txt.equals("start_service_delivery") ||
                txt.equals("start_contact_us");
    }

    /**
     * Handle START menu button clicks
     */
    private String handleStartMenuButton(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();

        if (txt.equals("start_service_taxi")) {
            // Customer chose taxi
            convoService.updateState(convo, ConversationState.TAXI_SERVICE);
            // Show car type buttons
            logger.info("Customer {} chosen taxi, showing car type selection", message.getPhone());
            return "בחר סוג כלי רכב:\n\n1️⃣ אופנוע\n2️⃣ מכונית פרטית\n3️⃣ מיניוואן";
        }

        if (txt.equals("start_service_delivery")) {
            // Customer chose delivery (business owner)
            convoService.updateState(convo, ConversationState.DELIVERY_CUSTOMER_PHONE);
            logger.info("Business owner {} chosen delivery", message.getPhone());
            return "טלפון הלקוח 📞";
        }

        if (txt.equals("start_contact_us")) {
            logger.info("Customer {} requested contact info", message.getPhone());
            return "📞 יצור קשר: +972-527-718199";
        }

        return null;
    }

    /**
     * Handle START state - show main menu with interactive buttons
     */
    private String handleStart(String phone) {
        // Check if driver
        if (driverHandler.isDriver(phone)) {
            // Show driver/business menu
            if (driverHandler.isBusinessOwner(phone)) {
                showBusinessMenu(phone);
            } else {
                showDriverMenu(phone);
            }
            return null; // Already sent via WhatsApp
        }

        // Show customer menu with interactive buttons
        showCustomerMenu(phone);
        return null; // Already sent via WhatsApp
    }

    /**
     * Send customer menu with interactive buttons
     */
    private void showCustomerMenu(String phone) {
        String bodyText = "שלום 👋 בחר שירות:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("start_service_taxi", "🚕 מונית (Taxi)"),
                new WhatsappService.InteractiveButton("start_service_delivery", "🚚 משלוח (Delivery)"),
                new WhatsappService.InteractiveButton("start_contact_us", "📞 התקשר אלינו")
        );
    }

    /**
     * Send driver menu with interactive buttons
     */
    private void showDriverMenu(String phone) {
        String bodyText = "שלום נהג 👋 בחר פעולה:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("driver_start_shift", "✅ התחל משמרת"),
                new WhatsappService.InteractiveButton("driver_end_shift", "🔚 סיים משמרת"),
                new WhatsappService.InteractiveButton("start_contact_us", "📞 תמיכה")
        );
    }

    /**
     * Send business owner menu with interactive buttons
     */
    private void showBusinessMenu(String phone) {
        String bodyText = "שלום בעלים 👋 בחר שירות:";

        whatsappService.sendInteractiveButtons(
                phone,
                bodyText,
                new WhatsappService.InteractiveButton("business_taxi_option", "🚕 מונית"),
                new WhatsappService.InteractiveButton("business_delivery_option", "🚚 משלוח"),
                new WhatsappService.InteractiveButton("driver_end_shift", "🔚 סיים משמרת")
        );
    }
}