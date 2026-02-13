package com.settlement.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_dlq")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncDlq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dlq_id")
    private Long id;

    @Column(name = "outbox_id", nullable = false)
    private Long outboxId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "total_attempts", nullable = false)
    private Integer totalAttempts;

    @Column(name = "last_error", nullable = false, columnDefinition = "TEXT")
    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_history")
    private String errorHistory;

    @Column(name = "failure_reason", length = 50)
    private String failureReason;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private SyncDlq(Long outboxId, Long orderId, String eventType, String payload,
                    Integer totalAttempts, String lastError, String errorHistory,
                    String failureReason) {
        this.outboxId = outboxId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.payload = payload;
        this.totalAttempts = totalAttempts;
        this.lastError = lastError;
        this.errorHistory = errorHistory;
        this.failureReason = failureReason;
        this.status = "DEAD";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
