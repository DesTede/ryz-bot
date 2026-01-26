package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.DeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {
    
    
}
