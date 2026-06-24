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

    public static final int DEFAULT_MAX_EXTRA_DELIVERY_MINUTES = 30;


    public BotConfigService(BotConfigRepository botConfigRepo) {
        this.botConfigRepo = botConfigRepo;
    }

    private double parseDoubleOrDefault(String key, double defaultValue) {
        return botConfigRepo.findById(key)
                .map(c -> {
                    try {
                        return Double.parseDouble(c.getValue());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    private int parseIntOrDefault(String key, int defaultValue) {
        return botConfigRepo.findById(key)
                .map(c -> {
                    try {
                        return Integer.parseInt(c.getValue());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
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
        return parseDoubleOrDefault("taxi.base.price", DEFAULT_TAXI_BASE_PRICE);
    }

    public double getTaxiPricePerKm() {
        return parseDoubleOrDefault("taxi.price.per.km", DEFAULT_TAXI_PRICE_PER_KM);
    }

    public double getTaxiPricePerMinute() {
        return parseDoubleOrDefault("taxi.price.per.minute", DEFAULT_TAXI_PRICE_PER_MINUTE);
    }

    public double getTaxiVat() {
        return parseDoubleOrDefault("taxi.vat", DEFAULT_TAXI_VAT);
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

    public int getMaxExtraDeliveryMinutes() {
        return parseIntOrDefault("delivery.max.extra.minutes", DEFAULT_MAX_EXTRA_DELIVERY_MINUTES);
    }

    public void setMaxExtraDeliveryMinutes(int minutes) {
        BotConfig config = botConfigRepo.findById("delivery.max.extra.minutes")
                .orElse(new BotConfig("delivery.max.extra.minutes", String.valueOf(DEFAULT_MAX_EXTRA_DELIVERY_MINUTES)));
        config.setValue(String.valueOf(minutes));
        botConfigRepo.save(config);
    }
}

