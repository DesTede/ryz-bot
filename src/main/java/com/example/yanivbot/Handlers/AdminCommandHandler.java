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
     * Handle admin commands (כבה בוט, הפעל בוט)
     * Returns response message, or null if not an admin command
     */
    public String handleAdminCommand(String phone, String message, WhatsappService whatsappService) {
        if (!isAdmin(phone)) {
            return null; // Not an admin
        }

        String txt = message.trim();

        if (txt.equals("כבה בוט")) {
            return handleBotOff(phone, whatsappService);
        }

        if (txt.equals("הפעל בוט")) {
            return handleBotOn(phone, whatsappService);
        }

        return null; // Not an admin command
    }

    /**
     * Handle "כבה בוט" - turn bot off
     */
    private String handleBotOff(String phone, WhatsappService whatsappService) {
        if (!botConfigService.isBotActive()) {
            // Bot already off
            logger.info("Admin {} tried to turn off bot, but it's already off", phone);
            // Send button to turn back on
            whatsappService.sendInteractiveButtonsSafe(
                    phone,
                    "שכחת שהבוט לא פעיל חבר? להפעלה לחץ 👇",
                    new WhatsappService.InteractiveButton("hepel_bot", "הפעל בוט")
            );
            return null;
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
            return "הבוט כבר פעיל וזמין ללקוחות ומשתמשים 🟢";
        }

        // Turn on bot
        botConfigService.setBotActive(true);
        logger.warn("Admin {} turned ON the bot", phone);

        // Notify all admins
        String adminMessage = "🟢 בוט פעיל וזמין ללקוחות ומשתמשים\n✅ השירות חזר לעבודה";
        notifyAllAdmins(adminMessage, whatsappService);

        return null; // Message already sent
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