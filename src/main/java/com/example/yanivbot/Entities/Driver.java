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
    private double latitude;
    private double longitude;
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
                  double latitude, double longitude, LocalDateTime locationUpdatedAt) {
        this.name = name;
        this.phone = phone;
        this.active = active;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationUpdatedAt = locationUpdatedAt;
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

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public LocalDateTime getLocationUpdatedAt() {
        return locationUpdatedAt;
    }

    public void setLocationUpdatedAt(LocalDateTime locationUpdatedAt) {
        this.locationUpdatedAt = locationUpdatedAt;
    }
}
