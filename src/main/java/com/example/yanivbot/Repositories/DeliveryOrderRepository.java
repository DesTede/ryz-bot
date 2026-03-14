package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {
    
    List<DeliveryOrder> findByDeliveryStatus(DeliveryStatus deliveryStatus);
    
    Optional<DeliveryOrder> findByIdAndDeliveryStatus(long id, DeliveryStatus status);
    
    List<DeliveryOrder> findAllByOrderByCreatedAtDesc();
    
    
    
}
