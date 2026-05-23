package com.example.yanivbot.Entities;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Data
public class Customer {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    @Column(unique = true, nullable = false)
    private String phone;
    
    private String name;
    private String email;
    
    private LocalDateTime createdAt;
    private LocalDateTime lastOrderAt;
    private int totalOrders;
    
    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.totalOrders = 0;
    }
    
    public Customer() {
    }
    
    public Customer(String phone, String name) {
        this.phone = phone;
        this.name = name;
        this.totalOrders = 0;
    }

    public long getId() {
        return id;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastOrderAt() {
        return lastOrderAt;
    }

    public void setLastOrderAt(LocalDateTime lastOrderAt) {
        this.lastOrderAt = lastOrderAt;
    }

    public int getTotalOrders() {
        return totalOrders;
    }

    public void setTotalOrders(int totalOrders) {
        this.totalOrders = totalOrders;
    }
    
    public void incrementTotalOrders() {
        this.totalOrders++;
        this.lastOrderAt = LocalDateTime.now();
    }
}
