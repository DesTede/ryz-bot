package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    /**
     * Used by a periodic cleanup to keep the table from growing unbounded.
     */
    long deleteByProcessedAtBefore(LocalDateTime cutoff);
}