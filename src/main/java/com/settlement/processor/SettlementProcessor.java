package com.settlement.processor;

import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.DailySettlement;
import com.settlement.domain.exception.ValidationException;

import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MerchantSettlementDto → DailySettlement 변환 Processor.
 * 검증 실패 시 ValidationException 발생 → Step의 Skip 정책 대상.
 * Spring Bean이 아닌 순수 Java 클래스 — JobConfig에서 직접 인스턴스화.
 */
public class SettlementProcessor implements ItemProcessor<MerchantSettlementDto, DailySettlement> {

    private static final BigDecimal AMOUNT_TOLERANCE = BigDecimal.ONE;

    @Override
    public DailySettlement process(MerchantSettlementDto dto) {
        validate(dto);

        BigDecimal refundAmount = dto.refundAmount() != null ? dto.refundAmount() : BigDecimal.ZERO;
        int refundCount = dto.refundCount() != null ? dto.refundCount() : 0;

        BigDecimal feeAmount = dto.totalPaymentAmount()
                .multiply(dto.feeRate())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal netAmount = dto.totalPaymentAmount()
                .subtract(feeAmount)
                .subtract(refundAmount);

        return DailySettlement.builder()
                .settlementDate(dto.orderDate())
                .merchantId(dto.merchantId())
                .totalOrderCount(dto.totalOrderCount())
                .totalOrderAmount(dto.totalOrderAmount())
                .totalPaymentAmount(dto.totalPaymentAmount())
                .feeRate(dto.feeRate())
                .feeAmount(feeAmount)
                .netAmount(netAmount)
                .refundAmount(refundAmount)
                .refundCount(refundCount)
                .build();
    }

    private void validate(MerchantSettlementDto dto) {
        if (dto.totalPaymentAmount() == null
                || dto.totalPaymentAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new ValidationException(
                    "MISSING_PAYMENT",
                    dto.merchantId(),
                    "totalPaymentAmount is null or zero"
            );
        }

        BigDecimal diff = dto.totalOrderAmount()
                .subtract(dto.totalPaymentAmount())
                .abs();

        if (diff.compareTo(AMOUNT_TOLERANCE) > 0) {
            throw new ValidationException(
                    "AMOUNT_MISMATCH",
                    dto.merchantId(),
                    String.format("totalOrderAmount=%s, totalPaymentAmount=%s, diff=%s",
                            dto.totalOrderAmount(), dto.totalPaymentAmount(), diff)
            );
        }
    }
}
