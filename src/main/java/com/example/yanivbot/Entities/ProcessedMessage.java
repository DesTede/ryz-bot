package com.example.yanivbot.Entities;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records WhatsApp message IDs that have already been processed, so that
 * Meta's at-least-once webhook retries don't reprocess the same message
 * (which could otherwise create duplicate orders, claims, etc.).
 */
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    @Column(name = "message_id", nullable = false, updatable = false)
    private String messageId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedMessage() {}

    public ProcessedMessage(String messageId) {
        this.messageId = messageId;
        this.processedAt = LocalDateTime.now();
    }

    public String getMessageId() { return messageId; }
    public LocalDateTime getProcessedAt() { return processedAt; }
}