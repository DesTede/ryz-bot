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
 * [COMPLETE FILE - WITH DEBUG LOGGING]
 * Syncs driver and business data from Google Sheets to database
 */
@Service
public class GoogleSheetsService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);

    @Value("${google.sheets.id}")
    private String sheetsId;

    @Value("${google.sheets.credentials-path}")
    private String credentialsPath;

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

    private synchronized Sheets getSheetsService() throws Exception {
        if (sheetsService == null) {
            JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
            InputStream credentialsStream = GoogleSheetsService.class.getResourceAsStream(credentialsPath);

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

    private void syncBusinesses() throws Exception {
        logger.info("DEBUG: Starting syncBusinesses()");
        logger.info("DEBUG: sheetsId = {}", sheetsId);

        Sheets sheets = getSheetsService();
        logger.info("DEBUG: Got Sheets service");

        logger.info("DEBUG: Fetching data from עסקים!A2:D");
        ValueRange response = sheets.spreadsheets().values()
                .get(sheetsId, "עסקים!A2:D")
                .execute();

        List<List<Object>> rows = response.getValues();
        logger.info("DEBUG: response.getValues() returned: {}", rows);

        if (rows == null || rows.isEmpty()) {
            logger.warn("DEBUG: rows is null or empty");
            businessRepo.deleteAll();
            logger.info("No businesses in sheet - cleared database");
            return;
        }

        logger.info("DEBUG: Found {} rows in sheet", rows.size());
        List<String> sheetPhones = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            logger.info("DEBUG: Processing row {}: {}", i, row);

            if (row.size() < 3) {
                logger.warn("DEBUG: Row {} too small (size={}), skipping", i, row.size());
                continue;
            }

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            String address = row.size() > 2 ? row.get(2).toString() : "";

            logger.info("DEBUG: Parsed business - name={}, phone={}, address={}", name, phone, address);
            sheetPhones.add(phone);

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

        businessRepo.findAll().forEach(business -> {
            if (!sheetPhones.contains(business.getPhone())) {
                businessRepo.delete(business);
                logger.info("Removed business (not in sheet): {}", business.getPhone());
            }
        });
    }

    private void syncDrivers() throws Exception {
        logger.info("DEBUG: Starting syncDrivers()");
        logger.info("DEBUG: sheetsId = {}", sheetsId);

        Sheets sheets = getSheetsService();
        logger.info("DEBUG: Got Sheets service");

        logger.info("DEBUG: Fetching data from נהגים!A2:C");
        ValueRange response = sheets.spreadsheets().values()
                .get(sheetsId, "נהגים!A2:C")
                .execute();

        List<List<Object>> rows = response.getValues();
        logger.info("DEBUG: response.getValues() returned: {}", rows);

        if (rows == null || rows.isEmpty()) {
            logger.warn("DEBUG: rows is null or empty");
            driverRepo.deleteAll();
            logger.info("No drivers in sheet - cleared database");
            return;
        }

        logger.info("DEBUG: Found {} rows in sheet", rows.size());
        List<String> sheetPhones = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            logger.info("DEBUG: Processing row {}: {}", i, row);

            if (row.size() < 3) {
                logger.warn("DEBUG: Row {} too small (size={}), skipping", i, row.size());
                continue;
            }

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            DriverType type = DriverType.valueOf(row.get(2).toString().toUpperCase());

            logger.info("DEBUG: Parsed driver - name={}, phone={}, type={}", name, phone, type);
            sheetPhones.add(phone);

            Driver existing = driverRepo.findDriverByPhone(phone).orElse(null);
            if (existing != null) {
                existing.setName(name);
                existing.setType(type);
                driverRepo.save(existing);
                logger.info("Updated driver: {}", phone);
            } else {
                driverRepo.save(new Driver(name, phone, false, type));
                logger.info("Added new driver: {}", phone);
            }
        }

        driverRepo.findAll().forEach(driver -> {
            if (!sheetPhones.contains(driver.getPhone())) {
                driverRepo.delete(driver);
                logger.info("Removed driver (not in sheet): {}", driver.getPhone());
            }
        });
    }

    public List<Driver> getAllDrivers() {
        return driverRepo.findAll();
    }
}