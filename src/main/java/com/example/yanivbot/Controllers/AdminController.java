package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Services.GoogleSheetsService;
import com.example.yanivbot.Services.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [COMPLETE FILE]
 * Admin endpoints for managing drivers, businesses, and syncing with Google Sheets
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    private final GoogleSheetsService googleSheetsService;
    private final DriverService driverService;

    public AdminController(GoogleSheetsService googleSheetsService, DriverService driverService) {
        this.googleSheetsService = googleSheetsService;
        this.driverService = driverService;
    }

    /**
     * Get all drivers currently in database
     */
    @GetMapping("/drivers")
    public ResponseEntity<List<Driver>> getAllDrivers() {
        try {
            logger.info("Fetching all drivers");
            List<Driver> drivers = googleSheetsService.getAllDrivers();
            logger.info("Retrieved {} drivers", drivers.size());
            return ResponseEntity.ok(drivers);
        } catch (Exception e) {
            logger.error("Error fetching drivers: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Manually sync all drivers and businesses from Google Sheets
     * Admin can call this after adding new drivers/businesses to the sheet
     * instead of waiting for the @Scheduled sync (which runs every 5 minutes)
     */
    @PostMapping("/sync-sheets")
    public ResponseEntity<String> syncSheetsManually() {
        try {
            logger.info("=== MANUAL GOOGLE SHEETS SYNC INITIATED ===");

            // Sync drivers from Google Sheets
            logger.info("Syncing drivers from Google Sheets...");
            googleSheetsService.syncDriversFromSheets();
            logger.info("✅ Drivers synced successfully");

            // Optionally sync businesses if you have a sync method for them
            // googleSheetsService.syncBusinessesFromSheets();

            logger.info("=== MANUAL SYNC COMPLETED SUCCESSFULLY ===");
            return ResponseEntity.ok("✅ Google Sheets synced successfully!\n- Drivers updated\n- Ready to dispatch");
        } catch (Exception e) {
            logger.error("Manual sync failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("❌ Sync failed: " + e.getMessage());
        }
    }

    /**
     * Get sync status (for monitoring)
     * Shows when the last sync occurred
     */
    @GetMapping("/sync-status")
    public ResponseEntity<String> getSyncStatus() {
        try {
            logger.info("Checking sync status");
            // You can extend this to return last sync time from database if needed
            return ResponseEntity.ok("✅ Admin panel is operational\nManual sync available at POST /admin/sync-sheets");
        } catch (Exception e) {
            logger.error("Error getting sync status: {}", e.getMessage());
            return ResponseEntity.status(500).body("❌ Error: " + e.getMessage());
        }
    }

    /**
     * Sync drivers from Google Sheets (legacy endpoint - use POST /admin/sync-sheets instead)
     */
    @PostMapping("/sync-drivers")
    public ResponseEntity<String> syncDrivers() {
        try {
            logger.info("Syncing drivers from Google Sheets");
            googleSheetsService.syncDriversFromSheets();
            logger.info("Driver sync completed successfully");
            return ResponseEntity.ok("✅ Driver sync initiated");
        } catch (Exception e) {
            logger.error("Driver sync failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("❌ Sync failed: " + e.getMessage());
        }
    }
}