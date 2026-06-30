package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RatingRepository extends JpaRepository<Rating, Long> {

    boolean existsByOrderIdAndOrderType(long orderId, String orderType);

    List<Rating> findTop10ByDriverPhoneOrderByCreatedAtDesc(String driverPhone);

    @Query("SELECT AVG(r.stars) FROM Rating r WHERE r.driverPhone = :phone AND r.orderType = :type")
    Double averageStarsForDriver(@Param("phone") String driverPhone, @Param("type") String orderType);

    @Query("SELECT COUNT(r) FROM Rating r WHERE r.driverPhone = :phone AND r.orderType = :type")
    long countForDriver(@Param("phone") String driverPhone, @Param("type") String orderType);
}