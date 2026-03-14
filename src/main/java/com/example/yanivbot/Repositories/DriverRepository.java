package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {
    
    List<Driver> findByActiveAndTypeIn(Boolean active, List<DriverType> types);
    
    Optional<Driver> findDriverByPhone(String phone);
}
