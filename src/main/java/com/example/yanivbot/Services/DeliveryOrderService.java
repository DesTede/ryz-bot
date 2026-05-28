package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Entities.Customer;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Repositories.BusinessRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;


@Service
public class DeliveryOrderService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryOrderService.class);

    private final ConversationService convoService;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final BusinessRepository businessRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final GeoCodingService geoCodingService;
    private final CustomerService customerService;

    public DeliveryOrderService(ConversationService convoService, DeliveryOrderRepository deliveryOrderRepo, BusinessRepository businessRepo,
                                WhatsappService whatsappService, DriverService driverService,
                                GeoCodingService geoCodingService, CustomerService customerService) {
        this.convoService = convoService;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.businessRepo = businessRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.geoCodingService = geoCodingService;
        this.customerService = customerService;
    }

    public void createDeliveryOrder(String businessPhone, String customerName, String customerPhone, String address,
                                    int readyInMinutes, double price, String notes) {
        customerService.saveOrUpdateCustomer(customerPhone, customerName);

        DeliveryOrder order = new DeliveryOrder();
        order.setBusinessPhone(businessPhone);
        order.setCustomerPhone(customerPhone);
        order.setDeliveryAddress(address);
        order.setReadyInMinutes(readyInMinutes);
        order.setDeliveryFee(price);
        order.setNotes(notes);
        order.setDeliveryStatus(DeliveryStatus.CREATED);

        // IMPORTANT: Save order FIRST and flush to DB before broadcasting
        deliveryOrderRepo.save(order);
        logger.info("Delivery order saved with ID: {}", order.getId());

        // Send confirmation to business owner with order details
        whatsappService.sendSafeText(businessPhone,
                "✅ ההזמנה נוצרה בהצלחה!\n\n📋 סיכום הזמנה מספר " + order.getId() + ":\n📞 שם לקוח: " + customerName + "\n📞 טלפון לקוח: " + customerPhone +
                        "\n📍 כתובת מסירה: " + address + "\n⏱️ זמן הכנה: " + readyInMinutes + " דקות\n💰 סכום לתשלום: ₪" + price +
                        "\n📝 הערות: " + (notes.isEmpty() ? "אין" : notes) + "\n\n🚚 הודעה נשלחה לשליחים קרובים...");

        logger.info("Broadcasting order #{} to drivers...", order.getId());
        broadcastToDrivers(order);
    }

    public void broadcastToDrivers(DeliveryOrder order) {
        String businessName = "Movez";
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
                order.getBusinessPhone(),
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                order.getNotes().isEmpty() ? "אין" : order.getNotes(),
                order.getId()
        );

        double[] coords = geoCodingService.geocode(order.getDeliveryAddress());

        if (coords != null) {
            driverService.dispatchDeliveryToClosestDrivers(msg, coords[0], coords[1],
                    "📍 כתובת: " + order.getDeliveryAddress() + "\n📞 לקוח: " + order.getCustomerPhone(),
                    order.getId());
        } else {
            driverService.dispatchDeliveryToDrivers(msg,
                    "📍 כתובת: " + order.getDeliveryAddress() + "\n📞 לקוח: " + order.getCustomerPhone(),
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
        com.example.yanivbot.Entities.Driver driver = driverService.findByPhone(driverPhone);
        if (driver == null) {
            logger.warn("[DELIVERY] Driver {} not found", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone));
            return "❌ הנהג לא רשום במערכת.";
        }

        // Check if BOTH/TAXI driver has active taxi order - blocks everything
        if (driver.getType() == com.example.yanivbot.Models.DriverType.BOTH ||
                driver.getType() == com.example.yanivbot.Models.DriverType.TAXI) {

            if (driverService.hasActiveTaxiOrder(driverPhone)) {
                com.example.yanivbot.Entities.TaxiOrder activeTaxi = driverService.getActiveTaxiOrder(driverPhone);
                logger.warn("[DELIVERY] BOTH driver {} blocked - has active taxi order #{}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone), activeTaxi.getId());

                String msg = "⚠️ שים לב\nיש לך נסיעה פעילה #" + activeTaxi.getId() + " בדרך\nסיים אותה קודם לאחר מכן תוכל לקבל משלוחים";
                whatsappService.sendSafeText(driverPhone, msg);
                return null; // Message sent directly
            }
        }

        // Check if DELIVERY or BOTH driver has reached delivery order limit
        if (driver.getType() == com.example.yanivbot.Models.DriverType.DELIVERY ||
                driver.getType() == com.example.yanivbot.Models.DriverType.BOTH) {

            if (!driverService.canClaimMoreDeliveries(driverPhone)) {
                int activeCount = driverService.getActiveDeliveryCount(driverPhone);
                logger.warn("[DELIVERY] Driver {} reached delivery limit - has {} active orders", PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone), activeCount);

                String msg = "⚠️ שים לב\nכבר יש לך " + activeCount + " משלוחים פעילים בדרך\nסיים אחד מהם קודם לפני שתוכל לקבל משלוח נוסף";
                whatsappService.sendSafeText(driverPhone, msg);
                return null; // Message sent directly
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
        deliveryOrderRepo.save(order);
        logger.info("[DELIVERY] ✅ Order #{} claimed by driver {}", orderId, PhoneNumberUtil.maskPhoneNumberWithCountryCode(driverPhone));

        Business business = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
        String businessName = (business != null && business.getName() != null) ? business.getName() : "העסק";

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
        );
                
        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                driverConfirmation,
                new WhatsappService.InteractiveButton("delivery_pickup_" + orderId, "✅ אספתי")
        );

        // Notify business owner that driver claimed the order
        String businessNotification = "🚚 נהג בדרך!\nמשלוח #" + orderId + " קורא לנהג\nהנהג בדרך לאיסוף מהעסק שלך 🚀";
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
        deliveryOrderRepo.save(order);
        logger.info("[DELIVERY] ✅ Order #{} marked as PICKED_UP by driver {}", orderId,
                PhoneNumberUtil.maskPhoneNumber(driverPhone));

        Business business = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
        String businessName = (business != null && business.getName() != null) ? business.getName() : "העסק";
        
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
                order.getCustomerPhone(),
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                order.getNotes().isEmpty() ? "אין" : order.getNotes()
        );

        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                driverMsg,
                new WhatsappService.InteractiveButton("delivery_complete_" + orderId, "✅ משלוח הושלם")
        );

        // Notify business owner
        
        String businessMsg = "📦 איסוף משלוח!\nמשלוח #" + orderId + " נאסף על ידי הנהג\nהנהג בדרך ללקוח ✨";
        whatsappService.sendSafeText(order.getBusinessPhone(), businessMsg);

        // Notify customer using WhatsApp template
        try {
            // Fetch customer name from database
            Customer customer = customerService.getCustomer(order.getCustomerPhone());
            String customerName = customer != null ? customer.getName() : "לקוח";
            
            // Fetch business name from database
//            Business business = businessRepo.findByPhone(order.getBusinessPhone()).orElse(null);
//            String businessName = business != null ? business.getName() : "העסק";

            // Generate real-time Google Maps link for driver location
            String mapsLink = generateGoogleMapsLink(driverPhone);

            // Build variables list in order: {{1}}, {{2}}, {{3}}, {{4}}, {{5}}
            List<String> templateVariables = Arrays.asList(
                    customerName,                    // {{1}} - Customer Name
                    businessName,                    // {{2}} - Business Name
                    driverPhone,                     // {{3}} - Driver Phone
                    order.getDeliveryAddress(),      // {{4}} - Delivery Address
                    mapsLink                         // {{5}} - Google Maps Link
            );

            // Send template message
            whatsappService.sendTemplateMessage(
                    whatsappService.normalizePhone(order.getCustomerPhone()),
                    "delivery_status_delivering",
                    templateVariables
            );

            logger.info("[DELIVERY] ✅ Sent delivery_status_delivering template to customer for order #{}",
                    orderId);

        } catch (Exception e) {
            logger.error("[DELIVERY] ⚠️ Error sending template to customer for order #{}: {}",
                    orderId, e.getMessage());
            // Log but don't fail - order processing continues even if template fails
        }

        logger.info("[DELIVERY] ✅ Sent pickup confirmations for order #{}", orderId);
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

        // Check order is in correct status (PICKED_UP)
        if (order.getDeliveryStatus() != DeliveryStatus.PICKED_UP) {
            logger.warn("[DELIVERY] Order #{} not in PICKED_UP status: {}", orderId, order.getDeliveryStatus());
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

            // Send template message
            whatsappService.sendTemplateMessage(
                    whatsappService.normalizePhone(order.getCustomerPhone()),
                    "delivery_status_completed",
                    templateVariables
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

    /**
     * Generate a real-time Google Maps link for driver location
     *
     * The link uses the driver's current coordinates and updates dynamically
     * when the customer opens it in their browser.
     *
     * Format: https://maps.google.com/?q=latitude,longitude
     *
     * @param driverPhone Driver's phone number
     * @return Google Maps URL with driver's current location
     */
    private String generateGoogleMapsLink(String driverPhone) {
        try {
            logger.debug("Generating maps link for driver {}",
                    PhoneNumberUtil.maskPhoneNumber(driverPhone));

            // Fetch driver from database
            Driver driver = driverService.findByPhone(driverPhone);

            if (driver == null) {
                logger.warn("Driver {} not found in database",
                        PhoneNumberUtil.maskPhoneNumber(driverPhone));
                return "https://maps.google.com";
            }

            // Check if driver has valid location
            if (driver.getLatitude() == 0 || driver.getLongitude() == 0) {
                logger.warn("Driver {} has no location data (lat/lng = 0)",
                        PhoneNumberUtil.maskPhoneNumber(driverPhone));
                return "https://maps.google.com";
            }

            // Generate Google Maps link with current coordinates
            // Format: https://maps.google.com/?q=latitude,longitude
            String mapsLink = String.format(
                    "https://maps.google.com/?q=%.6f,%.6f",
                    driver.getLatitude(),
                    driver.getLongitude()
            );

            logger.info("✅ Generated maps link for driver {}: lat={}, lng={}",
                    PhoneNumberUtil.maskPhoneNumber(driverPhone),
                    driver.getLatitude(),
                    driver.getLongitude());

            return mapsLink;

        } catch (Exception e) {
            logger.error("⚠️ Error generating maps link for driver {}: {}",
                    PhoneNumberUtil.maskPhoneNumber(driverPhone),
                    e.getMessage(), e);
            return "https://maps.google.com";
        }
    }
}