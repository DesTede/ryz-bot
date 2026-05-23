package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Services.GoogleSheetsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GoogleSheetsService googleSheetsService;

    public AdminController(GoogleSheetsService googleSheetsService) {
        this.googleSheetsService = googleSheetsService;
    }

    /**
     * Get all drivers
     */
    @GetMapping("/drivers")
    public ResponseEntity<List<Driver>> getAllDrivers() {
        List<Driver> drivers = googleSheetsService.getAllDrivers();
        return ResponseEntity.ok(drivers);
    }

    /**
     * Sync drivers from Google Sheets
     */
    @PostMapping("/sync-drivers")
    public ResponseEntity<String> syncDrivers() {
        googleSheetsService.syncDriversFromSheets();
        return ResponseEntity.ok("Driver sync initiated");
    }
}