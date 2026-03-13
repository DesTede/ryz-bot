package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DeliveryOrderService {

    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverService driverService;
    private final BusinessOwnerService businessOwnerService;
    private final WhatsappService whatsappService;
    private final GeoCodingService geoCodingService;

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
        int readyInMinutes = Integer.parseInt(parts[2].trim());

        try {
            readyInMinutes = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            whatsappService.sendText(businessPhone, "❌ זמן מוכנות חייב להיות מספר. נתחיל מחדש.");
            convo.setTempData(null);
            convo.setState(ConversationState.START);
            return "";
        }
        
        double deliveryFee = Double.parseDouble(parts[3].trim());

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

        deliveryOrderRepo.save(deliveryOrder);

        String businessAddress = businessOwnerService.getBusinessAddress(businessPhone);
        double[] coords = businessAddress != null ? geoCodingService.geocode(businessAddress) : null;

        if (coords != null) {
            broadcastToClosestDrivers(deliveryOrder, coords[0], coords[1]);
        } else {
            broadcastToDrivers(deliveryOrder);
        }
        
        whatsappService.sendText(businessPhone, """
                הודעה על יצירת הזמנת משלוח לבעל העסק:
                ✅ משלוח נוצר בהצלחה!
                📦 כתובת: %s
                💰 מחיר: %.2f₪
                📝 הערות: %s
                """.formatted(deliveryAddress, deliveryFee, notes));

        convo.setTempData(null);
        convo.setState(ConversationState.START);
        
        return "";
    }

    public void broadcastToClosestDrivers(DeliveryOrder order, double lat, double lng)  {
        String msg = buildDispatchMessage(order);
        driverService.dispatchToClosestDrivers(DriverType.DELIVERY, msg, lat, lng);
    }

    public void broadcastToDrivers(DeliveryOrder order)  {
        String msg = buildDispatchMessage(order);
        driverService.dispatchToDrivers(DriverType.DELIVERY, msg);
    }

    // need to edit after debugging
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

                ללקיחת ההזמנה השב:
                משלוח %d
                """.formatted(
                order.getId(),
                order.getCustomerPhone(),
                order.getDeliveryAddress(),
                order.getReadyInMinutes(),
                order.getDeliveryFee(),
                order.getNotes() != null ? order.getNotes() : "אין",
                order.getId()
        );
    }

    public String claimOrder(long orderId, String driverPhone) {
        Optional<DeliveryOrder> optionalOrder = deliveryOrderRepo
                .findByIdAndDeliveryStatus(orderId, DeliveryStatus.CREATED);

        if (optionalOrder.isEmpty())
            return "❌ משלוח #" + orderId + " כבר תפוס על ידי מישהו אחר!";

        DeliveryOrder order = optionalOrder.get();
        order.setPickedUpBy(driverPhone);
        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        notifyOtherDrivers(orderId, driverPhone);
        
        notifyCustomer(order);
        
        return """
                הודעה שנשלחת לנהג:
                ✅ הזמנה #%d שויכה אליך!
                📦 כתובת: %s
                💰 מחיר: %.2f₪
                """.formatted(order.getId(), order.getDeliveryAddress(), order.getDeliveryFee());
    }

    // probably delete this
    private void notifyCustomer(DeliveryOrder order) {
        String customerPhone = whatsappService.normalizePhone(order.getCustomerPhone());
        String msg = """
                הודעה שנשלחת ללקוח:
                🛵 המשלוח בדרך!
                📍 מאיפה: %s
                🎯 לאן: %s
                נהג: %s
                """.formatted(
                order.getBusinessPhone(),
                order.getDeliveryAddress(),
                order.getPickedUpBy()
        );

        whatsappService.sendText(customerPhone, msg);
    }
    
    private void notifyOtherDrivers(long orderId, String claimingDriverPhone) {
        String msg = "🚫 הזמנה #%d נלקחה על ידי נהג אחר".formatted(orderId);
        driverService.getActiveDrivers(DriverType.DELIVERY).forEach(driver -> {
            if (!driver.getPhone().equals(claimingDriverPhone)) {
                whatsappService.sendText(driver.getPhone(), msg);
            }
        });
    }
}