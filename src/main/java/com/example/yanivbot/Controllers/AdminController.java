package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.*;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverRepository driverRepository;
    private final BusinessRepository businessRepository;

    public AdminController(TaxiOrderRepository taxiOrderRepo,
                           DeliveryOrderRepository deliveryOrderRepo,
                           DriverRepository driverRepository,
                           BusinessRepository businessRepository) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverRepository = driverRepository;
        this.businessRepository = businessRepository;
    }

    // Active taxi orders
    @GetMapping("/taxi/active")
    public List<TaxiOrder> getActiveTaxiOrders() {
        return taxiOrderRepo.findByStatus(TaxiOrderStatus.CREATED);
    }

    // Active delivery orders
    @GetMapping("/delivery/active")
    public List<DeliveryOrder> getActiveDeliveryOrders() {
        return deliveryOrderRepo.findByDeliveryStatus(DeliveryStatus.CREATED);
    }

    // Taxi order history
    @GetMapping("/taxi/history")
    public List<TaxiOrder> getTaxiHistory() {
        return taxiOrderRepo.findAllByOrderByCreatedAtDesc();
    }

    // Delivery order history
    @GetMapping("/delivery/history")
    public List<DeliveryOrder> getDeliveryHistory() {
        return deliveryOrderRepo.findAllByOrderByCreatedAtDesc();
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
}