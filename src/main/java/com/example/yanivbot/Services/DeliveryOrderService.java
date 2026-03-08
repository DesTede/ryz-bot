package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.Optional;

@Service
public class DeliveryOrderService {

    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverService driverService;
    private final WhatsappService whatsappService;

    public DeliveryOrderService(DeliveryOrderRepository deliveryOrderRepo,
                                DriverService driverService,
                                WhatsappService whatsappService) {
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverService = driverService;
        this.whatsappService = whatsappService;
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
        int readyInMinutes = Integer.parseInt(parts[2].trim());
        double deliveryFee = Double.parseDouble(parts[3].trim());
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

        deliveryOrderRepo.save(deliveryOrder);
        broadcastToDrivers(deliveryOrder);

        convo.setTempData(null);
        convo.setState(ConversationState.START);

        return """
                ✅ משלוח נוצר בהצלחה!
                📦 כתובת: %s
                💰 מחיר: %.2f₪
                📝 הערות: %s
                """.formatted(deliveryAddress, deliveryFee, notes);
    }

    public void broadcastToDrivers(DeliveryOrder order) throws UnsupportedEncodingException {
        String msg = """
                📦 משלוח חדש!
                🆔 %d
                📞 לקוח: %s
                📍 כתובת: %s
                ⏱️ מוכן בעוד: %d דקות
                💰 לגבות: %.2f₪
                📝 הערות: %s

                כתוב:
                לקחתי %d
                """.formatted(
                order.getId(),
                order.getCustomerPhone(),
                order.getDeliveryAddress(),
                order.getReadyInMinutes(),
                order.getDeliveryFee(),
                order.getNotes() != null ? order.getNotes() : "אין",
                order.getId()
        );
        driverService.dispatchToDrivers(DriverType.DELIVERY, msg);
    }

    public String claimOrder(long orderId, String driverPhone) {
        Optional<DeliveryOrder> optionalOrder = deliveryOrderRepo
                .findByIdAndDeliveryStatus(orderId, DeliveryStatus.CREATED);

        if (optionalOrder.isEmpty())
            return "❌ Order #" + orderId + " כבר תפוס על ידי מישהו אחר!";

        DeliveryOrder order = optionalOrder.get();
        order.setPickedUpBy(driverPhone);
        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        notifyOtherDrivers(orderId, driverPhone);

        return """
                ✅ הזמנה #%d תפוסה על ידי %s
                📦 כתובת: %s
                💰 מחיר: %.2f₪
                """.formatted(order.getId(), driverPhone, order.getDeliveryAddress(), order.getDeliveryFee());
    }

    private void notifyOtherDrivers(long orderId, String claimingDriverPhone) {
        String msg = "🚫 הזמנה #%d נלקחה".formatted(orderId);
        driverService.getActiveDrivers(DriverType.DELIVERY).forEach(driver -> {
            if (!driver.getPhone().equals(claimingDriverPhone)) {
                try {
                    whatsappService.sendText(driver.getPhone(), msg);
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}