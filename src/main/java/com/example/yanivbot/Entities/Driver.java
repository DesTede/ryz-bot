package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.DriverType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
//@NoArgsConstructor
@Data
public class Driver {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String name;
    private String phone;
    private Boolean active;
    @Enumerated(EnumType.STRING)
    private DriverType type;
    private int activeTaxiOrders;
    private int activeDeliveryOrders;
    private Double latitude;
    private Double longitude;
    private LocalDateTime locationUpdatedAt;
    public Driver() {
    }

    public Driver(String name, String phone, Boolean active, DriverType driverType) {
        this.name = name;
        this.phone = phone;
        this.active = active;
        this.type = driverType;
    }

    public Driver(String name, String phone, Boolean active, DriverType type,
                  Double latitude, Double longitude, LocalDateTime locationUpdatedAt,int activeTaxiOrders, int activeDeliveryOrders) {
        this.name = name;
        this.phone = phone;
        this.active = active;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationUpdatedAt = locationUpdatedAt;
        this.activeTaxiOrders = activeTaxiOrders;
        this.activeDeliveryOrders = activeDeliveryOrders;
    }

    public long getId() {
        return id;
    }
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public DriverType getType() {
        return type;
    }

    public void setType(DriverType driverType) {
        this.type = driverType;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public LocalDateTime getLocationUpdatedAt() {
        return locationUpdatedAt;
    }

    public void setLocationUpdatedAt(LocalDateTime locationUpdatedAt) {
        this.locationUpdatedAt = locationUpdatedAt;
    }

    public int getActiveTaxiOrders() {
        return activeTaxiOrders;
    }

    public void setActiveTaxiOrders(int activeTaxiOrders) {
        this.activeTaxiOrders = activeTaxiOrders;
    }

    public int getActiveDeliveryOrders() {
        return activeDeliveryOrders;
    }

    public void setActiveDeliveryOrders(int activeDeliveryOrders) {
        this.activeDeliveryOrders = activeDeliveryOrders;
    }
}
