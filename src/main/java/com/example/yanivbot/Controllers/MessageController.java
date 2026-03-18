package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Services.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<String> startSession() {
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
    private String processMessage(IncomingMessage message) {

        if (message.getText() == null || message.getText().isBlank()) {
            return "⚠️ אנא שלח הודעת טקסט בלבד";
        }
        
        String txt = message.getText().trim();
        
        //restart check
        switch (txt) {
            case "התחל מחדש", "תפריט", "00" -> {
                Conversation convo = convoService.getOrCreate(message.getPhone());
                convo.setTempData(null);
                convoService.updateState(convo, ConversationState.START);
                return "🔄 מתחילים מחדש! שלח כל הודעה להתחלה.";
            }


            // drivers "clocks in" or "clock out" so that can receive order messages from the bot
            case "התחל משמרת" -> {
                Driver driver = driverService.findByPhone(message.getPhone());
                if (driver == null) return "❌ הטלפון שלך לא רשום במערכת כנהג.";

                Conversation convo = convoService.getOrCreate(message.getPhone());
                convoService.updateState(convo, ConversationState.AWAITING_DRIVER_LOCATION);

                return "📍 כדי להתחיל משמרת עליך לשתף מיקום בזמן אמת.\n" +
                        "שתף מיקום חי ואז תירשם כזמין לקבל הזמנות.";
            }
            case "סיים משמרת" -> {
                driverService.clockOut(message.getPhone());
                Conversation convo = convoService.getOrCreate(message.getPhone());
                if (businessOwnerService.isBusinessOwner(message.getPhone())) {
                    convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                    return """
                            👋 סיימת משמרת!

                            שלום \uD83D\uDC4B בחר שירות:
                            עבור מונית - 1
                            עבור יצירת משלוח - 2
                            (שלח 00 בכל עת לחזרה לתפריט הראשי)""";
                }
            }
        }

        // Driver claim checks
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

        //delivery customer location check
        if (txt.equals("מיקום")) {
            return  deliveryOrderService.getDriverLocation(message.getPhone());
        }

        //delivery order status checks
        if (txt.matches("^מוכן\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markReady(orderId, message.getPhone());
        }

        if (txt.matches("^איסוף\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markPickedUp(orderId, message.getPhone());
        }

        if (txt.matches("^נמסר\\s+\\d+$")) {
            long orderId = Long.parseLong(txt.split("\\s+")[1]);
            return deliveryOrderService.markDelivered(orderId, message.getPhone());
        }
        
        switch (convo.getState()) {

            case START:
                System.out.println("state is:" + convo.getState());

                // show shift options to drivers
                Driver driver = driverService.findByPhone(message.getPhone());
                if (driver != null) {
                    String shiftStatus = driver.isActive() ? "🟢 במשמרת" : "🔴 לא במשמרת";
                    return "שלום " + driver.getName() + " " + shiftStatus + "\n\n" +
                            "השב \"התחל משמרת\" להתחלת משמרת ולקבלת הזמנות\n" +
                            "השב \"סיים משמרת\" לסיום משמרת ולעצירת קבלת הזמנות";
                }
                
                if (businessOwnerService.isBusinessOwner(message.getPhone())) {
                    convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                    convoService.updateState(convo, ConversationState.BUSINESS_MENU);
                    return
                            """
                                    שלום \uD83D\uDC4B \
                                    בחר שירות:\

                                    עבור מונית - 1\

                                    עבור יצירת משלוח - 2\

                                    (שלח 00 בכל עת לחזרה לתפריט הראשי)""";

                }
//                System.out.println("state after hello is:" + convo.getState());
                convoService.updateState(convo, ConversationState.TAXI_SERVICE);
                return
                        """
                                שלום \uD83D\uDC4B \
                                בחר שירות:\

                                עבור מונית לחץ - 1\

                                (שלח 00 בכל עת לחזרה לתפריט הראשי)""";


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
                
            case TAXI_PICKUP:
                convoService.saveTempData(convo, message.getText());
                convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
                    return
                        "לאן נוסעים? \uD83D\uDCCD";


            case TAXI_DESTINATION:
                String pickUp = convo.getTempData();
                String destination = message.getText();

                taxiOrderService.createTaxiOrder(message.getPhone(), pickUp, destination);

                convo.setTempData(null);
                convoService.updateState(convo, ConversationState.START);
                System.out.println("state after destination is:" + convo.getState());
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
                
                convoService.updateState(convo, ConversationState.START);

                deliveryOrderService.createDelivery(convo, phone, notes);
                return "";

            case AWAITING_TAXI_CONFIRMATION:
                if (message.getText().trim().equals("אישור")) {
                    return taxiOrderService.confirmByCustomer(message.getPhone());
                } else if (message.getText().trim().equals("ביטול")) {
                    return taxiOrderService.cancelByCustomer(message.getPhone());
                }
                
                convoService.updateState(convo, ConversationState.START);

                System.out.println("state after confirmation is:" + convo.getState());

                return "אנא שלח אישור או ביטול";
                
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
//                String from = params.get("From").replace("whatsapp:", "");
                Conversation convo = convoService.getOrCreate(from);

                boolean isFirstUpdate = driverService.getDriverLocation(from) == null;
                driverService.updateDriverLocation(from, Double.parseDouble(latitude), Double.parseDouble(longitude));

                // if a driver was waiting to start shift, clock them in now 
                if (convo.getState() == ConversationState.AWAITING_DRIVER_LOCATION) {
                    driverService.clockIn(from);
                    convoService.updateState(convo, ConversationState.START);
                    whatsappService.sendText(from, "✅ המיקום התקבל! התחלת משמרת בהצלחה. תקבל הזמנות מעכשיו.");
                } else if (isFirstUpdate) {
                    whatsappService.sendText(from, "📍 המיקום שלך עודכן!");
                }

                return ResponseEntity.ok().build();
            }
            
            String body = params.get("Body");

            if (body == null || body.isBlank()) {
                whatsappService.sendText(from, "⚠️ אנא שלח הודעת טקסט בלבד");
                return ResponseEntity.ok().build();
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

        return ResponseEntity.ok().build();
    }
}
