package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {

    /**
     * Find drivers by active status and type
     */
    List<Driver> findByActiveAndTypeIn(Boolean active, List<DriverType> types);

    /**
     * Find all drivers by active status (any type)
     */
    List<Driver> findByActive(boolean active);

    /**
     * Find driver by phone number - original method name
     * Uses explicit @Query with Driver entity name (not table name)
     */
    @Query("SELECT d FROM Driver d WHERE d.phone = :phone")
    Optional<Driver> findDriverByPhone(@Param("phone") String phone);

    /**
     * Find driver by phone - alternative method name
     * Some code might call this instead
     */
    @Query("SELECT d FROM Driver d WHERE d.phone = :phone")
    Optional<Driver> findByPhone(@Param("phone") String phone);

    Optional<Driver> findByLocationToken(String locationToken);

}
