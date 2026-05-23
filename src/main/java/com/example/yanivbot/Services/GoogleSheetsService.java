package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Repositories.DriverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GoogleSheetsService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);

    @Value("${google.sheets.id:}")
    private String sheetsId;

    private final DriverRepository driverRepository;
    private final WhatsappService whatsappService;

    public GoogleSheetsService(DriverRepository driverRepository, WhatsappService whatsappService) {
        this.driverRepository = driverRepository;
        this.whatsappService = whatsappService;
    }

    /**
     * Sync drivers from Google Sheets to database
     * Called every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void syncDriversFromSheets() {
        logger.info("Syncing drivers from Google Sheets...");
        try {
            // This is a placeholder - actual implementation would:
            // 1. Read from Google Sheets API
            // 2. Parse driver data
            // 3. Update database

            logger.info("Driver sync completed");
        } catch (Exception e) {
            logger.error("Error syncing drivers from Google Sheets: {}", e.getMessage());
        }
    }

    /**
     * Add or update driver from Google Sheets data
     */
    public void addOrUpdateDriver(String phone, String name, String driverType, String carType, String carColor, String carModel) {
        try {
            phone = whatsappService.normalizePhone(phone);

            Driver existingDriver = driverRepository.findDriverByPhone(phone).orElse(null);

            DriverType type = DriverType.valueOf(driverType.toUpperCase());
            CarType carTypeEnum = null;

            try {
                carTypeEnum = CarType.valueOf(carType.toUpperCase());
            } catch (Exception e) {
                logger.warn("Invalid car type: {}", carType);
            }

            if (existingDriver != null) {
                existingDriver.setName(name);
                existingDriver.setType(type);
                if (carTypeEnum != null) {
                    existingDriver.setCarType(carTypeEnum);
                }
                if (carColor != null && !carColor.isEmpty()) {
                    existingDriver.setCarColor(carColor);
                }
                if (carModel != null && !carModel.isEmpty()) {
                    existingDriver.setCarModel(carModel);
                }
                driverRepository.save(existingDriver);
                logger.info("Updated driver: {}", phone);
            } else {
                Driver newDriver = new Driver(phone, name, false, type);
                if (carTypeEnum != null) {
                    newDriver.setCarType(carTypeEnum);
                }
                if (carColor != null && !carColor.isEmpty()) {
                    newDriver.setCarColor(carColor);
                }
                if (carModel != null && !carModel.isEmpty()) {
                    newDriver.setCarModel(carModel);
                }
                driverRepository.save(newDriver);
                logger.info("Added new driver: {}", phone);
            }
        } catch (Exception e) {
            logger.error("Error adding/updating driver {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Get all drivers from databaseץ
     */
    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }

}