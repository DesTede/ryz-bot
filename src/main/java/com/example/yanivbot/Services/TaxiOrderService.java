package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.stereotype.Service;

@Service
public class TaxiOrderService {
    
    private final TaxiOrderRepository taxiOrderRepo;
    private final WhatsappService whatsappService;

    public TaxiOrderService(TaxiOrderRepository taxiOrderRepo, WhatsappService whatsappService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.whatsappService = whatsappService;
    }

    public String createTaxiOrder(String customerPhone, String pickUp, String destination){
        TaxiOrder taxiOrder = new TaxiOrder(customerPhone,pickUp, destination);
        
        taxiOrderRepo.save(taxiOrder);
        
        broadcastToDrivers(taxiOrder);
        return
                """
                ✅ ההזמנה התקבלה!
                🚕 מאיפה: %s
                🎯 לאן: %s
                """.formatted(pickUp, destination);
    }
    
    public void broadcastToDrivers(TaxiOrder order){
        String msg =
                """
        🚕 הזמנת מונית חדשה
        🆔 %d
        📍 מאיפה: %s
        🎯 לאן: %s

        כתוב:
        לקחתי %d
        """.formatted(
                        order.getId(),
                        order.getPickUpLocation(),
                        order.getDestination(),
                        order.getId()
                );
        
        whatsappService.sendToGroup( msg);
    }

    public String claimTaxiOrder(long orderId, String driverPhone) {
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElseThrow(() -> new RuntimeException("Taxi not found"));
        
        if (order.getStatus() != TaxiOrderStatus.CREATED)
            return "❌ הזמנה #" + orderId + " כבר תפוסה על ידי מישהו אחר!";
        
        order.setStatus(TaxiOrderStatus.TAKEN);
        order.setDriverPhone(driverPhone);
        taxiOrderRepo.save(order);

        // notify the customer 
        notifyCustomer(order);
        
        //message to the group
        whatsappService.sendToGroup("🚫 הזמנה #%d נלקחה".formatted(orderId));
        
        return "✅ המונית שויכה אליך";    
    }
    
    private void notifyCustomer(TaxiOrder order) {
        String msg = """
        🚕 המונית בדרך!
        📍 מאיפה: %s
        🎯 לאן: %s
        📞 נהג: %s
        """.formatted(
                order.getPickUpLocation(),
                order.getDestination(),
                order.getDriverPhone()
        );

//        System.out.println("Private message to" + order.getPhone() + "\n" + order.getPickUpLocation());
        whatsappService.sendToPrivate(order.getPhone(), msg);
    }
}
