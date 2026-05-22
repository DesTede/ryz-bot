package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DeliveryStatus;
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
        // Parse readyInMinutes
        int readyMin = 0;
        try {
            readyMin = Integer.parseInt(readyInMinutes);
        } catch (NumberFormatException e) {
            readyMin = 0;
        }

        // Parse price
        double priceValue = 0;
        try {
            priceValue = Double.parseDouble(price);
        } catch (NumberFormatException e) {
            priceValue = 0;
        }

        DeliveryOrder order = new DeliveryOrder(businessOwnerPhone, customerPhone, "", address, readyMin, DeliveryStatus.CREATED, priceValue, notes);
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

        if (order.getDeliveryStatus() != DeliveryStatus.READY)
            return "❌ הזמנה #" + orderId + " לא מוכנה.";

        // Check if driver already has active delivery
        List<DeliveryOrder> activeOrders = deliveryOrderRepo
                .findByPickedUpByAndDeliveryStatusIn(driverPhone, List.of(DeliveryStatus.PICKED_UP));

        if (!activeOrders.isEmpty())
            return "❌ כבר יש לך משלוח פעיל. סיים אותו לפני שתיקח משלוח חדש.";

        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        order.setPickedUpBy(driverPhone);
        deliveryOrderRepo.save(order);

        // Notify customer with Google Maps link
        notifyDeliveryCustomer(order);

        return "✅ הזמנה #" + orderId + " שויכה אליך. בדרך למסירה!";
    }

    public String markPickedUp(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (order.getPickedUpBy() == null || !order.getPickedUpBy().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getDeliveryStatus() != DeliveryStatus.PICKED_UP)
            return "❌ הזמנה #" + orderId + " לא במצב הנכון.";

        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        return "✅ סיימת איסוף הזמנה #" + orderId + ".";
    }

    public String markDelivered(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (order.getPickedUpBy() == null || !order.getPickedUpBy().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getDeliveryStatus() != DeliveryStatus.PICKED_UP)
            return "❌ הזמנה #" + orderId + " לא נמצאת בדרך.";

        order.setDeliveryStatus(DeliveryStatus.DELIVERED);
        deliveryOrderRepo.save(order);

        whatsappService.sendSafeText(order.getCustomerPhone(),
                "✅ המשלוח הגיע! תודה על ההזמנה.");

        return "✅ הזמנה #" + orderId + " סומנה כמסורה!";
    }

    private void notifyDeliveryCustomer(DeliveryOrder order) {
        Driver driver = driverService.findByPhone(order.getPickedUpBy());
        String driverName = driver != null ? driver.getName() : order.getPickedUpBy();
        String driverPhone = order.getPickedUpBy();

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
        👤 נהג: %s
        📞 טלפון: %s
        """.formatted(
                order.getDeliveryAddress(),
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
        var activeOrder = deliveryOrderRepo
                .findByCustomerPhoneAndDeliveryStatus(customerPhone, DeliveryStatus.PICKED_UP)
                .orElse(null);

        if (activeOrder == null) {
            return "❌ אין משלוח פעיל כרגע.";
        }

        Driver driver = driverService.findByPhone(activeOrder.getPickedUpBy());
        if (driver == null) {
            return "❌ לא ניתן למצוא את הנהג.";
        }

        double[] driverLocation = driverService.getDriverLocation(activeOrder.getPickedUpBy());
        if (driverLocation == null || driverLocation.length != 2) {
            return "❌ מיקום הנהג לא זמין כרגע.";
        }

        String locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);

        return "🗺️ מיקום הנהג: " + locationLink;
    }

    public String dispatchNow(String businessOwnerPhone) {
        // Find orders ready to dispatch for this business owner
        List<DeliveryOrder> orders = deliveryOrderRepo.findByBusinessPhoneAndDeliveryStatusAndPickedUpByIsNull(businessOwnerPhone, DeliveryStatus.READY);

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
        💰 מחיר: %.2f
        📝 הערות: %s
        
        לקיחת ההזמנה: משלוח %d
        """.formatted(
                order.getId(),
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                order.getNotes(),
                order.getId()
        );

        System.out.println("Broadcasting delivery order " + order.getId() + " to drivers");

        double[] coords = geoCodingService.geocode(order.getDeliveryAddress());

        String orderDetails = "📍 לכתובת: " + order.getDeliveryAddress() + "\n" +
                "💰 מחיר: " + order.getDeliveryFee() + "\n" +
                "📞 לקוח: " + order.getCustomerPhone();

        if (coords != null) {
            driverService.dispatchToClosestDrivers(DriverType.DELIVERY, msg, coords[0], coords[1], orderDetails, order.getId());
        } else {
            driverService.dispatchToDrivers(DriverType.DELIVERY, msg, orderDetails, order.getId());
        }
    }
}