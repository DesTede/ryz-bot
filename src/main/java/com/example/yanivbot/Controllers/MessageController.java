package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Services.ConversationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/message")
public class MessageController {
    
    private final ConversationService convoService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;

    public MessageController(ConversationService convoService, TaxiOrderRepository taxiOrderRepo, DeliveryOrderRepository deliveryOrderRepo) {
        this.convoService = convoService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
    }

    @PostMapping
    public String receiveMessage(@RequestBody IncomingMessage message){

//        System.out.println("phone: " + message.getPhone());
        Conversation convo = convoService.getOrCreate(message.getPhone());
        
        
        switch (convo.getState()) {
            case START:
//                System.out.println("state is:" + convo.getState());
                convoService.updateState(convo, ConversationState.CHOOSING_SERVICE);
//                System.out.println("state after hello is:" + convo.getState());
                return "שלום \uD83D\uDC4B " +
                        "בחר שירות:" +
                        " מונית - 1" +
                        " משלוח - 2"
                        ;
            
            case CHOOSING_SERVICE:
                if (message.getText().equals("1")){
                    convoService.updateState(convo, ConversationState.TAXI_PICKUP);
//                    System.out.println("state after choosing service type is:" + convo.getState());
                    return "מאיפה לאסוף אותך? '\uD83D\uDCCD' ";
                }
                if ( message.getText().equals("2")){
                    convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
                    System.out.println("state after choosing service type is:" + convo.getState());
                    return "לאיזה כתובת המשלוח? ";
                }
                
                return "אנא בחר 1 או 2";
                
            case TAXI_PICKUP:
                convoService.saveTempData(convo, message.getText());
                convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
//                System.out.println("state after choosing  pickup is:" + convo.getState());
                return "לאן נוסעים? \uD83D\uDCCD";
                
            case TAXI_DESTINATION:
                String pickUp = convo.getTempData();
                String destination = message.getText();

                TaxiOrder order = new TaxiOrder(message.getPhone(), pickUp, destination);
                
                // need to add timestamp
                
                taxiOrderRepo.save(order);
                
                convo.setTempData(null);
                convoService.updateState(convo, ConversationState.START);
                System.out.println("state after destination is:" + convo.getState());

                return """
                        ✅ ההזמנה התקבלה!
                        🚕 מאיפה: %s
                        🎯 לאן: %s
                        """.formatted(pickUp, destination);
                
                
            case DELIVERY_ADDRESS:
                convoService.saveTempData(convo, message.getText());
                convoService.updateState(convo, ConversationState.DELIVERY_NOTES);
                
            case DELIVERY_NOTES:
                String address = convo.getTempData();
                String notes = message.getText();
                DeliveryOrder deliveryOrder = new DeliveryOrder(convo.getPhone(),address, notes);

                deliveryOrderRepo.save(deliveryOrder);

                convo.setTempData(null);
                convoService.updateState(convo, ConversationState.START);
                System.out.println("state after deliveryNotes is:" + convo.getState());
                
                return
                        """
                                ✅ משלוח התקבל!
                                                    📦 כתובת: %s
                                                    📝 הערות: %s
                        """.formatted(address, notes);
                
            default:
                return "משהו השתבשת נתחיל מחדש ";

        }
        
        
    }
    
    
}
