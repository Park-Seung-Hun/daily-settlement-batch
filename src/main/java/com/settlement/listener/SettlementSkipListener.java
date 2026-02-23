package com.settlement.listener;

import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.DailySettlement;
import com.settlement.domain.entity.SettlementError;
import com.settlement.domain.exception.ValidationException;
import com.settlement.domain.repository.SettlementErrorRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Processor에서 Skip된 항목을 즉시 settlement_error 테이블에 저장하는 리스너.
 * @StepScope로 선언되어 stepExecution이 직접 주입된다.
 */
@Slf4j
@Component
@StepScope
public class SettlementSkipListener implements SkipListener<MerchantSettlementDto, DailySettlement> {

    private final SettlementErrorRepository errorRepository;
    private final StepExecution stepExecution;

    public SettlementSkipListener(SettlementErrorRepository errorRepository,
                                  @Value("#{stepExecution}") StepExecution stepExecution) {
        this.errorRepository = errorRepository;
        this.stepExecution = stepExecution;
    }

    @Override
    public void onSkipInProcess(MerchantSettlementDto item, Throwable t) {
        if (t instanceof ValidationException ve) {
            errorRepository.save(SettlementError.builder()
                    .settlementDate(item.orderDate())
                    .merchantId(ve.getMerchantId())
                    .errorType(ve.getErrorType())
                    .errorMessage(ve.getMessage())
                    .errorDetail(ve.getErrorDetail())
                    .jobExecutionId(stepExecution.getJobExecution().getId())
                    .stepExecutionId(stepExecution.getId())
                    .build());
            log.warn("정산 검증 Skip 저장: merchantId={}, type={}, detail={}",
                    ve.getMerchantId(), ve.getErrorType(), ve.getErrorDetail());
        }
    }
}
