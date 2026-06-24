package com.example.yanivbot.Handlers;

import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Services.BotConfigService;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.WhatsappService;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AdminCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdminCommandHandler.class);

    private final BotConfigService botConfigService;
    private final WhatsappService whatsappService;
    private final ConversationService convoService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;

    @Value("${admin.phones}")
    private String adminPhones;

    public AdminCommandHandler(BotConfigService botConfigService, WhatsappService whatsappService, ConversationService convoService, TaxiOrderRepository taxiOrderRepo, DeliveryOrderRepository deliveryOrderRepo) {
        this.botConfigService = botConfigService;
        this.whatsappService = whatsappService;
        this.convoService = convoService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
    }

    /**
     * Check if user is an admin
     */
    public boolean isAdmin(String phone) {
        if (adminPhones == null || adminPhones.isEmpty()) {
            return false;
        }
        String[] phones = adminPhones.split(",");
        for (String adminPhone : phones) {
            if (adminPhone.trim().equals(phone)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle admin commands (כבה בוט, הפעל בוט, hepel_bot, kahah_bot)
     * Returns response message, or null if not an admin command
     */
    public String handleAdminCommand(String phone, String message, WhatsappService whatsappService) {
        if (!isAdmin(phone)) {
            return null; // Not an admin
        }

        String txt = message.trim();

        // Turn OFF bot (text or button)
        if (txt.equals("כבה בוט") || txt.equals("kahah_bot")) {
            return handleBotOff(phone, whatsappService);
        }

        // Turn ON bot (text or button)
        if (txt.equals("הפעל בוט") || txt.equals("hepel_bot")) {
            return handleBotOn(phone, whatsappService);
        }
        if (txt.startsWith("stop_redispatch_taxi_")) {
            return handleStopTaxiRedispatch(txt);
        }

        if (txt.startsWith("stop_redispatch_del_")) {
            return handleStopDeliveryRedispatch(txt);
        }

        return null; // Not an admin command
    }

    /**
     * Handle "כבה בוט" - turn bot off
     */

    private String handleBotOff(String phone, WhatsappService whatsappService) {
        if (!botConfigService.isBotActive()) {
            // Bot already off - send button to turn back on
            logger.info("Admin {} tried to turn off bot, but it's already off", PhoneNumberUtil.maskPhoneNumber(phone));
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    "🔴 שכחת שהבוט לא פעיל חבר?",
                    new WhatsappService.InteractiveButton("hepel_bot", "הפעל בוט")
            );
            return null; // Message already sent via button
        }

        // Turn off bot
        botConfigService.setBotActive(false);
        logger.warn("Admin {} turned OFF the bot", PhoneNumberUtil.maskPhoneNumber(phone));

        // Notify all admins using smart method (instead of notifyAllAdmins)
        String adminMessage = """
                🔴 מצב: OFF (כבוי)
                כיבינו אותו ידנית, אף אחד לא מקבל הזמנות כרגע \uD83D\uDE34
                להפעלה מחדש - שלח "הפעל בוט\"""";
        whatsappService.notifyAdminsSmartMessage(
                adminMessage,
                "bot_status_off_admin",
                List.of("OFF"),
                convoService
        );

        return null; // Message already sent
    }
    
    /**
     * Handle "הפעל בוט" - turn bot on
     */
    private String handleBotOn(String phone, WhatsappService whatsappService) {
        if (botConfigService.isBotActive()) {
            // Bot already on
            logger.info("Admin {} tried to turn on bot, but it's already on", PhoneNumberUtil.maskPhoneNumber(phone));
            return "🟢 הבוט כבר פעיל חבר 😄";
        }

        // Turn on bot
        botConfigService.setBotActive(true);
        logger.warn("Admin {} turned ON the bot", PhoneNumberUtil.maskPhoneNumber(phone));

        // Notify all admins using smart method (instead of notifyAllAdmins)
        String adminMessage = """
            🚀 *אנחנו שוב באוויר!*
            -------------------------
            🟢 הבוט פעיל, יציב וזמין לכולם.
            ✅ השירות חזר לעבודה כרגיל. RYZ 💙""";
        whatsappService.notifyAdminsSmartMessage(
                adminMessage,
                "bot_status_on_admin",
                List.of("ON"),
                convoService
        );

        return null; // Message already sent via notifyAdminsSmartMessage
    }
    

    /**
     * Get bot status message for users when bot is off
     */
    public String getBotInactiveMessage() {
        return "🔴 הבוט אינו פעיל כרגע\nאנא נסה שוב מאוחר יותר\n\nתודה על הסבלנות 💙";
    }

    private String handleStopTaxiRedispatch(String buttonId) {
        try {
            long orderId = Long.parseLong(buttonId.replace("stop_redispatch_taxi_", ""));
            return taxiOrderRepo.findById(orderId).map(order -> {
                order.setRedispatchStopped(true);
                taxiOrderRepo.save(order);
                logger.info("Admin stopped redispatch for taxi order #{}", orderId);
                return "✅ הפסקנו לשלוח הזמנת מונית #" + orderId + " לנהגים";
            }).orElse("❌ לא מצאנו הזמנת מונית #" + orderId);
        } catch (NumberFormatException e) {
            logger.error("Invalid order ID in button: {}", buttonId);
            return "❌ משהו השתבש, נסה שוב";
        }
    }

    private String handleStopDeliveryRedispatch(String buttonId) {
        try {
            long orderId = Long.parseLong(buttonId.replace("stop_redispatch_del_", ""));
            return deliveryOrderRepo.findById(orderId).map(order -> {
                order.setRedispatchStopped(true);
                deliveryOrderRepo.save(order);
                logger.info("Admin stopped redispatch for delivery order #{}", orderId);
                return "✅ הפסקנו לשלוח משלוח #" + orderId + " לנהגים";
            }).orElse("❌ לא מצאנו משלוח #" + orderId);
        } catch (NumberFormatException e) {
            logger.error("Invalid order ID in button: {}", buttonId);
            return "❌ משהו השתבש, נסה שוב";
        }
    }
}