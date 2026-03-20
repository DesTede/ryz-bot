package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.BotConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BotConfigRepository extends JpaRepository<BotConfig, String> {
    
    
}
