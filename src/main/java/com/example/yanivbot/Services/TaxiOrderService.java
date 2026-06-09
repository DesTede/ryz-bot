package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Handlers.MessageRouter;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TaxiOrderService {

    private static final Logger logger = LoggerFactory.getLogger(TaxiOrderService.class);

    @Value("${app.base-url}")
    private String baseUrl;

    private final ConversationService convoService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final GeoCodingService geoCodingService;
    private final CustomerService customerService;
    private final ShortLinkService shortLinkService;
    

    public TaxiOrderService(ConversationService convoService, TaxiOrderRepository taxiOrderRepo, WhatsappService whatsappService,
                            DriverService driverService, GeoCodingService geoCodingService, CustomerService customerService, ShortLinkService shortLinkService) {
        this.convoService = convoService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.geoCodingService = geoCodingService;
        this.customerService = customerService;
        this.shortLinkService = shortLinkService;
    }

    public void createTaxiOrder(String customerPhone, String pickUp, String destination, String notes, CarType carType) {

        // Prevent duplicate orders
        List<TaxiOrder> existing = taxiOrderRepo.findByPhoneAndStatus(customerPhone, TaxiOrderStatus.CREATED);
        existing.addAll(taxiOrderRepo.findByPhoneAndStatus(customerPhone, TaxiOrderStatus.ASSIGNED));
        existing.addAll(taxiOrderRepo.findByPhoneAndStatus(customerPhone, TaxiOrderStatus.CONFIRMED));
        if (!existing.isEmpty()) {
            logger.warn("Duplicate order attempt by {} — already has active order", customerPhone);
            whatsappService.sendSafeText(customerPhone, "⚠️ יש לך הזמנה פעילה כבר במערכת. אנא המתן לנהג שיאסוף אותך.");
            return;
        }
        
        // Save customer
        customerService.recordTaxiOrder(customerPhone);
        
        TaxiOrder taxiOrder = new TaxiOrder(customerPhone, pickUp, destination, notes);
        taxiOrder.setRequestedCarType(carType);
        taxiOrderRepo.save(taxiOrder);

        broadcastToDrivers(taxiOrder);
        // Don't send message here - the handler will send it
    }

    public void broadcastToDrivers(TaxiOrder order) {
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

        double[] coords = geoCodingService.geocode(order.getPickUpLocation());

        String orderDetails = "🚗 סוג כלי רכב: " + order.getRequestedCarType().getHebrewName() + "\n" +
                "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                "🎯 לאן: " + order.getDestination() + "\n" +
                "📞 לקוח: " + order.getPhone();

        if (coords != null) {
            driverService.dispatchToClosestDrivers(DriverType.TAXI, msg, coords[0], coords[1], orderDetails, order.getId(), order.getRequestedCarType());
        } else {
            driverService.dispatchToDrivers(DriverType.TAXI, msg, orderDetails, order.getId(), order.getRequestedCarType());
        }
    }


    public String claimTaxiOrder(long orderId, String driverPhone) {
        TaxiOrder activeOrder = taxiOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone, List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                .orElse(null);

        if (activeOrder != null)
            return "⚠️ שים לב\nכבר משויכת אליך נסיעה פעילה #" + activeOrder.getId() + " 🚗\n📍 יש לסיים אותה לפני קבלת נסיעה חדשה";

        Driver driver = driverService.findByPhone(driverPhone);
        if (driver != null && driver.getType() == DriverType.BOTH) {
            if (driverService.hasActiveDeliveryOrders(driverPhone)) {
                int activeDeliveries = driverService.getActiveDeliveryCount(driverPhone);
                logger.warn("[TAXI] BOTH driver {} blocked - has {} active deliveries",
                        PhoneNumberUtil.maskPhoneNumber(driverPhone), activeDeliveries);

                String msg = "⚠️ שים לב\nיש לך " + activeDeliveries + " משלוחים פעילים בדרך\n" +
                        "סיים אותם קודם לפני שתוכל לקבל נסיעות מונית";
                whatsappService.sendSafeText(driverPhone, msg);
                return null; // Message sent
            }
        }
        
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);

        if (order == null)
            return null;

        if (order.getStatus() != TaxiOrderStatus.CREATED) {
            String alreadyTakenMsg = "🚫 נסיעה #" + orderId + " כבר שויכה לנהג אחר\nהישאר זמין — הזמנה חדשה יכולה להגיע בכל רגע 🚀";
            whatsappService.sendSafeText(driverPhone, alreadyTakenMsg);
            return null; // Message sent directly
        }
        
        order.setStatus(TaxiOrderStatus.ASSIGNED);
        order.setDriverPhone(driverPhone);
        
        //live location updates
        order.setTrackingToken(UUID.randomUUID().toString());
        
        taxiOrderRepo.save(order);

        // Attempt to notify customer and other drivers, but don't let exceptions break the claim
        try {
            notifyTaxiCustomer(order);
        } catch (Exception e) {
            logger.warn("Error notifying taxi customer for order #{}: {}", orderId, e.getMessage(), e);
        }
        
        // Send confirmation with interactive button for completion
        Driver claimedDriver = driverService.findByPhone(driverPhone);
        String driverLiveLink = (claimedDriver != null && claimedDriver.getLocationToken() != null)
                ?  shortLinkService.createShortLink(baseUrl + "/driver/live/" + claimedDriver.getLocationToken())
                : "";
        
        String confirmationMsg = """
                    
                    🔥 *נסיעה חדשה התקבלה!*
                    -------------------------
                    🆔 *מספר הזמנה:* %s
                    -------------------------
                    📞 טלפון נוסע: %s
                    -------------------------
                    🏁 *בסיום לחץ לסיום נסיעה*
                    -------------------------
                    🚗 *סע בזהירות!* 🙌
                    
                    """.formatted(
                            orderId,
                            PhoneNumberUtil.toLocalFormat(order.getPhone())                           
                            );

        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                confirmationMsg,
                new WhatsappService.InteractiveButton("taxi_complete_" + orderId, "✅ נסיעה הסתיימה")
        );

        return null; // Message already sent via button
    }

    public String completeOrder(long orderId, String driverPhone) {
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getDriverPhone().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getStatus() != TaxiOrderStatus.ASSIGNED && order.getStatus() != TaxiOrderStatus.CONFIRMED)
            return "❌ הזמנה #" + orderId + " לא פעילה.";

        order.setStatus(TaxiOrderStatus.COMPLETED);
        taxiOrderRepo.save(order);

        

        try {
            Conversation convo = convoService.getOrCreate(driverPhone);
            if (convo != null && (convo.getTempData() == null || convo.getTempData().isEmpty())) {
                convoService.saveTempData(convo, "DRIVER_ACTIVE");
            }
        } catch (Exception e) {
            logger.warn("Could not update conversation state after order completion: {}", e.getMessage());
        }

        String customerMsg = "✅ הנסיעה הסתיימה בהצלחה\nתודה שבחרת לנסוע ב־Movez 🙌 🚙";

        whatsappService.sendSafeText(order.getPhone(), customerMsg);
        
        return "🏁 נסיעה #" + orderId + " הסתיימה\nהמערכת סימנה אותך כפנוי לנסיעה הבאה 👍";
    }

    private void notifyTaxiCustomer(TaxiOrder order) {
        try {
            logger.info("Notifying taxi customer for order #{} (phone: {})", order.getId(), order.getPhone());

            Driver driver = driverService.findByPhone(order.getDriverPhone());
            String driverName = driver != null ? driver.getName() : order.getDriverPhone();
            String driverPhone = PhoneNumberUtil.toLocalFormat(order.getDriverPhone());
            
            String vehicleInfo = "";
            if (driver != null && driver.getCarType() != null && driver.getCarModel() != null && driver.getCarColor() != null) {
                String carTypeName = driver.getCarType() != null && driver.getCarType().getHebrewName() != null
                        ? driver.getCarType().getHebrewName()
                        : "כלי רכב";
                vehicleInfo = String.format("\n\n🚘 פרטי הרכב:\n%s • %s • %s",
                        driver.getCarModel(),
                        carTypeName,
                        driver.getCarColor());
            }

            String msg = """
            ✅ *הנהג בדרכו אליך*
            -------------------------
            👤 שם הנהג: %s
            -------------------------
            📞 טלפון: %s%s
            -------------------------
            📍 איסוף: %s
            🎯 יעד: %s
            -------------------------
            """.formatted(
                    driverName,
                    driverPhone,
                    vehicleInfo,
                    order.getPickUpLocation(),
                    order.getDestination()
            );

            if (order.getTrackingToken() != null) {
                msg += "🗺️ מעקב חי אחר הנהג:\n" + shortLinkService.createShortLink(baseUrl + "/track/" + order.getTrackingToken());
            }

//            if (!locationLink.isEmpty()) {
//                msg += "🗺️ צפייה במיקום הנהג:\n\n" + locationLink;
//            }

            whatsappService.sendSafeText(order.getPhone(), msg);
            logger.info("Customer notification sent for order #{}", order.getId());
        } catch (Exception e) {
            logger.error("Error notifying taxi customer for order #{}: {}", order.getId(), e.getMessage(), e);
            throw e; // Re-throw so calling method can handle it
        }
    }

    public String getDriverLocation(String customerPhone) {
        TaxiOrder activeOrder = taxiOrderRepo
                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.ASSIGNED)
                .stream()
                .findFirst()
                .orElse(null);

        if (activeOrder == null) {
            return "❌ אין הזמנה פעילה כרגע.";
        }

        Driver driver = driverService.findByPhone(activeOrder.getDriverPhone());
        if (driver == null) {
            return "❌ לא ניתן למצוא את הנהג.";
        }

        double[] driverLocation = driverService.getDriverLocation(activeOrder.getDriverPhone());
        if (driverLocation == null || driverLocation.length != 2) {
            return "כרגע לא ניתן להציג את מיקום הנהג\n📍 המיקום יתעדכן בקרוב.";
        }

        String locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);
        return "🗺️ מיקום הנהג: " + locationLink;
    }
}