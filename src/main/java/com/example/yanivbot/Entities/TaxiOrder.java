package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
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

    @Column(name = "admin_alerted", nullable = false)
    private boolean adminAlerted = false;

    @Column(name = "admin_alerted_no_drivers", nullable = false)
    private boolean adminAlertedNoDrivers = false;
    
    @Column(name = "tracking_token")
    private String trackingToken;

    // Constructors
    public TaxiOrder() {
        this.createdAt = LocalDateTime.now();
        this.status = TaxiOrderStatus.CREATED;
        this.adminAlerted = false;
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

    public boolean isAdminAlerted() {
        return adminAlerted;
    }

    public void setAdminAlerted(boolean adminAlerted) {
        this.adminAlerted = adminAlerted;
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
}