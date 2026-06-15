package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.BotConfig;
import com.example.yanivbot.Repositories.BotConfigRepository;
import org.springframework.stereotype.Service;

@Service
public class BotConfigService {

    private final BotConfigRepository botConfigRepo;

    public static final double DEFAULT_TAXI_BASE_PRICE = 15.0;
    public static final double DEFAULT_TAXI_PRICE_PER_KM = 4.5;
    public static final double DEFAULT_TAXI_PRICE_PER_MINUTE = 1.5;
    public static final double DEFAULT_TAXI_VAT = 0.18;

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

    public double getTaxiBasePrice() {
        return botConfigRepo.findById("taxi.base.price")
                .map(c -> Double.parseDouble(c.getValue()))
                .orElse(DEFAULT_TAXI_BASE_PRICE);
    }

    public double getTaxiPricePerKm() {
        return botConfigRepo.findById("taxi.price.per.km")
                .map(c -> Double.parseDouble(c.getValue()))
                .orElse(DEFAULT_TAXI_PRICE_PER_KM);
    }

    public double getTaxiPricePerMinute() {
        return botConfigRepo.findById("taxi.price.per.minute")
                .map(c -> Double.parseDouble(c.getValue()))
                .orElse(DEFAULT_TAXI_PRICE_PER_MINUTE);
    }

    public double getTaxiVat() {
        return botConfigRepo.findById("taxi.vat")
                .map(c -> Double.parseDouble(c.getValue()))
                .orElse(DEFAULT_TAXI_VAT);
    }

    public void setTaxiBasePrice(double price) {
        BotConfig config = botConfigRepo.findById("taxi.base.price")
                .orElse(new BotConfig("taxi.base.price", String.valueOf(DEFAULT_TAXI_BASE_PRICE)));
        config.setValue(String.valueOf(price));
        botConfigRepo.save(config);
    }

    public void setTaxiPricePerKm(double price) {
        BotConfig config = botConfigRepo.findById("taxi.price.per.km")
                .orElse(new BotConfig("taxi.price.per.km", String.valueOf(DEFAULT_TAXI_PRICE_PER_KM)));
        config.setValue(String.valueOf(price));
        botConfigRepo.save(config);
    }

    public void setTaxiPricePerMinute(double price) {
        BotConfig config = botConfigRepo.findById("taxi.price.per.minute")
                .orElse(new BotConfig("taxi.price.per.minute", String.valueOf(DEFAULT_TAXI_PRICE_PER_MINUTE)));
        config.setValue(String.valueOf(price));
        botConfigRepo.save(config);
    }

    public void setTaxiVat(double vat) {
        BotConfig config = botConfigRepo.findById("taxi.vat")
                .orElse(new BotConfig("taxi.vat", String.valueOf(DEFAULT_TAXI_VAT)));
        config.setValue(String.valueOf(vat));
        botConfigRepo.save(config);
    }
}
