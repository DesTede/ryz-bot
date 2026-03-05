package com.example.yanivbot.Services;

import com.example.yanivbot.Models.IncomingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

@Service
public class WhatsappService {

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;


    @Value("${twilio.account-sid}")
    private String twilioAccountSid;

    @Value("${twilio.auth-token}")
    private String twilioAuthToken;

    @Value("${twilio.whatsapp-number}")
    private String twilioWhatsappNumber;
            
            

    private final RestTemplate restTemplate = new RestTemplate();

    
    // for testing:
    public void sendText(String to, String message) throws UnsupportedEncodingException {
        String accountSid = twilioAccountSid;
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";

        String body = "From=whatsapp:" + twilioWhatsappNumber.replace("+","") +
                "&To=whatsapp:%2B" + to.replace("+","") +
                "&Body=" + java.net.URLEncoder.encode(message,"UTF-8");

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(twilioAccountSid, twilioAuthToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<String> request = new HttpEntity<>(body, headers);

        restTemplate.exchange(url, HttpMethod.POST, request, String.class);
    }
    

//    public void sendText(String to, String message) {
//
//        System.out.println("Attempting to send to: [" + to + "]");
//        
//        String url = "https://graph.facebook.com/v19.0/" + phoneNumberId + "/messages";
//
//        String body = """
//        {
//          "messaging_product": "whatsapp",
//          "to": "%s",
//          "type": "text",
//          "text": { "body": "%s" }
//        }
//        """.formatted(to, message);
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setBearerAuth(accessToken);
//        headers.setContentType(MediaType.APPLICATION_JSON);
//
//        HttpEntity<String> request = new HttpEntity<>(body, headers);
//
//        restTemplate.exchange(url, HttpMethod.POST, request, String.class);
//    }


    public IncomingMessage parseIncomingMessage(Map<String, Object> payload) {

        try {

            List<Map<String, Object>> entry =
                    (List<Map<String, Object>>) payload.get("entry");

            if (entry == null) return null;

            Map<String, Object> changes =
                    ((List<Map<String, Object>>) entry.get(0).get("changes")).get(0);

            Map<String, Object> value =
                    (Map<String, Object>) changes.get("value");

            List<Map<String, Object>> messages =
                    (List<Map<String, Object>>) value.get("messages");

            if (messages == null) return null;

            Map<String, Object> msg = messages.get(0);

            String from = (String) msg.get("from");

            Map<String, Object> textObj =
                    (Map<String, Object>) msg.get("text");

            String text = (String) textObj.get("body");

            IncomingMessage message = new IncomingMessage();
            message.setPhone(from);
            message.setText(text);

            return message;

        } catch (Exception e) {
            return null;
        }
    }
    
    

}