package com.example.yanivbot.Entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor
@Data
public class DeliveryOrder {
    
    @Id
    private long id;
    
    private String phone;
    private String deliveryAddress;
    private String notes;
    private LocalDateTime createdAt;

    public DeliveryOrder(String phone, String deliveryAddress, String notes) {
        this.phone = phone;
        this.deliveryAddress = deliveryAddress;
        this.notes = notes;
        this.createdAt = LocalDateTime.now();
    }
}
