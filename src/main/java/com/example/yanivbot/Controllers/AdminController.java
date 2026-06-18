package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.*;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.*;
import com.example.yanivbot.Services.*;
import com.example.yanivbot.Utils.PhoneNumberUtil;
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

    
    private final DriverService driverService;
    private final BotConfigService botConfigService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverRepository driverRepo;
    private final CustomerRepository customerRepo;
    private final BusinessRepository businessRepo;
    private final ConversationService convoService;

    public AdminController(DriverService driverService,
                           BotConfigService botConfigService,
                           TaxiOrderRepository taxiOrderRepo,
                           DeliveryOrderRepository deliveryOrderRepo,
                           DriverRepository driverRepo,
                           CustomerRepository customerRepo,
                           BusinessRepository businessRepo, ConversationService convoService) {
        this.driverService = driverService;
        this.botConfigService = botConfigService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverRepo = driverRepo;
        this.customerRepo = customerRepo;
        this.businessRepo = businessRepo;
        this.convoService = convoService;
    }

    // =========================================================
    // EXISTING ENDPOINTS (unchanged)
    // =========================================================

    
    @GetMapping("/dashboard")
    public void dashboardRedirect(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.sendRedirect("/dashboard/index.html");
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

    @GetMapping("/drivers")
    public ResponseEntity<List<Driver>> getAllDrivers(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            logger.info("Fetching all drivers");
            List<Driver> drivers = driverRepo.findAll();
            logger.info("Retrieved {} drivers", drivers.size());
            return ResponseEntity.ok(drivers);
        } catch (Exception e) {
            logger.error("Error fetching drivers: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/drivers/add")
    public ResponseEntity<String> addDriver(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, String> body) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String name    = body.get("name");
        String phone   = PhoneNumberUtil.normalizePhone(body.get("phone"));
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
        DriverType driverType;
        try {
            driverType = DriverType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("❌ סוג נהג לא תקין: " + type);
        }
        Driver driver = new Driver(phone, name, false, driverType);
        driver.setCarColor(carColor.isBlank() ? null : carColor);
        driver.setCarModel(carModel.isBlank() ? null : carModel);
        driver.setLocationToken(UUID.randomUUID().toString());
        if (!carType.isBlank()) {
            try {
                driver.setCarType(CarType.valueOf(carType.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown carType {}, leaving null", carType);
            }
        }
        driverRepo.save(driver);
        if (convoService.exists(phone)) {
            convoService.deleteConversation(phone);
            logger.info("Cleared stale conversation state for newly added driver: {}", phone);
        }
        logger.info("Admin added new driver: {}", phone);
        return ResponseEntity.ok("✅ נהג נוסף בהצלחה");
    }

    @PutMapping("/drivers/{phone}")
    public ResponseEntity<String> editDriver(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone,
            @RequestBody Map<String, String> body) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return driverRepo.findByPhone(phone).map(driver -> {
            if (body.containsKey("name") && !body.get("name").isBlank())
                driver.setName(body.get("name"));
            if (body.containsKey("type") && !body.get("type").isBlank()) {
                try {
                    driver.setType(com.example.yanivbot.Models.DriverType.valueOf(body.get("type").toUpperCase()));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("❌ סוג נהג לא תקין: " + body.get("type"));
                }
            }
            if (body.containsKey("carType") && !body.get("carType").isBlank()) {
                try {
                    driver.setCarType(com.example.yanivbot.Models.CarType.valueOf(body.get("carType").toUpperCase()));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body("❌ סוג רכב לא תקין: " + body.get("carType"));
                }
            }
            if (body.containsKey("carColor")) driver.setCarColor(body.get("carColor"));
            if (body.containsKey("carModel")) driver.setCarModel(body.get("carModel"));
            if (body.containsKey("phone") && !body.get("phone").isBlank()) {
                String newPhone = PhoneNumberUtil.normalizePhone(body.get("phone"));
                if (!newPhone.equals(phone) && driverRepo.findByPhone(newPhone).isPresent()) {
                    return ResponseEntity.badRequest().body("❌ מספר טלפון זה כבר קיים במערכת");
                }
                driver.setPhone(newPhone);
            }            driverRepo.save(driver);
            logger.info("Admin edited driver: {}", phone);
            return ResponseEntity.ok("✅ פרטי הנהג עודכנו בהצלחה");
        }).orElse(ResponseEntity.notFound().build());
    }
    
    
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
    
    @DeleteMapping("/drivers/{phone}")
    public ResponseEntity<String> deleteDriver(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return driverRepo.findByPhone(phone).map(driver -> {
            driverRepo.delete(driver);
            logger.info("Admin deleted driver {}", phone);
            return ResponseEntity.ok("✅ נהג נמחק בהצלחה");
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
        String phone = PhoneNumberUtil.normalizePhone(body.get("phone"));
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
        if (convoService.exists(phone)) {
            convoService.deleteConversation(phone);
            logger.info("Cleared stale conversation state for newly added business: {}", phone);
        }
        logger.info("Admin added new business: {}", phone);
        return ResponseEntity.ok("✅ עסק נוסף בהצלחה");
    
    }

    @PutMapping("/businesses/{phone}")
    public ResponseEntity<String> editBusiness(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone,
            @RequestBody Map<String, String> body) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return businessRepo.findByPhone(phone).map(business -> {
            if (body.containsKey("name") && !body.get("name").isBlank())
                business.setName(body.get("name"));
            if (body.containsKey("address"))
                business.setAddress(body.get("address"));
            if (body.containsKey("phone") && !body.get("phone").isBlank()) {
                String newPhone = PhoneNumberUtil.normalizePhone(body.get("phone"));
                if (!newPhone.equals(phone) && businessRepo.findByPhone(newPhone).isPresent()) {
                    return ResponseEntity.badRequest().body("❌ מספר טלפון זה כבר קיים במערכת");
                }
                business.setPhone(newPhone);
            }
            businessRepo.save(business);
            logger.info("Admin edited business: {}", phone);
            return ResponseEntity.ok("✅ פרטי העסק עודכנו בהצלחה");
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/businesses/{phone}/toggle-active")
    public ResponseEntity<Map<String, Object>> toggleBusinessActive(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return businessRepo.findByPhone(phone).map(business -> {
            boolean newState = !Boolean.TRUE.equals(business.getActive());
            business.setActive(newState);
            businessRepo.save(business);
            logger.info("Admin toggled business {} active={}", phone, newState);
            Map<String, Object> result = new HashMap<>();
            result.put("phone", phone);
            result.put("active", newState);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/businesses/{phone}")
    public ResponseEntity<String> deleteBusiness(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return businessRepo.findByPhone(phone).map(business -> {
            businessRepo.delete(business);
            logger.info("Admin deleted business {}", phone);
            return ResponseEntity.ok("✅ עסק נמחק בהצלחה");
        }).orElse(ResponseEntity.notFound().build());
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
    // PRICING
    // =========================================================

    @GetMapping("/pricing")
    public ResponseEntity<Map<String, Object>> getPricing(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Map<String, Object> result = new HashMap<>();
        result.put("basePrice", botConfigService.getTaxiBasePrice());
        result.put("pricePerKm", botConfigService.getTaxiPricePerKm());
        result.put("pricePerMinute", botConfigService.getTaxiPricePerMinute());
        result.put("vat", botConfigService.getTaxiVat());
        result.put("maxExtraDeliveryMinutes", botConfigService.getMaxExtraDeliveryMinutes());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pricing")
    public ResponseEntity<String> updatePricing(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, Double> body) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        boolean anyUpdates = false;

        if (body.containsKey("basePrice")) {
            double newPrice = body.get("basePrice");
            double currentPrice = botConfigService.getTaxiBasePrice();
            if (newPrice != currentPrice) {
                botConfigService.setTaxiBasePrice(newPrice);
                logger.info("Admin updated taxi base price to {}", newPrice);
                anyUpdates = true;
            }
        }

        if (body.containsKey("pricePerKm")) {
            double newPrice = body.get("pricePerKm");
            double currentPrice = botConfigService.getTaxiPricePerKm();
            if (newPrice != currentPrice) {
                botConfigService.setTaxiPricePerKm(newPrice);
                logger.info("Admin updated taxi price per km to {}", newPrice);
                anyUpdates = true;
            }
        }

        if (body.containsKey("pricePerMinute")) {
            double newPrice = body.get("pricePerMinute");
            double currentPrice = botConfigService.getTaxiPricePerMinute();
            if (newPrice != currentPrice) {
                botConfigService.setTaxiPricePerMinute(newPrice);
                logger.info("Admin updated taxi price per minute to {}", newPrice);
                anyUpdates = true;
            }
        }

        if (body.containsKey("vat")) {
            double newVat = body.get("vat");
            double currentVat = botConfigService.getTaxiVat();
            if (newVat != currentVat) {
                botConfigService.setTaxiVat(newVat);
                logger.info("Admin updated taxi VAT to {}", newVat);
                anyUpdates = true;
            }
        }

        if (body.containsKey("maxExtraDeliveryMinutes")) {
            int newMinutes = body.get("maxExtraDeliveryMinutes").intValue();
            int currentMinutes = botConfigService.getMaxExtraDeliveryMinutes();
            if (newMinutes != currentMinutes) {
                botConfigService.setMaxExtraDeliveryMinutes(newMinutes);
                logger.info("Admin updated max extra delivery minutes to {}", newMinutes);
                anyUpdates = true;
            }
        }

        if (!anyUpdates) {
            return ResponseEntity.ok("ℹ️ לא נעשו שינויים");
        }
        return ResponseEntity.ok("✅ תעריפים עודכנו בהצלחה");
    }
    
    // =========================================================
    // AUTH
    // =========================================================

    private boolean isAuthorized(String key) {
        return adminApiKey != null && adminApiKey.equals(key);
    }
    
}