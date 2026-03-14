package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.TaxiOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaxiOrderRepository extends JpaRepository<TaxiOrder, Long> {
    
    Optional<TaxiOrder> findByPhoneAndStatus (String phone, TaxiOrderStatus status);
    
}
