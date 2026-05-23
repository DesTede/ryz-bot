package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.CarType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Driver {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    private String phone;
    private String name;
    
    private boolean active;
    
    private double latitude;
    private double longitude;
    private LocalDateTime locationUpdatedAt;
    
    @Enumerated(EnumType.STRING)
    private com.example.yanivbot.Models.DriverType type;
    
    // NEW FIELDS FOR CAR INFO
    @Enumerated(EnumType.STRING)
    private CarType carType;  // MOTORCYCLE, PRIVATE_CAR, MINIVAN
    
    private String carColor;   // e.g., "שחור", "לבן", "אדום"
    private String carModel;   // e.g., "טויוטה פריוס", "הונדה סיוויק"
    
    public Driver() {
    }

    public Driver(String phone, String name, com.example.yanivbot.Models.DriverType type) {
        this.phone = phone;
        this.name = name;
        this.type = type;
        this.active = false;
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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

    public com.example.yanivbot.Models.DriverType getType() {
        return type;
    }

    public void setType(com.example.yanivbot.Models.DriverType type) {
        this.type = type;
    }

    public CarType getCarType() {
        return carType;
    }

    public void setCarType(CarType carType) {
        this.carType = carType;
    }

    public String getCarColor() {
        return carColor;
    }

    public void setCarColor(String carColor) {
        this.carColor = carColor;
    }

    public String getCarModel() {
        return carModel;
    }

    public void setCarModel(String carModel) {
        this.carModel = carModel;
    }
}
