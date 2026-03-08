package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.DriverType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@Data
public class Driver {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String name;
    private String phone;
    private boolean active;
    private DriverType type;

    public Driver(String name, String phone, boolean active, DriverType driverType) {
        this.name = name;
        this.phone = phone;
        this.active = active;
        this.type = driverType;
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

    public boolean isActive() {
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
}
