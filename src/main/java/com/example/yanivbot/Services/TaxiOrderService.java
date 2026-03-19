package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class TaxiOrderService {
    
    private final ConversationService convoService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final GeoCodingService geoCodingService;

    public TaxiOrderService(ConversationService convoService, TaxiOrderRepository taxiOrderRepo, WhatsappService whatsappService,
                            DriverService driverService, GeoCodingService geoCodingService) {
        this.convoService = convoService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.geoCodingService = geoCodingService;
    }

    public String createTaxiOrder(String customerPhone, String pickUp, String destination) {
        TaxiOrder taxiOrder = new TaxiOrder(customerPhone,pickUp, destination);

        taxiOrderRepo.save(taxiOrder);
        System.out.println("Taxi order saved with ID: " + taxiOrder.getId());

        broadcastToDrivers(taxiOrder);
        
        String msg = 
                """
                הודעה ללקוח על הזמנה שנוצרה:
                ✅ ההזמנה אושרה! מחפשים נהג קרוב אליך
                🚕 מאיפה: %s
                🎯 לאן: %s
                """.formatted(pickUp, destination);
        
        whatsappService.sendSafeText(customerPhone, msg);
        System.out.println("Sending confirmation to customer: " + customerPhone);
        
        return "";
    }
    
    public void broadcastToDrivers(TaxiOrder order) {

        String msg =
                """
        הודעת הזמנה חדשה לנהגים קרובים
        🚕 הזמנת מונית חדשה
        🆔 %d
        📍 מאיפה: %s
        🎯 לאן: %s

        ללקיחת ההזמנה השב:
        מונית %d
        לסיום הנסיעה שלח: הסתיים %d
        """.formatted(
                        order.getId(),
                        order.getPickUpLocation(),
                        order.getDestination(),
                        order.getId(),
                        order.getId()
                );

        System.out.println("Broadcasting to drivers for order: " + order.getId());

        double[] coords = geoCodingService.geocode(order.getPickUpLocation());
        System.out.println("Geocoding result: " + (coords != null ? coords[0] + "," + coords[1] : "null"));

        if (coords != null) {
            driverService.dispatchToClosestDrivers(DriverType.TAXI, msg, coords[0], coords[1]);
        } else {
            // fallback to all drivers if geocoding fails
            driverService.dispatchToDrivers(DriverType.TAXI, msg);
        }
    }

    public String claimTaxiOrder(long orderId, String driverPhone) {

        // Check if driver already has an active taxi order
        TaxiOrder activeOrder = taxiOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone, List.of(TaxiOrderStatus.TAKEN, TaxiOrderStatus.CONFIRMED))
                .orElse(null);

        if (activeOrder != null)
            return "❌ כבר יש לך נסיעה פעילה #" + activeOrder.getId() + ". סיים אותה לפני שתיקח נסיעה חדשה.";
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);
        
        if (order == null)
            return null;
        
        if (order.getStatus() != TaxiOrderStatus.CREATED)
            return "❌ הזמנה #" + orderId + " כבר תפוסה על ידי מישהו אחר!";
        
        order.setStatus(TaxiOrderStatus.TAKEN);
        order.setDriverPhone(driverPhone);
        taxiOrderRepo.save(order);

        // notify the customer 
        notifyTaxiCustomer(order);
        
        notifyOtherDrivers(orderId,driverPhone);
        
        
        return "הודעה שנשלחת לנהג" +
                "\n" +
                "✅ נהג!" +
                " הזמנה מספר " + orderId + " שויכה אליך";    
    }

    public String completeOrder(long orderId, String driverPhone) {
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getDriverPhone().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getStatus() != TaxiOrderStatus.TAKEN && order.getStatus() != TaxiOrderStatus.CONFIRMED)
            return "❌ הזמנה #" + orderId + " לא פעילה.";

        order.setStatus(TaxiOrderStatus.COMPLETED);
        taxiOrderRepo.save(order);

        // notify customer
        whatsappService.sendSafeText(order.getPhone(),
                "✅ הנסיעה הסתיימה! תודה שנסעת איתנו.");

        return "✅ נסיעה #" + orderId + " סומנה כהושלמה. אתה פנוי לנסיעה חדשה!";
    }

    
//    public String confirmByCustomer(String customerPhone) {
//        List<TaxiOrder> orders = taxiOrderRepo
//                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.TAKEN);
//        
//        if (orders.isEmpty())
//            return "❌ לא נמצאה הזמנה פעילה";
//        
//        TaxiOrder order = orders.get(0);
//                
//
//        if (order == null)
//            return "❌ לא נמצאה הזמנה פעילה";
//
//        order.setStatus(TaxiOrderStatus.CONFIRMED);
//        taxiOrderRepo.save(order);
//
//        whatsappService.sendSafeText(order.getDriverPhone(),
//                "✅ הלקוח אישר את ההזמנה #" + order.getId());
//
//        convoService.updateStateByPhone(customerPhone, ConversationState.START);
//
//        return "";
//    }
    

//    public String cancelByCustomer(String customerPhone) {
//        List<TaxiOrder> orders = taxiOrderRepo
//                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.TAKEN);
//        
//        if (orders.isEmpty())
//            return "❌ לא נמצאה הזמנה פעילה";
//        
//        TaxiOrder order = orders.get(0); 
//                
//
//        if (order == null)
//            return "❌ לא נמצאה הזמנה פעילה";
//
//        order.setStatus(TaxiOrderStatus.CANCELLED);
//        taxiOrderRepo.save(order);
//
//        whatsappService.sendSafeText(order.getDriverPhone(),
//                "❌ הלקוח ביטל את ההזמנה #" + order.getId());
//
//        convoService.updateStateByPhone(customerPhone, ConversationState.START);
//
//        return "❌ ההזמנה בוטלה.";
//    }
    
    private void notifyTaxiCustomer(TaxiOrder order) {
        String msg = """
        הודעה שנשלחת ללקוח:
        🚕 המונית בדרך!
        📍 מאיפה: %s
        🎯 לאן: %s
        📞 נהג: %s
        
        """.formatted(
                order.getPickUpLocation(),
                order.getDestination(),
                order.getDriverPhone()
        );

        whatsappService.sendSafeText(order.getPhone(), msg);

    }

    private void notifyOtherDrivers(long orderId, String claimingDriverPhone) {
        String message = "🚫 הזמנה #%d נלקחה".formatted(orderId);
        driverService.getActiveDrivers(DriverType.TAXI).forEach(driver -> {
            if (!driver.getPhone().equals(claimingDriverPhone)) {
                whatsappService.sendSafeText(driver.getPhone(), message);
            }
        });
    }
    
}
