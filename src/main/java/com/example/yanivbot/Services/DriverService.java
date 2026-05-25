package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DriverRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
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

    @Value("${dispatch.radius.km:5.0}")
    private double dispatchRadiusKm;

    private final DriverRepository driverRepo;
    private final TaxiOrderRepository taxiOrderRepo;
    private final WhatsappService whatsappService;
    private final GoogleSheetsService googleSheetsService;

    @Value("${admin.phones}")
    private String adminPhones;

    public DriverService(DriverRepository driverRepo, TaxiOrderRepository taxiOrderRepo,
                         WhatsappService whatsappService, GoogleSheetsService googleSheetsService) {
        this.driverRepo = driverRepo;
        this.taxiOrderRepo = taxiOrderRepo;
        this.whatsappService = whatsappService;
        this.googleSheetsService = googleSheetsService;
    }

    /**
     * Clock in a driver (start shift)
     */
    public void clockIn(String phone) {
        Driver driver = findByPhone(phone);  // Use normalized findByPhone()
        if (driver != null) {
            driver.setActive(true);
            driver.setLocationUpdatedAt(LocalDateTime.now());
            driverRepo.save(driver);
            logger.info("Driver {} clocked in", phone);
        } else {
            logger.warn("Driver {} not found for clock in", phone);
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
            logger.info("Driver {} clocked out", phone);
        } else {
            logger.warn("Driver {} not found for clock out", phone);
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
            driverRepo.save(driver);
            logger.info("Driver {} location updated to {}, {}", phone, latitude, longitude);
        } else {
            logger.warn("Driver {} not found for location update", phone);
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
     * Dispatch to drivers within 5km radius of order location
     * Sends order as interactive button for easy claiming
     * Only sends to active drivers with valid location data
     * Excludes drivers who already have an active order (TAKEN or CONFIRMED)
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

        // Filter out drivers who already have an active order
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    List<TaxiOrder> activeOrders = taxiOrderRepo
                            .findByDriverPhoneAndStatusIn(driver.getPhone(),
                                    List.of(TaxiOrderStatus.TAKEN, TaxiOrderStatus.CONFIRMED))
                            .stream()
                            .limit(1)
                            .toList();
                    boolean isBusy = !activeOrders.isEmpty();
                    if (isBusy) {
                        logger.debug("Driver {} skipped - already has active order", driver.getPhone());
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
     * Excludes drivers who already have an active order (TAKEN or CONFIRMED)
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

        // Filter out drivers who already have an active order
        availableDrivers = availableDrivers.stream()
                .filter(driver -> {
                    List<TaxiOrder> activeOrders = taxiOrderRepo
                            .findByDriverPhoneAndStatusIn(driver.getPhone(),
                                    List.of(TaxiOrderStatus.TAKEN, TaxiOrderStatus.CONFIRMED))
                            .stream()
                            .limit(1)
                            .toList();
                    boolean isBusy = !activeOrders.isEmpty();
                    if (isBusy) {
                        logger.debug("Driver {} skipped - already has active order", driver.getPhone());
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
                    new WhatsappService.InteractiveButton("taxi_claim_" + orderId, "✅ קבל נסיעה #" + orderId)
            );
        }
    }

    /**
     * Alert admins if no drivers are available for an order
     *
     * Uses adminAlertedNoDrivers flag to ensure alert is sent ONLY ONCE
     * when the order is created, not repeatedly by OrderMonitorService
     */
    public void alertAdminIfNoDriversAvailable(long orderId, DriverType type, String orderDetails) {
        // Get the order
        Optional<TaxiOrder> orderOpt = taxiOrderRepo.findById(orderId);
        if (orderOpt.isEmpty()) {
            logger.warn("Order #{} not found", orderId);
            return;
        }

        TaxiOrder order = orderOpt.get();

        // Only send alert once per order
        if (order.isAdminAlertedNoDrivers()) {
            logger.info("No drivers alert already sent for order #{}, skipping", orderId);
            return;
        }

        logger.warn("No drivers available for order #{} - alerting admins", orderId);

        String adminMessage = "⚠️ הזמנת " + (type == DriverType.TAXI ? "מונית" : "משלוח") +
                " חדשה #" + orderId + " נוצרה אך אין נהגים זמינים!\n" +
                orderDetails;

        notifyAdmins(adminMessage);

        // Mark as alerted so this message is never sent again for this order
        order.setAdminAlertedNoDrivers(true);
        taxiOrderRepo.save(order);
    }

    /**
     * Notify all admins
     */
    private void notifyAdmins(String message) {
        if (adminPhones == null || adminPhones.isEmpty()) {
            logger.warn("No admin phones configured");
            return;
        }

        String[] phones = adminPhones.split(",");
        for (String phone : phones) {
            phone = phone.trim();
            if (!phone.isEmpty()) {
                logger.info("Sending admin alert to: {}", phone);
                whatsappService.sendSafeText(phone, message);
            }
        }
    }

    /**
     * Sync drivers from Google Sheets (called by GoogleSheetsService)
     */
    public void syncDriversFromSheets(List<Driver> drivers) {
        logger.info("Syncing {} drivers from Google Sheets", drivers.size());
        driverRepo.saveAll(drivers);
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
}