package com.settlement.listener;

import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.DailySettlement;
import com.settlement.domain.exception.ValidationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.listener.SkipListener;
import org.springframework.stereotype.Component;

/**
 * Processor에서 Skip된 항목을 SkipErrorCollector에 누적하는 리스너.
 * jobExecutionId/stepExecutionId는 SettlementStepListener.afterStep()에서 채운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementSkipListener implements SkipListener<MerchantSettlementDto, DailySettlement> {

    private final SkipErrorCollector collector;

    @Override
    public void onSkipInProcess(MerchantSettlementDto item, Throwable t) {
        if (t instanceof ValidationException ve) {
            collector.add(new SkipErrorCollector.ErrorData(
                    item.orderDate(),
                    ve.getMerchantId(),
                    ve.getErrorType(),
                    ve.getMessage(),
                    ve.getErrorDetail()
            ));
            log.warn("정산 검증 Skip: merchantId={}, type={}, detail={}",
                    ve.getMerchantId(), ve.getErrorType(), ve.getErrorDetail());
        }
    }
}
