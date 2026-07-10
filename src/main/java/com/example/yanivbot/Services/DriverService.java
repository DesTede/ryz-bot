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

    @Value("${dispatch.radius.steps}")
    private String radiusStepsRaw;

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
        List<Driver> drivers = driverRepo.findByActiveAndTypeIn(true, List.of(type, DriverType.BOTH));
        // Never dispatch real orders to the reviewer/demo account. This is the single
        // chokepoint all dispatch + pool-building paths route through.
        String demoPhone = System.getenv("DEMO_DRIVER_PHONE");
        if (demoPhone != null && !demoPhone.isBlank()) {
            drivers = drivers.stream()
                    .filter(d -> !demoPhone.equals(d.getPhone()))
                    .toList();
        }
        return drivers;
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

        // Store the dispatch origin on the order so the scheduler can re-cascade later without re-geocoding
        storeDispatchOrigin(orderId, type, latitude, longitude);

        // Expanding-radius cascade, starting from the smallest radius (step index 0)
        cascadeToRadiusSteps(type, message, latitude, longitude, orderDetails, orderId, 0,
                availableDrivers, "taxi_claim_" + orderId, "✅ קבל נסיעה #" + orderId);
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
                    if (driver.getType() == DriverType.BOTH) {
                        if (hasActiveTaxiOrder(driver.getPhone())) {
                            logger.debug("Driver {} skipped - has active taxi order", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driver.getPhone()));
                            return false;
                        }
                    }
                    // NEW POLICY: any driver with active orders is invisible to normal dispatch.
                    // They can only claim additional orders via the pickup-time list (after אספתי).
                    int activeCount = getActiveDeliveryCount(driver.getPhone());
                    if (activeCount > 0) {
                        logger.debug("Driver {} skipped - has {} active order(s); pickup-time list only", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()), activeCount);
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

        // Store the dispatch origin on the order so the scheduler can re-cascade later without re-geocoding
        storeDispatchOrigin(orderId, DriverType.DELIVERY, latitude, longitude);

        // Expanding-radius cascade, starting from the smallest radius (step index 0)
        cascadeToRadiusSteps(DriverType.DELIVERY, message, latitude, longitude, orderDetails, orderId, 0,
                availableDrivers, "delivery_claim_" + orderId, "✅ \uD83D\uDE80 אני לוקח #" + orderId);
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

        // Filter out drivers who have reached delivery limit or have active taxi orders
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    if (driver.getType() == DriverType.BOTH) {
                        if (hasActiveTaxiOrder(driver.getPhone())) {
                            logger.debug("Driver {} skipped - has active taxi order", PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
                            return false;
                        }
                    }
                    // NEW POLICY: any driver with active orders is invisible to normal dispatch.
                    // They can only claim additional orders via the pickup-time list (after אספתי).
                    int activeCount = getActiveDeliveryCount(driver.getPhone());
                    if (activeCount > 0) {
                        logger.debug("Driver {} skipped - has {} active order(s); pickup-time list only", driver.getPhone(), activeCount);
                        return false;
                    }
                    return true;
                })
                .toList();

        if (availableDrivers.isEmpty()) {
            logger.warn("No available delivery drivers for order #{} (all at capacity, stale, or have active taxi)", orderId);
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

        logger.debug("Distance from ({}, {}) to ({}, {}): {} km",
                lat1, lon1, lat2, lon2, String.format("%.2f", distance));

        return distance;
    }

    /** Public helper exposing the largest configured radius step (km). Used by pickup-time list. */
    public double getDispatchRadiusKm() {
        double[] steps = parseRadiusSteps();
        return steps[steps.length - 1];
    }

    /** Public haversine distance in km between two coords. */
    public double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        return calculateDistance(lat1, lon1, lat2, lon2);
    }

    /**
     * Parses dispatch.radius.steps from application.properties into a double array (in given order).
     * Example: "1,2,3" → [1.0, 2.0, 3.0]. Shared by taxi + delivery.
     */
    private double[] parseRadiusSteps() {
        try {
            String[] parts = radiusStepsRaw.split(",");
            double[] steps = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                steps[i] = Double.parseDouble(parts[i].trim());
            }
            return steps;
        } catch (Exception e) {
            logger.warn("Failed to parse dispatch.radius.steps '{}', falling back to default [1,2,3]", radiusStepsRaw);
            return new double[]{1.0, 2.0, 3.0};
        }
    }

    /**
     * Returns the number of configured radius steps (used by the scheduler to know when the max is reached).
     */
    public int getRadiusStepCount() {
        return parseRadiusSteps().length;
    }

    /**
     * Finds the step index whose km matches a stored lastDispatchRadiusKm value.
     * Returns the largest index whose step km is <= the given radius (with small epsilon), or -1 if none.
     */
    public int findStepIndexForRadius(double radiusKm) {
        double[] steps = parseRadiusSteps();
        int idx = -1;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] <= radiusKm + 0.0001) {
                idx = i;
            }
        }
        return idx;
    }

    /**
     * Persist the dispatch origin coordinates on the order so the scheduler can re-cascade
     * later without re-geocoding the address.
     */
    private void storeDispatchOrigin(long orderId, DriverType type, double latitude, double longitude) {
        if (type == DriverType.TAXI) {
            taxiOrderRepo.findById(orderId).ifPresent(order -> {
                order.setDispatchOriginLat(latitude);
                order.setDispatchOriginLng(longitude);
                taxiOrderRepo.save(order);
            });
        } else {
            deliveryOrderRepo.findById(orderId).ifPresent(order -> {
                order.setDispatchOriginLat(latitude);
                order.setDispatchOriginLng(longitude);
                deliveryOrderRepo.save(order);
            });
        }
    }

    /**
     * Record which radius an order was just dispatched to, and when. Drives the scheduler's
     * 2-minute expansion timer.
     */
    private void recordDispatchRadius(long orderId, DriverType type, double radiusKm) {
        if (type == DriverType.TAXI) {
            taxiOrderRepo.findById(orderId).ifPresent(order -> {
                order.setLastDispatchRadiusKm(radiusKm);
                order.setLastDispatchedAt(LocalDateTime.now());
                taxiOrderRepo.save(order);
            });
        } else {
            deliveryOrderRepo.findById(orderId).ifPresent(order -> {
                order.setLastDispatchRadiusKm(radiusKm);
                order.setLastDispatchedAt(LocalDateTime.now());
                deliveryOrderRepo.save(order);
            });
        }
    }

    /**
     * Core expanding-radius cascade (Option B — instantly skips empty rings).
     * <p>
     * Starting from {@code startStepIndex}, walks the configured radius steps outward. The first
     * step that contains at least one eligible driver becomes the dispatch radius: the claim button
     * is sent to ALL eligible drivers within that radius (cumulative from step 0 — inner-ring drivers
     * get a fresh button too). Empty rings are skipped instantly with no waiting. The order's
     * lastDispatchRadiusKm + lastDispatchedAt are recorded.
     * <p>
     * If no step from startStepIndex to the end contains any driver, the admin "no drivers" alert fires.
     *
     * @param availableDrivers the already-filtered eligible driver pool (active, fresh, not busy, etc.)
     */
    private void cascadeToRadiusSteps(DriverType type, String message, double originLat, double originLng,
                                      String orderDetails, long orderId, int startStepIndex,
                                      List<Driver> availableDrivers, String claimButtonId, String claimButtonLabel) {
        double[] steps = parseRadiusSteps();

        for (int i = Math.max(0, startStepIndex); i < steps.length; i++) {
            final double r = steps[i];
            List<Driver> nearbyDrivers = availableDrivers.stream()
                    .filter(driver -> driver.getLatitude() != 0 && driver.getLongitude() != 0)
                    .filter(driver -> calculateDistance(originLat, originLng, driver.getLatitude(), driver.getLongitude()) <= r)
                    .toList();

            if (!nearbyDrivers.isEmpty()) {
                logger.info("Dispatching {} order #{} to {} drivers within {}km radius (step {})",
                        type, orderId, nearbyDrivers.size(), r, i);
                for (Driver driver : nearbyDrivers) {
                    whatsappService.sendInteractiveButtonsSafe(
                            driver.getPhone(),
                            message,
                            new WhatsappService.InteractiveButton(claimButtonId, claimButtonLabel)
                    );
                }
                recordDispatchRadius(orderId, type, r);
                return;
            }
            logger.warn("No {} drivers within {}km radius for order #{} (step {}), skipping to next radius...",
                    type, r, orderId, i);
        }

        // Exhausted all radii from startStepIndex onward — record the max radius reached and alert admin
        if (steps.length > 0) {
            recordDispatchRadius(orderId, type, steps[steps.length - 1]);
        }
        logger.warn("No {} drivers found in any radius for order #{} - alerting admin", type, orderId);
        alertAdminIfNoDriversAvailable(orderId, type, orderDetails);
    }

    /**
     * Scheduler entry point: re-cascade a taxi order starting from a given radius step index.
     * Rebuilds the eligible-driver pool fresh (availability changes over time), then cascades.
     * Returns true if the order was (re)dispatched to a radius, false if no eligible pool / no coords.
     */
    public boolean expandTaxiDispatch(long orderId, int startStepIndex, String message, String orderDetails, CarType... carTypes) {
        Optional<TaxiOrder> orderOpt = taxiOrderRepo.findById(orderId);
        if (orderOpt.isEmpty()) return false;
        TaxiOrder order = orderOpt.get();

        if (order.getDispatchOriginLat() == 0 && order.getDispatchOriginLng() == 0) {
            return false; // no stored origin — cannot cascade by radius
        }

        List<Driver> pool = buildTaxiPool(orderId, orderDetails, carTypes);
        if (pool.isEmpty()) {
            return false; // buildTaxiPool already alerted admin if truly nobody
        }

        cascadeToRadiusSteps(DriverType.TAXI, message, order.getDispatchOriginLat(), order.getDispatchOriginLng(),
                orderDetails, orderId, startStepIndex, pool, "taxi_claim_" + orderId, "✅ קבל נסיעה #" + orderId);
        return true;
    }

    /**
     * Scheduler entry point: re-cascade a delivery order starting from a given radius step index.
     */
    public boolean expandDeliveryDispatch(long orderId, int startStepIndex, String message, String orderDetails) {
        Optional<DeliveryOrder> orderOpt = deliveryOrderRepo.findById(orderId);
        if (orderOpt.isEmpty()) return false;
        DeliveryOrder order = orderOpt.get();

        if (order.getDispatchOriginLat() == 0 && order.getDispatchOriginLng() == 0) {
            return false; // no stored origin — cannot cascade by radius
        }

        List<Driver> pool = buildDeliveryPool(orderId, orderDetails);
        if (pool.isEmpty()) {
            return false; // buildDeliveryPool already alerted admin if truly nobody
        }

        cascadeToRadiusSteps(DriverType.DELIVERY, message, order.getDispatchOriginLat(), order.getDispatchOriginLng(),
                orderDetails, orderId, startStepIndex, pool, "delivery_claim_" + orderId, "✅ \uD83D\uDE80 אני לוקח #" + orderId);
        return true;
    }

    /**
     * Builds the eligible TAXI driver pool: active + (car type) + fresh location + not currently busy.
     * Mirrors the filters in dispatchToClosestDrivers. Returns empty list if nobody qualifies.
     */
    private List<Driver> buildTaxiPool(long orderId, String orderDetails, CarType... carTypes) {
        List<Driver> availableDrivers = getActiveDrivers(DriverType.TAXI);
        if (availableDrivers.isEmpty()) return List.of();

        if (carTypes.length > 0) {
            CarType requiredCarType = carTypes[0];
            availableDrivers = availableDrivers.stream()
                    .filter(d -> d.getCarType() != null && d.getCarType() == requiredCarType)
                    .toList();
        }
        availableDrivers = availableDrivers.stream()
                .filter(this::isLocationFresh)
                .toList();
        availableDrivers = availableDrivers.stream()
                .filter(driver -> taxiOrderRepo
                        .findByDriverPhoneAndStatusIn(driver.getPhone(),
                                List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                        .isEmpty())
                .toList();
        return availableDrivers;
    }

    /**
     * Builds the eligible DELIVERY driver pool: active + fresh location + (BOTH: no active taxi) + capacity.
     * Mirrors the filters in dispatchDeliveryToClosestDrivers. Returns empty list if nobody qualifies.
     */
    private List<Driver> buildDeliveryPool(long orderId, String orderDetails) {
        List<Driver> availableDrivers = getActiveDrivers(DriverType.DELIVERY);
        if (availableDrivers.isEmpty()) return List.of();

        availableDrivers = availableDrivers.stream()
                .filter(this::isLocationFresh)
                .toList();
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    if (driver.getType() == DriverType.BOTH && hasActiveTaxiOrder(driver.getPhone())) {
                        return false;
                    }
                    // NEW POLICY: any driver with active orders is invisible to normal dispatch (incl. radius cascade).
                    // They can only claim additional orders via the pickup-time list (after אספתי).
                    return getActiveDeliveryCount(driver.getPhone()) == 0;
                })
                .toList();
        return availableDrivers;
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