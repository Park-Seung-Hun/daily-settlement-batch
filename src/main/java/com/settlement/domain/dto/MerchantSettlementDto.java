package com.settlement.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 가맹점별 정산 집계 결과 DTO.
 * 필드 순서는 네이티브 SQL SELECT 순서와 일치해야 함.
 */
public record MerchantSettlementDto(
        Long merchantId,
        LocalDate orderDate,
        BigDecimal feeRate,
        Integer totalOrderCount,
        BigDecimal totalOrderAmount,
        BigDecimal totalPaymentAmount,
        BigDecimal refundAmount,
        Integer refundCount
) {
}
