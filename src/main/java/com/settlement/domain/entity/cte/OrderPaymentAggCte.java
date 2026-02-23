package com.settlement.domain.entity.cte;

import com.blazebit.persistence.CTE;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * orders LEFT JOIN payments 집계를 위한 Blaze-Persistence CTE 가상 엔티티.
 * 실제 DB 테이블에 매핑되지 않으며, Hibernate가 인스턴스를 직접 생성·수정하지 않음.
 * Blaze-Persistence가 JPA 메타모델을 통해 필드명·타입 정보만 읽어 SQL CTE를 생성함.
 * 주문별(order_id) 결제·환불 집계 결과를 보유하여 order_amount 뻥튀기를 방지함.
 */
@CTE
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderPaymentAggCte {

    @Id
    private Long orderId;

    private Long merchantId;

    private LocalDate orderDate;

    private BigDecimal orderAmount;

    private BigDecimal feeRate;

    private BigDecimal paidAmount;

    private BigDecimal refundAmount;

    private Long refundCount;
}
