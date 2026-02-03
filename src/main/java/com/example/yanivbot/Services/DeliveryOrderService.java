package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DeliveryOrderService {
    
    private final DeliveryOrderRepository deliveryOrderRepo;

    public DeliveryOrderService(DeliveryOrderRepository deliveryOrderRepository) {
        this.deliveryOrderRepo = deliveryOrderRepository;
    }
    
    public String createDelivery(Conversation convo, String businessPhone, String notes){
        String temp = convo.getTempData();
        
        if (temp == null || temp.isBlank()){
            convo.setTempData(null);
            convo.setState(ConversationState.START);
            return "❌ משהו השתבש. נתחיל מחדש.";
        }
        String[] parts = temp.split("\\|");
        if (parts.length <4){
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
                finalNotes);

        deliveryOrderRepo.save(deliveryOrder);
        
        convo.setTempData(null);
        convo.setState(ConversationState.START);
        return
                """
                            ✅ משלוח נוצר בהצלחה!
                            📦 כתובת: %s
                            💰 מחיר: %.2f₪
                            📝 הערות: %s
                            """.formatted(parts[1], Double.parseDouble(parts[3]), notes);
    }

    // need to add send to group method
    
    public String claimOrder(long orderId, String driverPhone){
        Optional<DeliveryOrder> optionalOrder = deliveryOrderRepo.
                findByIdAndDeliveryStatus(orderId, DeliveryStatus.CREATED);

        if (optionalOrder.isEmpty())
            return "❌ Order #" + orderId + " כבר תפוס על ידי מישהו אחר!";

        DeliveryOrder order = optionalOrder.get();
        order.setPickedUpBy(driverPhone);
        order.setDeliveryStatus(DeliveryStatus.PICKED_UP);
        deliveryOrderRepo.save(order);

        return """
               ✅ הזמנה #%d תפוסה על ידי %s
               📦 כתובת: %s
               💰 מחיר: %.2f₪
               """.formatted(order.getId(), driverPhone, order.getDeliveryAddress(), order.getDeliveryFee());

//        return "✅ Order #" + orderId + " successfully assigned to you.";
    }
}
