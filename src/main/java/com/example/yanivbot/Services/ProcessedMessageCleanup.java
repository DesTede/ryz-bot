package com.example.yanivbot.Services;

import com.example.yanivbot.Repositories.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the processed_messages dedup table bounded. Meta does not retry
 * webhooks after a short window, so entries older than a couple of days
 * are safe to delete.
 */
@Component
public class ProcessedMessageCleanup {

    private static final Logger logger = LoggerFactory.getLogger(ProcessedMessageCleanup.class);
    private static final int RETENTION_DAYS = 2;

    private final ProcessedMessageRepository processedMessageRepo;

    public ProcessedMessageCleanup(ProcessedMessageRepository processedMessageRepo) {
        this.processedMessageRepo = processedMessageRepo;
    }

    @Scheduled(fixedDelay = 24, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void purgeOldProcessedMessages() {
        try {
            long deleted = processedMessageRepo.deleteByProcessedAtBefore(
                    LocalDateTime.now().minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                logger.info("Purged {} old processed-message records", deleted);
            }
        } catch (Exception e) {
            logger.warn("Failed to purge processed-message records: {}", e.getMessage());
        }
    }
}