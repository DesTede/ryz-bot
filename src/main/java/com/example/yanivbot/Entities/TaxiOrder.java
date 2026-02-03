package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.TaxiOrderStatus;
import jakarta.persistence.*;
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
    
    private String driverPhone;
    private  String pickUpLocation;
    private  String destination;
    
    @Enumerated(EnumType.STRING)
    private TaxiOrderStatus status;
    private LocalDateTime createdAt;
    
    
    
    @PrePersist
    public void onCreate(){
        this.createdAt = LocalDateTime.now();
    }

    public TaxiOrder(String phone, 
                     String pickUpLocation, String destination) {
        this.phone = phone;
        this.pickUpLocation = pickUpLocation;
        this.destination = destination;
        this.status = status = TaxiOrderStatus.CREATED;
    }
}
