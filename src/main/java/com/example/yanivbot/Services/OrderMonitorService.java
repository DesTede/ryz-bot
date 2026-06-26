package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    // Active-driver stale-location handling (change these to tune timing)
    private static final int DRIVER_STALE_LOCATION_MINUTES = 5;   // location considered stale after this
    private static final int DRIVER_STALE_REALERT_MINUTES = 15;   // re-alert driver only after this gap
    private static final int DRIVER_AUTO_CLOCKOUT_MINUTES = 20;   // auto clock-out after sustained staleness





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
            // Admin asked us to stop re-broadcasting this order
            if (order.isRedispatchStopped()) {
                continue;
            }

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


            try {
                whatsappService.notifyAdminsSmartMessage(
                        adminMsg,
                        "taxi_order_delayed_admin",
                        List.of(
                                String.valueOf(order.getId()),
                                String.valueOf(TAXI_ALERT_MINUTES),
                                order.getPickUpLocation(),
                                order.getDestination(),
                                order.getPhone()
                        ),
                        convoService
                );
                whatsappService.notifyAdminsInteractiveButtons(
                        "🛑 הפסק שליחת הודעה מחדש לנהגים #" + order.getId() + "?",
                        convoService,
                        new WhatsappService.InteractiveButton("stop_redispatch_taxi_" + order.getId(), "🛑 הפסק הפצה מחדש")
                );
            } catch (Exception e) {
                logger.error("Failed to send admin alert for taxi order #{}: {}", order.getId(), e.getMessage(), e);
            }
            
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
                            (order.getNotes() == null || order.getNotes().isEmpty()) ? "אין" : order.getNotes()

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
            // Admin asked us to stop re-broadcasting this order
            if (order.isRedispatchStopped()) {
                continue;
            }

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

            try {
                whatsappService.notifyAdminsSmartMessage(
                        adminMsg,
                        "delivery_order_delayed_admin",
                        List.of(String.valueOf(order.getId()),
                                String.valueOf(DELIVERY_ALERT_MINUTES),
                                order.getDeliveryAddress(),
                                order.getBusinessPhone()),
                        convoService
                );
                whatsappService.notifyAdminsInteractiveButtons(
                        "🛑 הפסק שליחת הודעה מחדש לשליחים #" + order.getId() + "?",
                        convoService,
                        new WhatsappService.InteractiveButton("stop_redispatch_del_" + order.getId(), "🛑 הפסק הפצה מחדש")
                );
            } catch (Exception e) {
                logger.error("Failed to send admin alert for delivery order #{}: {}", order.getId(), e.getMessage(), e);
            }

            // Notify business owner
            whatsappService.sendSafeText(order.getBusinessPhone(),
                    "⚠️ טרם נמצא שליח להזמנה #" + order.getId() + ". אנו ממשיכים לחפש...");

            // Re-dispatch to drivers
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
            
            // Mark as alerted to prevent sending this alert again
            order.setAdminLastAlertedAt(LocalDateTime.now());
            deliveryOrderRepo.save(order);
        }
    }

    // Runs every 5 minutes — notifies customer if driver location is stale during an active taxi or delivery order
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void checkStaleDriverLocation() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(DRIVER_STALE_LOCATION_MINUTES);

        // --- Taxi orders ---
        List<TaxiOrder> activeTaxi = taxiOrderRepo.findByStatusIn(
                List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED));

        for (TaxiOrder order : activeTaxi) {
            if (order.getDriverPhone() == null) continue;
            Driver driver = driverService.findByPhone(order.getDriverPhone());
            if (driver == null || driver.getLocationUpdatedAt() == null) continue;

            boolean stale = driver.getLocationUpdatedAt().isBefore(staleThreshold);

            if (!stale) {
                // Fresh again — reset so a later staleness episode can re-alert
                if (order.isCustomerAlertedStaleLocation()) {
                    order.setCustomerAlertedStaleLocation(false);
                    taxiOrderRepo.save(order);
                }
                continue;
            }

            if (order.isCustomerAlertedStaleLocation()) continue; // already alerted this episode

            whatsappService.sendSafeText(order.getPhone(),
                    "⚠️ המיקום לא מתעדכן כרגע, הנהג בדרכו אליך 🚗");
            order.setCustomerAlertedStaleLocation(true);
            taxiOrderRepo.save(order);
            logger.info("Stale location alert sent to customer for taxi order #{}", order.getId());
        }

        // --- Delivery orders ---
        List<DeliveryOrder> activeDeliveries = deliveryOrderRepo.findByDeliveryStatusIn(
                List.of(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERING));
        for (DeliveryOrder order : activeDeliveries) {
            if (order.getPickedUpBy() == null) continue;
            Driver driver = driverService.findByPhone(order.getPickedUpBy());
            if (driver == null || driver.getLocationUpdatedAt() == null) continue;

            boolean stale = driver.getLocationUpdatedAt().isBefore(staleThreshold);

            if (!stale) {
                if (order.isCustomerAlertedStaleLocation()) {
                    order.setCustomerAlertedStaleLocation(false);
                    deliveryOrderRepo.save(order);
                }
                continue;
            }

            if (order.isCustomerAlertedStaleLocation()) continue;

            String customer = order.getCustomerPhone();
            if (customer != null) {
                whatsappService.sendSafeText(customer,
                        "⚠️ המיקום לא מתעדכן כרגע, השליח בדרך אליך 🛵");
            }
            order.setCustomerAlertedStaleLocation(true);
            deliveryOrderRepo.save(order);
            logger.info("Stale location alert sent to customer for delivery order #{}", order.getId());
        }
    }

    // Runs every 5 minutes — alerts active drivers whose location is stale and auto-clocks-out long-stale ones
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void checkActiveDriversStaleLocation() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minusMinutes(DRIVER_STALE_LOCATION_MINUTES);
        LocalDateTime clockOutThreshold = now.minusMinutes(DRIVER_AUTO_CLOCKOUT_MINUTES);
        LocalDateTime reAlertThreshold = now.minusMinutes(DRIVER_STALE_REALERT_MINUTES);

        for (Driver driver : driverService.getAllActiveDrivers()) {
            // Skip drivers currently on an order — their staleness is handled by the customer alert
            if (driverService.hasActiveTaxiOrder(driver.getPhone())
                    || driverService.hasActiveDeliveryOrders(driver.getPhone())) {
                continue;
            }

            LocalDateTime lastUpdate = driver.getLocationUpdatedAt();
            if (lastUpdate == null) continue;                 // never reported
            if (lastUpdate.isAfter(staleThreshold)) continue; // location is fresh

            // Sustained staleness — auto clock-out and tell the driver why
            if (lastUpdate.isBefore(clockOutThreshold)) {
                driverService.clockOut(driver.getPhone());
                String clockOutMsg = "🔴 יצאת מהמשמרת אוטומטית\n" +
                        " כי המיקום שלך לא התעדכן כבר " + DRIVER_AUTO_CLOCKOUT_MINUTES + "דקות. \n" +
                        " "+ "כדי לחזור לעבוד פתח את האפליקציה ושתף שוב מיקום";
                whatsappService.sendSmartCustomerMessage(driver.getPhone(), clockOutMsg,
                        "driver_auto_clocked_out",
                        List.of(driver.getName(), String.valueOf(DRIVER_AUTO_CLOCKOUT_MINUTES)), convoService);
                logger.warn("Driver {} auto clocked-out — location stale > {} min",
                        PhoneNumberUtil.maskPhoneNumber(driver.getPhone()), DRIVER_AUTO_CLOCKOUT_MINUTES);
                continue;
            }

            
            // Stale but not long enough for clock-out — alert once, re-alert only after the gap
            LocalDateTime lastAlert = driver.getStaleLocationAlertedAt();
            if (lastAlert != null && lastAlert.isAfter(reAlertThreshold)) {
                continue; // alerted recently, don't spam
            }

            String driverName = driver.getName();
            String staleMsg = "⚠️ המיקום שלך לא מתעדכן כבר כ-" + DRIVER_STALE_LOCATION_MINUTES + " דקות.\n" +
                    "עד שהמיקום יחזור להתעדכן, לא תופיע כזמין ולא תקבל הזמנות חדשות.\n" +
                    "אנא ודא שהאפליקציה פתוחה ושיתוף המיקום פעיל 📍";
            whatsappService.sendSmartCustomerMessage(driver.getPhone(), staleMsg,
                    "driver_location_stale",
                    List.of(driverName, String.valueOf(DRIVER_STALE_LOCATION_MINUTES)), convoService);

            driver.setStaleLocationAlertedAt(now);
            driverService.saveDriver(driver);
            logger.info("Stale location alert sent to driver {}",
                    PhoneNumberUtil.maskPhoneNumber(driver.getPhone()));
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

    /**
     * Runs every 5 minutes — nudges customers who abandoned mid-flow between 20–30 minutes ago.
     * Sends a friendly reminder within the 24hr window before the hard reset kicks in.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void checkAbandonedConversations() {
        long now = System.currentTimeMillis();
        long nudgeFrom = now - (ConversationService.CONVERSATION_TIMEOUT_MINUTES * 60 * 1000); // 30 min ago
        long nudgeTo   = now - (ConversationService.CONVERSATION_NUDGE_MINUTES * 60 * 1000L); // 20 min ago

        List<ConversationState> midFlowStates = List.of(
                ConversationState.BUSINESS_MENU,
                ConversationState.TAXI_CAR_TYPE,
                ConversationState.TAXI_PICKUP,
                ConversationState.TAXI_DESTINATION,
                ConversationState.TAXI_NOTES,
                ConversationState.AWAITING_TAXI_ORDER_CONFIRMATION,
                ConversationState.DELIVERY_CUSTOMER_NAME,
                ConversationState.DELIVERY_CUSTOMER_PHONE,
                ConversationState.DELIVERY_ADDRESS,
                ConversationState.DELIVERY_READY_TIME,
                ConversationState.DELIVERY_PRICE,
                ConversationState.DELIVERY_NOTES,
                ConversationState.DELIVERY_AWAITING_CONFIRMATION
        );

        List<Conversation> abandoned = convoService.findIdleMidFlowConversations(midFlowStates, nudgeFrom, nudgeTo);

        for (Conversation convo : abandoned) {
            logger.info("Nudging abandoned conversation for {} (state: {})", convo.getPhone(), convo.getState());
            whatsappService.sendSafeText(convo.getPhone(),
                    """
                            👋 שנמשיך בשיחה?
                            לא שמענו ממך זמן מה

                            _(לאיפוס שלח 00)_""");
            convo.setNudgedAt(System.currentTimeMillis());
            convoService.save(convo);
        }
    }
}