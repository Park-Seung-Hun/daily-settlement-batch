package com.settlement.listener;

import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * Step 종료 시 Skip 건수를 로깅하는 리스너.
 * Skip된 에러의 DB 저장은 SettlementSkipListener.onSkipInProcess()에서 직접 처리된다.
 */
@Slf4j
@Component
public class SettlementStepListener implements StepExecutionListener {

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long skipCount = stepExecution.getSkipCount();
        if (skipCount > 0) {
            log.warn("정산 검증 실패 건수: {}건 (settlement_error 테이블 저장 완료)", skipCount);
        } else {
            log.info("정산 검증 실패 건 없음");
        }
        return stepExecution.getExitStatus();
    }
}
