package com.settlement.domain.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.Merchant;
import com.settlement.domain.entity.Order;
import com.settlement.domain.entity.Payment;
import com.settlement.domain.entity.cte.OrderPaymentAggCte;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final CriteriaBuilderFactory cbf;
    private final EntityManager em;

    /**
     * 가맹점별 일별 정산 집계 쿼리.
     *
     * <p>CTE 1단계: 주문별 결제/환불 집계 (orders LEFT JOIN payments).
     * ORDER BY 1건에 결제 N건이 있으면 단순 JOIN 시 order_amount가 N배 뻥튀기 되므로
     * 주문 단위로 먼저 집계한 뒤 2단계에서 가맹점별로 GROUP BY한다.
     *
     * <p>CTE 2단계(메인 쿼리): order_payment_agg CTE를 가맹점별로 집계.
     */
    @Override
    public Page<MerchantSettlementDto> findSettlementAggregation(LocalDate settlementDate, Pageable pageable) {
        // ── 1. 데이터 쿼리 (CTE + 페이지네이션) ───────────────────────────
        com.blazebit.persistence.CriteriaBuilder<Tuple> cb = cbf.create(em, Tuple.class)
                // CTE 정의: 주문별 결제 집계
                .with(OrderPaymentAggCte.class)
                    .from(Order.class, "o")
                    .leftJoinOn(Payment.class, "p")
                        .on("p.orderId").eqExpression("o.id")
                    .end()
                    .innerJoinOn(Merchant.class, "m")
                        .on("m.id").eqExpression("o.merchantId")
                    .end()
                    .where("o.orderDate").eq(settlementDate)
                    .where("o.status").in("PAID", "DELIVERED", "REFUNDED")
                    .where("m.status").eq("ACTIVE")
                    .bind("orderId").select("o.id")
                    .bind("merchantId").select("o.merchantId")
                    .bind("orderDate").select("o.orderDate")
                    .bind("orderAmount").select("o.orderAmount")
                    .bind("feeRate").select("m.feeRate")
                    .bind("paidAmount").select(
                            "SUM(CASE WHEN p.paymentType = 'PAYMENT' AND p.status = 'COMPLETED' THEN p.paymentAmount END)")
                    .bind("refundAmount").select(
                            "SUM(CASE WHEN p.paymentType = 'REFUND' AND p.status = 'COMPLETED' THEN p.paymentAmount END)")
                    .bind("refundCount").select(
                            "SUM(CASE WHEN p.paymentType = 'REFUND' AND p.status = 'COMPLETED' THEN 1 END)")
                    .groupBy("o.id")
                .end()
                // 메인 쿼리: 가맹점별 집계
                .from(OrderPaymentAggCte.class, "opa")
                .groupBy("opa.merchantId")
                .groupBy("opa.orderDate")
                .groupBy("opa.feeRate")
                .orderByAsc("opa.merchantId")
                .select("opa.merchantId")
                .select("opa.orderDate")
                .select("opa.feeRate")
                .select("COUNT(opa.orderId)")
                .select("SUM(opa.orderAmount)")
                .select("SUM(opa.paidAmount)")
                .select("SUM(opa.refundAmount)")
                .select("SUM(opa.refundCount)");

        TypedQuery<Tuple> dataQuery = cb.getQuery();
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());
        List<MerchantSettlementDto> content = dataQuery.getResultList().stream()
                .map(this::mapTuple)
                .toList();

        // ── 2. 카운트 쿼리 (CTE 없이 단순 집계) ──────────────────────────
        long total = cbf.create(em, Long.class)
                .from(Order.class, "o")
                .innerJoinOn(Merchant.class, "m")
                    .on("m.id").eqExpression("o.merchantId")
                .end()
                .where("o.orderDate").eq(settlementDate)
                .where("o.status").in("PAID", "DELIVERED", "REFUNDED")
                .where("m.status").eq("ACTIVE")
                .select("COUNT(DISTINCT o.merchantId)")
                .getQuery()
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private MerchantSettlementDto mapTuple(Tuple t) {
        return new MerchantSettlementDto(
                ((Number) t.get(0)).longValue(),
                t.get(1, LocalDate.class),
                t.get(2, BigDecimal.class),
                ((Number) t.get(3)).intValue(),
                t.get(4, BigDecimal.class),
                nullSafeBigDecimal(t.get(5)),
                nullSafeBigDecimal(t.get(6)),
                nullSafeInt(t.get(7))
        );
    }

    private BigDecimal nullSafeBigDecimal(Object value) {
        return value != null ? (BigDecimal) value : BigDecimal.ZERO;
    }

    private int nullSafeInt(Object value) {
        return value != null ? ((Number) value).intValue() : 0;
    }
}
