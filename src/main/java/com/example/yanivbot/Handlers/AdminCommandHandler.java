package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.BotConfigService;
import com.example.yanivbot.Services.WhatsappService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdminCommandHandler.class);

    private final BotConfigService botConfigService;
    private final WhatsappService whatsappService;

    @Value("${admin.phones}")
    private String adminPhones;

    public AdminCommandHandler(BotConfigService botConfigService, WhatsappService whatsappService) {
        this.botConfigService = botConfigService;
        this.whatsappService = whatsappService;
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

        return null; // Not an admin command
    }

    /**
     * Handle "כבה בוט" - turn bot off
     */
    private String handleBotOff(String phone, WhatsappService whatsappService) {
        if (!botConfigService.isBotActive()) {
            // Bot already off - send button to turn back on
            logger.info("Admin {} tried to turn off bot, but it's already off", phone);
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    "שכחת שהבוט לא פעיל חבר? להפעלה לחץ 👇",
                    new WhatsappService.InteractiveButton("hepel_bot", "הפעל בוט")
            );
            return null; // Message already sent via button
        }

        // Turn off bot
        botConfigService.setBotActive(false);
        logger.warn("Admin {} turned OFF the bot", phone);

        // Notify all admins
        String adminMessage = "🔴 הבוט כבוי ולא זמין ללקוחות ומשתמשים\n⚠️ מנהל הפסיק את השירות";
        notifyAllAdmins(adminMessage, whatsappService);

        return null; // Message already sent
    }

    /**
     * Handle "הפעל בוט" - turn bot on
     */
    private String handleBotOn(String phone, WhatsappService whatsappService) {
        if (botConfigService.isBotActive()) {
            // Bot already on
            logger.info("Admin {} tried to turn on bot, but it's already on", phone);
            return """
                    \uD83D\uDE80 אנחנו שוב באוויר!
                    \uD83D\uDFE2 הבוט פעיל, יציב וזמין לכולם.
                    ✅ השירות חזר לעבודה כרגיל.""";
        }

        // Turn on bot
        botConfigService.setBotActive(true);
        logger.warn("Admin {} turned ON the bot", phone);

        // Notify all admins
        String adminMessage = """
                🚀 *אנחנו שוב באוויר!*
                -------------------------
                🟢 הבוט פעיל, יציב וזמין לכולם.
                ✅ השירות חזר לעבודה כרגיל. Movez 💙""";
        notifyAllAdmins(adminMessage, whatsappService);

        return null; // Message already sent via notifyAllAdmins
    }

    /**
     * Notify all admins with a message
     */
    private void notifyAllAdmins(String message, WhatsappService whatsappService) {
        if (adminPhones == null || adminPhones.isEmpty()) {
            logger.warn("No admin phones configured");
            return;
        }

        String[] phones = adminPhones.split(",");
        for (String p : phones) {
            p = p.trim();
            if (!p.isEmpty()) {
                logger.info("Notifying admin: {}", p);
                whatsappService.sendSafeText(p, message);
            }
        }
    }

    /**
     * Get bot status message for users when bot is off
     */
    public String getBotInactiveMessage() {
        return "🔴 הבוט אינו פעיל כרגע\nאנא נסה שוב מאוחר יותר\n\nתודה על הסבלנות שלך 💙";
    }
}