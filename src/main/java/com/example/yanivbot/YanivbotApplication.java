package com.example.yanivbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YanivbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(YanivbotApplication.class, args);
    }

}
