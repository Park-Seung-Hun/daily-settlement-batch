package com.settlement.listener;

import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Job 스코프 내에서 Skip된 에러 데이터를 수집하는 컴포넌트.
 * SettlementSkipListener가 추가하고, SettlementStepListener가 DB에 저장 후 정리한다.
 */
@Component
@JobScope
public class SkipErrorCollector {

    /**
     * Skip 발생 시 SkipListener에서 수집하는 중간 데이터.
     * jobExecutionId/stepExecutionId는 StepListener.afterStep()에서 채운다.
     */
    public record ErrorData(
            LocalDate settlementDate,
            Long merchantId,
            String errorType,
            String errorMessage,
            String errorDetail
    ) {}

    private final List<ErrorData> errors = new CopyOnWriteArrayList<>();

    public void add(ErrorData error) {
        errors.add(error);
    }

    public List<ErrorData> getErrors() {
        return List.copyOf(errors);
    }

    public void clear() {
        errors.clear();
    }
}