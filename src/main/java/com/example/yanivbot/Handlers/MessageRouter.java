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
        ConversationState state = convo.getState();

        logger.info("Routing message for {}: '{}' | State: {}", phone, txt, state);

        // Reset conversation if user sends "00"
        if (txt.equals("00")) {
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            return "🔄 איפוס משתמש. בואו נתחיל מחדש! 🚀";
        }

        // Handle START state - capture customer name or check user type
        if (state == ConversationState.START) {
            logger.info("User in START state, determining user type");

            // Check if user is a driver
            if (driverService.findByPhone(phone) != null) {
                logger.info("User is a driver");
                String driverResponse = driverHandler.handleMessage(convo, message);
                if (driverResponse != null) {
                    return driverResponse;
                }
                return null;
            }

            // Check if user is a business owner
            if (businessOwnerService.isBusinessOwner(phone)) {
                logger.info("User is a business owner");
                convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                String businessResponse = businessHandler.handleMessage(convo, message);
                if (businessResponse != null) {
                    return businessResponse;
                }
                return null;
            }

            // Regular customer - capture name and show menu
            logger.info("User is a regular customer, capturing name: {}", txt);
            String name = txt;
            convoService.saveTempData(convo, name);
            convoService.updateState(convo, ConversationState.START_MENU);

            // Show service menu with customer's name
            showServiceMenu(phone, name);
            return null; // Menu buttons already sent
        }

        // Handle START_MENU state - customer selecting service (Taxi)
        if (state == ConversationState.START_MENU) {
            logger.info("User in START_MENU state");

            if (txt.equals("start_service_taxi")) {
                logger.info("Customer selected Taxi service");
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

            // Invalid choice - ask them to use the menu
            logger.warn("Invalid menu choice: {}", txt);
            return "אנא בחר מהתפריט למעלה 👆";
        }

        // Handle BUSINESS_MENU state
        if (state == ConversationState.BUSINESS_MENU) {
            logger.info("In BUSINESS_MENU state");
            String businessResponse = businessHandler.handleMessage(convo, message);
            if (businessResponse != null) {
                return businessResponse;
            }
            return null;
        }

        // Handle TAXI states
        if (state == ConversationState.TAXI_CAR_TYPE ||
                state == ConversationState.TAXI_PICKUP ||
                state == ConversationState.TAXI_DESTINATION ||
                state == ConversationState.TAXI_NOTES ||
                state == ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION) {

            logger.info("In TAXI state: {}", state);
            String taxiResponse = taxiHandler.handleMessage(convo, message);
            if (taxiResponse != null) {
                logger.info("TaxiHandler returned: {}", taxiResponse);
                return taxiResponse;
            }
            // Handler sent message directly (e.g., buttons)
            logger.info("TaxiHandler returned null - message sent via WhatsApp");
            return null;
        }

        // Handle DELIVERY states
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

        logger.warn("Unknown state: {} - returning null", state);
        return null;
    }

    /**
     * Show service selection menu with customer's name
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
}