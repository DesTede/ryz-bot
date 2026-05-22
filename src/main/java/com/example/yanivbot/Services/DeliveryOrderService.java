package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DeliveryOrderStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeliveryOrderService {

    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverService driverService;
    private final WhatsappService whatsappService;
    private final GeoCodingService geoCodingService;

    public DeliveryOrderService(DeliveryOrderRepository deliveryOrderRepo, DriverService driverService,
                                WhatsappService whatsappService, GeoCodingService geoCodingService) {
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverService = driverService;
        this.whatsappService = whatsappService;
        this.geoCodingService = geoCodingService;
    }

    public void createDeliveryOrder(String businessOwnerPhone, String customerPhone, String address,
                                    String readyInMinutes, String price, String notes) {
        DeliveryOrder order = new DeliveryOrder(businessOwnerPhone, customerPhone, address, readyInMinutes, price, notes);
        deliveryOrderRepo.save(order);

        System.out.println("Delivery order saved with ID: " + order.getId());

        // Send confirmation to business owner
        whatsappService.sendSafeText(businessOwnerPhone,
                "✅ ההזמנה נוצרה! #" + order.getId() + "\n" +
                        "המשלוח ישודר לנהגים כשתהיה מוכנה");
    }

    public String claimOrder(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null)
            return "❌ הזמנה #" + orderId + " לא נמצאה.";

        if (order.getStatus() != DeliveryOrderStatus.READY)
            return "❌ הזמנה #" + orderId + " לא מוכנה.";

        // Check if driver already has active delivery
        DeliveryOrder activeOrder = deliveryOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone, List.of(DeliveryOrderStatus.PICKED_UP))
                .orElse(null);

        if (activeOrder != null)
            return "❌ כבר יש לך משלוח פעיל. סיים אותו לפני שתיקח משלוח חדש.";

        order.setStatus(DeliveryOrderStatus.PICKED_UP);
        order.setDriverPhone(driverPhone);
        deliveryOrderRepo.save(order);

        // Notify customer with Google Maps link
        notifyDeliveryCustomer(order);

        return "✅ הזמנה #" + orderId + " שויכה אליך. בדרך למסירה!";
    }

    public String markPickedUp(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getDriverPhone().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getStatus() != DeliveryOrderStatus.PICKED_UP)
            return "❌ הזמנה #" + orderId + " לא במצב הנכון.";

        order.setStatus(DeliveryOrderStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        return "✅ סיימת איסוף הזמנה #" + orderId + ".";
    }

    public String markDelivered(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getDriverPhone().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getStatus() != DeliveryOrderStatus.PICKED_UP)
            return "❌ הזמנה #" + orderId + " לא נמצאת בדרך.";

        order.setStatus(DeliveryOrderStatus.DELIVERED);
        deliveryOrderRepo.save(order);

        whatsappService.sendSafeText(order.getCustomerPhone(),
                "✅ המשלוח הגיע! תודה על ההזמנה.");

        return "✅ הזמנה #" + orderId + " סומנה כמסורה!";
    }

    private void notifyDeliveryCustomer(DeliveryOrder order) {
        Driver driver = driverService.findByPhone(order.getDriverPhone());
        String driverName = driver != null ? driver.getName() : order.getDriverPhone();
        String driverPhone = order.getDriverPhone();

        // Get driver's current location
        double[] driverLocation = driverService.getDriverLocation(driverPhone);
        String locationLink = "";
        if (driverLocation != null && driverLocation.length == 2) {
            locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);
        }

        String msg = """
        הודעה שנשלחת ללקוח:
        🚚 המשלוח בדרך!
        📍 לכתובת: %s
         נהג: %s
        📞 טלפון: %s
        """.formatted(
                order.getAddress(),
                driverName,
                driverPhone
        );

        if (!locationLink.isEmpty()) {
            msg += "\n🗺️ מיקום הנהג: " + locationLink;
        }

        whatsappService.sendSafeText(order.getCustomerPhone(), msg);
    }

    /**
     * Get driver's current location as a Google Maps link
     * Used when customer sends "מיקום"
     */
    public String getDriverLocation(String customerPhone) {
        // Find active delivery order for this customer
        DeliveryOrder activeOrder = deliveryOrderRepo
                .findByCustomerPhoneAndStatus(customerPhone, DeliveryOrderStatus.PICKED_UP)
                .orElse(null);

        if (activeOrder == null) {
            return "❌ אין משלוח פעיל כרגע.";
        }

        Driver driver = driverService.findByPhone(activeOrder.getDriverPhone());
        if (driver == null) {
            return "❌ לא ניתן למצוא את הנהג.";
        }

        double[] driverLocation = driverService.getDriverLocation(activeOrder.getDriverPhone());
        if (driverLocation == null || driverLocation.length != 2) {
            return "❌ מיקום הנהג לא זמין כרגע.";
        }

        String locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);

        return "🗺️ מיקום הנהג: " + locationLink;
    }

    public String dispatchNow(String businessOwnerPhone) {
        // Find orders ready to dispatch for this business owner
        List<DeliveryOrder> orders = deliveryOrderRepo.findByBusinessPhoneAndStatus(businessOwnerPhone, DeliveryOrderStatus.READY);

        if (orders.isEmpty()) {
            return "❌ אין הזמנות מוכנות לשידור.";
        }

        for (DeliveryOrder order : orders) {
            broadcastToDrivers(order);
        }

        return "✅ ההזמנות שודרו לנהגים!";
    }

    private void broadcastToDrivers(DeliveryOrder order) {
        String msg = """
        🚚 הזמנת משלוח חדשה
        🆔 %d
        📍 לכתובת: %s
        💰 מחיר: %s
        📝 הערות: %s
        
        לקיחת ההזמנה: משלוח %d
        """.formatted(
                order.getId(),
                order.getAddress(),
                order.getPrice(),
                order.getNotes(),
                order.getId()
        );

        System.out.println("Broadcasting delivery order " + order.getId() + " to drivers");

        double[] coords = geoCodingService.geocode(order.getAddress());

        String orderDetails = "📍 לכתובת: " + order.getAddress() + "\n" +
                "💰 מחיר: " + order.getPrice() + "\n" +
                "📞 לקוח: " + order.getCustomerPhone();

        if (coords != null) {
            driverService.dispatchToClosestDrivers(DriverType.DELIVERY, msg, coords[0], coords[1], orderDetails, order.getId());
        } else {
            driverService.dispatchToDrivers(DriverType.DELIVERY, msg, orderDetails, order.getId());
        }
    }
}
