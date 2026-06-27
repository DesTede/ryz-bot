package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    
    @Value("${monitor.taxi.alert.minutes}")
    private int TAXI_ALERT_MINUTES;

    @Value("${monitor.delivery.alert.minutes}")
    private int DELIVERY_ALERT_MINUTES;

    @Value("${monitor.admin.repeat.alert.minutes}")
    private int ADMIN_REPEAT_ALERT_MINUTES;

    // Active-driver stale-location handling (tune via application.properties)
    @Value("${monitor.driver.stale.location.minutes}")
    private int DRIVER_STALE_LOCATION_MINUTES;   // location considered stale after this

    @Value("${monitor.driver.stale.realert.minutes}")
    private int DRIVER_STALE_REALERT_MINUTES;    // re-alert driver only after this gap

    @Value("${monitor.driver.auto.clockout.minutes}")
    private int DRIVER_AUTO_CLOCKOUT_MINUTES;    // auto clock-out after sustained staleness
    
    @Value("${dispatch.unclaimed.expand.minutes}")
    private int DISPATCH_UNCLAIMED_EXPAND_MINUTES;



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

    // Runs every 30 seconds — expands unclaimed orders to the next radius, or alerts admin once max radius is exhausted.
    // Replaces the old fixed-interval re-dispatch: the expanding-radius cascade now owns the full unclaimed lifecycle.
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void checkUnclaimedOrders() {
        expandUnclaimedTaxiOrders();
        expandUnclaimedDeliveryOrders();
    }

    /**
     * For each unclaimed taxi order whose current radius has gone stale (DISPATCH_UNCLAIMED_EXPAND_MINUTES),
     * expand to the next radius. If already at the max radius, fire the admin "delayed" alert once.
     */
    private void expandUnclaimedTaxiOrders() {
        LocalDateTime expandCutoff = LocalDateTime.now().minusMinutes(DISPATCH_UNCLAIMED_EXPAND_MINUTES);

        for (TaxiOrder order : taxiOrderRepo.findByStatus(TaxiOrderStatus.CREATED)) {
            if (order.isRedispatchStopped()) continue;          // admin halted expansion
            if (order.getDriverPhone() != null) continue;       // already claimed
            if (order.getLastDispatchedAt() == null) continue;  // never cascaded (e.g. no-coords path)
            if (order.getLastDispatchedAt().isAfter(expandCutoff)) continue; // current radius still fresh

            int currentIndex = driverService.findStepIndexForRadius(order.getLastDispatchRadiusKm());
            int lastIndex = driverService.getRadiusStepCount() - 1;

            // Not yet at max radius — try to expand to the next ring (cascade instantly skips empty rings)
            if (currentIndex >= 0 && currentIndex < lastIndex) {
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

                boolean expanded = driverService.expandTaxiDispatch(order.getId(), currentIndex + 1, msg, orderDetails, order.getRequestedCarType());
                if (expanded) {
                    logger.info("Taxi order #{} expanded outward from {}km radius", order.getId(), order.getLastDispatchRadiusKm());
                } else {
                    // No eligible drivers available to expand to (all stale/busy, or no coords) — escalate to admin
                    logger.warn("Taxi order #{} could not expand (no eligible drivers) - alerting admins", order.getId());
                    alertAdminTaxiDelayed(order);
                }
                continue;
            }

            // Already at (or beyond) the max radius — alert admin once
            alertAdminTaxiDelayed(order);
        }
    }

    /**
     * Admin "taxi unclaimed too long" alert + stop-redispatch button. Fires once per ADMIN_REPEAT_ALERT_MINUTES window.
     */
    private void alertAdminTaxiDelayed(TaxiOrder order) {
        LocalDateTime lastAlert = order.getAdminLastAlertedAt();
        if (lastAlert != null && lastAlert.isAfter(LocalDateTime.now().minusMinutes(ADMIN_REPEAT_ALERT_MINUTES))) {
            return; // alerted recently
        }

        logger.warn("Taxi order #{} exhausted all radii - alerting admins", order.getId());

        String adminMsg = "⏰ *מונית מחכה יותר מדי זמן!* (#" + order.getId() + ")\n" +
                "עברנו על כל אזורי החיפוש ואף נהג עדיין לא לקח את הנסיעה 😱\n\n" +
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

        order.setAdminLastAlertedAt(LocalDateTime.now());
        taxiOrderRepo.save(order);
    }

    /**
     * For each unclaimed delivery order whose current radius has gone stale, expand to the next radius.
     * If already at the max radius, fire the admin "delayed" alert once.
     */
    private void expandUnclaimedDeliveryOrders() {
        LocalDateTime expandCutoff = LocalDateTime.now().minusMinutes(DISPATCH_UNCLAIMED_EXPAND_MINUTES);

        for (DeliveryOrder order : deliveryOrderRepo.findByDeliveryStatus(DeliveryStatus.CREATED)) {
            if (order.isRedispatchStopped()) continue;
            if (order.getPickedUpBy() != null) continue;
            if (!order.isDispatched()) continue;
            if (order.getLastDispatchedAt() == null) continue;
            if (order.getLastDispatchedAt().isAfter(expandCutoff)) continue;

            int currentIndex = driverService.findStepIndexForRadius(order.getLastDispatchRadiusKm());
            int lastIndex = driverService.getRadiusStepCount() - 1;

            if (currentIndex >= 0 && currentIndex < lastIndex) {
                String msg = buildDispatchMessage(order);
                String orderDetails = "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                        "📞 עסק: " + order.getBusinessPhone();

                boolean expanded = driverService.expandDeliveryDispatch(order.getId(), currentIndex + 1, msg, orderDetails);
                if (expanded) {
                    logger.info("Delivery order #{} expanded outward from {}km radius", order.getId(), order.getLastDispatchRadiusKm());
                } else {
                    logger.warn("Delivery order #{} could not expand (no eligible drivers) - alerting admins", order.getId());
                    alertAdminDeliveryDelayed(order);
                }
                continue;
            }

            alertAdminDeliveryDelayed(order);
        }
    }
    
    /**
     * Admin "delivery unclaimed too long" alert + stop-redispatch button. Fires once per ADMIN_REPEAT_ALERT_MINUTES window.
     */
    private void alertAdminDeliveryDelayed(DeliveryOrder order) {
        LocalDateTime lastAlert = order.getAdminLastAlertedAt();
        if (lastAlert != null && lastAlert.isAfter(LocalDateTime.now().minusMinutes(ADMIN_REPEAT_ALERT_MINUTES))) {
            return;
        }

        logger.warn("Delivery order #{} exhausted all radii - alerting admins", order.getId());

        String adminMsg = "\uD83C\uDF55\uD83C\uDF54 *משלוח תקוע באוויר!* (#" + order.getId() + ")\n" +
                "עברנו על כל אזורי החיפוש וההזמנה עדיין לא נאספה ⏳\n\n" +
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

        order.setAdminLastAlertedAt(LocalDateTime.now());
        deliveryOrderRepo.save(order);
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