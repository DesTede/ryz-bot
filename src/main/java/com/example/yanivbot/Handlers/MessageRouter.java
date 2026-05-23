package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.BusinessOwnerService;
import com.example.yanivbot.Services.DriverService;
import com.example.yanivbot.Services.CustomerService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [COMPLETE FILE]
 * Routes incoming messages to the appropriate conversation handler based on state.
 *
 * CRITICAL FIXES:
 * 1. When handler returns null, DON'T fall through to error message
 * 2. Only capture name ONCE - use START_MENU state for menu display
 * 3. Send welcome message on START state and return early (don't process message)
 */
@Component
public class MessageRouter {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);

    private final TaxiConversationHandler taxiHandler;
    private final DeliveryConversationHandler deliveryHandler;
    private final BusinessConversationHandler businessHandler;
    private final DriverConversationHandler driverHandler;
    private final ConversationService convoService;
    private final BusinessOwnerService businessOwnerService;
    private final DriverService driverService;
    private final CustomerService customerService;
    private final WhatsappService whatsappService;

    private static final String WELCOME_MESSAGE = "ברוכים הבאים ל־Movez — מזמינים נסיעה תוך שניות בוואטסאפ ⚡\nאז איך קוראים לך?";

    public MessageRouter(TaxiConversationHandler taxiHandler,
                         DeliveryConversationHandler deliveryHandler,
                         BusinessConversationHandler businessHandler,
                         DriverConversationHandler driverHandler,
                         ConversationService convoService,
                         BusinessOwnerService businessOwnerService,
                         DriverService driverService,
                         CustomerService customerService,
                         WhatsappService whatsappService) {
        this.taxiHandler = taxiHandler;
        this.deliveryHandler = deliveryHandler;
        this.businessHandler = businessHandler;
        this.driverHandler = driverHandler;
        this.convoService = convoService;
        this.businessOwnerService = businessOwnerService;
        this.driverService = driverService;
        this.customerService = customerService;
        this.whatsappService = whatsappService;
    }

    /**
     * Route incoming message to appropriate handler based on conversation state and user type
     */
    public String route(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String phone = message.getPhone();
        ConversationState state = convo.getState();

        logger.info("========== ROUTE START ==========");
        logger.info("Phone: {}", phone);
        logger.info("Message: '{}'", txt);
        logger.info("State: {}", state);
        logger.info("================================");

        // Reset conversation if user sends "00"
        if (txt.equals("00")) {
            logger.info("Reset signal received");
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            return "🔄 איפוס משתמש. בואו נתחיל מחדש! 🚀";
        }

        // ===== START STATE =====
        if (state == ConversationState.START) {
            logger.info("In START state - determining user type");

            // Check if user is a driver
            if (driverService.findByPhone(phone) != null) {
                logger.info("User is a DRIVER");
                String driverResponse = driverHandler.handleMessage(convo, message);
                if (driverResponse != null) {
                    return driverResponse;
                }
                return null;
            }

            // Check if user is a business owner
            if (businessOwnerService.isBusinessOwner(phone)) {
                logger.info("User is a BUSINESS OWNER");
                convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                String businessResponse = businessHandler.handleMessage(convo, message);
                if (businessResponse != null) {
                    return businessResponse;
                }
                return null;
            }

            // Regular customer in START state
            logger.info("User is a CUSTOMER in START state");

            // Check if we already sent welcome (tempData will be set after welcome is sent)
            // If tempData is empty, send welcome and return (don't capture name yet)
            if (convo.getTempData() == null || convo.getTempData().isEmpty()) {
                logger.info("Sending welcome message, will capture name on next message");
                whatsappService.sendSafeText(phone, WELCOME_MESSAGE);
                // Set a flag in tempData so we know welcome was sent
                convoService.saveTempData(convo, "WELCOME_SENT");
                return null;
            }

            // Welcome was already sent, now capture the name
            logger.info("Welcome already sent, capturing name: '{}'", txt);
            String name = txt;
            convoService.saveTempData(convo, name);
            convoService.updateState(convo, ConversationState.START_MENU);

            // Show service menu with customer's name
            logger.info("Showing service menu for customer: {}", name);
            showServiceMenu(phone, name);
            return null; // Menu buttons already sent
        }

        // ===== START_MENU STATE =====
        // Customer has entered name and is seeing the menu
        if (state == ConversationState.START_MENU) {
            logger.info("In START_MENU state");

            if (txt.equals("start_service_taxi")) {
                logger.info("Customer selected TAXI service");
                convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);

                String bodyText = "מעולה 👍\nעכשיו בחרו את סוג הרכב:";
                whatsappService.sendInteractiveButtonsSafe(
                        phone,
                        bodyText,
                        new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "אופנוע – לעקוף את כל הפקקים"),
                        new WhatsappService.InteractiveButton("taxi_car_type_private_car", "מכונית פרטית – פשוט ולעניין"),
                        new WhatsappService.InteractiveButton("taxi_car_type_minivan", "הסעות גדולות +6")
                );
                return null; // Buttons already sent
            }

            // Invalid choice - ask them to use the menu
            logger.warn("Invalid menu choice in START_MENU: '{}'", txt);
            return "אנא בחר מהתפריט למעלה 👆";
        }

        // ===== BUSINESS_MENU STATE =====
        if (state == ConversationState.BUSINESS_MENU) {
            logger.info("In BUSINESS_MENU state");
            String businessResponse = businessHandler.handleMessage(convo, message);
            if (businessResponse != null) {
                return businessResponse;
            }
            return null;
        }

        // ===== TAXI STATES =====
        if (state == ConversationState.TAXI_CAR_TYPE ||
                state == ConversationState.TAXI_PICKUP ||
                state == ConversationState.TAXI_DESTINATION ||
                state == ConversationState.TAXI_NOTES ||
                state == ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION) {

            logger.info("In TAXI state: {}", state);
            String taxiResponse = taxiHandler.handleMessage(convo, message);
            if (taxiResponse != null) {
                logger.info("TaxiHandler returned response");
                return taxiResponse;
            }
            // Handler sent message directly (e.g., buttons)
            logger.info("TaxiHandler returned null - message sent via WhatsApp");
            return null;
        }

        // ===== DELIVERY STATES =====
        if (state == ConversationState.DELIVERY_CUSTOMER_PHONE ||
                state == ConversationState.DELIVERY_ADDRESS ||
                state == ConversationState.DELIVERY_READY_TIME ||
                state == ConversationState.DELIVERY_PRICE ||
                state == ConversationState.DELIVERY_NOTES) {

            logger.info("In DELIVERY state: {}", state);
            String deliveryResponse = deliveryHandler.handleMessage(convo, message);
            if (deliveryResponse != null) {
                return deliveryResponse;
            }
            return null;
        }

        logger.warn("========== UNKNOWN STATE: {} ==========", state);
        return null;
    }

    /**
     * Show service selection menu with customer's name
     */
    private void showServiceMenu(String phone, String name) {
        String message = "מה בא לך " + name + "?";

        try {
            logger.info("Sending service menu to {}: {}", phone, message);
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    message,
                    new WhatsappService.InteractiveButton("start_service_taxi", "🚕 מונית")
            );
            logger.info("Service menu sent successfully");
        } catch (Exception e) {
            logger.error("Error sending service menu: {}", e.getMessage(), e);
            whatsappService.sendSafeText(phone, message + "\n🚕 מונית");
        }
    }
}