package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OrderMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(OrderMonitorService.class);

    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final BusinessOwnerService businessOwnerService;
    private final GeoCodingService geoCodingService;
    private final ConversationService convoService;

    private static final int TAXI_ALERT_MINUTES = 5;
    private static final int DELIVERY_ALERT_MINUTES = 10;

    private static final int ADMIN_REPEAT_ALERT_MINUTES = 5;

    public OrderMonitorService(TaxiOrderRepository taxiOrderRepo,
                               DeliveryOrderRepository deliveryOrderRepo,
                               WhatsappService whatsappService,
                               DriverService driverService,
                               BusinessOwnerService businessOwnerService,
                               GeoCodingService geoCodingService, ConversationService convoService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.businessOwnerService = businessOwnerService;
        this.geoCodingService = geoCodingService;
        this.convoService = convoService;
    }

    // Runs every X minute — checks unclaimed orders
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void checkUnclaimedOrders() {
        checkUnclaimedTaxiOrders();
        checkUnclaimedDeliveryOrders();
    }

    // Runs every minute — dispatches delivery orders when ready
    @Scheduled(fixedDelay = 5,timeUnit = TimeUnit.MINUTES)
    public void checkOrdersReadyToDispatch() {
        List<DeliveryOrder> ordersToDispatch = deliveryOrderRepo
                .findByDeliveryStatusAndScheduledDispatchTimeBefore(
                        DeliveryStatus.CREATED, LocalDateTime.now());

        for (DeliveryOrder order : ordersToDispatch) {
            if (order.getPickedUpBy() != null) continue; // already claimed
            if (order.isDispatched()) continue; // already dispatched

            logger.info("Dispatching delivery order #{}", order.getId());

            String businessAddress = businessOwnerService.getBusinessAddress(order.getBusinessPhone());
            double[] coords = businessAddress != null ? geoCodingService.geocode(businessAddress) : null;

            String msg = buildDispatchMessage(order);
            String orderDetails = "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                    "📞 עסק: " + order.getBusinessPhone();

            if (coords != null) {
                driverService.dispatchToClosestDrivers(DriverType.DELIVERY, msg, coords[0], coords[1], orderDetails, order.getId());
            } else {
                driverService.dispatchToDrivers(DriverType.DELIVERY, msg, orderDetails, order.getId());
            }

            order.setDispatched(true);
            deliveryOrderRepo.save(order);
        }
    }

    /**
     * Check unclaimed taxi orders
     * <p>
     * Prevents duplicate alerts:
     * - adminAlertedNoDrivers: Set by DriverService when no drivers are initially available
     * - adminAlerted: Set here after TAXI_ALERT_MINUTES to prevent repeated alerts
     */
    private void checkUnclaimedTaxiOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(TAXI_ALERT_MINUTES);
        List<TaxiOrder> unclaimedOrders = taxiOrderRepo
                .findByStatusAndCreatedAtBefore(TaxiOrderStatus.CREATED, cutoff);

        for (TaxiOrder order : unclaimedOrders) {
            // Skip if admin was already alerted about this order
            LocalDateTime lastAlert = order.getAdminLastAlertedAt();
            if (lastAlert != null && lastAlert.isAfter(LocalDateTime.now().minusMinutes(ADMIN_REPEAT_ALERT_MINUTES))) {
                logger.debug("Order #{} alerted recently, skipping", order.getId());
                continue;
            }

            logger.warn("Order #{} unclaimed for {} minutes - alerting admins",
                    order.getId(), TAXI_ALERT_MINUTES);

            // Notify admins - "Order unclaimed for X minutes" alert
            String adminMsg ="⏰ *מונית מחכה יותר מדי זמן!* (#" + order.getId() + ")\n" +
                    "כבר " + TAXI_ALERT_MINUTES + " דקות שאף נהג לא לקח את הנסיעה 😱\n\n" +
                    "📍 *איסוף:* " + order.getPickUpLocation() + "\n" +
                    "🎯 *יעד:* " + order.getDestination() + "\n" +
                    "📞 *לקוח:* " + order.getPhone();

            
            whatsappService.notifyAdminsSmartMessage(
                    adminMsg,
                    "taxi_order_unclaimed_admin",
                    List.of(String.valueOf(order.getId()), String.valueOf(TAXI_ALERT_MINUTES)),
                    convoService  
            );
            
            // Notify customer
            whatsappService.sendSafeText(order.getPhone(),
                    "⚠️ טרם נמצא נהג להזמנתך. אנו ממשיכים לחפש...");

            // Re-broadcast to drivers with same format as initial dispatch (button style)
            String msg = """
                                 🚖 נסיעה חדשה זמינה עבורך
                    🆔 מספר הזמנה: %s
                    📍 נקודת איסוף: %s
                    🎯 יעד הנסיעה: %s
                    📝 פרטים נוספים: %s
                    """.formatted(
                            order.getId(),
                            order.getPickUpLocation(),
                            order.getDestination(),
                            order.getNotes().isEmpty() ? "אין" : order.getNotes()
            );

            String orderDetails = "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                    "🎯 לאן: " + order.getDestination() + "\n" +
                    "📞 לקוח: " + order.getPhone();

            // Re-dispatch using same method as initial dispatch (sends buttons, not text)
            
            driverService.dispatchToDrivers(DriverType.TAXI, msg, orderDetails, order.getId(),order.getRequestedCarType());

            // Mark as alerted to prevent sending this alert again
            order.setAdminLastAlertedAt(LocalDateTime.now());
            taxiOrderRepo.save(order);
        }
    }

    /**
     * Check unclaimed delivery orders
     */
    private void checkUnclaimedDeliveryOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(DELIVERY_ALERT_MINUTES);
        List<DeliveryOrder> unclaimedOrders = deliveryOrderRepo
                .findByDeliveryStatusAndCreatedAtBefore(DeliveryStatus.CREATED, cutoff);

        for (DeliveryOrder order : unclaimedOrders) {
            // Skip if already alerted
            LocalDateTime lastAlert = order.getAdminLastAlertedAt();
            if (lastAlert != null && lastAlert.isAfter(LocalDateTime.now().minusMinutes(ADMIN_REPEAT_ALERT_MINUTES))) {
                logger.debug("Delivery order #{} alerted recently, skipping", order.getId());
                continue;
            }

            // Skip if already claimed
            if (order.getPickedUpBy() != null) {
                logger.debug("Delivery order #{} already claimed, skipping", order.getId());
                continue;
            }

            // Skip if not yet dispatched
            if (!order.isDispatched()) {
                logger.debug("Delivery order #{} not yet dispatched, skipping", order.getId());
                continue;
            }

            logger.warn("Delivery order #{} unclaimed for {} minutes - alerting admins",
                    order.getId(), DELIVERY_ALERT_MINUTES);

            
            // Notify admins - "Order unclaimed for X minutes" alert
            String adminMsg = "\uD83C\uDF55\uD83C\uDF54 *משלוח תקוע באוויר!* (#" + order.getId() + ")\n" +
                    "עברו כבר " + DELIVERY_ALERT_MINUTES + " דקות וההזמנה עדיין לא נאספה ⏳\n\n" +
                    "📍 *כתובת למשלוח:* " + order.getDeliveryAddress() + "\n" +
                    "📞 טלפון העסק: " + order.getBusinessPhone();
            
            driverService.notifyAdminsSmartMessage(
                    adminMsg,
                    "delivery_order_delayed_admin",
                    List.of(String.valueOf(order.getId()), String.valueOf(DELIVERY_ALERT_MINUTES))
            );

            // Notify business owner
            whatsappService.sendSafeText(order.getBusinessPhone(),
                    "⚠️ טרם נמצא שליח להזמנה #" + order.getId() + ". אנו ממשיכים לחפש...");

            // Mark as alerted to prevent sending this alert again
            order.setAdminLastAlertedAt(LocalDateTime.now());
            deliveryOrderRepo.save(order);
        }
    }

    private String buildDispatchMessage(DeliveryOrder order) {
        return """
                📦 משלוח חדש!
                🆔 %d
                📍 כתובת: %s
                ⏱️ מוכן בעוד: %d דקות
                💰 לגבות: %.2f₪
                📝 הערות: %s

                ללקיחת ההזמנה שלח: משלוח %d
                לאחר איסוף שלח: איסוף %d
                לאחר מסירה שלח: נמסר %d
                """.formatted(
                order.getId(),
                order.getDeliveryAddress(),
                order.getReadyInMinutes(),
                order.getDeliveryFee(),
                order.getNotes() != null ? order.getNotes() : "אין",
                order.getId(),
                order.getId(),
                order.getId()
        );
    }

    // Commented out: Check if active driver's location is updated every 15 minutes
    // This feature will be implemented in the future
    
    /*
    private static final int LOCATION_STALE_MINUTES = 15; // alert if no update in 15 min

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void checkDriverLocations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(LOCATION_STALE_MINUTES);

        // get all active drivers of any type
        List<Driver> allActiveDrivers = new ArrayList<>();
        allActiveDrivers.addAll(driverService.getActiveDrivers(DriverType.TAXI));
        allActiveDrivers.addAll(driverService.getActiveDrivers(DriverType.DELIVERY));

        // deduplicate by phone
        allActiveDrivers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Driver::getPhone, d -> d, (d1, d2) -> d1))
                .values()
                .forEach(driver -> {
                    if (driver.getLocationUpdatedAt() != null &&
                            driver.getLocationUpdatedAt().isBefore(cutoff)) {
                        whatsappService.sendSafeText(driver.getPhone(),
                                "📍 המיקום שלך לא עודכן כבר " + LOCATION_STALE_MINUTES + " דקות.\n" +
                                        "אנא שלח מיקום מעודכן כדי להמשיך לקבל הזמנות.");
                    }
                });
    }
    */
}