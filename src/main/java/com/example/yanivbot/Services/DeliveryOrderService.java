package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DeliveryOrderService {

    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverService driverService;
    private final BusinessOwnerService businessOwnerService;
    private final WhatsappService whatsappService;
    private final GeoCodingService geoCodingService;
    
    // How many minutes before ready time to dispatch to drivers
    @Value("${delivery.dispatch.before.minutes:5}")
    private int dispatchBeforeMinutes;

    public DeliveryOrderService(DeliveryOrderRepository deliveryOrderRepo,
                                DriverService driverService, BusinessOwnerService businessOwnerService,
                                WhatsappService whatsappService, GeoCodingService geoCodingService) {
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverService = driverService;
        this.businessOwnerService = businessOwnerService;
        this.whatsappService = whatsappService;
        this.geoCodingService = geoCodingService;
    }
    
    
 
    
    public String createDelivery(Conversation convo, String businessPhone, String notes) {
        String temp = convo.getTempData();

        if (temp == null || temp.isBlank()) {
            convo.setTempData(null);
            convo.setState(ConversationState.START);
            return "❌ משהו השתבש. נתחיל מחדש.";
        }

        String[] parts = temp.split("\\|");
        if (parts.length < 4) {
            convo.setTempData(null);
            convo.setState(ConversationState.START);
            return "❌ משהו השתבש. נתחיל מחדש.";
        }

        String customerPhone = parts[0];
        String deliveryAddress = parts[1];
        int readyInMinutes;
        
        try {
            readyInMinutes = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            whatsappService.sendText(businessPhone, "❌ זמן מוכנות חייב להיות מספר. נתחיל מחדש.");
            convo.setTempData(null);
            convo.setState(ConversationState.START);
            return "";
        }
        
        double deliveryFee;

        try {
            deliveryFee = Double.parseDouble(parts[3].trim());
        } catch (NumberFormatException e) {
            whatsappService.sendText(businessPhone, "❌ מחיר חייב להיות מספר. נתחיל מחדש.");
            convo.setTempData(null);
            convo.setState(ConversationState.START);
            return "";
        }
        
        String finalNotes = notes.equals("אין") ? null : notes;

        DeliveryOrder deliveryOrder = new DeliveryOrder(
                businessPhone,
                customerPhone,
                null,
                deliveryAddress,
                readyInMinutes,
                DeliveryStatus.CREATED,
                deliveryFee,
                finalNotes
        );


        // calculate dispatch time — dispatch X minutes before ready time
        int dispatchDelay = Math.max(0, readyInMinutes - dispatchBeforeMinutes);
        deliveryOrder.setScheduledDispatchTime(LocalDateTime.now().plusMinutes(dispatchDelay));
        deliveryOrder.setDispatched(false);

        deliveryOrderRepo.save(deliveryOrder);
        System.out.println("Delivery order saved with ID: " + deliveryOrder.getId());

        // notify business owner — dispatch will happen automatically by OrderMonitorService
        String confirmationMsg = """
                ✅ משלוח נוצר בהצלחה!
                📦 כתובת: %s
                💰 מחיר: %.2f₪
                📝 הערות: %s
                
                ⏱️ הנהגים יקבלו את ההזמנה בעוד %d דקות.
                לשליחה מיידית שלח: מוכן עכשיו
                לעדכון סטטוס מוכן שלח: מוכן %d
                """.formatted(deliveryAddress, deliveryFee, notes, dispatchDelay, deliveryOrder.getId());

        whatsappService.sendSafeText(businessPhone, confirmationMsg);

        convo.setTempData(null);
        convo.setState(ConversationState.START);

        return "";
    }

    // Called by OrderMonitorService when scheduled dispatch time arrives
    public void broadcastToClosestDrivers(DeliveryOrder order, double lat, double lng) {
        String msg = buildDispatchMessage(order);
        String businessName = businessOwnerService.getBusinessName(order.getBusinessPhone());
        String orderDetails = "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                "🏪 עסק: " + (businessName != null ? businessName : order.getBusinessPhone());
        driverService.dispatchToClosestDrivers(DriverType.DELIVERY, msg, lat, lng, orderDetails, order.getId());
    }

    public void broadcastToDrivers(DeliveryOrder order) {
        String msg = buildDispatchMessage(order);
        String businessName = businessOwnerService.getBusinessName(order.getBusinessPhone());
        String orderDetails = "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                "🏪 עסק: " + (businessName != null ? businessName : order.getBusinessPhone());
        driverService.dispatchToDrivers(DriverType.DELIVERY, msg, orderDetails,order.getId());
    }

    // Business owner sends "מוכן עכשיו" to dispatch immediately
    public String dispatchNow(String businessPhone) {
        DeliveryOrder order = deliveryOrderRepo
                .findFirstByBusinessPhoneAndDeliveryStatusOrderByCreatedAtDesc(
                        businessPhone, DeliveryStatus.CREATED)
                .orElse(null);

        if (order == null) return "❌ לא נמצאה הזמנה פעילה.";
        if (order.isDispatched()) return "❌ ההזמנה כבר נשלחה לנהגים.";

        // trigger immediate dispatch by setting time to past
        order.setScheduledDispatchTime(LocalDateTime.now().minusMinutes(1));
        deliveryOrderRepo.save(order);

        return "✅ ההזמנה תשלח לנהגים עכשיו!";
    }
    
    public String  buildDispatchMessage(DeliveryOrder order) {
        return  """
                הודעה שנשלחת לכל הנהגים:
                📦 משלוח חדש!
                🆔 %d
                📞 לקוח: %s
                📍 כתובת: %s
                ⏱️ מוכן בעוד: %d דקות
                💰 לגבות: %.2f₪
                📝 הערות: %s

                ללקיחת ההזמנה שלח: משלוח %d
                לאחר איסוף שלח: איסוף %d
                לאחר מסירה שלח: נמסר %d
                """.formatted(
                order.getId(),
                order.getCustomerPhone(),
                order.getDeliveryAddress(),
                order.getReadyInMinutes(),
                order.getDeliveryFee(),
                order.getNotes() != null ? order.getNotes() : "אין",
                order.getId(),
                order.getId(),
                order.getId()
        );
    }
    
    public String claimOrder(long orderId, String driverPhone) {

        // Check if driver already has 4 active delivery orders
        List<DeliveryOrder> activeOrders = deliveryOrderRepo
                .findByPickedUpByAndDeliveryStatusIn(driverPhone,
                        List.of(DeliveryStatus.CREATED, DeliveryStatus.PICKED_UP));

        if (activeOrders.size() >= 4)
            return "❌ יש לך כבר 4 משלוחים פעילים. סיים משלוח לפני שתוכל לקחת משלוח חדש.";
        
        Optional<DeliveryOrder> optionalOrder = deliveryOrderRepo
                .findByIdAndDeliveryStatus(orderId, DeliveryStatus.CREATED);
        
        if (optionalOrder.isEmpty())
            return "❌ משלוח #" + orderId + " כבר תפוס על ידי מישהו אחר!";

        DeliveryOrder order = optionalOrder.get();
        order.setPickedUpBy(driverPhone);
        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        
        deliveryOrderRepo.save(order);

        
        notifyOtherDrivers(orderId, driverPhone);
        notifyBusinessOwner(order,driverPhone);
        notifyCustomer(order);
        
        return """
                הודעה שנשלחת לנהג:
                ✅ הזמנה #%d שויכה אליך!
                📦 כתובת: %s
                💰 מחיר: %.2f₪
                📞 טלפון לקוח: %s
                
                לאחר איסוף שלח: איסוף %d
                """.formatted(order.getId(), order.getDeliveryAddress(), order.getDeliveryFee(), order.getCustomerPhone() , order.getId());
    }

    public String markReady(long orderId, String businessPhone) {
        DeliveryOrder order = deliveryOrderRepo
                .findByIdAndDeliveryStatus(orderId, DeliveryStatus.CREATED)
                .orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה או כבר עודכנה.";
        if (!order.getBusinessPhone().equals(businessPhone))
            return "❌ אין לך הרשאה לעדכן הזמנה זו.";

        order.setDeliveryStatus(DeliveryStatus.READY);
        deliveryOrderRepo.save(order);

        // notify driver if already claimed
        if (order.getPickedUpBy() != null) {
            whatsappService.sendSafeText(order.getPickedUpBy(),
                    "✅ הזמנה #" + orderId + " מוכנה לאיסוף!\n📍 " + order.getDeliveryAddress());
        }

        return "✅ הזמנה #" + orderId + " סומנה כמוכנה!";
    }

    public String markPickedUp(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getPickedUpBy().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getDeliveryStatus() != DeliveryStatus.READY &&
                order.getDeliveryStatus() != DeliveryStatus.CREATED)
            return "❌ הזמנה #" + orderId + " לא במצב מתאים לאיסוף.";

        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        notifyCustomer(order);

        // check for nearby open orders
        String extraOrders = checkForNearbyOpenOrders(driverPhone, orderId);

        return "✅ הזמנה #" + orderId + " אוספה! בדרך ללקוח.\n" + extraOrders;
    }

    public String markDelivered(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo
                .findByIdAndDeliveryStatus(orderId, DeliveryStatus.PICKED_UP)
                .orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה או לא באיסוף.";
        if (!order.getPickedUpBy().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";

        order.setDeliveryStatus(DeliveryStatus.DELIVERED);
        deliveryOrderRepo.save(order);

        whatsappService.sendSafeText(order.getBusinessPhone(),
                "✅ הזמנה #" + orderId + " נמסרה ללקוח בהצלחה!");

        String customerPhone = whatsappService.normalizePhone(order.getCustomerPhone());
        whatsappService.sendSafeText(customerPhone,
                "✅ המשלוח שלך הגיע! תודה שהשתמשת בשירות.");

        return "✅ הזמנה #" + orderId + " סומנה כנמסרה. כל הכבוד!";
    }

    public String getDriverLocation(String customerPhone) {
        DeliveryOrder order = deliveryOrderRepo
                .findByCustomerPhoneAndDeliveryStatus(customerPhone, DeliveryStatus.PICKED_UP)
                .orElse(null);

        if (order == null) return "❌ לא נמצאה הזמנה פעילה.";

        Driver driver = driverService.findByPhone(order.getPickedUpBy());

        if (driver == null || driver.getLatitude() == null || driver.getLatitude() == 0)
            return "⚠️ מיקום הנהג אינו זמין כרגע.";

        String mapsLink = "https://www.google.com/maps?q=" +
                driver.getLatitude() + "," + driver.getLongitude();

        return "📍 מיקום הנהג:\n" + mapsLink;
    }

    private String checkForNearbyOpenOrders(String driverPhone, long currentOrderId) {
        Driver driver = driverService.findByPhone(driverPhone);
        if (driver == null || driver.getLatitude() == null || driver.getLatitude() == 0) return "";

        List<DeliveryOrder> openOrders = deliveryOrderRepo
                .findByDeliveryStatusAndPickedUpByIsNull(DeliveryStatus.CREATED)
                .stream()
                .filter(o -> o.getId() != currentOrderId)
                .filter(o -> o.isDispatched()) // only show already dispatched orders
                .toList();

        if (openOrders.isEmpty()) return "";

        List<DeliveryOrder> nearbyOrders = new ArrayList<>();
        for (DeliveryOrder o : openOrders) {
            String businessAddress = businessOwnerService.getBusinessAddress(o.getBusinessPhone());
            if (businessAddress == null) continue;
            double[] coords = geoCodingService.geocode(businessAddress);
            if (coords == null) continue;
            double distance = calculateDistance(driver.getLatitude(), driver.getLongitude(),
                    coords[0], coords[1]);
            if (distance <= 3.0) {
                nearbyOrders.add(o);
            }
        }

        if (nearbyOrders.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n📦 יש הזמנות פתוחות קרובות אליך:\n");
        for (DeliveryOrder o : nearbyOrders) {
            sb.append("🆔 ").append(o.getId())
                    .append(" | 📍 ").append(o.getDeliveryAddress())
                    .append(" | 💰 ").append(o.getDeliveryFee()).append("₪\n")
                    .append("ללקיחה שלח: משלוח ").append(o.getId()).append("\n\n");
        }

        return sb.toString();
    }
    
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
    
    // probably delete this
    private void notifyCustomer(DeliveryOrder order) {
        String customerPhone = whatsappService.normalizePhone(order.getCustomerPhone());
        Driver driver = driverService.findByPhone(order.getPickedUpBy());
        String driverName = driver != null ? driver.getName() : order.getPickedUpBy(); 
        String msg = """
                הודעה שנשלחת ללקוח:
                🛵 המשלוח בדרך!
                📍 מאיפה: %s
                🎯 לאן: %s
                👤 נהג: %s
                📞 טלפון: %s
                
                למעקב אחר מיקום הנהג שלח: "מיקום"
                """.formatted(
                order.getBusinessPhone(),
                order.getDeliveryAddress(),
                driverName,
                order.getPickedUpBy()
        );
        whatsappService.sendSafeText(customerPhone, msg);
    }

    private void notifyBusinessOwner(DeliveryOrder order, String driverPhone) {
        String msg = """
            הודעה שנשלחת לבעל העסק:
            🚗 נהג נטל את ההזמנה #%d!
            📦 כתובת: %s
            📞 נהג: %s
            """.formatted(order.getId(), order.getDeliveryAddress(), driverPhone);
        whatsappService.sendSafeText(order.getBusinessPhone(), msg);
    }

    private void notifyOtherDrivers(long orderId, String claimingDriverPhone) {
        String msg = "🚫 הזמנה #%d נלקחה על ידי נהג אחר".formatted(orderId);
        driverService.getActiveDrivers(DriverType.DELIVERY).forEach(driver -> {
            if (!driver.getPhone().equals(claimingDriverPhone)) {
                whatsappService.sendSafeText(driver.getPhone(), msg);
            }
        });
    }
}