package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.BusinessRepository;
import com.example.yanivbot.Repositories.DriverRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class GoogleSheetsService {

    @Value("${google.sheets.id}")
    private String sheetId;

    private final BusinessRepository businessRepo;
    private final DriverRepository driverRepo;
    private final WhatsappService whatsappService;

    private Sheets sheetsService;

    public GoogleSheetsService(BusinessRepository businessRepository,
                               DriverRepository driverRepository,
                               WhatsappService whatsappService) {
        this.businessRepo = businessRepository;
        this.driverRepo = driverRepository;
        this.whatsappService = whatsappService;
    }

    @PostConstruct
    public void init() throws Exception {
        GoogleCredentials credentials;

        String credentialsJson = System.getenv("GOOGLE_CREDENTIALS_JSON");

        if (credentialsJson != null) {
            // Production: read from environment variable
            InputStream credentialsStream = new java.io.ByteArrayInputStream(
                    credentialsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            credentials = GoogleCredentials
                    .fromStream(credentialsStream)
                    .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS_READONLY));
        } else {
            // Local: read from file
            InputStream credentialsStream = getClass().getClassLoader()
                    .getResourceAsStream("credentials.json");
            if (credentialsStream == null) {
                throw new RuntimeException("credentials.json not found in resources.");
            }
            credentials = GoogleCredentials
                    .fromStream(credentialsStream)
                    .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS_READONLY));
        }

        sheetsService = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("YanivBot")
                .build();
    }

    // Runs every 5 minutes
//    @Scheduled(fixedDelay = 300000)
    public void syncFromSheets() {
        System.out.println("Syncing from Google Sheets...");
        try {
            syncBusinesses();
            syncDrivers();
            System.out.println("Sync complete!");
        } catch (Exception e) {
            System.err.println("Sync failed: " + e.getMessage());
        }
    }

    private void syncBusinesses() throws Exception {
        ValueRange response = sheetsService.spreadsheets().values()
                .get(sheetId, "עסקים!A2:D")
                .execute();

        List<List<Object>> rows = response.getValues();
        if (rows == null || rows.isEmpty()) {
            businessRepo.deleteAll();
            return;
        }

        List<String> sheetPhones = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() < 3) continue;

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            String address = row.size() > 2 ? row.get(2).toString() : "";
            sheetPhones.add(phone);

            Business existing = businessRepo.findByPhone(phone).orElse(null);
            if (existing != null) {
                existing.setName(name);
                existing.setAddress(address);
                businessRepo.save(existing);
            } else {
                Business business = new Business(name, phone, true);
                business.setAddress(address);
                businessRepo.save(business);
            }
        }

        // remove businesses not in sheet
        businessRepo.findAll().forEach(business -> {
            if (!sheetPhones.contains(business.getPhone())) {
                businessRepo.delete(business);
            }
        });
    }

    private void syncDrivers() throws Exception {
        ValueRange response = sheetsService.spreadsheets().values()
                .get(sheetId, "נהגים!A2:D")
                .execute();

        List<List<Object>> rows = response.getValues();
        if (rows == null || rows.isEmpty()) {
            driverRepo.deleteAll();
            return;
        }

        // collect phones from sheet
        List<String> sheetPhones = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() < 3) continue;

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            DriverType type = DriverType.valueOf(row.get(2).toString().toUpperCase());
            sheetPhones.add(phone);

            Driver existing = driverRepo.findDriverByPhone(phone).orElse(null);
            if (existing != null) {
                // update name and type, keep active status
                existing.setName(name);
                existing.setType(type);
                driverRepo.save(existing);
            } else {
                // new driver — start inactive
                driverRepo.save(new Driver(name, phone, false, type));
            }
        }

        // remove drivers not in sheet
        driverRepo.findAll().forEach(driver -> {
            if (!sheetPhones.contains(driver.getPhone())) {
                driverRepo.delete(driver);
            }
        });
    }
}