package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {
    
    Optional<Business> findByPhone(String phone);
    
    List<Business> findByActiveTrue();
}
