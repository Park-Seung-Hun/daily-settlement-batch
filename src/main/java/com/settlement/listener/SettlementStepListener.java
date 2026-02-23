package com.settlement.listener;

import com.settlement.domain.entity.SettlementError;
import com.settlement.domain.repository.SettlementErrorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step 종료 시 SkipErrorCollector에 누적된 에러를 settlement_error 테이블에 일괄 저장하는 리스너.
 * afterStep()의 StepExecution 파라미터에서 jobExecutionId/stepExecutionId를 추출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementStepListener implements StepExecutionListener {

    private final SkipErrorCollector collector;
    private final SettlementErrorRepository errorRepository;

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        List<SkipErrorCollector.ErrorData> errors = collector.getErrors();

        if (!errors.isEmpty()) {
            List<SettlementError> entities = errors.stream()
                    .map(e -> SettlementError.builder()
                            .settlementDate(e.settlementDate())
                            .merchantId(e.merchantId())
                            .errorType(e.errorType())
                            .errorMessage(e.errorMessage())
                            .errorDetail(e.errorDetail())
                            .jobExecutionId(stepExecution.getJobExecution().getId())
                            .stepExecutionId(stepExecution.getId())
                            .build())
                    .toList();

            errorRepository.saveAll(entities);
            log.warn("정산 검증 실패 건 저장 완료: {}건 (jobExecutionId={})",
                    entities.size(), stepExecution.getJobExecution().getId());

            collector.clear();
        }

        return stepExecution.getExitStatus();
    }
}