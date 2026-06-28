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
    private String deliveryAddressPlaceId;
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

    @Version
    private long version;

    @Column(nullable = false)
    private boolean customerAlertedStaleLocation = false;
    
    @Column(name = "admin_last_alerted_at")
    private LocalDateTime adminLastAlertedAt;

    @Column(name = "redispatch_stopped", nullable = false)
    private boolean redispatchStopped = false;

    @Column(name = "admin_alerted_no_drivers", nullable = false)
    private boolean adminAlertedNoDrivers = false;

    // ===== Expanding-radius cascade tracking =====
    @Column(name = "last_dispatch_radius_km", nullable = false)
    private double lastDispatchRadiusKm = 0;

    @Column(name = "last_dispatched_at")
    private LocalDateTime lastDispatchedAt;

    @Column(name = "dispatch_origin_lat", nullable = false)
    private double dispatchOriginLat = 0;

    @Column(name = "dispatch_origin_lng", nullable = false)
    private double dispatchOriginLng = 0;

    // ===== Timing tracking (for arrival duration metrics) =====
    @Column(name = "on_the_way_at")
    private LocalDateTime onTheWayAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
    
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

    public LocalDateTime getAdminLastAlertedAt() {
        return adminLastAlertedAt; 
    }
    
    public void setAdminLastAlertedAt(LocalDateTime adminLastAlertedAt) {
        this.adminLastAlertedAt = adminLastAlertedAt;
    }

    public boolean isRedispatchStopped() {
        return redispatchStopped;
    }

    public void setRedispatchStopped(boolean redispatchStopped) {
        this.redispatchStopped = redispatchStopped;
    }

    public LocalDateTime getScheduledDispatchTime() {return scheduledDispatchTime;}

    public void setScheduledDispatchTime(LocalDateTime scheduledDispatchTime) {this.scheduledDispatchTime = scheduledDispatchTime;}

    public boolean isDispatched() {
        return isDispatched;
    }

    public void setDispatched(boolean dispatched) {
        isDispatched = dispatched;
    }

    public boolean isAdminAlertedNoDrivers() {
        return adminAlertedNoDrivers;
    }

    public void setAdminAlertedNoDrivers(boolean adminAlertedNoDrivers) {
        this.adminAlertedNoDrivers = adminAlertedNoDrivers;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public String getDeliveryAddressPlaceId() {
        return deliveryAddressPlaceId;
    }

    public long getVersion() {
        return version;
    }

    public boolean isCustomerAlertedStaleLocation() {
        return customerAlertedStaleLocation;
    }

    public void setDeliveryAddressPlaceId(String deliveryAddressPlaceId) {
        this.deliveryAddressPlaceId = deliveryAddressPlaceId;
    }

    public double getDispatchOriginLng() {
        return dispatchOriginLng;
    }

    public void setDispatchOriginLng(double dispatchOriginLng) {
        this.dispatchOriginLng = dispatchOriginLng;
    }

    public double getDispatchOriginLat() {
        return dispatchOriginLat;
    }

    public void setDispatchOriginLat(double dispatchOriginLat) {
        this.dispatchOriginLat = dispatchOriginLat;
    }

    public LocalDateTime getLastDispatchedAt() {
        return lastDispatchedAt;
    }

    public void setLastDispatchedAt(LocalDateTime lastDispatchedAt) {
        this.lastDispatchedAt = lastDispatchedAt;
    }

    public double getLastDispatchRadiusKm() {
        return lastDispatchRadiusKm;
    }

    public void setLastDispatchRadiusKm(double lastDispatchRadiusKm) {
        this.lastDispatchRadiusKm = lastDispatchRadiusKm;
    }

    public void setCustomerAlertedStaleLocation(boolean customerAlertedStaleLocation) {
        this.customerAlertedStaleLocation = customerAlertedStaleLocation;
    }

    public LocalDateTime getOnTheWayAt() {
        return onTheWayAt;
    }

    public void setOnTheWayAt(LocalDateTime onTheWayAt) {
        this.onTheWayAt = onTheWayAt;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
    
}
