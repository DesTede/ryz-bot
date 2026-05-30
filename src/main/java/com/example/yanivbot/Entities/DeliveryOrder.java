package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.DeliveryStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
//@NoArgsConstructor
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
    
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 50)
    private DeliveryStatus deliveryStatus;
    
    private double deliveryFee;
    
    @Column(length = 1000)
    private String notes;
    private LocalDateTime createdAt;
    @PrePersist
    public void onCreate(){
        this.createdAt = LocalDateTime.now();
    }
    private LocalDateTime scheduledDispatchTime;
    
    @Column(name = "tracking_token")
    private String trackingToken;
    

    private boolean isDispatched;
    private boolean adminAlerted = false;
    
    

    public DeliveryOrder() {
    }

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
    }

    public long getId() {
        return id;
    }

    public String getBusinessPhone() {
        return businessPhone;
    }

    public void setBusinessPhone(String businessPhone) {
        this.businessPhone = businessPhone;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getPickedUpBy() {
        return pickedUpBy;
    }

    public void setPickedUpBy(String pickedUpBy) {
        this.pickedUpBy = pickedUpBy;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public int getReadyInMinutes() {
        return readyInMinutes;
    }

    public void setReadyInMinutes(int readyInMinutes) {
        this.readyInMinutes = readyInMinutes;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(DeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public double getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(double deliveryFee) {this.deliveryFee = deliveryFee;}
    
    public String getNotes() {return notes;}
    
    public void setNotes(String notes) {
        this.notes = notes;
    }
    
    public boolean isAdminAlerted() {return adminAlerted;}
    
    public void setAdminAlerted(boolean adminAlerted) {this.adminAlerted = adminAlerted;}

    public LocalDateTime getScheduledDispatchTime() {return scheduledDispatchTime;}

    public void setScheduledDispatchTime(LocalDateTime scheduledDispatchTime) {this.scheduledDispatchTime = scheduledDispatchTime;}

    public boolean isDispatched() {
        return isDispatched;
    }

    public void setDispatched(boolean dispatched) {
        isDispatched = dispatched;
    }
}
