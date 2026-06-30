package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.Customer;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Entities.IncomingMessage;
import com.example.yanivbot.Services.*;
import com.example.yanivbot.Utils.PhoneNumberUtil;
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
    private final CustomerService customerService;
    private final WhatsappService whatsappService;
    private final BotConfigService botConfigService;
    private final TaxiOrderService taxiOrderService;
    private final DeliveryOrderService deliveryOrderService;
    private final RatingService ratingService;

    private static final String WELCOME_MESSAGE = """
              ברוכים הבאים ל־RYZ — מזמינים נסיעה תוך שניות בוואטסאפ ⚡
            (לחץ 00 לאתחול)
            אז איך קוראים לך?""";
    private static final String DRIVER_WELCOME_MESSAGE = """
            💡 ברוכים הבאים למערכת הנהגים של RYZ!
            כדי להתחיל לקבל נסיעות לחץ על
            🟢 התחל משמרת

            כדי לצאת מהמערכת לחץ על
            🔴 סיים משמרת

            לבחירת פעולה 👇""";

    public MessageRouter(TaxiConversationHandler taxiHandler,
                         DeliveryConversationHandler deliveryHandler,
                         BusinessConversationHandler businessHandler,
                         DriverConversationHandler driverHandler,
                         AdminCommandHandler adminCommandHandler,
                         ConversationService convoService,
                         BusinessOwnerService businessOwnerService,
                         DriverService driverService,
                         CustomerService customerService,
                         WhatsappService whatsappService,
                         BotConfigService botConfigService,
                         TaxiOrderService taxiOrderService,
                         DeliveryOrderService deliveryOrderService,
                         RatingService ratingService) {
        this.taxiHandler = taxiHandler;
        this.deliveryHandler = deliveryHandler;
        this.businessHandler = businessHandler;
        this.driverHandler = driverHandler;
        this.adminCommandHandler = adminCommandHandler;
        this.convoService = convoService;
        this.businessOwnerService = businessOwnerService;
        this.driverService = driverService;
        this.customerService = customerService;
        this.whatsappService = whatsappService;
        this.botConfigService = botConfigService;
        this.taxiOrderService = taxiOrderService;
        this.deliveryOrderService = deliveryOrderService;
        this.ratingService = ratingService;
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

        // ===== CHECK ADMIN COMMANDS FIRST (works even when bot is off) =====
        boolean isAdminCommand = txt.equals("כבה בוט") || txt.equals("kahah_bot")
                || txt.equals("הפעל בוט") || txt.equals("hepel_bot")
                || txt.startsWith("stop_redispatch_taxi_") || txt.startsWith("stop_redispatch_del_");


        if (isAdminCommand) {
            // Check if user is actually an admin BEFORE processing the command
            if (!adminCommandHandler.isAdmin(phone)) {
                logger.warn("Non-admin {} tried to use admin command: {}", PhoneNumberUtil.maskPhoneNumber(phone), txt);
                whatsappService.sendSafeText(phone, "❌ רק מנהלים יכולים להשתמש בפקודה זו");
                return null;
            }

            // User is admin - handle the command
            String adminResponse = adminCommandHandler.handleAdminCommand(phone, txt, whatsappService);
            return adminResponse; // May be null if message was sent via WhatsApp
        }

        // ===== CHECK BOT STATUS FOR NON-ADMINS BEFORE ANYTHING ELSE =====
        // If bot is inactive AND user is not an admin, reject the message immediately
        if (!botConfigService.isBotActive() && !adminCommandHandler.isAdmin(phone)) {
            logger.warn("Bot is inactive - rejecting message from {}", phone);
            return adminCommandHandler.getBotInactiveMessage();
        }

        // Customer cancelling an order
        if (txt.startsWith("taxi_cancel_customer_")) {
            try {
                long orderId = Long.parseLong(txt.replace("taxi_cancel_customer_", ""));
                return taxiOrderService.cancelOrderByCustomer(orderId, phone);
            } catch (NumberFormatException e) {
                logger.warn("Invalid taxi cancel button payload: {}", txt);
                return null;
            }
        }

        // Business owner cancelling a delivery order
        if (txt.startsWith("delivery_cancel_business_")) {
            try {
                long orderId = Long.parseLong(txt.replace("delivery_cancel_business_", ""));
                return deliveryOrderService.cancelDeliveryOrderByBusiness(orderId, phone);
            } catch (NumberFormatException e) {
                logger.warn("Invalid delivery cancel button payload: {}", txt);
                return null;
            }
        }

        // ===== RATING — customer tapped a star in the post-ride list =====
        // Format: rate_taxi_{orderId}_{stars}
        if (txt.startsWith("rate_taxi_")) {
            return handleTaxiRatingSelection(convo, phone, txt);
        }

        // ===== RATING — customer tapped "Skip" on the comment prompt =====
        // Format: rate_skip_comment_{ratingId}
        if (txt.startsWith("rate_skip_comment_")) {
            return handleRatingCommentSkip(convo, phone, txt);
        }

        // Reset conversation if user sends "00" or "התחל מחדש"
        if (txt.equals("00") || txt.equals("התחל מחדש")) {
            logger.info("Reset signal received: '{}'", txt);
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            convo.setNudgedAt(0);
            convoService.save(convo);
            return "🔄 איפוס משתמש. בואו נתחיל מחדש! 🚀";
        }

        // ===== TIMEOUT CHECK — reset mid-flow conversations idle for 30+ minutes =====
        boolean isMidFlow = state != ConversationState.START
                && state != ConversationState.START_MENU
                && state != ConversationState.AWAITING_DRIVER_LOCATION;

        if (isMidFlow) {
            long idleMs = System.currentTimeMillis() - convo.getLastMessageTime();
            if (idleMs > ConversationService.CONVERSATION_TIMEOUT_MINUTES * 60 * 1000) {
                logger.info("Conversation timed out for {} (idle {}min, state {})", PhoneNumberUtil.maskPhoneNumber(phone), idleMs / 60000, state);
                convoService.updateState(convo, ConversationState.START);
                convoService.saveTempData(convo, "");
                convo.setNudgedAt(0);
                convoService.updateLastMessageTime(convo);
                convoService.save(convo);
                return """
                        ⏱️ עבר קצת זמן, אז איפסנו את השיחה
                        שלח כל הודעה כדי להתחיל מחדש 🚀
                        """;
            }
        }

        // Update 24h window on every inbound message
        convoService.updateLastMessageTime(convo);

        // ===== AWAITING_RATING_COMMENT — customer typing a comment after low rating =====
        if (state == ConversationState.AWAITING_RATING_COMMENT) {
            return handleRatingCommentText(convo, phone, txt);
        }

        // ===== START STATE =====
        if (state == ConversationState.START) {
            logger.info("In START state - determining user type");

            // An unambiguous driver command (shift toggle / order action) can never be a
            // customer name or a business-menu selection - used below to safely bypass
            // both the business-owner check and the WELCOME_SENT bridge guard.
            boolean isUnambiguousDriverCommand = txt.startsWith("taxi_claim_") || txt.startsWith("delivery_claim_") ||
                    txt.startsWith("taxi_arrived_") || txt.startsWith("taxi_complete_") || txt.startsWith("taxi_cancel_driver_") ||
                    txt.startsWith("delivery_pickup_") || txt.startsWith("delivery_complete_") ||
                    txt.equals("driver_show_route") ||
                    txt.startsWith("איסוף ") || txt.startsWith("נמסר ") || txt.startsWith("בדרך ") ||
                    txt.equals("התחל משמרת") || txt.equals("driver_start_shift") ||
                    txt.equals("סיים משמרת") || txt.equals("driver_end_shift");

            // Check if user is a business owner FIRST (before checking driver) -
            // unless they just sent an unambiguous driver command, so dual-role
            // driver+business accounts aren't swallowed by the business menu
            if (businessOwnerService.isBusinessOwner(phone) && !isUnambiguousDriverCommand) {
                logger.info("User is a BUSINESS OWNER - showing business menu");
                businessHandler.showBusinessMenuButtons(phone);
                convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                return null;
            }


            // If a driver was already bridged into customer name-capture (WELCOME_SENT),
            // skip the driver lookup so their name isn't swallowed by DriverConversationHandler -
            // unless this message is itself an unambiguous driver command, which can't be a name
            Driver driver = ("WELCOME_SENT".equals(convo.getTempData()) && !isUnambiguousDriverCommand)
                    ? null : driverService.findByPhone(phone);
            if (driver == null && "WELCOME_SENT".equals(convo.getTempData())) {
                logger.info("Customer name-capture in progress (WELCOME_SENT) - skipping driver lookup");
            } else {
                logger.info("Driver lookup for {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), (driver != null ? "FOUND" : "NOT FOUND"));
            }
            if (driver != null) {
                logger.info("User is a DRIVER (active={}, showing driver menu)", driver.isActive());

                if (txt.startsWith("taxi_claim_") || txt.startsWith("delivery_claim_") ||
                        txt.startsWith("taxi_arrived_") || txt.startsWith("taxi_complete_") || txt.startsWith("taxi_cancel_driver_") ||
                        txt.startsWith("delivery_pickup_") || txt.startsWith("delivery_delivering_") || txt.startsWith("delivery_complete_") ||
                        txt.equals("driver_show_route") ||
                        txt.startsWith("איסוף ") || txt.startsWith("נמסר ") ||
                        txt.startsWith("בדרך ")) {
                    logger.info(">>> DRIVER ORDER COMMAND DETECTED <<<");
                    logger.info(">>> Message: '{}' starts with order prefix? checking:", txt);
                    logger.info(">>> taxi_claim_? {}", txt.startsWith("taxi_claim_"));
                    logger.info(">>> delivery_claim_? {}", txt.startsWith("delivery_claim_"));
                    logger.info(">>> taxi_complete_? {}", txt.startsWith("taxi_complete_"));
                    logger.info(">>> אישור? {}", txt.startsWith("אישור"));
                    logger.info(">>> Routing to DriverHandler regardless of shift state");
                    String driverResponse = driverHandler.handleMessage(convo, message);
                    if (driverResponse != null) {
                        return driverResponse;
                    }
                    return null;
                }

                // Check if trying to start or end shift - ALWAYS route to handler regardless of tempData state
                if (txt.equals("התחל משמרת") || txt.equals("driver_start_shift") ||
                        txt.equals("סיים משמרת") || txt.equals("driver_end_shift")) {
                    logger.info("Driver attempting to start/end shift, routing to DriverHandler");
                    String driverResponse = driverHandler.handleMessage(convo, message);
                    if (driverResponse != null) {
                        return driverResponse;
                    }
                    return null;
                }
            }

            // Check if user is a business owner AFTER checking driver
            // (Don't capture customer name if they're actually a business owner)
            if (businessOwnerService.isBusinessOwner(phone)) {
                logger.info("User is a BUSINESS OWNER - showing business menu");
                businessHandler.showBusinessMenuButtons(phone);
                convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                return null;
            }

            // Regular customer in START state
            logger.info("User is a CUSTOMER in START state");

// Check if this is a returning customer (already in DB with a name)
            Customer existingCustomer = customerService.getCustomer(phone);
            if (existingCustomer != null && existingCustomer.getName() != null && !existingCustomer.getName().isEmpty()) {
                String name = existingCustomer.getName();
                logger.info("Returning customer '{}' - skipping name capture", name);
                convoService.saveTempData(convo, name);
                convoService.updateState(convo, ConversationState.START_MENU);
                whatsappService.sendSafeText(phone, "ברוך שובך " + name + "! 👋\nשמחים לראות אותך שוב ב-RYZ ⚡");
                showServiceMenu(phone, name);
                return null;
            }

// New customer - check if we already sent welcome
            if (convo.getTempData() == null || convo.getTempData().isEmpty()) {
                logger.info("Sending welcome message, will capture name on next message");
                whatsappService.sendSafeText(phone, WELCOME_MESSAGE);
                convoService.saveTempData(convo, "WELCOME_SENT");
                return null;
            }

// Welcome was already sent, now capture the name
            logger.info("Welcome already sent, capturing name: '{}'", txt);
            String name = txt;
            convoService.saveTempData(convo, name);
            customerService.registerNewCustomer(phone, name);
            convoService.updateState(convo, ConversationState.START_MENU);

// Show service menu with customer's name
            logger.info("Showing service menu for customer: {}", name);
            showServiceMenu(phone, name);
            return null;
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
        // Customer has entered name and is seeing the menu
        if (state == ConversationState.START_MENU) {
            logger.info("In START_MENU state");

            if (txt.equals("start_service_taxi")) {
                logger.info("Customer selected TAXI service");

                // Block up front: a customer may only have one active order at a time
                if (taxiOrderService.hasActiveOrder(phone)) {
                    logger.info("Customer {} tried to start a taxi order but already has an active one", PhoneNumberUtil.maskPhoneNumber(phone));
                    return "⚠️ יש לך כבר הזמנה פעילה במערכת.\nניתן לבצע הזמנה אחת בכל פעם — אנא המתן לסיום ההזמנה הנוכחית לפני שתבצע חדשה 🚕";
                }

                convoService.updateState(convo, ConversationState.TAXI_CAR_TYPE);

                String bodyText = "מעולה 👍\nעכשיו בחרו את סוג הרכב:";
                whatsappService.sendInteractiveButtonsSafe(
                        phone,
                        bodyText,
//                        new WhatsappService.InteractiveButton("taxi_car_type_motorcycle", "אופנוע 🏍️"),
                        new WhatsappService.InteractiveButton("taxi_car_type_private_car", "מכונית 🚗"),
                        new WhatsappService.InteractiveButton("taxi_car_type_minivan", "רכב גדול +6 🚐")
                );
                return null; // Buttons already sent
            }

            // Invalid choice - ask them to use the menu
            logger.warn("Invalid menu choice in START_MENU: '{}' - showing menu again", txt);
            showServiceMenu(phone, convo.getTempData() != null ? convo.getTempData() : "");
            return null;
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
                state == ConversationState.AWAITING_PICKUP_SELECTION ||
                state == ConversationState.TAXI_DESTINATION ||
                state == ConversationState.AWAITING_DESTINATION_SELECTION ||
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
                state == ConversationState.DELIVERY_AWAITING_CUSTOMER_CONFIRM ||
                state == ConversationState.DELIVERY_CUSTOMER_NAME ||
                state == ConversationState.DELIVERY_ADDRESS ||
                state == ConversationState.AWAITING_DELIVERY_ADDRESS_SELECTION ||
                state == ConversationState.DELIVERY_READY_TIME ||
                state == ConversationState.DELIVERY_PRICE ||
                state == ConversationState.DELIVERY_NOTES ||
                state == ConversationState.DELIVERY_AWAITING_CONFIRMATION) {

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
        String displayName = (name != null && !name.isEmpty()) ? name : "ידידי";
        String message = "אז מה בא לך היום?";

        try {
            logger.info("Sending service menu to {}: {}", PhoneNumberUtil.maskPhoneNumber(phone), message);
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

    /**
     * Show driver welcome menu with start/end shift buttons
     */
    private void sendDriverWelcomeMenu(String phone) {
        try {
            logger.info("Sending driver welcome menu to {}", phone);
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    DRIVER_WELCOME_MESSAGE,
                    new WhatsappService.InteractiveButton("driver_start_shift", "🟢 התחל משמרת"),
                    new WhatsappService.InteractiveButton("driver_end_shift", "🔴 סיים משמרת")
            );
            logger.info("Driver welcome menu sent successfully");
        } catch (Exception e) {
            logger.error("Error sending driver welcome menu: {}", e.getMessage(), e);
            whatsappService.sendSafeText(phone, DRIVER_WELCOME_MESSAGE);
        }
    }

    // =========================================================
    // RATING FLOW HELPERS (taxi)
    // =========================================================

    /**
     * Handle a customer's star selection from the post-ride list message.
     * Payload format: rate_taxi_{orderId}_{stars}
     * - Creates the Rating row (idempotent via orderId+orderType uniqueness check)
     * - On stars <= 3: prompts for an optional comment (with Skip button) and sets
     *   state AWAITING_RATING_COMMENT, preserving the customer's name in tempData
     *   via format "RATING_COMMENT:{ratingId}:{originalTempData}".
     * - On stars >= 4: thanks the customer and returns.
     */
    private String handleTaxiRatingSelection(Conversation convo, String phone, String txt) {
        try {
            String payload = txt.substring("rate_taxi_".length()); // e.g. "42_5"
            int sep = payload.lastIndexOf('_');
            if (sep <= 0) {
                logger.warn("Malformed rate_taxi payload: {}", txt);
                return null;
            }
            long orderId = Long.parseLong(payload.substring(0, sep));
            int stars = Integer.parseInt(payload.substring(sep + 1));

            com.example.yanivbot.Entities.TaxiOrder order =
                    taxiOrderService.findByIdForRating(orderId);
            if (order == null) {
                logger.warn("rate_taxi: order #{} not found", orderId);
                return null;
            }
            if (!phone.equals(order.getPhone())) {
                logger.warn("rate_taxi: phone {} does not match order #{} customer",
                        PhoneNumberUtil.maskPhoneNumber(phone), orderId);
                return null;
            }
            if (order.getDriverPhone() == null) {
                logger.warn("rate_taxi: order #{} has no driver assigned", orderId);
                return null;
            }

            com.example.yanivbot.Entities.Rating rating = ratingService.createRating(
                    order.getDriverPhone(), phone, orderId,
                    RatingService.ORDER_TYPE_TAXI, stars, null);

            if (rating == null) {
                // Duplicate or invalid — silent ack
                return "תודה! כבר קיבלנו את הדירוג שלך עבור נסיעה זו 🙏";
            }

            if (stars <= 3) {
                // Preserve any existing tempData (typically customer name) so we can restore it
                String original = convo.getTempData() == null ? "" : convo.getTempData();
                convoService.saveTempData(convo, "RATING_COMMENT:" + rating.getId() + ":" + original);
                convoService.updateState(convo, ConversationState.AWAITING_RATING_COMMENT);

                whatsappService.sendInteractiveButtonsSafe(
                        phone,
                        "תודה על הדירוג 🙏\nרוצה לספר לנו מה לא היה בסדר? (אופציונלי)",
                        new WhatsappService.InteractiveButton("rate_skip_comment_" + rating.getId(), "⏭️ דלג")
                );
                return null;
            } else {
                return "תודה רבה על הדירוג! נשמח לראות אותך שוב ב־RYZ 💙";
            }
        } catch (NumberFormatException e) {
            logger.warn("Invalid rate_taxi payload: {}", txt);
            return null;
        } catch (Exception e) {
            logger.error("Error handling rate_taxi: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Customer typed free text while in AWAITING_RATING_COMMENT.
     * tempData format: "RATING_COMMENT:{ratingId}:{originalTempData}"
     */
    private String handleRatingCommentText(Conversation convo, String phone, String txt) {
        try {
            String temp = convo.getTempData() == null ? "" : convo.getTempData();
            if (!temp.startsWith("RATING_COMMENT:")) {
                logger.warn("AWAITING_RATING_COMMENT but tempData malformed: '{}' — resetting", temp);
                convoService.updateState(convo, ConversationState.START);
                convoService.saveTempData(convo, "");
                return null;
            }
            String rest = temp.substring("RATING_COMMENT:".length()); // "{ratingId}:{original}"
            int colon = rest.indexOf(':');
            if (colon < 0) {
                logger.warn("AWAITING_RATING_COMMENT tempData missing ':' separator: '{}'", temp);
                convoService.updateState(convo, ConversationState.START);
                convoService.saveTempData(convo, "");
                return null;
            }
            long ratingId = Long.parseLong(rest.substring(0, colon));
            String original = rest.substring(colon + 1);

            // Truncate to a sane comment length to protect DB
            String comment = txt.length() > 1000 ? txt.substring(0, 1000) : txt;
            ratingService.addComment(ratingId, comment);

            // Restore prior tempData (usually customer name) and reset state
            convoService.saveTempData(convo, original);
            convoService.updateState(convo, ConversationState.START);

            return "תודה רבה על המשוב! נשתמש בו כדי להשתפר 💙";
        } catch (Exception e) {
            logger.error("Error handling rating comment text: {}", e.getMessage(), e);
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            return null;
        }
    }

    /**
     * Customer tapped "Skip" on the comment prompt.
     * Payload format: rate_skip_comment_{ratingId}
     */
    private String handleRatingCommentSkip(Conversation convo, String phone, String txt) {
        try {
            String temp = convo.getTempData() == null ? "" : convo.getTempData();
            String original = "";
            if (temp.startsWith("RATING_COMMENT:")) {
                String rest = temp.substring("RATING_COMMENT:".length());
                int colon = rest.indexOf(':');
                if (colon >= 0) {
                    original = rest.substring(colon + 1);
                }
            }
            convoService.saveTempData(convo, original);
            convoService.updateState(convo, ConversationState.START);
            return "תודה רבה! 💙";
        } catch (Exception e) {
            logger.error("Error handling rating comment skip: {}", e.getMessage(), e);
            convoService.updateState(convo, ConversationState.START);
            convoService.saveTempData(convo, "");
            return null;
        }
    }
}

  
