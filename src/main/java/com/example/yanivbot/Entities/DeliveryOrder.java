package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.DeliveryStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor
@Data
public class DeliveryOrder {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    private String businessPhone;
    private String customerPhone;
    private String pickedUpBy;
    private String deliveryAddress;
    private int readyInMinutes;
    private DeliveryStatus deliveryStatus;
    private double deliveryFee;
    
    @Column(length = 1000)
    private String notes;
    
    private LocalDateTime createdAt;

    public DeliveryOrder(String businessPhone, String customerPhone, String pickedUpBy, 
                         String deliveryAddress, int readyInMinutes, 
                         DeliveryStatus deliveryStatus, double deliveryFee, String notes) {
        this.businessPhone = businessPhone;
        this.customerPhone = customerPhone;
        this.pickedUpBy = pickedUpBy;
        this.deliveryAddress = deliveryAddress;
        this.readyInMinutes = readyInMinutes;
        this.deliveryStatus = deliveryStatus;
        this.deliveryFee = deliveryFee;
        this.notes = notes;
        this.createdAt = LocalDateTime.now();
    }
    
    
    
    
    
}
