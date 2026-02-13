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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_staging")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementStaging {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "settlement_staging_seq")
    @SequenceGenerator(
            name = "settlement_staging_seq",
            sequenceName = "settlement_staging_id_seq",
            allocationSize = 50
    )
    @Column(name = "staging_id")
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "order_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal orderAmount;

    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "order_status", nullable = false, length = 20)
    private String orderStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private SettlementStaging(LocalDate settlementDate, Long orderId, Long merchantId,
                              BigDecimal orderAmount, BigDecimal paidAmount,
                              BigDecimal refundedAmount, String orderStatus) {
        this.settlementDate = settlementDate;
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.orderAmount = orderAmount;
        this.paidAmount = paidAmount;
        this.refundedAmount = refundedAmount;
        this.orderStatus = orderStatus;
        this.createdAt = LocalDateTime.now();
    }
}
