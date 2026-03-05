package com.example.yanivbot.Controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController("/test")
public class TestController {
    
    @GetMapping
    public String test(){
        return  "Backend is alive";
    }

    @PostMapping
    public ResponseEntity<String> test(@RequestBody String body){
        System.out.println("BODY: " + body);
        return ResponseEntity.ok("OK");
    }
}
