package com.example.yanivbot.Services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;


@Service
public class WhatsappService {

    private static final String BASE_URL = "https://graph.facebook.com/v17.0";
    private static final String PHONE_NUMBER_ID = "988041821056873"; // test number
//    private static final String TOKEN = "EAASWJ2DQQEwBQ0lp7ZAJu6YO1OGcn1WZAbgsO7EX5ZA2JE3TOp5CEEY0ksp3DcgVSZBcoW2FoqQRGxnuIwxvGntAGtZCwnTUNRsvJAQQwdS3RmVQkaOuZAPIwQlQ40DCQEZBjoDDfQmZC4L3cZAa3RAFmDxlTZBuhDucPSOyGVX3bwC4M8NgH4pOXftLoag9abSWdvpld5RDZBjkNcBffjS6IIMJfIbpwsp0cGGPV5xErUZCJEDoHpD6URtNi2X1yDmEN3v4YMuCOXD0wy1eYUIfITvwZAFGDYTCnjxs4pQsb1RYZD";
//    private final WebClient webClient;
//
//    public WhatsappService() {
//        this.webClient = WebClient.builder()
//                .baseUrl(BASE_URL)
//                .defaultHeader("Authorization", "Bearer " + TOKEN)
//                .build();
//    }

//    public void sendTextMessage(String to, String message) {
//        webClient.post()
//                .uri("/" + PHONE_NUMBER_ID + "/messages")
//                .bodyValue(new WhatsappMessage(to, message))
//                .retrieve()
//                .bodyToMono(String.class)
//                .doOnError(System.err::println)
//                .subscribe();
//    }

    private static class WhatsappMessage {
        public final String messaging_product = "whatsapp";
        public final String to;
        public final String type = "text";
        public final Text text;

        public WhatsappMessage(String to, String body) {
            this.to = to;
            this.text = new Text(body);
        }

        static class Text {
            public final String body;

            public Text(String body) {
                this.body = body;
            }
        }
    }
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
