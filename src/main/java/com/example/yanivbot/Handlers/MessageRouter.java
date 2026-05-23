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
 * Routes incoming messages to the appropriate conversation handler based on state.
 *
 * CRITICAL: When a handler returns null (message already sent via WhatsApp),
 * we MUST return null instead of falling through to the error message!
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

        logger.info("Routing message for {}: '{}' | State: {}", phone, txt, convo.getState());

        // Reset conversation if user sends "00"
        if (txt.equals("00")) {
            convoService.updateState(convo, ConversationState.START);
            return "🔄 איפוס משתמש. בואו נתחיל מחדש! 🚀";
        }

        // Try driver handler first (drivers have priority)
        String driverResponse = driverHandler.handleMessage(convo, message);
        if (driverResponse != null) {
            logger.info("DriverHandler processed message");
            return driverResponse;
        }

        // If in START state and not a driver/business owner, capture name
        ConversationState state = convo.getState();
        if (state == ConversationState.START && !txt.isEmpty() && !isStartMenuButton(txt) &&
                driverService.findByPhone(phone) == null && !businessOwnerService.isBusinessOwner(phone)) {
            logger.info("Capturing customer name: {}", txt);
            // Save name (optional: uncomment if using Customer entity)
            // customerService.saveOrUpdateCustomer(phone, txt);
            convoService.saveTempData(convo, txt);
            showServiceMenu(phone, txt);
            return null;
        }

        // Check for start menu buttons (Taxi/Delivery selection)
        if (isStartMenuButton(txt)) {
            logger.info("Processing START menu button: {}", txt);
            String buttonResponse = handleStartMenuButton(convo, message);
            if (buttonResponse != null) {
                return buttonResponse;
            }
            // If null, we already sent the message via WhatsApp (car type buttons)
            return null;
        }

        logger.info("Current state before switch: {}", state);

        switch (state) {
            case START:
                logger.info("In START state");
                return handleStart(convo, phone);

            case BUSINESS_MENU:
                logger.info("In BUSINESS_MENU state");
                String businessResponse = businessHandler.handleMessage(convo, message);
                if (businessResponse != null) {
                    return businessResponse;
                }
                return null;

            case TAXI_SERVICE:
            case TAXI_CAR_TYPE:
                logger.info("In TAXI state (TAXI_CAR_TYPE)");
                String taxiResponse = taxiHandler.handleMessage(convo, message);
                if (taxiResponse != null) {
                    logger.info("TaxiHandler returned: {}", taxiResponse);
                    return taxiResponse;
                }
                // Handler sent message directly (e.g., buttons)
                return null;

            case TAXI_PICKUP:
            case TAXI_DESTINATION:
            case TAXI_NOTES:
                logger.info("In TAXI state: {}", state);
                String taxiResponse2 = taxiHandler.handleMessage(convo, message);
                if (taxiResponse2 != null) {
                    logger.info("TaxiHandler returned: {}", taxiResponse2);
                    return taxiResponse2;
                }
                // Handler sent message directly (e.g., confirmation buttons)
                logger.info("TaxiHandler returned null - message sent via WhatsApp");
                return null;

            case AWAITING_TAXI_ORDER_CONFIRMATION:
                logger.info("In AWAITING_TAXI_ORDER_CONFIRMATION state");
                String confirmResponse = taxiHandler.handleMessage(convo, message);
                if (confirmResponse != null) {
                    logger.info("TaxiHandler returned confirmation: {}", confirmResponse);
                    return confirmResponse;
                }
                // Handler sent message directly
                logger.info("TaxiHandler returned null");
                return null;

            case DELIVERY_CUSTOMER_PHONE:
            case DELIVERY_ADDRESS:
            case DELIVERY_READY_TIME:
            case DELIVERY_PRICE:
            case DELIVERY_NOTES:
                logger.info("In DELIVERY state: {}", state);
                String deliveryResponse = deliveryHandler.handleMessage(convo, message);
                if (deliveryResponse != null) {
                    return deliveryResponse;
                }
                return null;

            default:
                logger.warn("Unknown state: {}", state);
                return null;
        }
    }

    /**
     * Handle START state - determine if user is customer, driver, or business owner
     */
    private String handleStart(Conversation convo, String phone) {
        // Customer should already have been handled
        return null;
    }

    /**
     * Show service selection menu (Taxi only for customers)
     */
    private void showServiceMenu(String phone, String name) {
        String message = "מה בא לך " + name + "?";

        try {
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    message,
                    new WhatsappService.InteractiveButton("start_service_taxi", "🚕 מונית")
            );
        } catch (Exception e) {
            logger.error("Error sending service menu: {}", e.getMessage());
            whatsappService.sendSafeText(phone, message + "\n🚕 מונית");
        }
    }

    /**
     * Handle start menu button clicks
     */
    private String handleStartMenuButton(Conversation convo, IncomingMessage message) {
        String txt = message.getText().trim();
        String phone = message.getPhone();

        if (txt.equals("start_service_taxi")) {
            logger.info("Customer selected taxi service");
            convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);

            String bodyText = "מעולה 👍\nעכשיו בחרו את סוג הרכב:";
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    bodyText,
                    new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "🏍️ אופנוע"),
                    new WhatsappService.InteractiveButton("taxi_car_type_private_car", "🚗 מכונית פרטית"),
                    new WhatsappService.InteractiveButton("taxi_car_type_minivan", "🚐 מיניוואן")
            );
            return null; // Buttons already sent
        }

        logger.warn("Unknown start menu button: {}", txt);
        return null;
    }

    /**
     * Check if text is a start menu button ID
     */
    private boolean isStartMenuButton(String txt) {
        boolean result = txt.equals("start_service_taxi") ||
                txt.equals("start_service_delivery");
        logger.info("isStartMenuButton('{}') = {}", txt, result);
        return result;
    }
}