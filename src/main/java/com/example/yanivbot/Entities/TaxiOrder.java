package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "taxi_order")
public class TaxiOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false)
    private String phone;

    @Column(name = "driver_phone")
    private String driverPhone;

    @Column(nullable = false)
    private String pickUpLocation;

    @Column(name = "pick_up_place_id")        // NEW
    private String pickUpPlaceId;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaxiOrderStatus status;

    @Enumerated(EnumType.STRING)
    private CarType requestedCarType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true, length = 500)
    private String notes;


    @Column(name = "admin_last_alerted_at")
    private LocalDateTime adminLastAlertedAt;

    @Column(name = "redispatch_stopped", nullable = false)
    private boolean redispatchStopped = false;

    @Column(name = "admin_alerted_no_drivers", nullable = false)
    private boolean adminAlertedNoDrivers = false;

    @Column(nullable = false)
    private boolean customerAlertedStaleLocation = false;
    
    @Column(name = "tracking_token")
    private String trackingToken;

    @Version
    private long version;
    
    @Column(name = "estimated_fare")
    private Double estimatedFare;

    // ===== Timing tracking (for arrival duration metrics) =====
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "arrived_at")
    private LocalDateTime arrivedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // ===== Expanding-radius cascade tracking =====
    @Column(name = "last_dispatch_radius_km", nullable = false)
    private double lastDispatchRadiusKm = 0;

    @Column(name = "last_dispatched_at")
    private LocalDateTime lastDispatchedAt;

    @Column(name = "dispatch_origin_lat", nullable = false)
    private double dispatchOriginLat = 0;

    @Column(name = "dispatch_origin_lng", nullable = false)
    private double dispatchOriginLng = 0;

    
    // Constructors
    public TaxiOrder() {
        this.createdAt = LocalDateTime.now();
        this.status = TaxiOrderStatus.CREATED;
        this.adminAlertedNoDrivers = false;
    }

    public TaxiOrder(String phone, String pickUpLocation, String destination, String notes) {
        this();
        this.phone = phone;
        this.pickUpLocation = pickUpLocation;
        this.destination = destination;
        this.notes = notes;
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDriverPhone() {
        return driverPhone;
    }

    public void setDriverPhone(String driverPhone) {
        this.driverPhone = driverPhone;
    }

    public String getPickUpLocation() {
        return pickUpLocation;
    }

    public void setPickUpLocation(String pickUpLocation) {
        this.pickUpLocation = pickUpLocation;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public TaxiOrderStatus getStatus() {
        return status;
    }

    public void setStatus(TaxiOrderStatus status) {
        this.status = status;
    }

    public CarType getRequestedCarType() {
        return requestedCarType;
    }

    public void setRequestedCarType(CarType requestedCarType) {
        this.requestedCarType = requestedCarType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getNotes() {
        return notes;
    }

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

    public boolean isAdminAlertedNoDrivers() {
        return adminAlertedNoDrivers;
    }

    public void setAdminAlertedNoDrivers(boolean adminAlertedNoDrivers) {
        this.adminAlertedNoDrivers = adminAlertedNoDrivers;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public Double getEstimatedFare() {
        return estimatedFare;
    }

    public void setEstimatedFare(Double estimatedFare) {
        this.estimatedFare = estimatedFare;
    }

    public boolean isCustomerAlertedStaleLocation() {
        return customerAlertedStaleLocation;
    }

    public void setCustomerAlertedStaleLocation(boolean customerAlertedStaleLocation) {
        this.customerAlertedStaleLocation = customerAlertedStaleLocation;
    }

    public String getPickUpPlaceId() {
        return pickUpPlaceId;
    }

    public void setPickUpPlaceId(String pickUpPlaceId) {
        this.pickUpPlaceId = pickUpPlaceId;
    }

    public double getLastDispatchRadiusKm() {
        return lastDispatchRadiusKm;
    }

    public void setLastDispatchRadiusKm(double lastDispatchRadiusKm) {
        this.lastDispatchRadiusKm = lastDispatchRadiusKm;
    }

    public LocalDateTime getLastDispatchedAt() {
        return lastDispatchedAt;
    }

    public void setLastDispatchedAt(LocalDateTime lastDispatchedAt) {
        this.lastDispatchedAt = lastDispatchedAt;
    }

    public double getDispatchOriginLat() {
        return dispatchOriginLat;
    }

    public void setDispatchOriginLat(double dispatchOriginLat) {
        this.dispatchOriginLat = dispatchOriginLat;
    }

    public double getDispatchOriginLng() {
        return dispatchOriginLng;
    }

    public void setDispatchOriginLng(double dispatchOriginLng) {
        this.dispatchOriginLng = dispatchOriginLng;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public LocalDateTime getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(LocalDateTime arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}

