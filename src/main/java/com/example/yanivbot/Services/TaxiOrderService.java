package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
public class TaxiOrderService {
    
    private final TaxiOrderRepository taxiOrderRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;

    public TaxiOrderService(TaxiOrderRepository taxiOrderRepo, WhatsappService whatsappService, DriverService driverService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
    }

    public String createTaxiOrder(String customerPhone, String pickUp, String destination) throws UnsupportedEncodingException {
        TaxiOrder taxiOrder = new TaxiOrder(customerPhone,pickUp, destination);

        taxiOrderRepo.save(taxiOrder);


        broadcastToDrivers(taxiOrder);
        return
                """
                הודעה ללקוח על הזמנה שנוצרה:
                ✅ ההזמנה התקבלה!
                🚕 מאיפה: %s
                🎯 לאן: %s
                """.formatted(pickUp, destination);
    }
    
    public void broadcastToDrivers(TaxiOrder order) throws UnsupportedEncodingException {

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
        
        driverService.dispatchToDrivers(DriverType.TAXI,msg);
    }

    public String claimTaxiOrder(long orderId, String driverPhone) throws UnsupportedEncodingException {
        
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
    
    private void notifyTaxiCustomer(TaxiOrder order) throws UnsupportedEncodingException {
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
