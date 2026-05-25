package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.BotConfigService;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import com.example.yanivbot.Services.DriverService;
import com.example.yanivbot.Services.BusinessOwnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [COMPLETE FILE]
 * Routes incoming messages to the appropriate conversation handler based on state.
 *
 * ADDITIONS:
 * - Admin command handling (כבה בוט, הפעל בוט)
 * - Bot status checking (rejects non-admin messages when bot is off)
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
    private final AdminCommandHandler adminCommandHandler;
    private final ConversationService convoService;
    private final BusinessOwnerService businessOwnerService;
    private final DriverService driverService;
    private final WhatsappService whatsappService;
    private final BotConfigService botConfigService;

    private static final String WELCOME_MESSAGE = "ברוכים הבאים ל־Movez — מזמינים נסיעה תוך שניות בוואטסאפ ⚡\nאז איך קוראים לך?";
    private static final String DRIVER_WELCOME_MESSAGE = "כדי להתחיל לקבל נסיעות לחץ על\n🟢 התחל משמרת\n\nכדי לצאת מהמערכת לחץ על\n🔴 סיים משמרת\n\nלבחירת פעולה 👇";

    public MessageRouter(TaxiConversationHandler taxiHandler,
                         DeliveryConversationHandler deliveryHandler,
                         BusinessConversationHandler businessHandler,
                         DriverConversationHandler driverHandler,
                         AdminCommandHandler adminCommandHandler,
                         ConversationService convoService,
                         BusinessOwnerService businessOwnerService,
                         DriverService driverService,
                         WhatsappService whatsappService,
                         BotConfigService botConfigService) {
        this.taxiHandler = taxiHandler;
        this.deliveryHandler = deliveryHandler;
        this.businessHandler = businessHandler;
        this.driverHandler = driverHandler;
        this.adminCommandHandler = adminCommandHandler;
        this.convoService = convoService;
        this.businessOwnerService = businessOwnerService;
        this.driverService = driverService;
        this.whatsappService = whatsappService;
        this.botConfigService = botConfigService;
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

        // ===== ADMIN COMMANDS (works even when bot is off) =====
        String adminResponse = adminCommandHandler.handleAdminCommand(phone, txt);
        if (adminResponse != null) {
            return adminResponse; // "כבה בוט" or "הפעל בוט" response
        }

        // ===== CHECK BOT STATUS =====
        // If bot is inactive AND user is not an admin, reject the message
        if (!botConfigService.isBotActive() && !adminCommandHandler.isAdmin(phone)) {
            logger.warn("Bot is inactive - rejecting message from {}", phone);
            return adminCommandHandler.getBotInactiveMessage();
        }

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

            // Check if user is a driver AND is currently active (in shift)
            Driver driver = driverService.findByPhone(phone);
            if (driver != null && driver.isActive()) {
                logger.info("User is an ACTIVE DRIVER");

                // Send driver welcome message with buttons
                if (convo.getTempData() == null || convo.getTempData().isEmpty()) {
                    logger.info("Sending driver welcome message with buttons");
                    sendDriverWelcomeMenu(phone);
                    convoService.saveTempData(convo, "DRIVER_WELCOME_SENT");
                    return null;
                }

                // Welcome was sent, now handle driver commands
                logger.info("Driver welcome already sent, routing to DriverHandler");
                String driverResponse = driverHandler.handleMessage(convo, message);
                if (driverResponse != null) {
                    return driverResponse;
                }
                return null;
            }

            // Check if this is a driver trying to start shift (even if inactive)
            if (driver != null && (txt.equals("התחל משמרת") || txt.equals("driver_start_shift"))) {
                logger.info("Inactive driver attempting to start shift");
                convoService.updateState(convo, ConversationState.START); // Already in START
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

        // ===== AWAITING_DRIVER_LOCATION STATE =====
        if (state == ConversationState.AWAITING_DRIVER_LOCATION) {
            logger.info("In AWAITING_DRIVER_LOCATION state");
            String driverResponse = driverHandler.handleMessage(convo, message);
            if (driverResponse != null) {
                return driverResponse;
            }
            return null;
        }

        // ===== START_MENU STATE =====
        if (state == ConversationState.START_MENU) {
            logger.info("In START_MENU state");
            if (txt.equals("start_service_taxi")) {
                convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);
                return "🚗 איזה סוג רכב אתה צריך?";
            } else {
                return "אנא בחר מהתפריט למעלה 👆";
            }
        }

        // ===== TAXI STATES =====
        if (state.toString().startsWith("TAXI_")) {
            logger.info("In TAXI state: {}", state);
            String taxiResponse = taxiHandler.handleMessage(convo, message);
            if (taxiResponse != null) {
                logger.info("TaxiHandler returned response");
                return taxiResponse;
            }
            return null;
        }

        // ===== DELIVERY STATES =====
        if (state.toString().startsWith("DELIVERY_")) {
            logger.info("In DELIVERY state: {}", state);
            String deliveryResponse = deliveryHandler.handleMessage(convo, message);
            if (deliveryResponse != null) {
                logger.info("DeliveryHandler returned response");
                return deliveryResponse;
            }
            return null;
        }

        // ===== BUSINESS STATES =====
        if (state == ConversationState.BUSINESS_MENU) {
            logger.info("In BUSINESS_MENU state");
            String businessResponse = businessHandler.handleMessage(convo, message);
            if (businessResponse != null) {
                logger.info("BusinessHandler returned response");
                return businessResponse;
            }
            return null;
        }

        logger.warn("Unknown state: {}", state);
        return null;
    }

    private void sendDriverWelcomeMenu(String phone) {
        whatsappService.sendInteractiveButtonsSafe(
                phone,
                DRIVER_WELCOME_MESSAGE,
                new WhatsappService.InteractiveButton("driver_start_shift", "🟢 התחל משמרת"),
                new WhatsappService.InteractiveButton("driver_end_shift", "🔴 סיים משמרת")
        );
    }

    private void showServiceMenu(String phone, String name) {
        whatsappService.sendInteractiveButtonsSafe(
                phone,
                "בחר שירות, " + name + " 👇",
                new WhatsappService.InteractiveButton("start_service_taxi", "🚖 הזמן מונית"),
                new WhatsappService.InteractiveButton("start_service_delivery", "📦 שלח משלוח")
        );
    }
}