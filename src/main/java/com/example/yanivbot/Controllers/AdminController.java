package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.*;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.*;
import com.example.yanivbot.Services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin endpoints for managing drivers, businesses, orders, customers, and bot config.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    @Value("${admin.api-key}")
    private String adminApiKey;

    private static final int TAXI_STUCK_MINUTES = 5;
    private static final int DELIVERY_STUCK_MINUTES = 10;

    private final GoogleSheetsService googleSheetsService;
    private final DriverService driverService;
    private final BotConfigService botConfigService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverRepository driverRepo;
    private final CustomerRepository customerRepo;
    private final BusinessRepository businessRepo;

    public AdminController(GoogleSheetsService googleSheetsService,
                           DriverService driverService,
                           BotConfigService botConfigService,
                           TaxiOrderRepository taxiOrderRepo,
                           DeliveryOrderRepository deliveryOrderRepo,
                           DriverRepository driverRepo,
                           CustomerRepository customerRepo,
                           BusinessRepository businessRepo) {
        this.googleSheetsService = googleSheetsService;
        this.driverService = driverService;
        this.botConfigService = botConfigService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverRepo = driverRepo;
        this.customerRepo = customerRepo;
        this.businessRepo = businessRepo;
    }

    // =========================================================
    // EXISTING ENDPOINTS (unchanged)
    // =========================================================

    
    @GetMapping("/dashboard")
    public void dashboardRedirect(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.sendRedirect("/dashboard/index.html");
    }
    
    @GetMapping("/drivers")
    public ResponseEntity<List<Driver>> getAllDrivers(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
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

    @PostMapping("/sync-sheets")
    public ResponseEntity<String> syncSheetsManually(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            logger.info("=== MANUAL GOOGLE SHEETS SYNC INITIATED ===");
            logger.info("Syncing drivers from Google Sheets (נהגים)...");
            googleSheetsService.syncDriversFromSheets();
            logger.info("✅ Drivers synced successfully");
            logger.info("Syncing businesses from Google Sheets (עסקים)...");
            googleSheetsService.syncBusinessesFromSheets();
            logger.info("✅ Businesses synced successfully");
            logger.info("=== MANUAL SYNC COMPLETED SUCCESSFULLY ===");
            return ResponseEntity.ok("✅ Google Sheets synced successfully!\n- Drivers (נהגים) updated\n- Businesses (עסקים) updated\n- Ready to dispatch");
        } catch (Exception e) {
            logger.error("Manual sync failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("❌ Sync failed: " + e.getMessage());
        }
    }

    @GetMapping("/sync-status")
    public ResponseEntity<String> getSyncStatus(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            return ResponseEntity.ok("✅ Admin panel is operational\nManual sync available at POST /admin/sync-sheets\nSyncs: נהגים (drivers) + עסקים (businesses)");
        } catch (Exception e) {
            logger.error("Error getting sync status: {}", e.getMessage());
            return ResponseEntity.status(500).body("❌ Error: " + e.getMessage());
        }
    }

    @PostMapping("/sync-drivers")
    public ResponseEntity<String> syncDrivers(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
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

    @PostMapping("/drivers/add")
    public ResponseEntity<String> addDriver(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, String> body) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String name    = body.get("name");
        String phone   = body.get("phone");
        String type    = body.getOrDefault("type", "TAXI");
        String carType = body.getOrDefault("carType", "");
        String carColor = body.getOrDefault("carColor", "");
        String carModel = body.getOrDefault("carModel", "");
        if (name == null || name.isBlank() || phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body("❌ שם וטלפון הם שדות חובה");
        }
        if (driverRepo.findByPhone(phone).isPresent()) {
            return ResponseEntity.badRequest().body("❌ נהג עם מספר זה כבר קיים");
        }
        com.example.yanivbot.Models.DriverType driverType;
        try {
            driverType = com.example.yanivbot.Models.DriverType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("❌ סוג נהג לא תקין: " + type);
        }
        Driver driver = new Driver(phone, name, false, driverType);
        driver.setCarColor(carColor.isBlank() ? null : carColor);
        driver.setCarModel(carModel.isBlank() ? null : carModel);
        driver.setLocationToken(java.util.UUID.randomUUID().toString());
        if (!carType.isBlank()) {
            try {
                driver.setCarType(com.example.yanivbot.Models.CarType.valueOf(carType.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown carType {}, leaving null", carType);
            }
        }
        driverRepo.save(driver);
        try {
            googleSheetsService.addDriverToSheet(driver);
        } catch (Exception e) {
            logger.error("Failed to write driver to Google Sheets: {}", e.getMessage(), e);
            return ResponseEntity.ok("✅ נהג נוסף למסד הנתונים אך לא נכתב ל-Sheets: " + e.getMessage());
        }
        logger.info("Admin added new driver: {}", phone);
        return ResponseEntity.ok("✅ נהג נוסף בהצלחה למסד הנתונים ול-Google Sheets");
    }

    // =========================================================
    // BOT CONTROL
    // =========================================================

    @GetMapping("/bot/status")
    public ResponseEntity<Map<String, Object>> getBotStatus(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Map<String, Object> result = new HashMap<>();
        result.put("active", botConfigService.isBotActive());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/bot/toggle")
    public ResponseEntity<Map<String, Object>> toggleBot(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        boolean current = botConfigService.isBotActive();
        botConfigService.setBotActive(!current);
        logger.info("Bot toggled from {} to {}", current, !current);
        Map<String, Object> result = new HashMap<>();
        result.put("active", !current);
        return ResponseEntity.ok(result);
    }

    // =========================================================
    // ORDERS
    // =========================================================

    @GetMapping("/orders/taxi")
    public ResponseEntity<List<TaxiOrder>> getAllTaxiOrders(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(taxiOrderRepo.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/orders/delivery")
    public ResponseEntity<List<DeliveryOrder>> getAllDeliveryOrders(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(deliveryOrderRepo.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/orders/live")
    public ResponseEntity<Map<String, Object>> getLiveOrders(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        List<TaxiOrder> liveTaxi = taxiOrderRepo.findByStatusIn(
                List.of(TaxiOrderStatus.CREATED, TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED));
        List<DeliveryOrder> liveDelivery = deliveryOrderRepo.findByDeliveryStatusIn(
                List.of(DeliveryStatus.CREATED, DeliveryStatus.ASSIGNED,
                        DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERING));
        Map<String, Object> result = new HashMap<>();
        result.put("taxi", liveTaxi);
        result.put("delivery", liveDelivery);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/orders/stuck")
    public ResponseEntity<Map<String, Object>> getStuckOrders(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        LocalDateTime taxiCutoff = LocalDateTime.now().minusMinutes(TAXI_STUCK_MINUTES);
        LocalDateTime deliveryCutoff = LocalDateTime.now().minusMinutes(DELIVERY_STUCK_MINUTES);

        List<TaxiOrder> stuckTaxi = taxiOrderRepo
                .findByStatusAndCreatedAtBefore(TaxiOrderStatus.CREATED, taxiCutoff);

        List<DeliveryOrder> stuckDelivery = deliveryOrderRepo
                .findByDeliveryStatusAndCreatedAtBefore(DeliveryStatus.CREATED, deliveryCutoff)
                .stream()
                .filter(DeliveryOrder::isDispatched)
                .filter(o -> o.getPickedUpBy() == null)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("taxi", stuckTaxi);
        result.put("delivery", stuckDelivery);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/orders/taxi/{id}/cancel")
    public ResponseEntity<String> cancelTaxiOrder(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable long id) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return taxiOrderRepo.findById(id).map(order -> {
            if (order.getStatus() == TaxiOrderStatus.COMPLETED ||
                    order.getStatus() == TaxiOrderStatus.CANCELLED) {
                return ResponseEntity.badRequest().body("❌ Order #" + id + " is already " + order.getStatus());
            }
            order.setStatus(TaxiOrderStatus.CANCELLED);
            taxiOrderRepo.save(order);
            logger.info("Admin cancelled taxi order #{}", id);
            return ResponseEntity.ok("✅ Taxi order #" + id + " cancelled");
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/delivery/{id}/cancel")
    public ResponseEntity<String> cancelDeliveryOrder(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable long id) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return deliveryOrderRepo.findById(id).map(order -> {
            if (order.getDeliveryStatus() == DeliveryStatus.DELIVERED ||
                    order.getDeliveryStatus() == DeliveryStatus.CANCELLED) {
                return ResponseEntity.badRequest().body("❌ Order #" + id + " is already " + order.getDeliveryStatus());
            }
            order.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryOrderRepo.save(order);
            logger.info("Admin cancelled delivery order #{}", id);
            return ResponseEntity.ok("✅ Delivery order #" + id + " cancelled");
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/taxi/{id}/reassign/{driverPhone}")
    public ResponseEntity<String> reassignTaxiOrder(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable long id, @PathVariable String driverPhone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return taxiOrderRepo.findById(id).map(order -> {
            Driver driver = driverRepo.findByPhone(driverPhone).orElse(null);
            if (driver == null) {
                return ResponseEntity.badRequest().body("❌ Driver " + driverPhone + " not found");
            }
            order.setDriverPhone(driverPhone);
            order.setStatus(TaxiOrderStatus.ASSIGNED);
            taxiOrderRepo.save(order);
            logger.info("Admin reassigned taxi order #{} to driver {}", id, driverPhone);
            return ResponseEntity.ok("✅ Taxi order #" + id + " reassigned to " + driver.getName());
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/delivery/{id}/reassign/{driverPhone}")
    public ResponseEntity<String> reassignDeliveryOrder(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable long id, @PathVariable String driverPhone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return deliveryOrderRepo.findById(id).map(order -> {
            Driver driver = driverRepo.findByPhone(driverPhone).orElse(null);
            if (driver == null) {
                return ResponseEntity.badRequest().body("❌ Driver " + driverPhone + " not found");
            }
            order.setPickedUpBy(driverPhone);
            order.setDeliveryStatus(DeliveryStatus.ASSIGNED);
            deliveryOrderRepo.save(order);
            logger.info("Admin reassigned delivery order #{} to driver {}", id, driverPhone);
            return ResponseEntity.ok("✅ Delivery order #" + id + " reassigned to " + driver.getName());
        }).orElse(ResponseEntity.notFound().build());
    }

    // =========================================================
    // DRIVERS
    // =========================================================

    @PostMapping("/drivers/{phone}/toggle-active")
    public ResponseEntity<Map<String, Object>> toggleDriverActive(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return driverRepo.findByPhone(phone).map(driver -> {
            boolean newState = !driver.isActive();
            driver.setActive(newState);
            driverRepo.save(driver);
            logger.info("Admin toggled driver {} active={}", phone, newState);
            Map<String, Object> result = new HashMap<>();
            result.put("phone", phone);
            result.put("active", newState);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/drivers/{phone}/orders")
    public ResponseEntity<Map<String, Object>> getDriverOrders(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        List<TaxiOrder> taxiOrders = taxiOrderRepo.findByDriverPhoneOrderByCreatedAtDesc(phone);
        List<DeliveryOrder> deliveryOrders = deliveryOrderRepo
                .findByPickedUpByAndDeliveryStatusIn(phone,
                        List.of(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP,
                                DeliveryStatus.DELIVERING, DeliveryStatus.DELIVERED));
        Map<String, Object> result = new HashMap<>();
        result.put("taxi", taxiOrders);
        result.put("delivery", deliveryOrders);
        return ResponseEntity.ok(result);
    }

    // =========================================================
    // BUSINESSES
    // =========================================================

    @GetMapping("/businesses")
    public ResponseEntity<List<Business>> getAllBusinesses(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(businessRepo.findAll());
    }

    @PostMapping("/businesses/add")
    public ResponseEntity<String> addBusiness(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, String> body) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String name  = body.get("name");
        String phone = body.get("phone");
        String address = body.getOrDefault("address", "");
        if (name == null || name.isBlank() || phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body("❌ שם וטלפון הם שדות חובה");
        }
        if (businessRepo.findByPhone(phone).isPresent()) {
            return ResponseEntity.badRequest().body("❌ עסק עם מספר זה כבר קיים");
        }
        Business business = new Business(name, phone, true);
        business.setAddress(address);
        businessRepo.save(business);
        try {
            googleSheetsService.addBusinessToSheet(business);
        } catch (Exception e) {
            logger.error("Failed to write business to Google Sheets: {}", e.getMessage(), e);
            return ResponseEntity.ok("✅ עסק נוסף למסד הנתונים אך לא נכתב ל-Sheets: " + e.getMessage());
        }
        logger.info("Admin added new business: {}", phone);
        return ResponseEntity.ok("✅ עסק נוסף בהצלחה למסד הנתונים ול-Google Sheets");
    }

    @PostMapping("/sync-businesses")
    public ResponseEntity<String> syncBusinesses(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            logger.info("Syncing businesses from Google Sheets");
            googleSheetsService.syncBusinessesFromSheets();
            logger.info("Business sync completed successfully");
            return ResponseEntity.ok("✅ Business sync initiated");
        } catch (Exception e) {
            logger.error("Business sync failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("❌ Sync failed: " + e.getMessage());
        }
    }

    // =========================================================
    // STATS
    // =========================================================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();

        List<TaxiOrder> allTaxi = taxiOrderRepo.findAllByOrderByCreatedAtDesc();
        List<DeliveryOrder> allDelivery = deliveryOrderRepo.findAllByOrderByCreatedAtDesc();

        long taxiToday = allTaxi.stream()
                .filter(o -> o.getCreatedAt().isAfter(startOfDay)).count();
        long deliveryToday = allDelivery.stream()
                .filter(o -> o.getCreatedAt().isAfter(startOfDay)).count();

        double revenueToday = allDelivery.stream()
                .filter(o -> o.getCreatedAt().isAfter(startOfDay))
                .filter(o -> o.getDeliveryStatus() == DeliveryStatus.DELIVERED)
                .mapToDouble(DeliveryOrder::getDeliveryFee).sum();
        double revenueAllTime = allDelivery.stream()
                .filter(o -> o.getDeliveryStatus() == DeliveryStatus.DELIVERED)
                .mapToDouble(DeliveryOrder::getDeliveryFee).sum();

        long activeDrivers = driverRepo.findAll().stream().filter(Driver::isActive).count();
        long totalDrivers = driverRepo.count();
        long totalCustomers = customerRepo.count();

        long activeTaxiOrders = taxiOrderRepo.findByStatusIn(
                List.of(TaxiOrderStatus.CREATED, TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED)).size();
        long activeDeliveryOrders = deliveryOrderRepo.findByDeliveryStatusIn(
                List.of(DeliveryStatus.CREATED, DeliveryStatus.ASSIGNED,
                        DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERING)).size();

        Map<String, Object> stats = new HashMap<>();
        stats.put("taxiOrdersToday", taxiToday);
        stats.put("deliveryOrdersToday", deliveryToday);
        stats.put("taxiOrdersAllTime", allTaxi.size());
        stats.put("deliveryOrdersAllTime", allDelivery.size());
        stats.put("revenueToday", revenueToday);
        stats.put("revenueAllTime", revenueAllTime);
        stats.put("activeDrivers", activeDrivers);
        stats.put("totalDrivers", totalDrivers);
        stats.put("totalCustomers", totalCustomers);
        stats.put("activeTaxiOrders", activeTaxiOrders);
        stats.put("activeDeliveryOrders", activeDeliveryOrders);
        stats.put("botActive", botConfigService.isBotActive());

        return ResponseEntity.ok(stats);
    }

    // =========================================================
    // AUTH
    // =========================================================

    private boolean isAuthorized(String key) {
        return adminApiKey != null && adminApiKey.equals(key);
    }
}