package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Repositories.BusinessRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;


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
        📍 יעד משלוח: %s
        💰 סכום: ₪%s
        📝 הערות: %s
        🆔 מספר הזמנה: %s
        ✅ לקבלת המשלוח לחץ על הכפתור למטה
        """.formatted(
                businessName,
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

        // Send confirmation to driver with order details
        String driverConfirmation = """
        🔥 קיבלת משלוח!
        🆔 מספר הזמנה: %s
        📞 טלפון לקוח: %s
        📍 כתובת מסירה: %s
        💰 סכום לתשלום: ₪%s
        📝 הערות: %s
        
        בואו נתחיל 🚀
        """.formatted(
                order.getId(),
                order.getCustomerPhone(),
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                order.getNotes().isEmpty() ? "אין" : order.getNotes()
        );

        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                driverConfirmation,
                new WhatsappService.InteractiveButton("delivery_pickup_" + orderId, "✅ אני באיסוף")
        );

        // Notify business owner that driver claimed the order
        String businessNotification = "🚚 נהג בדרך!\nמשלוח #" + orderId + " קורא לנהג\nהנהג בדרך לאיסוף מהעסק שלך 🚀";
        whatsappService.sendSafeText(order.getBusinessPhone(), businessNotification);

        logger.info("[DELIVERY] ✅ Sent confirmations for order #{}", orderId);
        return null; // Message already sent via button
    }

    
    
    public String markPickedUp(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getPickedUpBy().equals(driverPhone)) return "❌ הזמנה זו לא שויכה אליך.";

        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        return "✅ סימנת איסוף הזמנה #" + orderId + ".";
    }

    public String markDelivered(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getPickedUpBy().equals(driverPhone)) return "❌ הזמנה זו לא שויכה אליך.";

        order.setDeliveryStatus(DeliveryStatus.DELIVERED);
        deliveryOrderRepo.save(order);

        whatsappService.sendSafeText(order.getBusinessPhone(),
                "הזמנה #" + orderId + " הגיעה ליעד!\n✅ הלקוח קיבל את המשלוח");

        whatsappService.sendSafeText(order.getCustomerPhone(),
                "✅ ההזמנה שלך מ־Movez הגיעה בהצלחה!\n🚚 Movez דואגים שהמשלוח יגיע אליך במהירות ובבטחה 💙\n✅ תודה על ההזמנה ובתיאבון 😋");

        return "🏁 הזמנה #" + orderId + " סומנה כמסורה.";
    }
}