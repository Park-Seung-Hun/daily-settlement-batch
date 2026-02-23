package com.settlement.job.settlement;

import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.DailySettlement;
import com.settlement.domain.exception.ValidationException;
import com.settlement.domain.repository.DailySettlementRepository;
import com.settlement.domain.repository.OrderRepository;
import com.settlement.listener.SettlementSkipListener;
import com.settlement.listener.SettlementStepListener;
import com.settlement.processor.SettlementProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.Map;

@Configuration
public class DailySettlementJobConfig {

    private static final Logger log = LoggerFactory.getLogger(DailySettlementJobConfig.class);

    private static final int CHUNK_SIZE = 100;
    private static final long SKIP_LIMIT = 100L;

    private final OrderRepository orderRepository;
    private final DailySettlementRepository settlementRepository;
    private final SettlementSkipListener skipListener;
    private final SettlementStepListener stepListener;

    public DailySettlementJobConfig(OrderRepository orderRepository,
                                    DailySettlementRepository settlementRepository,
                                    SettlementSkipListener skipListener,
                                    SettlementStepListener stepListener) {
        this.orderRepository = orderRepository;
        this.settlementRepository = settlementRepository;
        this.skipListener = skipListener;
        this.stepListener = stepListener;
    }

    @Bean
    public Job dailySettlementJob(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager) {
        return new JobBuilder("dailySettlementJob", jobRepository)
                .start(settlementAggregationStep(jobRepository, transactionManager))
                .next(errorIsolationStep(jobRepository, transactionManager))
                .build();
    }

    @Bean
    public Step settlementAggregationStep(JobRepository jobRepository,
                                          PlatformTransactionManager transactionManager) {
        return new StepBuilder("settlementAggregationStep", jobRepository)
                .<MerchantSettlementDto, DailySettlement>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(settlementAggregationReader(null))
                .processor(new SettlementProcessor())
                .writer(settlementUpsertWriter())
                .faultTolerant()
                .skip(ValidationException.class)
                .skipLimit(SKIP_LIMIT)
                .skipListener(skipListener)
                .listener(stepListener)
                .build();
    }

    /**
     * 정산 집계 Reader. JobParameter의 settlementDate 기준으로 가맹점별 집계 데이터를 페이지 단위로 읽는다.
     * sorts 설정은 RepositoryItemReader 내부 페이지 관리용이며,
     * 실제 정렬은 CTE 쿼리의 ORDER BY merchantId가 담당한다.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<MerchantSettlementDto> settlementAggregationReader(
            @Value("#{jobParameters['settlementDate']}") LocalDate settlementDate) {
        return new RepositoryItemReaderBuilder<MerchantSettlementDto>()
                .name("settlementAggregationReader")
                .repository(orderRepository)
                .methodName("findSettlementAggregation")
                .arguments(settlementDate)
                .sorts(Map.of("merchantId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    @Bean
    public ItemWriter<DailySettlement> settlementUpsertWriter() {
        return chunk -> {
            for (DailySettlement s : chunk.getItems()) {
                settlementRepository.upsert(
                        s.getSettlementDate(), s.getMerchantId(),
                        s.getTotalOrderCount(), s.getTotalOrderAmount(),
                        s.getTotalPaymentAmount(), s.getFeeRate(),
                        s.getFeeAmount(), s.getNetAmount(),
                        s.getRefundAmount(), s.getRefundCount()
                );
            }
        };
    }

    /**
     * Step 1에서 DB에 저장된 검증 실패 건수를 StepExecution의 skipCount로 집계하여 로깅한다.
     */
    @Bean
    public Step errorIsolationStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager) {
        return new StepBuilder("errorIsolationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    long skipCount = chunkContext.getStepContext()
                            .getStepExecution()
                            .getJobExecution()
                            .getStepExecutions()
                            .stream()
                            .filter(se -> "settlementAggregationStep".equals(se.getStepName()))
                            .mapToLong(se -> se.getSkipCount())
                            .sum();
                    if (skipCount > 0) {
                        log.warn("정산 검증 실패 건수: {}건 (settlement_error 테이블 저장 완료)", skipCount);
                    } else {
                        log.info("정산 검증 실패 건 없음");
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}
