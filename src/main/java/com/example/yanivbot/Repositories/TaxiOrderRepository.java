package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.TaxiOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaxiOrderRepository extends JpaRepository<TaxiOrder, Long> {
    
    List<TaxiOrder> findByPhoneAndStatus (String phone, TaxiOrderStatus status);

    List<TaxiOrder> findByStatusAndCreatedAtBefore(TaxiOrderStatus status, LocalDateTime time);    List<TaxiOrder> findByStatusIn(List<TaxiOrderStatus> statuses);
    
    Optional<TaxiOrder> findByDriverPhoneAndStatusIn(String driverPhone, List<TaxiOrderStatus> statuses);
    
    List<TaxiOrder> findByStatus(TaxiOrderStatus status);
    
    List<TaxiOrder> findAllByOrderByCreatedAtDesc();
    
    
}
