package com.example.yanivbot.Entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "driver_otps")
public class DriverOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private boolean used;

    public DriverOtp() {}

    public DriverOtp(String phone, String code, LocalDateTime expiresAt) {
        this.phone = phone;
        this.code = code;
        this.expiresAt = expiresAt;
        this.used = false;
    }

    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public String getCode() { return code; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
}