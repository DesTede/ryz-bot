package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Customer;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Handlers.DeliveryConversationHandler;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeliveryOrderService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryConversationHandler.class);

    private final ConversationService convoService;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final GeoCodingService geoCodingService;
    private final CustomerService customerService;

    public DeliveryOrderService(ConversationService convoService, DeliveryOrderRepository deliveryOrderRepo,
                                WhatsappService whatsappService, DriverService driverService,
                                GeoCodingService geoCodingService, CustomerService customerService) {
        this.convoService = convoService;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.geoCodingService = geoCodingService;
        this.customerService = customerService;
    }

    public void createDeliveryOrder(String businessPhone, String customerName, String customerPhone, String address,
                                    int readyInMinutes, double price, String notes) {
        customerService.saveOrUpdateCustomer(customerPhone, customerName);

        DeliveryOrder order = new DeliveryOrder();
        order.setBusinessPhone(businessPhone);
        order.setCustomerPhone(customerPhone);
        order.setDeliveryAddress(address);
        order.setReadyInMinutes(readyInMinutes);
        order.setDeliveryFee(price);
        order.setNotes(notes);
        order.setDeliveryStatus(DeliveryStatus.CREATED);

        // IMPORTANT: Save order FIRST and flush to DB before broadcasting
        deliveryOrderRepo.save(order);
        logger.info("Delivery order saved with ID: {}", order.getId());

        // Send confirmation to business owner with order details
        whatsappService.sendSafeText(businessPhone,
                "✅ ההזמנה נוצרה בהצלחה!\n\n📋 סיכום הזמנה מספר " + order.getId() + ":\n📞 שם לקוח: " + customerName + "\n📞 טלפון לקוח: " + customerPhone +
                        "\n📍 כתובת מסירה: " + address + "\n⏱️ זמן הכנה: " + readyInMinutes + " דקות\n💰 סכום לתשלום: ₪" + price +
                        "\n📝 הערות: " + (notes.isEmpty() ? "אין" : notes) + "\n\n🚚 הודעה נשלחה לשליחים קרובים...");

        logger.info("Broadcasting order #{} to drivers...", order.getId());
        broadcastToDrivers(order);
    }

    public void broadcastToDrivers(DeliveryOrder order) {
        String msg = """
        🚚 הזמנת משלוח חדשה
        בית עסק: Movez
        📍 יעד משלוח: %s
        💰 סכום: ₪%s
        📝 הערות: %s
        🆔 מספר הזמנה: %s
        ✅ לקבלת המשלוח לחץ על הכפתור למטה
        """.formatted(
                order.getDeliveryAddress(),
                order.getDeliveryFee(),
                order.getNotes().isEmpty() ? "אין" : order.getNotes(),
                order.getId()
        );

        double[] coords = geoCodingService.geocode(order.getDeliveryAddress());

        if (coords != null) {
            driverService.dispatchDeliveryToClosestDrivers(msg, coords[0], coords[1],
                    "📍 כתובת: " + order.getDeliveryAddress() + "\n📞 לקוח: " + order.getCustomerPhone(),
                    order.getId());
        } else {
            driverService.dispatchDeliveryToDrivers(msg,
                    "📍 כתובת: " + order.getDeliveryAddress() + "\n📞 לקוח: " + order.getCustomerPhone(),
                    order.getId());
        }
    }

    public String markPickedUp(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getPickedUpBy().equals(driverPhone)) return "❌ הזמנה זו לא שויכה אליך.";

        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        return "✅ סימנת איסוף הזמנה #" + orderId + ".";
    }

    public String markDelivered(long orderId, String driverPhone) {
        DeliveryOrder order = deliveryOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getPickedUpBy().equals(driverPhone)) return "❌ הזמנה זו לא שויכה אליך.";

        order.setDeliveryStatus(DeliveryStatus.DELIVERED);
        deliveryOrderRepo.save(order);

        whatsappService.sendSafeText(order.getBusinessPhone(),
                "הזמנה #" + orderId + " הגיעה ליעד!\n✅ הלקוח קיבל את המשלוח");

        whatsappService.sendSafeText(order.getCustomerPhone(),
                "✅ ההזמנה שלך מ־Movez הגיעה בהצלחה!\n🚚 Movez דואגים שהמשלוח יגיע אליך במהירות ובבטחה 💙\n✅ תודה על ההזמנה ובתיאבון 😋");

        return "🏁 הזמנה #" + orderId + " סומנה כמסורה.";
    }
}