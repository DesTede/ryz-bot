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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * [COMPLETE FILE]
 * Syncs driver and business data from Google Sheets to database
 * Credentials loaded from base64-encoded environment variable
 */
@Service
public class GoogleSheetsService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);

    @Value("${google.sheets.id}")
    private String sheetsId;

    @Value("${google.sheets.credentials-content-base64:}")
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
            logger.info("Initializing Google Sheets service...");

            if (credentialsBase64 == null || credentialsBase64.isEmpty()) {
                throw new RuntimeException("GOOGLE_SHEETS_CREDENTIALS_CONTENT_BASE64 not set in environment variables");
            }

            JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

            try {
                // Decode base64 credentials
                byte[] decodedBytes = Base64.getDecoder().decode(credentialsBase64);
                InputStream credentialsStream = new ByteArrayInputStream(decodedBytes);

                ServiceAccountCredentials credentials = (ServiceAccountCredentials) ServiceAccountCredentials
                        .fromStream(credentialsStream)
                        .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

                sheetsService = new Sheets.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        jsonFactory,
                        new HttpCredentialsAdapter(credentials))
                        .setApplicationName("YanivBot")
                        .build();

                logger.info("✅ Google Sheets service initialized successfully");
            } catch (IllegalArgumentException e) {
                logger.error("❌ Base64 decoding failed - credentials may be corrupted: {}", e.getMessage());
                throw new RuntimeException("Invalid base64 format for credentials", e);
            }
        }
        return sheetsService;
    }

    /**
     * Sync drivers from Google Sheets
     * Called every 30 minutes via @Scheduled
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void syncDriversFromSheets() {
        logger.info("🔄 Syncing drivers from Google Sheets (נהגים)...");
        try {
            syncDrivers();
            logger.info("✅ Driver sync completed successfully");
        } catch (Exception e) {
            logger.error("❌ Error syncing drivers: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync businesses from Google Sheets
     * Called every 30 minutes via @Scheduled
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void syncBusinessesFromSheets() {
        logger.info("🔄 Syncing businesses from Google Sheets (עסקים)...");
        try {
            syncBusinesses();
            logger.info("✅ Business sync completed successfully");
        } catch (Exception e) {
            logger.error("❌ Error syncing businesses: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync businesses from Google Sheets to database
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

        List<String> sheetPhones = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() < 3) continue;

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            // Remove 972 prefix if present to keep consistent format
            if (phone.startsWith("972")) {
                phone = phone.substring(3);
            }
            String address = row.size() > 2 ? row.get(2).toString() : "";

            sheetPhones.add(phone);

            Business existing = businessRepo.findByPhone(phone).orElse(null);
            if (existing != null) {
                existing.setName(name);
                existing.setAddress(address);
                businessRepo.save(existing);
                logger.info("Updated business: {} - {}", phone, name);
            } else {
                Business business = new Business(name, phone, true);
                business.setAddress(address);
                businessRepo.save(business);
                logger.info("Added new business: {} - {}", phone, name);
            }
        }

        businessRepo.findAll().forEach(business -> {
            if (!sheetPhones.contains(business.getPhone())) {
                businessRepo.delete(business);
                logger.info("Removed business (not in sheet): {}", business.getPhone());
            }
        });
    }

    /**
     * Sync drivers from Google Sheets to database
     */
    private void syncDrivers() throws Exception {
        Sheets sheets = getSheetsService();

        ValueRange response = sheets.spreadsheets().values()
                .get(sheetsId, "נהגים!A2:C")
                .execute();

        List<List<Object>> rows = response.getValues();

        if (rows == null || rows.isEmpty()) {
            driverRepo.deleteAll();
            logger.info("No drivers in sheet - cleared database");
            return;
        }

        List<String> sheetPhones = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() < 3) continue;

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            // Remove 972 prefix if present to keep consistent format
            if (phone.startsWith("972")) {
                phone = phone.substring(3);
            }
            DriverType type = DriverType.valueOf(row.get(2).toString().toUpperCase());

            sheetPhones.add(phone);

            Driver existing = driverRepo.findDriverByPhone(phone).orElse(null);
            if (existing != null) {
                existing.setName(name);
                existing.setType(type);
                driverRepo.save(existing);
                logger.info("Updated driver: {} - {}", phone, name);
            } else {
                driverRepo.save(new Driver(name, phone, false, type));
                logger.info("Added new driver: {} - {}", phone, name);
            }
        }

        driverRepo.findAll().forEach(driver -> {
            if (!sheetPhones.contains(driver.getPhone())) {
                driverRepo.delete(driver);
                logger.info("Removed driver (not in sheet): {}", driver.getPhone());
            }
        });
    }

    /**
     * Get all drivers from database
     */
    public List<Driver> getAllDrivers() {
        return driverRepo.findAll();
    }
}