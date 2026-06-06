package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.DriverOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DriverOtpRepository extends JpaRepository<DriverOtp, Long> {
    Optional<DriverOtp> findTopByPhoneOrderByExpiresAtDesc(String phone);
}