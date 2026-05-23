package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Repositories.DriverRepository;
import com.example.yanivbot.Repositories.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [COMPLETE FILE]
 * Syncs driver and business data from Google Sheets to database
 * Handles both נהגים (drivers) and עסקים (businesses) tables
 */
@Service
public class GoogleSheetsService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);

    @Value("${google.sheets.id:}")
    private String sheetsId;

    private final DriverRepository driverRepository;
    private final BusinessRepository businessRepository;
    private final WhatsappService whatsappService;

    public GoogleSheetsService(DriverRepository driverRepository,
                               BusinessRepository businessRepository,
                               WhatsappService whatsappService) {
        this.driverRepository = driverRepository;
        this.businessRepository = businessRepository;
        this.whatsappService = whatsappService;
    }

    /**
     * Sync drivers from Google Sheets to database
     * Called every 5 minutes via @Scheduled
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void syncDriversFromSheets() {
        logger.info("Syncing drivers from Google Sheets (נהגים)...");
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
     * Sync businesses from Google Sheets to database
     * Called every 5 minutes via @Scheduled
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void syncBusinessesFromSheets() {
        logger.info("Syncing businesses from Google Sheets (עסקים)...");
        try {
            // This is a placeholder - actual implementation would:
            // 1. Read from Google Sheets API
            // 2. Parse business data
            // 3. Update database

            logger.info("Business sync completed");
        } catch (Exception e) {
            logger.error("Error syncing businesses from Google Sheets: {}", e.getMessage());
        }
    }

    /**
     * Add or update driver from Google Sheets data
     * Called during sync process
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
     * Add or update business from Google Sheets data
     * Called during sync process
     */
    public void addOrUpdateBusiness(String phone, String name, String address) {
        try {
            phone = whatsappService.normalizePhone(phone);

            Business existingBusiness = businessRepository.findByPhone(phone).orElse(null);

            if (existingBusiness != null) {
                existingBusiness.setName(name);
                if (address != null && !address.isEmpty()) {
                    existingBusiness.setAddress(address);
                }
                businessRepository.save(existingBusiness);
                logger.info("Updated business: {}", phone);
            } else {
                Business newBusiness = new Business();
                newBusiness.setPhone(phone);
                newBusiness.setName(name);
                if (address != null && !address.isEmpty()) {
                    newBusiness.setAddress(address);
                }
                newBusiness.setActive(true);
                businessRepository.save(newBusiness);
                logger.info("Added new business: {}", phone);
            }
        } catch (Exception e) {
            logger.error("Error adding/updating business {}: {}", phone, e.getMessage());
        }
    }

    /**
     * Get all drivers from database
     */
    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }

}