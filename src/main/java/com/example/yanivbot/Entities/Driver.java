package com.example.yanivbot.Entities;

import jakarta.persistence.*;
import lombok.Data;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.DriverType;

import java.time.LocalDateTime;

@Entity
@Table(name = "drivers")
@Data
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(unique = true, nullable = false)
    private String phone;

    private String name;
    private boolean active;

    private double latitude;
    private double longitude;
    private LocalDateTime locationUpdatedAt;

    @Enumerated(EnumType.STRING)
    private DriverType type;

    @Enumerated(EnumType.STRING)
    private CarType carType;

    private String carColor;
    private String carModel;

    public Driver() {
    }

    public Driver(String phone, String name, DriverType type) {
        this.phone = phone;
        this.name = name;
        this.type = type;
        this.active = false;
    }

    public Driver(String phone, String name, boolean active, DriverType type) {
        this.phone = phone;
        this.name = name;
        this.active = active;
        this.type = type;
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

    public DriverType getType() {
        return type;
    }

    public void setType(DriverType type) {
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