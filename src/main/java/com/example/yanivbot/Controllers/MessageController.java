package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Models.IncomingMessage;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Services.ConversationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/message")
public class MessageController {
    
    private final ConversationService convoService;
    private final TaxiOrderRepository taxiOrderRepo;

    public MessageController(ConversationService convoService, TaxiOrderRepository taxiOrderRepo) {
        this.convoService = convoService;
        this.taxiOrderRepo = taxiOrderRepo;
    }

    @PostMapping
    public String receiveMessage(@RequestBody IncomingMessage message){

        Conversation convo = convoService.getOrCreate(message.getPhone());
        
        
        switch (convo.getState()) {
            case START:
                return "שלום \uD83D\uDC4B " +
                        "בחר שירות:" +
                        " מונית - 1" +
                        " משלוח - 2"
                        ;
            
            case CHOOSING_SERVICE:
                if (message.getText().equals("1")){
                    convoService.updateState(convo, ConversationState.TAXI_PICKUP);
                    return "מאיפה לאסוף אותך? '\uD83D\uDCCD' ";
                }
                if ( message.getText().equals("2")){
                    return "לאיזה כתובת המשלוח? ";
                }
                return "אנא בחר 1 או 2";
                
            case TAXI_PICKUP:
                convoService.saveTempData(convo, message.getText());
                convoService.updateState(convo, ConversationState.TAXI_DESTINATION);
                return "לאן נוסעים? \uD83D\uDCCD";
                
            case TAXI_DESTINATION:
                String pickUp = convo.getTempData();
                String destination = message.getText();

                TaxiOrder order = new TaxiOrder(message.getPhone(), pickUp, destination);
                
                taxiOrderRepo.save(order);
                
                convo.setTempData(null);
                convoService.updateState(convo, ConversationState.START);

                return """
                        ✅ ההזמנה התקבלה!
                        🚕 מאיפה: %s
                        🎯 לאן: %s
                        """.formatted(pickUp, destination);
                
                
            default:
                return "משהו השתבשת נתחיל מחדש ";

        }
        
        
        
    }
    
    
}
