package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.*;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.*;
import com.example.yanivbot.Services.GoogleSheetsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverRepository driverRepository;
    private final BusinessRepository businessRepository;
    private final GoogleSheetsService googleSheetsService;

    public AdminController(TaxiOrderRepository taxiOrderRepo,
                           DeliveryOrderRepository deliveryOrderRepo,
                           DriverRepository driverRepository,
                           BusinessRepository businessRepository, GoogleSheetsService googleSheetsService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverRepository = driverRepository;
        this.businessRepository = businessRepository;
        this.googleSheetsService = googleSheetsService;
    }

    // Active taxi orders
    @GetMapping("/taxi/active")
    public List<TaxiOrder> getActiveTaxiOrders() {
        return taxiOrderRepo.findByStatusIn(
                List.of(TaxiOrderStatus.CREATED, TaxiOrderStatus.TAKEN, TaxiOrderStatus.CONFIRMED));
    }

    // Closed taxi orders
    @GetMapping("/taxi/closed")
    public List<TaxiOrder> getClosedTaxiOrders() {
        return taxiOrderRepo.findByStatusIn(
                List.of(TaxiOrderStatus.COMPLETED, TaxiOrderStatus.CANCELLED));
    }

    // Active delivery orders
    @GetMapping("/delivery/active")
    public List<DeliveryOrder> getActiveDeliveryOrders() {
        return deliveryOrderRepo.findByDeliveryStatusIn(
                List.of(DeliveryStatus.CREATED, DeliveryStatus.READY, DeliveryStatus.PICKED_UP));
    }

    // Closed delivery orders
    @GetMapping("/delivery/closed")
    public List<DeliveryOrder> getClosedDeliveryOrders() {
        return deliveryOrderRepo.findByDeliveryStatusIn(
                List.of(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED));
    }
    
    // Taxi order history
    @GetMapping("/taxi/history")
    public List<TaxiOrder> getTaxiHistory() {
        return taxiOrderRepo.findAllByOrderByCreatedAtDesc();
    }

    
    // Driver locations
    @GetMapping("/drivers/locations")
    public List<Driver> getDriverLocations() {
        return driverRepository.findAll().stream()
                .filter(d -> d.getLatitude() != 0 && d.getLongitude() != 0)
                .toList();
    }

    // All drivers
    @GetMapping("/drivers")
    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }

    // All businesses
    @GetMapping("/businesses")
    public List<Business> getAllBusinesses() {
        return businessRepository.findAll();
    }

    // Taxi orders by status
    @GetMapping("/taxi")
    public List<TaxiOrder> getTaxiByStatus(@RequestParam(required = false) String status) {
        if (status != null) {
            return taxiOrderRepo.findByStatus(TaxiOrderStatus.valueOf(status.toUpperCase()));
        }
        return taxiOrderRepo.findAllByOrderByCreatedAtDesc();
    }

    // Delivery orders by status
    @GetMapping("/delivery")
    public List<DeliveryOrder> getDeliveryByStatus(@RequestParam(required = false) String status) {
        if (status != null) {
            return deliveryOrderRepo.findByDeliveryStatus(DeliveryStatus.valueOf(status.toUpperCase()));
        }
        return deliveryOrderRepo.findAllByOrderByCreatedAtDesc();
    }
    

    @PostMapping("/sync")
    public ResponseEntity<String> syncSheets() {
        googleSheetsService.syncFromSheets();
        return ResponseEntity.ok("Sync triggered!");
    }
}