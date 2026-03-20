package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.BotConfig;
import com.example.yanivbot.Repositories.BotConfigRepository;
import org.springframework.stereotype.Service;

@Service
public class BotConfigService {

    private final BotConfigRepository botConfigRepo;

    public BotConfigService(BotConfigRepository botConfigRepo) {
        this.botConfigRepo = botConfigRepo;
    }

    public boolean isBotActive() {
        return botConfigRepo.findById("bot.active")
                .map(config -> config.getValue().equals("true"))
                .orElse(true); // default to active
    }

    public void setBotActive(boolean active) {
        BotConfig config = botConfigRepo.findById("bot.active")
                .orElse(new BotConfig("bot.active", "true"));
        config.setValue(active ? "true" : "false");
        botConfigRepo.save(config);
    }
}
