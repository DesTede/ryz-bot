package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DriverRepository;
import com.example.yanivbot.Repositories.BusinessRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [COMPLETE FILE]
 * Syncs driver and business data from Google Sheets to database
 * Reads from Google Sheets API and keeps database in sync
 *
 * Syncs:
 * - נהגים (drivers) table
 * - עסקים (businesses) table
 *
 * Every 5 minutes via @Scheduled
 */
@Service
public class GoogleSheetsService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);

    @Value("${google.sheets.id}")
    private String sheetsId;

    @Value("${google.sheets.credentials-content-base64}")
    private String credentialsBase64;

    private final DriverRepository driverRepo;
    private final BusinessRepository businessRepo;
    private final WhatsappService whatsappService;

    private Sheets sheetsService;

    public GoogleSheetsService(DriverRepository driverRepo,
                               BusinessRepository businessRepo,
                               WhatsappService whatsappService) {
        this.driverRepo = driverRepo;
        this.businessRepo = businessRepo;
        this.whatsappService = whatsappService;
    }

    /**
     * Initialize Google Sheets API connection
     */
    private synchronized Sheets getSheetsService() throws Exception {
        if (sheetsService == null) {
            JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

            // Decode base64 credentials
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(credentialsBase64);
            InputStream credentialsStream = new java.io.ByteArrayInputStream(decodedBytes);

            ServiceAccountCredentials credentials = (ServiceAccountCredentials) ServiceAccountCredentials
                    .fromStream(credentialsStream)
                    .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

            sheetsService = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    jsonFactory,
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("YanivBot")
                    .build();
        }
        return sheetsService;
    }

    /**
     * Sync drivers from Google Sheets
     * Called every 5 minutes via @Scheduled
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void syncDriversFromSheets() {
        logger.info("Syncing drivers from Google Sheets (נהגים)...");
        try {
            syncDrivers();
            logger.info("✅ Driver sync completed");
        } catch (Exception e) {
            logger.error("❌ Error syncing drivers from Google Sheets: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync businesses from Google Sheets
     * Called every 5 minutes via @Scheduled
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void syncBusinessesFromSheets() {
        logger.info("Syncing businesses from Google Sheets (עסקים)...");
        try {
            syncBusinesses();
            logger.info("✅ Business sync completed");
        } catch (Exception e) {
            logger.error("❌ Error syncing businesses from Google Sheets: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync businesses from Google Sheets to database
     * Reads עסקים sheet and updates database
     * Columns: A=name, B=phone, C=address
     */
    private void syncBusinesses() throws Exception {
        Sheets sheets = getSheetsService();

        ValueRange response = sheets.spreadsheets().values()
                .get(sheetsId, "עסקים!A2:D")
                .execute();

        List<List<Object>> rows = response.getValues();

        if (rows == null || rows.isEmpty()) {
            businessRepo.deleteAll();
            logger.info("No businesses in sheet - cleared database");
            return;
        }

        // Collect phones from sheet for cleanup
        List<String> sheetPhones = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() < 3) continue; // Need at least name, phone, address

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            String address = row.size() > 2 ? row.get(2).toString() : "";

            sheetPhones.add(phone);

            // Update or create business
            Business existing = businessRepo.findByPhone(phone).orElse(null);
            if (existing != null) {
                existing.setName(name);
                existing.setAddress(address);
                businessRepo.save(existing);
                logger.info("Updated business: {}", phone);
            } else {
                Business business = new Business(name, phone, true);
                business.setAddress(address);
                businessRepo.save(business);
                logger.info("Added new business: {}", phone);
            }
        }

        // Remove businesses not in sheet
        businessRepo.findAll().forEach(business -> {
            if (!sheetPhones.contains(business.getPhone())) {
                businessRepo.delete(business);
                logger.info("Removed business (not in sheet): {}", business.getPhone());
            }
        });
    }

    /**
     * Sync drivers from Google Sheets to database
     * Reads נהגים sheet and updates database
     * Columns: A=name, B=phone, C=type, D=carType, E=carColor, F=carModel
     */
    private void syncDrivers() throws Exception {
        Sheets sheets = getSheetsService();

        ValueRange response = sheets.spreadsheets().values()
                .get(sheetsId, "נהגים!A2:F")
                .execute();

        List<List<Object>> rows = response.getValues();

        if (rows == null || rows.isEmpty()) {
            driverRepo.deleteAll();
            logger.info("No drivers in sheet - cleared database");
            return;
        }

        // Collect phones from sheet for cleanup
        List<String> sheetPhones = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() < 3) continue; // Need at least name, phone, type

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());

            // Parse driver type - support both English and Hebrew
            String typeInput = row.get(2).toString().trim();
            DriverType type = parseDriverType(typeInput);

            // Parse car details (optional columns D, E, F)
            String carType = row.size() > 3 ? row.get(3).toString().trim() : "";
            String carColor = row.size() > 4 ? row.get(4).toString().trim() : "";
            String carModel = row.size() > 5 ? row.get(5).toString().trim() : "";

            sheetPhones.add(phone);

            // Update or create driver
            Driver existing = driverRepo.findDriverByPhone(phone).orElse(null);
            if (existing != null) {
                // Update name and type, keep active status
                existing.setName(name);
                existing.setType(type);
                existing.setCarType(parseCarType(carType));
                existing.setCarColor(carColor.isEmpty() ? null : carColor);
                existing.setCarModel(carModel.isEmpty() ? null : carModel);
                driverRepo.save(existing);
                logger.info("Updated driver: {} with car details: {}/{}/{}", phone, carType, carColor, carModel);
            } else {
                // New driver — start inactive
                Driver newDriver = new Driver(phone, name, false, type);
                newDriver.setCarType(parseCarType(carType));
                newDriver.setCarColor(carColor.isEmpty() ? null : carColor);
                newDriver.setCarModel(carModel.isEmpty() ? null : carModel);
                driverRepo.save(newDriver);
                logger.info("Added new driver: {} with car details: {}/{}/{}", phone, carType, carColor, carModel);
            }
        }

        // Remove drivers not in sheet
        driverRepo.findAll().forEach(driver -> {
            if (!sheetPhones.contains(driver.getPhone())) {
                driverRepo.delete(driver);
                logger.info("Removed driver (not in sheet): {}", driver.getPhone());
            }
        });
    }

    /**
     * Parse driver type from both English and Hebrew
     */
    private DriverType parseDriverType(String input) {
        String normalized = input.trim().toUpperCase();

        // Try English names first
        try {
            return DriverType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Try Hebrew names
            return switch (input.trim()) {
                case "אופנוע" -> DriverType.TAXI;
                case "מכונית פרטית" -> DriverType.DELIVERY;
                case "רכב גדול" -> DriverType.BOTH;
                default -> {
                    logger.warn("Unknown driver type: {}, defaulting to TAXI", input);
                    yield DriverType.TAXI;
                }
            };
        }
    }

    /**
     * Parse car type from both English and Hebrew
     */
    private com.example.yanivbot.Models.CarType parseCarType(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        String normalized = input.trim().toUpperCase();

        // Try English names first
        try {
            return com.example.yanivbot.Models.CarType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Try Hebrew names
            return switch (input.trim()) {
                case "אופנוע" -> com.example.yanivbot.Models.CarType.MOTORCYCLE;
                case "רכב פרטי" ,"מכונית פרטית" -> com.example.yanivbot.Models.CarType.PRIVATE_CAR;
                case "רכב גדול" -> com.example.yanivbot.Models.CarType.MINIVAN;
                default -> {
                    logger.warn("Unknown car type: {}, skipping", input);
                    yield null;
                }
                //case " -> com.example.yanivbot.Models.CarType.PRIVATE_CAR;
            };
        }
    }

    /**
     * Get all drivers from database
     */
    public List<Driver> getAllDrivers() {
        return driverRepo.findAll();
    }
}