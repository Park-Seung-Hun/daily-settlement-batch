package com.settlement.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_error")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementError {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "settlement_error_seq")
    @SequenceGenerator(
            name = "settlement_error_seq",
            sequenceName = "settlement_error_id_seq",
            allocationSize = 50
    )
    @Column(name = "error_id")
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "error_type", nullable = false, length = 50)
    private String errorType;

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "resolved", nullable = false)
    private Boolean resolved;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "step_execution_id")
    private Long stepExecutionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private SettlementError(LocalDate settlementDate, Long merchantId, Long orderId,
                            String errorType, String errorMessage, String errorDetail,
                            Long jobExecutionId, Long stepExecutionId) {
        this.settlementDate = settlementDate;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.errorDetail = errorDetail;
        this.resolved = false;
        this.jobExecutionId = jobExecutionId;
        this.stepExecutionId = stepExecutionId;
        this.createdAt = LocalDateTime.now();
    }
}
