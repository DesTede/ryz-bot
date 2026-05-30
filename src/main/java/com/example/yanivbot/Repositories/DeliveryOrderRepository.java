package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {
    
    List<DeliveryOrder> findByDeliveryStatus(DeliveryStatus deliveryStatus);
    
    List<DeliveryOrder> findByDeliveryStatusIn(List<DeliveryStatus> statuses);

    List<DeliveryOrder> findByBusinessPhoneAndDeliveryStatusAndPickedUpByIsNull(
            String businessPhone, DeliveryStatus status);

    List<DeliveryOrder> findByDeliveryStatusAndPickedUpByIsNull(DeliveryStatus status);

    List<DeliveryOrder> findByDeliveryStatusAndCreatedAtBefore(DeliveryStatus status, LocalDateTime time);
    
    List <DeliveryOrder> findByPickedUpByAndDeliveryStatusIn(String driverPhone, List<DeliveryStatus> statuses);
    
    Optional<DeliveryOrder> findByCustomerPhoneAndDeliveryStatus(String customerPhone, DeliveryStatus status);
    
    Optional<DeliveryOrder> findByIdAndDeliveryStatus(long id, DeliveryStatus status);
    
    List<DeliveryOrder> findAllByOrderByCreatedAtDesc();

    List<DeliveryOrder> findByDeliveryStatusAndScheduledDispatchTimeBefore(
            DeliveryStatus status, LocalDateTime time);

    Optional<DeliveryOrder> findFirstByBusinessPhoneAndDeliveryStatusOrderByCreatedAtDesc(
            String businessPhone, DeliveryStatus status);

    Optional<DeliveryOrder> findByTrackingToken(String trackingToken);



//    List<DeliveryOrder> active = repo.findByPickedUpByAndDeliveryStatusIn(
//            driverPhone,
//            List.of(ASSIGNED, PICKED_UP, DELIVERING)
//    );
    
    
    
    
    
}
