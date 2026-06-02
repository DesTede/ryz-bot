package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    
    Optional<Customer> findByPhone(String phone);
    
    List<Customer> findAllByOrderByLastOrderAtDesc();
    
    List<Customer> findAllByOrderByCreatedAtDesc();

    List<Customer> findByNameContainingIgnoreCaseOrPhoneContaining(String name, String phone);
    
    int countByPhoneContaining(String phone);
    
    
}
