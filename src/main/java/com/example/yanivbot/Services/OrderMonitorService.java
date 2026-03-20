package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
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
//    private final TaxiOrderService taxiOrderService;
    private final DriverService driverService;

    @Value("${admin.phones}")
    private String adminPhones;

    // How many minutes before alerting admin
    private static final int TAXI_ALERT_MINUTES = 5;
    private static final int DELIVERY_ALERT_MINUTES = 5;
    
    

    public OrderMonitorService(TaxiOrderRepository taxiOrderRepo,
                               DeliveryOrderRepository deliveryOrderRepo,
                               WhatsappService whatsappService,
                               DriverService driverService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
    }

//     Runs every minute
    @Scheduled(fixedDelay = 60000)
    public void checkUnclaimedOrders() {
        checkUnclaimedTaxiOrders();
        checkUnclaimedDeliveryOrders();
    }

    private void checkUnclaimedTaxiOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(TAXI_ALERT_MINUTES);
        List<TaxiOrder> unclaimedOrders = taxiOrderRepo
                .findByStatusAndCreatedAtBefore(TaxiOrderStatus.CREATED, cutoff);

        for (TaxiOrder order : unclaimedOrders) {
            if (order.isAdminAlerted()) continue; // already alerted, skip


            whatsappService.notifyAdmins(
                            "הודעה שנשלחת למנהל:" +
                            "⚠️ הזמנת מונית #" + order.getId() + " לא נלקחה כבר " +
                            TAXI_ALERT_MINUTES + " דקות!\n" +
                            "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                            "🎯 לאן: " + order.getDestination() + "\n" +
                            "📞 לקוח: " + order.getPhone());
            
            // notify the customer
//            whatsappService.sendSafeText(order.getPhone(),
//                    "⚠️ טרם נמצא נהג להזמנתך. אנו ממשיכים לחפש...");

            String msg = """
                    🚕 הזמנת מונית לא נלקחה - שידור מחדש!
                    🆔 %d
                    📍 מאיפה: %s
                    🎯 לאן: %s

                    ללקיחת ההזמנה שלח: מונית %d
                    לסיום הנסיעה שלח: הסתיים %d
                    """.formatted(
                    order.getId(),
                    order.getPickUpLocation(),
                    order.getDestination(),
                    order.getId(),
                    order.getId()
            );

            String orderDetails = "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                    "🎯 לאן: " + order.getDestination() + "\n" +
                    "📞 לקוח: " + order.getPhone();

            driverService.dispatchToDrivers(DriverType.TAXI, msg, orderDetails);
            
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

            whatsappService.notifyAdmins(
                            "הודעה שנשלחת למנהל:" +
                            "⚠️ הזמנת משלוח #" + order.getId() + " לא נלקחה כבר " +
                            DELIVERY_ALERT_MINUTES + " דקות!\n" +
                            "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                            "📞 עסק: " + order.getBusinessPhone());
            
            // notify the business
//            whatsappService.sendSafeText(order.getBusinessPhone(),
//                    "⚠️ טרם נמצא שליח להזמנה #" + order.getId() + ". אנו ממשיכים לחפש...");


            // re-broadcast to ALL delivery drivers
            String msg = """
                    📦 משלוח לא נלקח - שידור מחדש!
                    🆔 %d
                    📍 כתובת: %s
                    ⏱️ מוכן בעוד: %d דקות
                    💰 לגבות: %.2f₪
                    📝 הערות: %s

                    ללקיחת ההזמנה שלח: משלוח %d
                    לאחר איסוף שלח: איסוף %d
                    לאחר מסירה שלח: נמסר %d
                    """.formatted(
                    order.getId(),
                    order.getDeliveryAddress(),
                    order.getReadyInMinutes(),
                    order.getDeliveryFee(),
                    order.getNotes() != null ? order.getNotes() : "אין",
                    order.getId(),
                    order.getId(),
                    order.getId()
            );

            String orderDetails = "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                    "📞 עסק: " + order.getBusinessPhone();

            driverService.dispatchToDrivers(DriverType.DELIVERY, msg, orderDetails);

            order.setAdminAlerted(true);
            deliveryOrderRepo.save(order);
        }
    }
}