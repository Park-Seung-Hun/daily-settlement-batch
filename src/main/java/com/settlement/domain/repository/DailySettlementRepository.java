package com.settlement.domain.repository;

import com.settlement.domain.entity.DailySettlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailySettlementRepository extends JpaRepository<DailySettlement, Long> {

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO daily_settlement (
                settlement_date, merchant_id,
                total_order_count, total_order_amount, total_payment_amount,
                fee_rate, fee_amount, net_amount,
                refund_amount, refund_count,
                created_at, updated_at
            ) VALUES (
                :settlementDate, :merchantId,
                :totalOrderCount, :totalOrderAmount, :totalPaymentAmount,
                :feeRate, :feeAmount, :netAmount,
                :refundAmount, :refundCount,
                now(), now()
            )
            ON CONFLICT (settlement_date, merchant_id) DO UPDATE SET
                total_order_count    = EXCLUDED.total_order_count,
                total_order_amount   = EXCLUDED.total_order_amount,
                total_payment_amount = EXCLUDED.total_payment_amount,
                fee_rate             = EXCLUDED.fee_rate,
                fee_amount           = EXCLUDED.fee_amount,
                net_amount           = EXCLUDED.net_amount,
                refund_amount        = EXCLUDED.refund_amount,
                refund_count         = EXCLUDED.refund_count,
                updated_at           = now()
            """)
    void upsert(
            @Param("settlementDate") LocalDate settlementDate,
            @Param("merchantId") Long merchantId,
            @Param("totalOrderCount") Integer totalOrderCount,
            @Param("totalOrderAmount") BigDecimal totalOrderAmount,
            @Param("totalPaymentAmount") BigDecimal totalPaymentAmount,
            @Param("feeRate") BigDecimal feeRate,
            @Param("feeAmount") BigDecimal feeAmount,
            @Param("netAmount") BigDecimal netAmount,
            @Param("refundAmount") BigDecimal refundAmount,
            @Param("refundCount") Integer refundCount
    );
}