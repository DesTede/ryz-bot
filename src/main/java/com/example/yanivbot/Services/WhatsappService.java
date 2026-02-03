package com.example.yanivbot.Services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;


@Service
public class WhatsappService {

    @Value("${whatsapp.token}")
    private String token;

    @Value("${whatsapp.phone-id}")
    private String phoneNumberId;

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendText(String to, String message) {

        String url = "https://graph.facebook.com/v19.0/"
                + phoneNumberId + "/messages";

        String body = """
        {
          "messaging_product": "whatsapp",
          "to": "%s",
          "type": "text",
          "text": { "body": "%s" }
        }
        """.formatted(to, message);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(body, headers);

        restTemplate.exchange(url, HttpMethod.POST, request, String.class);
    }
    
    
    public void sendToGroup(String msg){
        System.out.println("Group:\n" + msg);
    }
    
    public void sendToPrivate(String phone, String msg){
        System.out.println("To" + phone + "\n" + msg) ;
    }
}
