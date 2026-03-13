package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.util.Map;


@RestController
@RequestMapping("/message")
public class MessageController {
    
    private final ConversationService convoService;
    private final DriverService driverService;
    private final TaxiOrderService taxiOrderService;
    private final BusinessOwnerService businessOwnerService;
    private final DeliveryOrderService deliveryOrderService;
    private final WhatsappService whatsappService;

    public MessageController(ConversationService convoService,
                             TaxiOrderService taxiOrderService,
                             BusinessOwnerService businessOwnerService,
                             DeliveryOrderService deliveryOrderService,
                             WhatsappService whatsappService, DriverService driverService) {
        this.convoService = convoService;
        this.taxiOrderService = taxiOrderService;
        this.businessOwnerService = businessOwnerService;
        this.deliveryOrderService = deliveryOrderService;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
    }


    @GetMapping("/start-session")
    public ResponseEntity<String> startSession() throws UnsupportedEncodingException {
        String testPhoneNumber = "972549711059"; // include country code, e.g., 9725xxxxxxx
        String message = "Hello from bot — initiating session";

        whatsappService.sendText(testPhoneNumber, message);

        return ResponseEntity.ok("Outbound message sent to start session");
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
    
    

    
    
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receiveWebhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader Map<String, String> headers) {

        System.out.println("WEBHOOK EVENT RECEIVED");
        System.out.println("Headers: " + headers);
        System.out.println("Payload: " + payload);

        try {

            IncomingMessage message = whatsappService.parseIncomingMessage(payload);

            if (message == null) {
                System.out.println("EVENT_RECEIVED");
                return ResponseEntity.ok("EVENT_RECEIVED");
            }

            String reply = processMessage(message);

            if (reply != null && !reply.isEmpty()) {
                whatsappService.sendText(message.getPhone(), reply);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return ResponseEntity.ok("EVENT_RECEIVED");
    }
    
    
//    @PostMapping
    private String processMessage(IncomingMessage message) throws UnsupportedEncodingException {

        if (message.getText() == null || message.getText().isBlank()) {
            return "⚠️ אנא שלח הודעת טקסט בלבד";
        }
        
        String txt = message.getText().trim();
        
        if (txt.matches("^מונית\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            // try taxi first, then delivery
//            String taxiResult = taxiOrderService.claimTaxiOrder(orderId, message.getPhone());
//            if (taxiResult != null) return taxiResult;
            return taxiOrderService.claimTaxiOrder(orderId, message.getPhone());
        }
        
        if (txt.matches("^משלוח\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.claimOrder(orderId, message.getPhone());
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
                                    "\n" +
                                    "עבור מונית - 1" +
                                    " עבור יצירת משלוח - 2";

                }
//                System.out.println("state after hello is:" + convo.getState());
                convoService.updateState(convo, ConversationState.TAXI_SERVICE);
                return
                         "שלום \uD83D\uDC4B " +
                                "בחר שירות:" +
                                 "\n" +
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

//                whatsappService.sendText(message.getPhone(), "✅ הזמנת מונית התקבלה!\n" + destinationReply);

                return ""; 

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
                try {
                    Integer.parseInt(message.getText().trim());
                } catch (NumberFormatException e) {
                    return "⚠️ אנא הכנס מספר בלבד (לדוגמה: 30)";
                }
                convo.setTempData(convo.getTempData() + "|" + message.getText());
                convoService.updateState(convo,ConversationState.DELIVERY_PRICE);
                return
                        "💰 כמה לגבות מהלקוח?";

            case DELIVERY_PRICE:
                try {
                    Double.parseDouble(message.getText().trim());
                } catch (NumberFormatException e) {
                    return "⚠️ אנא הכנס מספר בלבד (לדוגמה: 50)";
                }
                
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

                deliveryOrderService.createDelivery(convo, phone, notes);
//                whatsappService.sendText(phone, "✅ הזמנת משלוח התקבלה!\n" + deliveryReply);

//                return deliveryOrderService.createDelivery(convo, phone, notes);
                return "";

            default:
                convoService.updateState(convo, ConversationState.START);
                return 
                        "משהו השתבש נתחיל מחדש ";

        }

    }
    
    
    

    @PostMapping(value = "/twilio", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> receiveTwilioWebhook(
            @RequestParam Map<String, String> params) {

        System.out.println("TWILIO WEBHOOK RECEIVED: " + params);

        
        
        try {
            String from = params.get("From").replace("whatsapp:", "");

            String latitude = params.get("Latitude");
            String longitude = params.get("Longitude");

            if (latitude != null && longitude != null) {
                driverService.updateDriverLocation(from, Double.parseDouble(latitude), Double.parseDouble(longitude));
                whatsappService.sendText(from, "📍 המיקום שלך עודכן!");
                return ResponseEntity.ok("EVENT_RECEIVED");
            }
            
            String body = params.get("Body");

            if (body == null || body.isBlank()) {
                whatsappService.sendText(from, "⚠️ אנא שלח הודעת טקסט בלבד");
                return ResponseEntity.ok("EVENT_RECEIVED");
            }
            
            IncomingMessage message = new IncomingMessage();
            message.setPhone(from);
            message.setText(body);

            String reply = processMessage(message);

            if (reply != null && !reply.isEmpty()) {
                whatsappService.sendText(from, reply);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
