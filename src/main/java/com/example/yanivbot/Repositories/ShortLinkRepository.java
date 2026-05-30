package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {
    
    
    Optional<ShortLink> findByCode(String code);
    
    boolean existsByCode(String code);
}