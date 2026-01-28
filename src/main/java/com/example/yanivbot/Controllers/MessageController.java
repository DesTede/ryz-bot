package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.BusinessOwnerService;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DeliveryOrderService;
import com.example.yanivbot.Services.TaxiOrderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/message")
public class MessageController {
    
    private final ConversationService convoService;
    private final TaxiOrderService taxiOrderService;
    private final BusinessOwnerService businessOwnerService;
    private final DeliveryOrderService deliveryOrderService;

    public MessageController(ConversationService convoService,
                             TaxiOrderService taxiOrderService,
                             BusinessOwnerService businessOwnerService,
                             DeliveryOrderService deliveryOrderService) {
        this.convoService = convoService;
        this.taxiOrderService = taxiOrderService;
        this.businessOwnerService = businessOwnerService;
        this.deliveryOrderService = deliveryOrderService;
    }

    @PostMapping
    public String receiveMessage(@RequestBody IncomingMessage message){

        if (message.isGroupMessage()) 
            return handleDriverGroupMessage(message);
        
//        System.out.println("phone: " + message.getPhone());
        Conversation convo = convoService.getOrCreate(message.getPhone());
        
        switch (convo.getState()) {
            
            case START:
                System.out.println("state is:" + convo.getState());
                
                if (businessOwnerService.isBusinessOwner(message.getPhone())) {
                    convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                    return
                            "שלום \uD83D\uDC4B " +
                                    "בחר שירות:" +
                                    "עבור מונית - 1" +
                                    " עבור יצירת משלוח - 2";
                            
                }
//                System.out.println("state after hello is:" + convo.getState());
                convoService.updateState(convo, ConversationState.TAXI_SERVICE);
                return
                         "שלום \uD83D\uDC4B " +
                                "בחר שירות:" +
                                 "עבור מונית לחץ - 1";


            case BUSINESS_MENU:
                if (message.getText().equals("1")) {
                    convoService.updateState(convo,ConversationState.TAXI_PICKUP);
                    return
//                            "שלום \uD83D\uDC4B " +
                            "מאיפה לאסוף אותך? '\uD83D\uDCCD' ";
                    
                } else if (message.getText().equals("2")){
                    convo.setTempData("");
                    convoService.updateState(convo,ConversationState.DELIVERY_CUSTOMER_PHONE);
                    
                    return
                            "טלפון הלקוח \uD83D\uDCDE";
                    
                }
                return
                        "אנא בחר 1 או 2";

                
            case TAXI_SERVICE:
                convoService.updateState(convo, ConversationState.TAXI_PICKUP);
                System.out.println("state after choosing service type is:" + convo.getState());
                return
//                            "שלום \uD83D\uDC4B " +
                        "מאיפה לאסוף אותך? '\uD83D\uDCCD' ";
                    
                    
                    
            case TAXI_PICKUP:
                convoService.saveTempData(convo, message.getText());
                convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
    //                System.out.println("state after choosing pickup is:" + convo.getState());
                    return
                        "לאן נוסעים? \uD83D\uDCCD";
            
                    
            case TAXI_DESTINATION:
                String pickUp = convo.getTempData();
                String destination = message.getText();

                String destinationReply = taxiOrderService.
                        createTaxiOrder(message.getPhone(), pickUp, destination);
                
                convo.setTempData(null);
                convoService.updateState(convo, ConversationState.START);
                System.out.println("state after destination is:" + convo.getState());
                
                return destinationReply; 
                
            case DELIVERY_CUSTOMER_PHONE:
                convoService.saveTempData(convo,message.getText());
                convoService.updateState(convo, ConversationState.DELIVERY_ADDRESS);
                return
                        "📦 כתובת משלוח?";
                        

            case DELIVERY_ADDRESS:
//                convoService.saveTempData(convo, message.getText());
                convo.setTempData(convo.getTempData() + "|" + message.getText());
                convoService.updateState(convo, ConversationState.DELIVERY_READY_TIME);
                System.out.println("state after delivery address is:" + convo.getState());
                
                return
                        "⏱️ עוד כמה דקות מוכן?";
            
            case DELIVERY_READY_TIME:
                convo.setTempData(convo.getTempData() + "|" + message.getText());
                convoService.updateState(convo,ConversationState.DELIVERY_PRICE);
                return
                        "💰 כמה לגבות מהלקוח?";
                        
            case DELIVERY_PRICE:
                convo.setTempData(convo.getTempData() + "|" + message.getText());
                convoService.updateState(convo, ConversationState.DELIVERY_NOTES);
                
                return
                        "📝 יש הערות למשלוח? (או כתוב 'אין')";
                
                
            case DELIVERY_NOTES:
                String notes = message.getText();
                String phone = message.getPhone();
                
//                String deliveryReply = deliveryOrderService.createDelivery(convo, phone, notes);
//                return deliveryReply;
                return deliveryOrderService.createDelivery(convo, phone, notes);
                
                
            default:
                convoService.updateState(convo, ConversationState.START);
                return 
                        "משהו השתבש נתחיל מחדש ";

        }
        
    }
    
    private String handleDriverGroupMessage(IncomingMessage message){
        String txt = message.getText().trim();
        
        if (txt.matches("^לקחתי\\s+\\d+$")){
            long orderId = Long.parseLong(txt.split("\\s+ ")[1]);
            return deliveryOrderService.claimOrder(orderId, message.getPhone());
        }
        
        return "";
    }
}
