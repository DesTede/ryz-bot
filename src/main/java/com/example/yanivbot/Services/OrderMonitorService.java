package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderMonitorService {

    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final WhatsappService whatsappService;

    @Value("${admin.phone}")
    private String adminPhone;

    // How many minutes before alerting admin
    private static final int TAXI_ALERT_MINUTES = 5;
    private static final int DELIVERY_ALERT_MINUTES = 5;

    public OrderMonitorService(TaxiOrderRepository taxiOrderRepo,
                               DeliveryOrderRepository deliveryOrderRepo,
                               WhatsappService whatsappService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.whatsappService = whatsappService;
    }

     //Runs every minute
//    @Scheduled(fixedDelay = 60000)
//    public void checkUnclaimedOrders() {
//        checkUnclaimedTaxiOrders();
//        checkUnclaimedDeliveryOrders();
//    }

    private void checkUnclaimedTaxiOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(TAXI_ALERT_MINUTES);
        List<TaxiOrder> unclaimedOrders = taxiOrderRepo
                .findByStatusAndCreatedAtBefore(TaxiOrderStatus.CREATED, cutoff);

        for (TaxiOrder order : unclaimedOrders) {
            if (order.isAdminAlerted()) continue; // already alerted, skip

            whatsappService.sendSafeText(adminPhone,
                    "⚠️ הזמנת מונית #" + order.getId() + " לא נלקחה כבר " +
                            TAXI_ALERT_MINUTES + " דקות!\n" +
                            "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                            "🎯 לאן: " + order.getDestination() + "\n" +
                            "📞 לקוח: " + order.getPhone());

            // notify the customer
//            whatsappService.sendSafeText(order.getPhone(),
//                    "⚠️ טרם נמצא נהג להזמנתך. אנו ממשיכים לחפש...");

            order.setAdminAlerted(true);
            taxiOrderRepo.save(order);
        }
    }

    private void checkUnclaimedDeliveryOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(DELIVERY_ALERT_MINUTES);
        List<DeliveryOrder> unclaimedOrders = deliveryOrderRepo
                .findByDeliveryStatusAndCreatedAtBefore(DeliveryStatus.CREATED, cutoff);

        for (DeliveryOrder order : unclaimedOrders) {
            if (order.isAdminAlerted()) continue; // already alerted, skip
            if (order.getPickedUpBy() != null) continue;

            whatsappService.sendSafeText(adminPhone,
                    "⚠️ הזמנת משלוח #" + order.getId() + " לא נלקחה כבר " +
                            DELIVERY_ALERT_MINUTES + " דקות!\n" +
                            "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                            "📞 עסק: " + order.getBusinessPhone());

            // notify the business
//            whatsappService.sendSafeText(order.getBusinessPhone(),
//                    "⚠️ טרם נמצא שליח להזמנה #" + order.getId() + ". אנו ממשיכים לחפש...");

            order.setAdminAlerted(true);
            deliveryOrderRepo.save(order);
        }
    }
}