package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;


@RestController
@RequestMapping("/message")
public class MessageController {
    
    private final ConversationService convoService;
    private final TaxiOrderService taxiOrderService;
    private final BusinessOwnerService businessOwnerService;
    private final DeliveryOrderService deliveryOrderService;
    private final WhatsappService whatsappService;

    public MessageController(ConversationService convoService,
                             TaxiOrderService taxiOrderService,
                             BusinessOwnerService businessOwnerService,
                             DeliveryOrderService deliveryOrderService, WhatsappService whatsappService) {
        this.convoService = convoService;
        this.taxiOrderService = taxiOrderService;
        this.businessOwnerService = businessOwnerService;
        this.deliveryOrderService = deliveryOrderService;
        this.whatsappService = whatsappService;
    }
    
    
    
    @GetMapping (produces = "text/plain")
    public String verifyWebHook(
            @RequestParam(name = "hub.mode") String mode,
            @RequestParam(name = "hub.verify_token") String token,
            @RequestParam(name = "hub.challenge") String challenge
    ){

        System.out.println("Webhook verification called");
        System.out.println("mode=" + mode);
        System.out.println("token=" + token);
        System.out.println("challenge=" + challenge);

        if ("subscribe".equals(mode) && "yanivbot_verify".equals(token))
            return challenge;

        return "Verification failed";
    }

    
    
    @PostMapping
    public String receiveMessage(@RequestBody IncomingMessage message){

//        if (message.isGroupMessage()) 
//            return handleDriverGroupMessage(message);

        if (message.isGroupMessage()) {
            // check if it's taxi driver group 
            String txt = message.getText().trim();
            if (txt.matches("^לקחתי\\s+\\d+$")) {
                // taxi driver claiming an order
                return handleTaxiDriverGroupMessage(message);
            }
            // fallback to delivery driver group
            return handleDriverGroupMessage(message);
        }
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
                if (message.getText().equals("1")) {
                    convoService.updateState(convo, ConversationState.TAXI_PICKUP);
                    return
//                            "שלום \uD83D\uDC4B " +
                            "מאיפה לאסוף אותך? '\uD83D\uDCCD' ";

                }
                return
                        "בחר שירות:" +
                                "עבור מונית לחץ - 1";
                
//                convoService.updateState(convo, ConversationState.TAXI_PICKUP);
//                System.out.println("state after choosing service type is:" + convo.getState());
//                return
//                        "מאיפה לאסוף אותך? '\uD83D\uDCCD' ";
                    
                    
                    
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

                whatsappService.sendText(message.getPhone(), "✅ הזמנת מונית התקבלה!\n" + destinationReply);

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
                convoService.updateState(convo, ConversationState.START);
                
                String deliveryReply = deliveryOrderService.createDelivery(convo, phone, notes);
                whatsappService.sendText(phone, "✅ הזמנת משלוח התקבלה!\n" + deliveryReply);

//                return deliveryOrderService.createDelivery(convo, phone, notes);
                return deliveryReply;
                
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

    
    private String handleTaxiDriverGroupMessage(IncomingMessage message){
        String txt = message.getText().trim();

        if (txt.matches("^לקחתי\\s+\\d+$")){
            long orderId = Long.parseLong(txt.split("\\s+ ")[1]);
            
            String reply = taxiOrderService.claimTaxiOrder(orderId, message.getPhone());

            
//            String customerPhone = taxiOrderService.getCustomerPhone(orderId);

            whatsappService.sendText(message.getPhone(), "🚖 המונית שלך נלקחה ע״י הנהג!");

        }

        return "";
    }
}
