package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.DriverRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DriverService {

    private static final Logger logger = LoggerFactory.getLogger(DriverService.class);

    @Value("${dispatch.radius.km}")
    private double dispatchRadiusKm;
    
    @Value("${driver.max-active-deliveries}")
    private int maxActiveDeliveries;
    
    
    private final DriverRepository driverRepo;
    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final WhatsappService whatsappService;
    private final ConversationService convoService;

    public DriverService(DriverRepository driverRepo, TaxiOrderRepository taxiOrderRepo, DeliveryOrderRepository deliveryOrderRepo,
                         WhatsappService whatsappService, ConversationService convoService) {
        this.driverRepo = driverRepo;
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.whatsappService = whatsappService;
        this.convoService = convoService;
    }

    /**
     * Clock in a driver (start shift)
     */
    public void clockIn(String phone) {
        Driver driver = findByPhone(phone);  // Use normalized findByPhone()
        if (driver != null) {
            driver.setActive(true);
            driver.setLocationUpdatedAt(LocalDateTime.now());
            driver.setStaleLocationAlertedAt(null);  // fresh shift — clear any prior stale alert
            driverRepo.save(driver);
            logger.info("Driver {} clocked in", PhoneNumberUtil.maskPhoneNumberWithCountryCode(phone));
        } else {
            logger.warn("Driver {} not found for clock in", PhoneNumberUtil.maskPhoneNumber(phone));
        }
    }

    /**
     * Clock out a driver (end shift)
     */
    public void clockOut(String phone) {
        Driver driver = findByPhone(phone);  // Use normalized findByPhone()
        if (driver != null) {
            driver.setActive(false);
            driverRepo.save(driver);
            logger.info("Driver {} clocked out", PhoneNumberUtil.maskPhoneNumberWithCountryCode(phone));
        } else {
            logger.warn("Driver {} not found for clock out", PhoneNumberUtil.maskPhoneNumber(phone));
        }
    }

    /**
     * Update driver location
     */
    public void updateDriverLocation(String phone, double latitude, double longitude) {
        Driver driver = findByPhone(phone);  // Use normalized findByPhone()
        if (driver != null) {
            driver.setLatitude(latitude);
            driver.setLongitude(longitude);
            driver.setLocationUpdatedAt(LocalDateTime.now());
            driver.setStaleLocationAlertedAt(null);  // fresh update — clear any prior stale alert
            driverRepo.save(driver);
            logger.info("Driver {} location updated to {}, {}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(phone), latitude, longitude);
        } else {
            logger.warn("Driver {} not found for location update", PhoneNumberUtil.maskPhoneNumber(phone));
        }
    }

    /**
     * Find driver by phone
     */
    public Driver findByPhone(String phone) {
        // GoogleSheets adds 972 prefix, so DB stores phone WITH 972
        // Phone from WhatsApp already has 972, keep it as is
        return driverRepo.findDriverByPhone(phone).orElse(null);
    }
    
    /**
     * Get all active drivers of a specific type
     */
    public List<Driver> getActiveDrivers(DriverType type) {
        return driverRepo.findByActiveAndTypeIn(true, List.of(type, DriverType.BOTH));
    }

    /**
     * Get all active drivers (any type)
     */
    public List<Driver> getAllActiveDrivers() {
        return driverRepo.findByActive(true);
    }

    /**
     * Persist a driver entity
     */
    public void saveDriver(Driver driver) {
        driverRepo.save(driver);
    }

    /**
     * Get driver location as [latitude, longitude]
     */
    public double[] getDriverLocation(String driverPhone) {
        Driver driver = findByPhone(driverPhone);
        if (driver != null && driver.getLatitude() != 0 && driver.getLongitude() != 0) {
            return new double[]{driver.getLatitude(), driver.getLongitude()};
        }
        return null;
    }

    /**
     * Returns true if driver's location was updated within the last 5 minutes.
     */
    private boolean isLocationFresh(Driver driver) {
        if (driver.getLocationUpdatedAt() == null) return false;
        return driver.getLocationUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(5));
    }

    /**
     * Dispatch to drivers within 5km radius of order location
     * Sends order as interactive button for easy claiming
     * Only sends to active drivers with valid location data
     * Excludes drivers who already have an active order (ASSIGNED or CONFIRMED)
     */
    public void dispatchToClosestDrivers(DriverType type, String message, double latitude, double longitude,
                                         String orderDetails, long orderId, CarType... carTypes) {
        List<Driver> availableDrivers = getActiveDrivers(type);

        if (availableDrivers.isEmpty()) {
            logger.warn("No available drivers of type {} for order #{}", type, orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        // Filter by car type if specified
        if (carTypes.length > 0) {
            CarType requiredCarType = carTypes[0];
            availableDrivers = availableDrivers.stream()
                    .filter(d -> d.getCarType() != null && d.getCarType() == requiredCarType)
                    .toList();
            logger.info("Filtered {} drivers by required car type: {}", availableDrivers.size(), requiredCarType);
        }

        if (availableDrivers.isEmpty()) {
            logger.warn("No available drivers with car type {} for order #{}",
                    carTypes.length > 0 ? carTypes[0] : "ANY", orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        // Filter out drivers with stale location (not sharing from app)
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    if (!isLocationFresh(driver)) {
                        logger.debug("Driver {} skipped - location stale", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                        return false;
                    }
                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No drivers with fresh location for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }
        
        // Filter out drivers who already have an active order
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    List<TaxiOrder> activeOrders = taxiOrderRepo
                            .findByDriverPhoneAndStatusIn(driver.getPhone(),
                                    List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                            .stream()
                            .limit(1)
                            .toList();
                    boolean isBusy = !activeOrders.isEmpty();
                    if (isBusy) {
                        logger.debug("Driver {} skipped - already has active order", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                    }
                    return !isBusy;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No available drivers without active orders for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        // Filter by distance - only include drivers within radius
        List<Driver> nearbyDrivers = availableDrivers.stream()
                .filter(driver -> driver.getLatitude() != 0 && driver.getLongitude() != 0) // Must have location
                .filter(driver -> calculateDistance(latitude, longitude, driver.getLatitude(), driver.getLongitude()) <= dispatchRadiusKm)
                .toList();

        if (nearbyDrivers.isEmpty()) {
            logger.warn("No drivers within {}km radius for order #{}", dispatchRadiusKm, orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        logger.info("Dispatching to {} drivers within {}km radius for order #{}", nearbyDrivers.size(), dispatchRadiusKm, orderId);

        // Send to all drivers within radius as INTERACTIVE BUTTON
        for (Driver driver : nearbyDrivers) {
            whatsappService.sendInteractiveButtonsSafe(
                    driver.getPhone(),
                    message,
                    new WhatsappService.InteractiveButton("taxi_claim_" + orderId, "✅ קבל נסיעה #" + orderId)
            );
        }
    }

    /**
     * Dispatch to all available drivers (no location filtering)
     * Sends order as interactive button for easy claiming
     * Excludes drivers who already have an active order (ASSIGNED or CONFIRMED)
     */
    public void dispatchToDrivers(DriverType type, String message, String orderDetails, long orderId, CarType... carTypes) {
        List<Driver> availableDrivers = getActiveDrivers(type);

        if (availableDrivers.isEmpty()) {
            logger.warn("No available drivers of type {} for order #{}", type, orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        // Filter by car type if specified
        if (carTypes.length > 0) {
            CarType requiredCarType = carTypes[0];
            availableDrivers = availableDrivers.stream()
                    .filter(d -> d.getCarType() != null && d.getCarType() == requiredCarType)
                    .toList();
            logger.info("Filtered {} drivers by required car type: {}", availableDrivers.size(), requiredCarType);
        }

        if (availableDrivers.isEmpty()) {
            logger.warn("No available drivers with car type {} for order #{}",
                    carTypes.length > 0 ? carTypes[0] : "ANY", orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        // Filter out drivers with stale location (not sharing from app)
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    if (!isLocationFresh(driver)) {
                        logger.debug("Driver {} skipped - location stale", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                        return false;
                    }
                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No drivers with fresh location for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }
        
        // Filter out drivers who already have an active order
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    List<TaxiOrder> activeOrders = taxiOrderRepo
                            .findByDriverPhoneAndStatusIn(driver.getPhone(),
                                    List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                            .stream()
                            .limit(1)
                            .toList();
                    boolean isBusy = !activeOrders.isEmpty();
                    if (isBusy) {
                        logger.debug("Driver {} skipped - already has active order", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                    }
                    return !isBusy;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No available drivers without active orders for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
            return;
        }

        logger.info("Dispatching to {} drivers of type {}", availableDrivers.size(), type);

        // Send message to all available drivers as INTERACTIVE BUTTON
        for (Driver driver : availableDrivers) {
            whatsappService.sendInteractiveButtonsSafe(
                    driver.getPhone(),
                    message,
                    new WhatsappService.InteractiveButton("taxi_claim_" + orderId, "✅ אני לוקח #" + orderId)
            );
        }
    }

    /**
     * Dispatch delivery order to closest drivers within radius
     * Sends as interactive button for easy claiming
     * Filters by:
     * - Active delivery order count (DELIVERY and BOTH drivers limited to maxActiveDeliveries)
     * - Active taxi orders (BOTH drivers blocked if they have any)
     */
    public void dispatchDeliveryToClosestDrivers(String message, double latitude, double longitude,
                                                 String orderDetails, long orderId) {
        List<Driver> availableDrivers = getActiveDrivers(DriverType.DELIVERY);

        if (availableDrivers.isEmpty()) {
            logger.warn("No available delivery drivers for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }

        // Filter out drivers with stale location (not sharing from app)
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    if (!isLocationFresh(driver)) {
                        logger.debug("Driver {} skipped - location stale", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                        return false;
                    }
                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No drivers with fresh location for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }
        
        // Filter out drivers who have reached delivery limit or have active taxi orders
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    // For BOTH drivers: check if they have an active taxi order
                    if (driver.getType() == DriverType.BOTH) {                          // ← NEW
                        if (hasActiveTaxiOrder(driver.getPhone())) {                    // ← NEW
                            logger.debug("Driver {} skipped - has active taxi order", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driver.getPhone()));
                            return false;                                               // ← NEW
                        }                                                               // ← NEW
                    }                                                                   // ← NEW

                    // Check delivery order limit                                        // ← NEW
                    if (!canClaimMoreDeliveries(driver.getPhone())) {                   // ← NEW
                        int activeCount = getActiveDeliveryCount(driver.getPhone());    // ← NEW
                        logger.debug("Driver {} skipped - reached delivery limit ({}/{})", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()), activeCount, maxActiveDeliveries);
                        return false;                                                   // ← NEW
                    }                                                                   // ← NEW

                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No available delivery drivers for order #{} (all at capacity or have active taxi)", orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }

        // Filter by distance - only include drivers within radius
        List<Driver> nearbyDrivers = availableDrivers.stream()
                .filter(driver -> driver.getLatitude() != 0 && driver.getLongitude() != 0)
                .filter(driver -> calculateDistance(latitude, longitude, driver.getLatitude(), driver.getLongitude()) <= dispatchRadiusKm)
                .toList();

        if (nearbyDrivers.isEmpty()) {
            logger.warn("No delivery drivers within {}km radius for order #{}", dispatchRadiusKm, orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }

        logger.info("Dispatching delivery to {} drivers within {}km radius for order #{}", nearbyDrivers.size(), dispatchRadiusKm, orderId);

        // Send to all drivers within radius as INTERACTIVE BUTTON
        for (Driver driver : nearbyDrivers) {
            whatsappService.sendInteractiveButtonsSafe(
                    driver.getPhone(),
                    message,
                    new WhatsappService.InteractiveButton("delivery_claim_" + orderId, "✅ \uD83D\uDE80 אני לוקח #" + orderId)
            );
        }
    }

    /**
     * Dispatch delivery order to all available drivers (no location filtering)
     * Filters by:
     * - Active delivery order count (DELIVERY and BOTH drivers limited to maxActiveDeliveries)
     * - Active taxi orders (BOTH drivers blocked if they have any)
     */
    public void dispatchDeliveryToDrivers(String message, String orderDetails, long orderId) {
        List<Driver> availableDrivers = getActiveDrivers(DriverType.DELIVERY);

        if (availableDrivers.isEmpty()) {
            logger.warn("No available delivery drivers for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }

        // Filter out drivers with stale location (not sharing from app)
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    if (!isLocationFresh(driver)) {
                        logger.debug("Driver {} skipped - location stale", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                        return false;
                    }
                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No drivers with fresh location for order #{}", orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }
        
        // Filter out drivers who have reached delivery limit or have active taxi orders
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    // For BOTH drivers: check if they have an active taxi order
                    if (driver.getType() == DriverType.BOTH) {                          
                        if (hasActiveTaxiOrder(driver.getPhone())) {                    
                            logger.debug("Driver {} skipped - has active taxi order", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                            return false;                                              
                        }                                                              
                    }                                                                  

                    // Check delivery order limit                                     
                    if (!canClaimMoreDeliveries(driver.getPhone())) {                  
                        int activeCount = getActiveDeliveryCount(driver.getPhone());   
                        logger.debug("Driver {} skipped - reached delivery limit ({}/{})", driver.getPhone(), activeCount, maxActiveDeliveries);
                        return false;                                                  
                    }                                                                  

                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No available delivery drivers for order #{} (all at capacity or have active taxi)", orderId);
            alertAdminIfNoDriversAvailable(orderId, DriverType.DELIVERY, orderDetails);
            return;
        }

        logger.info("Dispatching delivery to {} available drivers for order #{}", availableDrivers.size(), orderId);

        // Send to all drivers as INTERACTIVE BUTTON
        for (Driver driver : availableDrivers) {
            whatsappService.sendInteractiveButtonsSafe(
                    driver.getPhone(),
                    message,
                    new WhatsappService.InteractiveButton("delivery_claim_" + orderId, "✅ \uD83D\uDE80 אני לוקח #" + orderId)
            );
        }
    }

    public void alertAdminIfNoDriversAvailable(long orderId, DriverType type, String orderDetails) {
        if (type == DriverType.TAXI) {
            Optional<TaxiOrder> orderOpt = taxiOrderRepo.findById(orderId);
            if (orderOpt.isEmpty()) {
                logger.warn("Taxi order #{} not found", orderId);
                return;
            }
            TaxiOrder order = orderOpt.get();
            if (order.isAdminAlertedNoDrivers()) {
                logger.info("No drivers alert already sent for taxi order #{}, skipping", orderId);
                return;
            }
            logger.warn("No drivers available for taxi order #{} - alerting admins", orderId);
            String adminMessage = "🚨 *אין נהגים פנויים!*\n" +
                    "נוצרה הזמנת מונית חדשה (#" + order.getId() + ") אבל אין אף נהג זמין כרגע במערכת 😰\n\n" +
                    "📍 *איסוף:* " + order.getPickUpLocation() + "\n" +
                    "🎯 *יעד:* " + order.getDestination() + "\n" +
                    "📞 *לקוח:* " + order.getPhone();
            notifyAdminsSmartMessage(adminMessage, "no_taxi_driver_available_admin",
                    List.of(String.valueOf(order.getId()), order.getPickUpLocation(),
                            order.getDestination(), order.getPhone()));
            order.setAdminAlertedNoDrivers(true);
            taxiOrderRepo.save(order);
        } else {
            Optional<DeliveryOrder> orderOpt = deliveryOrderRepo.findById(orderId);
            if (orderOpt.isEmpty()) {
                logger.warn("Delivery order #{} not found", orderId);
                return;
            }
            DeliveryOrder order = orderOpt.get();
            if (order.isAdminAlertedNoDrivers()) {
                logger.info("No drivers alert already sent for delivery order #{}, skipping", orderId);
                return;
            }
            logger.warn("No drivers available for delivery order #{} - alerting admins", orderId);
            String adminMessage = "🚨 *אין שליחים פנויים!*\n" +
                    "נוצר משלוח חדש (#" + order.getId() + ") אבל אין אף שליח זמין כרגע במערכת 😰\n\n" +
                    "📍 *כתובת למשלוח:* " + order.getDeliveryAddress() + "\n" +
                    "📞 *טלפון העסק:* " + order.getBusinessPhone();
            
            notifyAdminsSmartMessage(adminMessage, "no_delivery_driver_available_admin",
                    List.of(String.valueOf(order.getId()), order.getDeliveryAddress(),
                            order.getBusinessPhone()));
            order.setAdminAlertedNoDrivers(true);
            deliveryOrderRepo.save(order);
        }
    }

    /**
     * Calculate distance between two coordinates using Haversine formula
     * Returns distance in kilometers
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS_KM = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = EARTH_RADIUS_KM * c;

        logger.debug("Distance from ({}, {}) to ({}, {}): {:.2f} km",
                lat1, lon1, lat2, lon2, distance);

        return distance;
    }

    /**
     * Check if driver has any active taxi orders (ASSIGNED or CONFIRMED)
     */
    public boolean hasActiveTaxiOrder(String driverPhone) {
        return taxiOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone,
                        List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                .isPresent();  // ✅ Use isPresent() for Optional
    }

    public TaxiOrder getActiveTaxiOrder(String driverPhone) {
        return taxiOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone,
                        List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                .orElse(null);  // ✅ Use orElse() for Optional
    }

    /**
     * Check if driver has any active delivery orders (ASSIGNED, PICKED_UP, or DELIVERING)
     */
    public boolean hasActiveDeliveryOrders(String driverPhone) {
        List<DeliveryOrder> active = getActiveDeliveryOrders(driverPhone);
        return !active.isEmpty();
    }

    /**
     * Check if driver can claim more delivery orders
     * Returns true if driver hasn't reached maxActiveDeliveries limit
     */
    public boolean canClaimMoreDeliveries(String driverPhone) {
        List<DeliveryOrder> active = getActiveDeliveryOrders(driverPhone);
        return active.size() < maxActiveDeliveries;
    }

    /**
     * Get count of active delivery orders for a driver
     */
    public int getActiveDeliveryCount(String driverPhone) {
        return getActiveDeliveryOrders(driverPhone).size();
    }

    /**
     * Get all active delivery orders for a driver (ASSIGNED, PICKED_UP, or DELIVERING)
     */
    public List<DeliveryOrder> getActiveDeliveryOrders(String driverPhone) {
        return deliveryOrderRepo
                .findByPickedUpByAndDeliveryStatusIn(driverPhone,
                        List.of(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERING));
    }

    /**
     * Notify admins smartly about events
     * Delegates to WhatsappService for 24-hour informed delivery
     */
    public void notifyAdminsSmartMessage(String regularMessage, String templateName,
                                         List<String> templateVariables) {
        whatsappService.notifyAdminsSmartMessage(regularMessage, templateName,
                templateVariables, convoService);
    }

    /**
     * Notify admins with explicit choice
     */
    public void notifyAdminsWithOption(String regularMessage, String templateName,
                                       List<String> templateVariables,
                                       boolean useTemplate) {
        whatsappService.notifyAdminsWithOption(regularMessage, templateName,
                templateVariables, useTemplate);
    }

    /**
     * Event-based notification
     */
    public void notifyAdminsEvent(String message, String templateName,
                                  List<String> variables, boolean shouldUseTemplate) {
        logger.info("notifyAdminsEvent: {} | Using template: {}", templateName, shouldUseTemplate);
        notifyAdminsWithOption(message, templateName, variables, shouldUseTemplate);
    }
    
}