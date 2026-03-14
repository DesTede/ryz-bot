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
import java.util.Collections;
import java.util.List;

@Service
public class GoogleSheetsService {

    @Value("${google.sheets.id}")
    private String sheetId;

    private final BusinessRepository businessRepository;
    private final DriverRepository driverRepository;
    private final WhatsappService whatsappService;

    private Sheets sheetsService;

    public GoogleSheetsService(BusinessRepository businessRepository,
                               DriverRepository driverRepository,
                               WhatsappService whatsappService) {
        this.businessRepository = businessRepository;
        this.driverRepository = driverRepository;
        this.whatsappService = whatsappService;
    }

    @PostConstruct
    public void init() throws Exception {
        InputStream credentialsStream = getClass().getClassLoader()
                .getResourceAsStream("credentials.json");

        if (credentialsStream == null) {
            throw new RuntimeException("credentials.json not found in resources. Make sure it's in src/main/resources");
        }
        
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(credentialsStream)
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS_READONLY));

        sheetsService = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("YanivBot")
                .build();
    }

    // Runs every 5 minutes
    @Scheduled(fixedDelay = 300000)
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
                .get(sheetId, "Businesses!A2:D")
                .execute();

        List<List<Object>> rows = response.getValues();
        if (rows == null || rows.isEmpty()) return;

        businessRepository.deleteAll();

        for (List<Object> row : rows) {
            if (row.size() < 4) continue;

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            String address = row.get(2).toString();
            boolean active = row.get(3).toString().equalsIgnoreCase("TRUE");

            Business business = new Business(name, phone, active);
            business.setAddress(address);
            businessRepository.save(business);
        }
    }

    private void syncDrivers() throws Exception {
        ValueRange response = sheetsService.spreadsheets().values()
                .get(sheetId, "Drivers!A2:D")
                .execute();

        List<List<Object>> rows = response.getValues();
        if (rows == null || rows.isEmpty()) return;

        driverRepository.deleteAll();

        for (List<Object> row : rows) {
            if (row.size() < 4) continue;

            String name = row.get(0).toString();
            String phone = whatsappService.normalizePhone(row.get(1).toString().trim());
            DriverType type = DriverType.valueOf(row.get(2).toString().toUpperCase());
            boolean active = row.get(3).toString().equalsIgnoreCase("TRUE");

            Driver driver = new Driver(name, phone, active, type);
            driverRepository.save(driver);
        }
    }
}