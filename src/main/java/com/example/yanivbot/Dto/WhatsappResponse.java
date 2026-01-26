package com.example.yanivbot.Dto;
import lombok.Data;
import lombok.Getter;

import java.util.List;

public class WhatsappResponse {
    
    private List<Message> messages;
    
    public WhatsappResponse(String text){
        this.messages = List.of(new Message(text));
    }
    
    
    public static class Message{
        
        private Text text;

        public Message(String  body) {
            this.text = new Text(body);
        }

        public Text getText() {
            return text;
        }
    }
    
    
    public static class Text{
        private String body;

        public Text(String body) {
            this.body = body;
        }

    }
}
