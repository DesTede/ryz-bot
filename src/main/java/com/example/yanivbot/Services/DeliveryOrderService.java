package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.*;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.BusinessRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;


@Service
public class DeliveryOrderService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryOrderService.class);

    @Value("${app.base-url}")
    private String baseUrl;

    private final ConversationService convoService;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final BusinessRepository businessRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final GeoCodingService geoCodingService;
    private final CustomerService customerService;
    private final ShortLinkService shortLinkService;
    private final BotConfigService botConfigService;


    public DeliveryOrderService(ConversationService convoService, DeliveryOrderRepository deliveryOrderRepo, BusinessRepository businessRepo,
                                WhatsappService whatsappService, DriverService driverService,
                                GeoCodingService geoCodingService, CustomerService customerService, ShortLinkService shortLinkService, BotConfigService botConfigService) {
        this.convoService = convoService;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.businessRepo = businessRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.geoCodingService = geoCodingService;
        this.customerService = customerService;
        this.shortLinkService = shortLinkService;
        this.botConfigService = botConfigService;
    }

    /**
     * Look up the most recent name and delivery address for a customer at this business.
     * Returns [name, address] if found, or null if this is a new customer.
     */
    public String[] getPreviousCustomerDetails(String businessPhone, String customerPhone) {
        Customer customer = customerService.getCustomer(customerPhone);
        if (customer == null || customer.getName() == null || customer.getName().isEmpty()) {
            return null;
        }
        return deliveryOrderRepo
                .findFirstByBusinessPhoneAndCustomerPhoneOrderByCreatedAtDesc(businessPhone, customerPhone)
                .map(order -> new String[]{customer.getName(), order.getDeliveryAddress(), order.getDeliveryAddressPlaceId()})
                .orElse(null);
    }

    public void createDeliveryOrder(String businessPhone, String customerName, String customerPhone, String address, String addressPlaceId,
                                    int readyInMinutes, double price, String notes) {
        customerService.saveOrUpdateCustomer(customerPhone, customerName);

        DeliveryOrder order = new DeliveryOrder();
        order.setBusinessPhone(businessPhone);
        order.setCustomerPhone(customerPhone);
        order.setDeliveryAddress(address);
        order.setDeliveryAddressPlaceId(addressPlaceId);
        order.setReadyInMinutes(readyInMinutes);
        order.setDeliveryFee(price);
        order.setNotes(notes);
        order.setDeliveryStatus(DeliveryStatus.CREATED);

        // IMPORTANT: Save order FIRST and flush to DB before broadcasting
        deliveryOrderRepo.save(order);
        logger.info("Delivery order saved with ID: {}", order.getId());

        // Send confirmation to business owner with order details
        whatsappService.sendInteractiveButtonsSafe(businessPhone,
                "✅ ההזמנה נוצרה בהצלחה!\n\n📋 סיכום הזמנה מספר " + order.getId() + ":\n📞 שם לקוח: " + customerName + "\n📞 טלפון לקוח: " + customerPhone +
                        "\n📍 כתובת מסירה: " + address + "\n⏱️ זמן הכנה: " + readyInMinutes + " דקות\n💰 סכום לתשלום: ₪" + price +
                        "\n📝 הערות: " + (notes.isEmpty() ? "אין" : notes) + "\n\n🚚 הודעה נשלחה לשליחים קרובים...",
                new WhatsappService.InteractiveButton("delivery_cancel_business_" + order.getId(), "🚫 בטל הזמנה")
        );
        

        logger.info("Broadcasting order #{} to drivers...", order.getId());
        broadcastToDrivers(order);
        order.setDispatched(true);
        deliveryOrderRepo.save(order);
    }
    
    

    public String cancelDeliveryOrderByBusiness(long orderId, String businessPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getBusinessPhone().equals(businessPhone)) return "❌ הזמנה זו לא שייכת לעסק שלך.";
        if (order.getPickedUpBy() != null) return "❌ לא ניתן לבטל הזמנה שכבר נלקחה על ידי שליח.";
        if (order.getDeliveryStatus() == DeliveryStatus.DELIVERED || order.getDeliveryStatus() == DeliveryStatus.CANCELLED) {
            return "❌ לא ניתן לבטל הזמנה שכבר " + (order.getDeliveryStatus() == DeliveryStatus.DELIVERED ? "נמסרה" : "בוטלה") + ".";
        }

        order.setDeliveryStatus(DeliveryStatus.CANCELLED);
        deliveryOrderRepo.save(order);

        logger.info("Delivery order #{} cancelled by business {}", orderId, PhoneNumberUtil.maskPhoneNumber(businessPhone));
        return "✅ ההזמנה בוטלה בהצלחה.\nנשמח לשרת אותך שוב ב־RYZ 💙";
    }

    public void broadcastToDrivers(DeliveryOrder order) {
        String businessName = "RYZ";
        try {
            var business = businessRepo.findByPhone(order.getBusinessPhone());
            if (business.isPresent()) {
                businessName = business.get().getName();  // ✅ Get actual name
            }
        } catch (Exception e) {
            logger.warn("Could not fetch business name...", e);
        }
        String msg = """
        🚚 הזמנת משלוח חדשה
        בית עסק: %s
        📞 טלפון העסק: %s
        📍 יעד משלוח: %s
        💰 סכום: ₪%s
        📝 הערות: %s
        🆔 מספר הזמנה: %s
        ✅ לקבלת המשלוח לחץ על הכפתור למטה
        """.formatted(
                businessName,
                PhoneNumberUtil.toLocalFormat(order.getBusinessPhone()),
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                (order.getNotes() == null || order.getNotes().isEmpty()) ? "אין" : order.getNotes(),
                order.getId()
        );

        double[] coords = (order.getDeliveryAddressPlaceId() != null && !order.getDeliveryAddressPlaceId().isEmpty())
                ? geoCodingService.geocodeByPlaceId(order.getDeliveryAddressPlaceId())
                : geoCodingService.geocode(order.getDeliveryAddress());

        if (coords != null) {
            driverService.dispatchDeliveryToClosestDrivers(msg, coords[0], coords[1],
                    "📍 כתובת: " + order.getDeliveryAddress() + "\n📞 לקוח: " + PhoneNumberUtil.toLocalFormat(order.getCustomerPhone()),
                    order.getId());
        } else {
            driverService.dispatchDeliveryToDrivers(msg,
                    "📍 כתובת: " + order.getDeliveryAddress() + "\n📞 לקוח: " + PhoneNumberUtil.toLocalFormat(order.getCustomerPhone()),
                    order.getId());
        }
    }

    /**
     * Driver claims a delivery order
     * Rules:
     * - BOTH drivers with active taxi orders: BLOCKED
     * - DELIVERY drivers: Can claim up to maxActiveDeliveries orders
     * - TAXI drivers: N/A
     */
    public String claimDeliveryOrder(long orderId, String driverPhone) {
        logger.info("[DELIVERY] Driver {} attempting to claim delivery order #{}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone), orderId);

        // Get the driver to check their type
        Driver driver = driverService.findByPhone(driverPhone);
        if (driver == null) {
            logger.warn("[DELIVERY] Driver {} not found", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone));
            return "❌ הנהג לא רשום במערכת.";
        }

        // Check if BOTH/TAXI driver has active taxi order - blocks everything
        if (driver.getType() == DriverType.BOTH ||
                driver.getType() == DriverType.TAXI) {

            if (driverService.hasActiveTaxiOrder(driverPhone)) {
                TaxiOrder activeTaxi = driverService.getActiveTaxiOrder(driverPhone);
                logger.warn("[DELIVERY] BOTH driver {} blocked - has active taxi order #{}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone), activeTaxi.getId());

                String msg = "⚠️ שים לב\nיש לך נסיעה פעילה #" + activeTaxi.getId() + " בדרך\nסיים אותה קודם לאחר מכן תוכל לקבל משלוחים";
                whatsappService.sendSafeText(driverPhone, msg);
                return null; // Message sent directly
            }
        }

        // Check if DELIVERY or BOTH driver has reached delivery order limit
        if (driver.getType() == DriverType.DELIVERY ||
                driver.getType() == DriverType.BOTH) {

            if (!driverService.canClaimMoreDeliveries(driverPhone)) {
                int activeCount = driverService.getActiveDeliveryCount(driverPhone);
                logger.warn("[DELIVERY] Driver {} reached delivery limit - has {} active orders", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone), activeCount);

                String msg = "⚠️ שים לב\nכבר יש לך " + activeCount + " משלוחים פעילים בדרך\nסיים אחד מהם קודם לפני שתוכל לקבל משלוח נוסף";
                whatsappService.sendSafeText(driverPhone, msg);
                return null; // Message sent directly
            }
        }

        // Route feasibility check: only runs if driver already has active orders
        if (driverService.hasActiveDeliveryOrders(driverPhone)) {
            DeliveryOrder candidateOrder = deliveryOrderRepo.findById(orderId).orElse(null);
            if (candidateOrder != null && !isRouteFeasible(driverPhone, candidateOrder)) {
                int maxMinutes = botConfigService.getMaxExtraDeliveryMinutes();
                logger.warn("[ROUTE] Driver {} blocked from claiming order #{} - route exceeds {} min threshold",
                        PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone), orderId, maxMinutes);
                String msg = "⚠️ לא ניתן לקבל את המשלוח\n" +
                        "המשלוח הזה ייקח יותר מ-" + maxMinutes + " דקות\n" +
                        "סיים את המשלוחים הפעילים שלך קודם 🚚";
                whatsappService.sendSafeText(driverPhone, msg);
                return null;
            }
        }
        // Check the delivery order
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);
        

        if (order == null) {
            logger.warn("[DELIVERY] Order #{} not found", orderId);
            return null; // Order doesn't exist
        }

        if (order.getDeliveryStatus() != DeliveryStatus.CREATED) {
            logger.warn("[DELIVERY] Order #{} already claimed or not in CREATED status: {}", orderId, order.getDeliveryStatus());
            String alreadyTakenMsg = "🚫 משלוח #" + orderId + " כבר שויך לנהג אחר\nהישאר זמין — הזמנה חדשה יכולה להגיע בכל רגע 🚀";
            whatsappService.sendSafeText(driverPhone, alreadyTakenMsg);
            return null; // Message sent directly
        }

        // OK to claim - mark driver and update status
        order.setPickedUpBy(driverPhone);
        order.setDeliveryStatus(DeliveryStatus.ASSIGNED);
        try {
            deliveryOrderRepo.saveAndFlush(order);
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // Another driver claimed this order at the same moment and won the race
            logger.warn("[DELIVERY] Concurrent claim race on order #{} — driver {} lost", orderId,
                    PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone));
            String alreadyTakenMsg = "🚫 משלוח #" + orderId + " כבר שויך לנהג אחר\nהישאר זמין — הזמנה חדשה יכולה להגיע בכל רגע 🚀";
            whatsappService.sendSafeText(driverPhone, alreadyTakenMsg);
            return null;
        }
        logger.info("[DELIVERY] ✅ Order #{} claimed by driver {}", orderId, PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone));

        Business business = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
        String businessName = (business != null && business.getName() != null) ? business.getName() : "העסק";

        // v2 Trip planning: next stop + order summary
        String pickupNav = buildDriverNavBlock(driverPhone);
        
        // Send confirmation to driver with order details
        String driverConfirmation ="""
        🔥 קיבלת משלוח!
        🆔 מספר הזמנה: %s
        שם העסק:%s
        📍 כתובת מסירה: %s
        
        בואו נתחיל 🚀
        """.formatted(
                order.getId(),
                businessName,
                order.getDeliveryAddress()
        ) + pickupNav;

        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                driverConfirmation,
                new WhatsappService.InteractiveButton("delivery_pickup_" + orderId, "✅ אספתי"),
                new WhatsappService.InteractiveButton("driver_show_route", "📍 הצג מסלול")
        );

        // Notify business owner that driver claimed the order
        String driverDisplayName = driver.getName() != null ? driver.getName() : "השליח";
        String businessNotification = "יאללה, יצאנו לדרך! 🎉\nמשלוח #" + orderId + " בטיפול.\n" +
                "🛵 שליח: " + driverDisplayName + "\n" +
                "📞 טלפון: " + PhoneNumberUtil.toLocalFormat(driverPhone) + "\n" +
                "הנהג כבר בדרך לעסק שלך לאסוף את ההזמנה 🤙";
        
        whatsappService.sendSafeText(order.getBusinessPhone(), businessNotification);

        logger.info("[DELIVERY] ✅ Sent confirmations for order #{}", orderId);
        return null; // Message already sent via button
    }
    
    /**
     * Driver marks delivery as picked up from business
     * Changes status: ASSIGNED → PICKED_UP
     * Notifies business owner and sends complete button to driver
     */
    public String markPickedUp(long orderId, String driverPhone) {
        logger.info("[DELIVERY] Driver {} marking order #{} as picked up",
                PhoneNumberUtil.maskPhoneNumber(driverPhone), orderId);

        // Find the delivery order
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) {
            logger.warn("[DELIVERY] Order #{} not found", orderId);
            return null;
        }

        // Check order is in correct status (ASSIGNED)
        if (order.getDeliveryStatus() != DeliveryStatus.ASSIGNED) {
            logger.warn("[DELIVERY] Order #{} not in ASSIGNED status: {}", orderId, order.getDeliveryStatus());
            String msg = "🚫 משלוח #" + orderId + " לא במצב נכון לאיסוף\nמצב נוכחי: " + order.getDeliveryStatus();
            whatsappService.sendSafeText(driverPhone, msg);
            return null;
        }

        // Check driver matches
        if (!order.getPickedUpBy().equals(driverPhone)) {
            logger.warn("[DELIVERY] Driver {} trying to pickup order assigned to {}",
                    PhoneNumberUtil.maskPhoneNumber(driverPhone),
                    PhoneNumberUtil.maskPhoneNumber(order.getPickedUpBy()));
            String msg = "🚫 משלוח #" + orderId + " לא שויך לך\nמשלוח זה שויך לנהג אחר";
            whatsappService.sendSafeText(driverPhone, msg);
            return null;
        }

        // Update order status
        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        order.setTrackingToken(UUID.randomUUID().toString());
        deliveryOrderRepo.save(order);
        logger.info("[DELIVERY] ✅ Order #{} marked as PICKED_UP by driver {}", orderId,
                PhoneNumberUtil.maskPhoneNumber(driverPhone));

        Business business = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
        String businessName = (business != null && business.getName() != null) ? business.getName() : "העסק";

        Driver pickupDriver = driverService.findByPhone(driverPhone);
        

        // v1 Trip planning + ETA: driver -> customer delivery leg (shared by driver & customer messages)
        double[] deliveryCoords = resolveCoords(order.getDeliveryAddressPlaceId(), order.getDeliveryAddress());
        Integer deliveryEta = (deliveryCoords != null && pickupDriver != null)
                ? etaMinutes(pickupDriver.getLatitude(), pickupDriver.getLongitude(), deliveryCoords[0], deliveryCoords[1])
                : null;
        String deliveryNav = buildDriverNavBlock(driverPhone);
        
        // Send confirmation to driver with complete button
        String driverMsg ="""
        🔥 אתה בדרך ללקוח!
        🆔 מספר הזמנה: %s
        שם העסק:%s
        📞 טלפון לקוח: %s
        📍 כתובת מסירה: %s
        💰 סכום לתשלום: ₪%s
        📝 הערות: %s

        🛵 סע בזהירות 🙌
        """.formatted(
                order.getId(),
                businessName,
                PhoneNumberUtil.toLocalFormat(order.getCustomerPhone()),
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                order.getNotes().isEmpty() ? "אין" : order.getNotes()
                ) +
                deliveryNav;

        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                driverMsg,
                new WhatsappService.InteractiveButton("delivery_delivering_" + orderId, "🛵 יצאתי לדרך"),
                new WhatsappService.InteractiveButton("driver_show_route", "📍 הצג מסלול")
        );

        // Notify business owner (customer is NOT notified yet — that happens when driver taps "on my way")
        String businessMsg = "🛵 השליח אסף את ההזמנה ויוצא לדרך!\nמעדכנים שמשלוח #" + order.getId() + " נאסף מהעסק שלך עכשיו וטס ללקוח 🔥";
        whatsappService.sendSafeText(order.getBusinessPhone(), businessMsg);

        logger.info("[DELIVERY] ✅ Sent pickup confirmations for order #{}", orderId);
        return null;
    }

    /**
     * Driver marks they are on the way to the customer.
     * Changes status: PICKED_UP → DELIVERING
     * This is the point where the customer is notified and gets the live tracking link.
     */
    public String markDelivering(long orderId, String driverPhone) {
        logger.info("[DELIVERY] Driver {} marking order #{} as on-the-way",
                PhoneNumberUtil.maskPhoneNumber(driverPhone), orderId);

        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);
        if (order == null) {
            logger.warn("[DELIVERY] Order #{} not found", orderId);
            return null;
        }

        if (order.getDeliveryStatus() != DeliveryStatus.PICKED_UP) {
            logger.warn("[DELIVERY] Order #{} not in PICKED_UP status: {}", orderId, order.getDeliveryStatus());
            String msg = "🚫 משלוח #" + orderId + " לא במצב נכון\nמצב נוכחי: " + order.getDeliveryStatus();
            whatsappService.sendSafeText(driverPhone, msg);
            return null;
        }

        if (order.getPickedUpBy() == null || !order.getPickedUpBy().equals(driverPhone)) {
            String msg = "🚫 משלוח #" + orderId + " לא שויך לך";
            whatsappService.sendSafeText(driverPhone, msg);
            return null;
        }

        order.setDeliveryStatus(DeliveryStatus.DELIVERING);
        deliveryOrderRepo.save(order);
        logger.info("[DELIVERY] ✅ Order #{} marked as DELIVERING by driver {}", orderId,
                PhoneNumberUtil.maskPhoneNumber(driverPhone));

        Driver deliveringDriver = driverService.findByPhone(driverPhone);

        // Driver gets the complete + route buttons now
        String driverMsg = "🛵 בדרך ללקוח עם משלוח #" + orderId + "\nבסיום לחץ על הכפתור 👇";
        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                driverMsg,
                new WhatsappService.InteractiveButton("delivery_complete_" + orderId, "✅ משלוח הושלם"),
                new WhatsappService.InteractiveButton("driver_show_route", "📍 הצג מסלול")
        );

        // Now notify the customer with the live tracking link + ETA
        try {
            double[] deliveryCoords = resolveCoords(order.getDeliveryAddressPlaceId(), order.getDeliveryAddress());
            Integer deliveryEta = (deliveryCoords != null && deliveringDriver != null)
                    ? etaMinutes(deliveringDriver.getLatitude(), deliveringDriver.getLongitude(), deliveryCoords[0], deliveryCoords[1])
                    : null;

            Customer customer = customerService.getCustomer(order.getCustomerPhone());
            String customerName = customer != null ? customer.getName() : "לקוח";

            Business customerBusiness = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
            String notifBusinessName = (customerBusiness != null && customerBusiness.getName() != null)
                    ? customerBusiness.getName() : "העסק";

            String trackingLink = shortLinkService.createShortLink(baseUrl + "/track/" + order.getTrackingToken());
            String customerEtaText = (deliveryEta != null) ? "כ-" + deliveryEta + " " : "בקרוב";

            String customerMsg = "🛵 ההזמנה שלך בדרך!\n\n" +
                    "👤 " + customerName + ", ההזמנה מ-" + notifBusinessName + " יצאה לדרך.\n" +
                    "📞 טלפון שליח: " + PhoneNumberUtil.toLocalFormat(driverPhone) + "\n" +
                    "⏱️ זמן הגעה משוער: " + customerEtaText + "\n" +
                    "📍 כתובת מסירה: " + order.getDeliveryAddress() + "\n\n" +
                    "🗺️ מעקב חי אחר השליח:\n" + trackingLink;

            whatsappService.sendSmartCustomerMessage(
                    PhoneNumberUtil.normalizePhone(order.getCustomerPhone()),
                    customerMsg,
                    "delivery_status_delivering",
                    Arrays.asList(customerName, notifBusinessName,
                            PhoneNumberUtil.toLocalFormat(driverPhone), customerEtaText, order.getDeliveryAddress(), trackingLink),
                    convoService
            );
            logger.info("[DELIVERY] ✅ Sent tracking link to customer for order #{}", orderId);
        } catch (Exception e) {
            logger.error("[DELIVERY] ⚠️ Error sending tracking link to customer for order #{}: {}", orderId, e.getMessage());
        }

        return null;
    }

    /**
     * Driver marks delivery as complete
     * Changes status: PICKED_UP → DELIVERED
     * Notifies business owner and customer
     */
    public String completeDelivery(long orderId, String driverPhone) {
        logger.info("[DELIVERY] Driver {} completing delivery order #{}",
                PhoneNumberUtil.maskPhoneNumber(driverPhone), orderId);

        // Find the delivery order
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) {
            logger.warn("[DELIVERY] Order #{} not found", orderId);
            return null;
        }

        // Check order is in correct status (PICKED_UP or DELIVERING)
        if (order.getDeliveryStatus() != DeliveryStatus.PICKED_UP
                && order.getDeliveryStatus() != DeliveryStatus.DELIVERING) {
            logger.warn("[DELIVERY] Order #{} not in a completable status: {}", orderId, order.getDeliveryStatus());
            String msg = "🚫 משלוח #" + orderId + " לא במצב נכון לסיום\nמצב נוכחי: " + order.getDeliveryStatus();
            whatsappService.sendSafeText(driverPhone, msg);
            return null;
        }

        // Check driver matches
        if (!order.getPickedUpBy().equals(driverPhone)) {
            logger.warn("[DELIVERY] Driver {} trying to complete order assigned to {}",
                    PhoneNumberUtil.maskPhoneNumber(driverPhone),
                    PhoneNumberUtil.maskPhoneNumber(order.getPickedUpBy()));
            String msg = "🚫 משלוח #" + orderId + " לא שויך לך";
            whatsappService.sendSafeText(driverPhone, msg);
            return null;
        }

        // Update order status
        order.setDeliveryStatus(DeliveryStatus.DELIVERED);
        deliveryOrderRepo.save(order);
        logger.info("[DELIVERY] ✅ Order #{} marked as DELIVERED by driver {}", orderId,
                PhoneNumberUtil.maskPhoneNumber(driverPhone));

        // Send confirmation to driver
        String driverMsg = """
        🎉 משלוח הושלם בהצלחה!
        🆔 מספר הזמנה: %s
        
        תודה על העבודה המעולה! 💪
        """.formatted(order.getId());

        whatsappService.sendSafeText(driverPhone, driverMsg);

        // Notify business owner
        String businessMsg = "✅ משלוח הושלם!\nמשלוח #" + orderId + " הגיע בהצלחה ללקוח\nתודה! 🎉";
        whatsappService.sendSafeText(order.getBusinessPhone(), businessMsg);

        // Notify customer
        try {
            // Fetch business name from database
            Business business = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
            String businessName = business != null ? business.getName() : "העסק";

            // Build variables list in order: {{1}}, {{2}}
            List<String> templateVariables = Arrays.asList(
                    businessName,                    // {{1}} - Business Name
                    order.getDeliveryAddress()       // {{2}} - Delivery Address
            );
            
            String customerMsg = """
                    המשלוח הגיע אליך!
                    ההזמנה שלך מ%s
                    הגיעה בהצלחה!
                    *לכתובת*: %s
                    
                    🏍️ *RYZ* דואגת שהמשלוח יגיע אליך
                     במהירות ובבטחה 💙
                    ✅ תודה על ההזמנה ובתיאבון 😋
                    """.formatted(businessName,
                    order.getDeliveryAddress());

            // Send smart message: Free if in 24-hour window, template if outside
            whatsappService.sendSmartCustomerMessage(
                    order.getCustomerPhone(),
                    customerMsg,
                    "delivery_status_completed",
                    templateVariables,
                    convoService  // You need to inject this
            );

            logger.info("[DELIVERY] ✅ Sent delivery_status_completed template to customer for order #{}",
                    orderId);

        } catch (Exception e) {
            logger.error("[DELIVERY] ⚠️ Error sending template to customer for order #{}: {}",
                    orderId, e.getMessage());
            // Log but don't fail - order processing continues even if template fails
        }
        return null;
    }

    // ---- v1 Trip planning + ETA helpers (single-order) ----

    /** Resolve a destination to [lat,lng] using place_id when available, else address text. */
    private double[] resolveCoords(String placeId, String address) {
        if (placeId != null && !placeId.isEmpty()) {
            double[] c = geoCodingService.geocodeByPlaceId(placeId);
            if (c != null) return c;
        }
        return address != null ? geoCodingService.geocode(address) : null;
    }

    /** Driver -> destination travel time in whole minutes, or null if unavailable. */
    private Integer etaMinutes(double fromLat, double fromLng, double destLat, double destLng) {
        if (fromLat == 0 || fromLng == 0) return null; // no driver GPS yet
        GeoCodingService.TripInfo trip =
                geoCodingService.getTripInfoByCoords(fromLat, fromLng, destLat, destLng);
        return (trip != null && trip.durationMinutes > 0) ? (int) Math.round(trip.durationMinutes) : null;
    }

    /** Short Google Maps directions link to coords; driver's live position is the origin. */
    private String mapsNavLink(double destLat, double destLng) {
        return shortLinkService.createShortLink(
                "https://www.google.com/maps/dir/?api=1&destination=" + destLat + "," + destLng);
    }

    /** Waze navigation link to coords; driver's live position is the origin. */
    private String wazeNavLink(double destLat, double destLng) {
        return shortLinkService.createShortLink(
                "https://waze.com/ul?ll=" + destLat + "," + destLng + "&navigate=yes");
    }

    /** Carrier for stop metadata used in v2 route planning. */
    private static class StopEntry {
        final double[] coords;
        final long orderId;
        final boolean isPickup;
        StopEntry(double[] coords, long orderId, boolean isPickup) {
            this.coords = coords; this.orderId = orderId; this.isPickup = isPickup;
        }
    }

    /**
     * v2: Post-processes optimized stop order to enforce pickup-before-drop.
     * If a delivery drop appears before its business pickup, moves the pickup just before it.
     */
    private List<StopEntry> enforcePickupBeforeDrop(List<double[]> orderedStops,
                                                    List<StopEntry> originalEntries) {
        // Map each coord reference back to its StopEntry (by object identity)
        List<StopEntry> ordered = new ArrayList<>();
        for (int i = 1; i < orderedStops.size(); i++) {
            double[] coords = orderedStops.get(i);
            for (StopEntry e : originalEntries) {
                if (e.coords[0] == coords[0] && e.coords[1] == coords[1]) { ordered.add(e); break; }
            }
        }
        // Scan and fix: for each drop, ensure its pickup precedes it
        boolean fixed = true;
        while (fixed) {
            fixed = false;
            for (int i = 0; i < ordered.size(); i++) {
                StopEntry drop = ordered.get(i);
                if (drop.isPickup) continue;
                for (int j = i + 1; j < ordered.size(); j++) {
                    if (ordered.get(j).isPickup && ordered.get(j).orderId == drop.orderId) {
                        StopEntry pickup = ordered.remove(j);
                        ordered.add(i, pickup);
                        fixed = true;
                        break;
                    }
                }
                if (fixed) break;
            }
        }
        return ordered;
    }

    /**
     * v2: Builds the nav block shown to the driver: next stop link + order summary.
     * Full optimized route is computed internally; only the first stop is surfaced.
     * Public so DriverConversationHandler can call it for the on-demand button.
     */
    public String buildDriverNavBlock(String driverPhone) {
        try {
            Driver driver = driverService.findByPhone(driverPhone);
            if (driver == null || (driver.getLatitude() == 0 && driver.getLongitude() == 0)) return "";

            List<DeliveryOrder> activeOrders = driverService.getActiveDeliveryOrders(driverPhone);
            if (activeOrders.isEmpty()) return "";

            // Count for summary line
            long toPickup = activeOrders.stream()
                    .filter(o -> o.getDeliveryStatus() == DeliveryStatus.ASSIGNED).count();
            long enRoute = activeOrders.stream()
                    .filter(o -> o.getDeliveryStatus() == DeliveryStatus.PICKED_UP
                            || o.getDeliveryStatus() == DeliveryStatus.DELIVERING).count();

            // Build stop entries
            List<StopEntry> entries = new ArrayList<>();
            for (DeliveryOrder o : activeOrders) {
                if (o.getDeliveryStatus() == DeliveryStatus.ASSIGNED) {
                    Business b = businessRepo.findByPhone(o.getBusinessPhone()).orElse(null);
                    if (b != null) {
                        double[] coords = resolveCoords(b.getAddressPlaceId(), b.getAddress());
                        if (coords != null) entries.add(new StopEntry(coords, o.getId(), true));
                    }
                }
                double[] coords = resolveCoords(o.getDeliveryAddressPlaceId(), o.getDeliveryAddress());
                if (coords != null) entries.add(new StopEntry(coords, o.getId(), false));
            }
            if (entries.isEmpty()) return "";

            // Build stops list for Routes API
            List<double[]> stops = new ArrayList<>();
            stops.add(new double[]{driver.getLatitude(), driver.getLongitude()});
            for (StopEntry e : entries) stops.add(e.coords);

            GeoCodingService.OptimizedRoute route = geoCodingService.getOptimizedRoute(stops);
            if (route == null) return "";

            // Enforce pickup-before-drop and get the first stop
            List<StopEntry> orderedEntries = enforcePickupBeforeDrop(route.orderedStops, entries);
            if (orderedEntries.isEmpty()) return "";

            StopEntry nextStop = orderedEntries.get(0);
            int totalMin = (int) Math.round(route.totalMinutes);
            int totalStops = orderedEntries.size();

            // Next stop nav links (single destination — privacy preserved)
            String nextMaps = mapsNavLink(nextStop.coords[0], nextStop.coords[1]);
            String nextWaze = wazeNavLink(nextStop.coords[0], nextStop.coords[1]);
            String stopType = nextStop.isPickup ? "🏪 הבא: איסוף מעסק" : "🏠 הבא: מסירה ללקוח";

            return "\n\n📦 משלוחים פעילים: " + activeOrders.size()
                    + " (" + toPickup + " לאיסוף, " + enRoute + " בדרך ללקוח)"
                    + "\n⏱️ זמן כולל משוער: ~" + totalMin + " דקות (" + totalStops + " עצירות)"
                    + "\n" + stopType + ":"
                    + " "
                    + "\n🗺️ Google Maps: " + nextMaps
                    + " "
                    + "\n🔵 Waze: " + nextWaze;

        } catch (Exception e) {
            logger.warn("[ROUTE] buildDriverNavBlock failed for {}: {}", driverPhone, e.getMessage());
            return "";
        }
    }
    
    private boolean isRouteFeasible(String driverPhone, DeliveryOrder newOrder) {
        try {
            // Get driver's current location
            Driver driver = driverService.findByPhone(driverPhone);
            if (driver == null || driver.getLatitude() == 0 || driver.getLongitude() == 0) {
                logger.warn("[ROUTE] Driver {} has no location data, skipping feasibility check",
                        PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone));
                return true;
            }

            double[] driverCoords = new double[]{driver.getLatitude(), driver.getLongitude()};

            // Build stops for existing active orders
            List<DeliveryOrder> activeOrders = driverService.getActiveDeliveryOrders(driverPhone);
            List<double[]> existingStops = new ArrayList<>();
            for (DeliveryOrder active : activeOrders) {
                // Add the business PICKUP stop only if the order hasn't been picked up yet
                if (active.getDeliveryStatus() == DeliveryStatus.ASSIGNED) {
                    Business activeBusiness = businessRepo.findByPhone(active.getBusinessPhone()).orElse(null);
                    if (activeBusiness != null) {
                        double[] pickupCoords = resolveCoords(activeBusiness.getAddressPlaceId(), activeBusiness.getAddress());
                        if (pickupCoords != null) existingStops.add(pickupCoords);
                    }
                }
                // Always add the DELIVERY (drop-off) stop
                double[] deliveryCoords = resolveCoords(active.getDeliveryAddressPlaceId(), active.getDeliveryAddress());
                if (deliveryCoords != null) existingStops.add(deliveryCoords);
            }

            if (existingStops.isEmpty()) {
                logger.warn("[ROUTE] No geocodable stops for existing orders, skipping feasibility check");
                return true;
            }

            // Build stops for the new order
            Business newBusiness = businessRepo.findByPhone(newOrder.getBusinessPhone()).orElse(null);
            double[] newPickupCoords = (newBusiness != null)
                    ? resolveCoords(newBusiness.getAddressPlaceId(), newBusiness.getAddress())
                    : null;
            double[] newDeliveryCoords = (newOrder.getDeliveryAddressPlaceId() != null && !newOrder.getDeliveryAddressPlaceId().isEmpty())
                    ? geoCodingService.geocodeByPlaceId(newOrder.getDeliveryAddressPlaceId())
                    : geoCodingService.geocode(newOrder.getDeliveryAddress());
            if (newDeliveryCoords == null) {
                logger.warn("[ROUTE] Could not geocode new order #{} delivery address, skipping feasibility check", newOrder.getId());
                return true;
            }

            // Route WITHOUT new order: driver → existing stops
            List<double[]> withoutNewOrder = new ArrayList<>();
            withoutNewOrder.add(driverCoords);
            withoutNewOrder.addAll(existingStops);

            // Route WITH new order: driver → existing stops + new order stops
            List<double[]> withNewOrder = new ArrayList<>(withoutNewOrder);
            if (newPickupCoords != null) withNewOrder.add(newPickupCoords);
            withNewOrder.add(newDeliveryCoords);

            Double durationWithout = geoCodingService.getRouteDurationMinutes(withoutNewOrder);
            Double durationWith = geoCodingService.getRouteDurationMinutes(withNewOrder);

            if (durationWithout == null || durationWith == null) {
                logger.warn("[ROUTE] Routes API returned null for order #{}, skipping feasibility check", newOrder.getId());
                return true;
            }

            double extraMinutes = durationWith - durationWithout;
            int maxMinutes = botConfigService.getMaxExtraDeliveryMinutes();

            logger.info("[ROUTE] Order #{} adds {} min to route (limit: {} min)",
                    newOrder.getId(), String.format("%.1f", extraMinutes), maxMinutes);

            return extraMinutes <= maxMinutes;

        } catch (Exception e) {
            logger.error("[ROUTE] Feasibility check failed for order #{}: {}", newOrder.getId(), e.getMessage(), e);
            return true;
        }
    }
}