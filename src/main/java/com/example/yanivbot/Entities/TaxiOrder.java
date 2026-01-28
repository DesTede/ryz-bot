package com.example.yanivbot.Entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor
@Data
public class TaxiOrder {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    private String phone;
    private  String pickUpLocation;
    private  String destination;
    private LocalDateTime createdAt;

    public TaxiOrder(String phone, String pickUpLocation, String destination) {
        this.phone = phone;
        this.pickUpLocation = pickUpLocation;
        this.destination = destination;
    }
}
