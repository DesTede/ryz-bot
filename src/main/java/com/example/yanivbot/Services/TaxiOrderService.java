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
                ✅ ההזמנה התקבלה!
                🚕 מאיפה: %s
                🎯 לאן: %s
                """.formatted(pickUp, destination);
        
        System.out.println("Sending confirmation to customer: " + customerPhone);
        whatsappService.sendSafeText(customerPhone, msg);
        
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
        """.formatted(
                        order.getId(),
                        order.getPickUpLocation(),
                        order.getDestination(),
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

    public String confirmByCustomer(String customerPhone) {
        List<TaxiOrder> orders = taxiOrderRepo
                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.TAKEN);
        
        if (orders.isEmpty())
            return "❌ לא נמצאה הזמנה פעילה";
        
        TaxiOrder order = orders.get(0);
                

        if (order == null)
            return "❌ לא נמצאה הזמנה פעילה";

        order.setStatus(TaxiOrderStatus.CONFIRMED);
        taxiOrderRepo.save(order);

        whatsappService.sendSafeText(order.getDriverPhone(),
                "✅ הלקוח אישר את ההזמנה #" + order.getId());

        convoService.updateStateByPhone(customerPhone, ConversationState.START);

        return "✅ ההזמנה אושרה! המונית בדרך אליך.";
    }
    

    public String cancelByCustomer(String customerPhone) {
        List<TaxiOrder> orders = taxiOrderRepo
                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.TAKEN);
        
        if (orders.isEmpty())
            return "❌ לא נמצאה הזמנה פעילה";
        
        TaxiOrder order = orders.get(0); 
                

        if (order == null)
            return "❌ לא נמצאה הזמנה פעילה";

        order.setStatus(TaxiOrderStatus.CANCELLED);
        taxiOrderRepo.save(order);

        whatsappService.sendSafeText(order.getDriverPhone(),
                "❌ הלקוח ביטל את ההזמנה #" + order.getId());

        convoService.updateStateByPhone(customerPhone, ConversationState.START);

        return "❌ ההזמנה בוטלה.";
    }
    
    private void notifyTaxiCustomer(TaxiOrder order) {
        String msg = """
        הודעה שנשלחת ללקוח:
        🚕 המונית בדרך!
        📍 מאיפה: %s
        🎯 לאן: %s
        📞 נהג: %s
        
        לאישור ההזמנה שלח: אישור
        לביטול ההזמנה שלח: ביטול
        """.formatted(
                order.getPickUpLocation(),
                order.getDestination(),
                order.getDriverPhone()
        );

        whatsappService.sendSafeText(order.getPhone(), msg);
        convoService.updateStateByPhone(order.getPhone(), ConversationState.AWAITING_TAXI_CONFIRMATION);

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
