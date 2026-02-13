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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailySettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "total_order_count", nullable = false)
    private Integer totalOrderCount;

    @Column(name = "total_order_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalOrderAmount;

    @Column(name = "total_payment_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPaymentAmount;

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    @Column(name = "fee_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "refund_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_count", nullable = false)
    private Integer refundCount;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private DailySettlement(LocalDate settlementDate, Long merchantId,
                            Integer totalOrderCount, BigDecimal totalOrderAmount,
                            BigDecimal totalPaymentAmount, BigDecimal feeRate,
                            BigDecimal feeAmount, BigDecimal netAmount,
                            BigDecimal refundAmount, Integer refundCount) {
        this.settlementDate = settlementDate;
        this.merchantId = merchantId;
        this.totalOrderCount = totalOrderCount;
        this.totalOrderAmount = totalOrderAmount;
        this.totalPaymentAmount = totalPaymentAmount;
        this.feeRate = feeRate;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.refundAmount = refundAmount;
        this.refundCount = refundCount;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateSettlement(Integer totalOrderCount, BigDecimal totalOrderAmount,
                                 BigDecimal totalPaymentAmount, BigDecimal feeRate,
                                 BigDecimal feeAmount, BigDecimal netAmount,
                                 BigDecimal refundAmount, Integer refundCount) {
        this.totalOrderCount = totalOrderCount;
        this.totalOrderAmount = totalOrderAmount;
        this.totalPaymentAmount = totalPaymentAmount;
        this.feeRate = feeRate;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.refundAmount = refundAmount;
        this.refundCount = refundCount;
        this.updatedAt = LocalDateTime.now();
    }
}
