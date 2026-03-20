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
    private final DriverService driverService;
    private final BusinessOwnerService businessOwnerService;
    private final GeoCodingService geoCodingService;

    @Value("${admin.phones}")
    private String adminPhones;

    private static final int TAXI_ALERT_MINUTES = 5;
    private static final int DELIVERY_ALERT_MINUTES = 10;

    public OrderMonitorService(TaxiOrderRepository taxiOrderRepo,
                               DeliveryOrderRepository deliveryOrderRepo,
                               WhatsappService whatsappService,
                               DriverService driverService,
                               BusinessOwnerService businessOwnerService,
                               GeoCodingService geoCodingService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.businessOwnerService = businessOwnerService;
        this.geoCodingService = geoCodingService;
    }

    // Runs every minute — checks unclaimed orders
    @Scheduled(fixedDelay = 60000)
    public void checkUnclaimedOrders() {
        checkUnclaimedTaxiOrders();
        checkUnclaimedDeliveryOrders();
    }

    // Runs every minute — dispatches delivery orders when ready
    @Scheduled(fixedDelay = 60000)
    public void checkOrdersReadyToDispatch() {
        List<DeliveryOrder> ordersToDispatch = deliveryOrderRepo
                .findByDeliveryStatusAndScheduledDispatchTimeBefore(
                        DeliveryStatus.CREATED, LocalDateTime.now());

        for (DeliveryOrder order : ordersToDispatch) {
            if (order.getPickedUpBy() != null) continue; // already claimed
            if (order.isDispatched()) continue; // already dispatched

            System.out.println("Dispatching delivery order #" + order.getId());

            String businessAddress = businessOwnerService.getBusinessAddress(order.getBusinessPhone());
            double[] coords = businessAddress != null ? geoCodingService.geocode(businessAddress) : null;

            String msg = buildDispatchMessage(order);
            String orderDetails = "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                    "📞 עסק: " + order.getBusinessPhone();

            if (coords != null) {
                driverService.dispatchToClosestDrivers(DriverType.DELIVERY, msg, coords[0], coords[1], orderDetails);
            } else {
                driverService.dispatchToDrivers(DriverType.DELIVERY, msg, orderDetails);
            }

            order.setDispatched(true);
            deliveryOrderRepo.save(order);
        }
    }

    private void checkUnclaimedTaxiOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(TAXI_ALERT_MINUTES);
        List<TaxiOrder> unclaimedOrders = taxiOrderRepo
                .findByStatusAndCreatedAtBefore(TaxiOrderStatus.CREATED, cutoff);

        for (TaxiOrder order : unclaimedOrders) {
            if (order.isAdminAlerted()) continue;

            // notify admins
            whatsappService.notifyAdmins(
                    "⚠️ הזמנת מונית #" + order.getId() + " לא נלקחה כבר " +
                            TAXI_ALERT_MINUTES + " דקות!\n" +
                            "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                            "🎯 לאן: " + order.getDestination() + "\n" +
                            "📞 לקוח: " + order.getPhone());

            // notify customer
            whatsappService.sendSafeText(order.getPhone(),
                    "⚠️ טרם נמצא נהג להזמנתך. אנו ממשיכים לחפש...");

            // re-broadcast to ALL drivers
            String msg = """
                    🚕 הזמנת מונית לא נלקחה - שידור מחדש!
                    🆔 %d
                    📍 מאיפה: %s
                    🎯 לאן: %s

                    ללקיחת ההזמנה שלח: מונית %d
                    לסיום הנסיעה שלח: הסתיים %d
                    """.formatted(
                    order.getId(), order.getPickUpLocation(),
                    order.getDestination(), order.getId(), order.getId());

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
            if (order.isAdminAlerted()) continue;
            if (order.getPickedUpBy() != null) continue;
            if (!order.isDispatched()) continue; // only alert if already dispatched to drivers

            // notify admins
            whatsappService.notifyAdmins(
                    "⚠️ הזמנת משלוח #" + order.getId() + " לא נלקחה כבר " +
                            DELIVERY_ALERT_MINUTES + " דקות!\n" +
                            "📍 כתובת: " + order.getDeliveryAddress() + "\n" +
                            "📞 עסק: " + order.getBusinessPhone());

            // notify business
            whatsappService.sendSafeText(order.getBusinessPhone(),
                    "⚠️ טרם נמצא שליח להזמנה #" + order.getId() + ". אנו ממשיכים לחפש...");

            order.setAdminAlerted(true);
            deliveryOrderRepo.save(order);
        }
    }

    private String buildDispatchMessage(DeliveryOrder order) {
        return """
                📦 משלוח חדש!
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
    }
}