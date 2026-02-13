package com.settlement.config;

import com.settlement.domain.entity.Merchant;
import com.settlement.domain.entity.Order;
import com.settlement.domain.repository.MerchantRepository;
import com.settlement.domain.repository.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

@Configuration
public class VerificationJobConfig {

    private static final Logger log = LoggerFactory.getLogger(VerificationJobConfig.class);

    private static final List<String> REQUIRED_TABLES = List.of(
            "merchants", "orders", "payments",
            "settlement_staging", "daily_settlement", "settlement_error",
            "sync_outbox", "sync_dlq"
    );

    private final DataSource dataSource;
    private final MerchantRepository merchantRepository;
    private final OrderRepository orderRepository;

    public VerificationJobConfig(DataSource dataSource,
                                 MerchantRepository merchantRepository,
                                 OrderRepository orderRepository) {
        this.dataSource = dataSource;
        this.merchantRepository = merchantRepository;
        this.orderRepository = orderRepository;
    }

    @Bean
    public Job verificationJob(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager) {
        return new JobBuilder("verificationJob", jobRepository)
                .start(verifyDatabaseConnectionStep(jobRepository, transactionManager))
                .next(verifyJpaCrudStep(jobRepository, transactionManager))
                .build();
    }

    @Bean
    public Step verifyDatabaseConnectionStep(JobRepository jobRepository,
                                             PlatformTransactionManager transactionManager) {
        return new StepBuilder("verifyDatabaseConnectionStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    log.info("=== Step 1: DB 연결 확인 및 테이블 존재 검증 시작 ===");

                    try (Connection connection = dataSource.getConnection()) {
                        DatabaseMetaData metaData = connection.getMetaData();
                        log.info("DB 연결 성공: {} {}",
                                metaData.getDatabaseProductName(),
                                metaData.getDatabaseProductVersion());

                        for (String tableName : REQUIRED_TABLES) {
                            try (ResultSet rs = metaData.getTables(
                                    null, "public", tableName, new String[]{"TABLE"})) {
                                if (!rs.next()) {
                                    throw new IllegalStateException(
                                            "필수 테이블이 존재하지 않습니다: " + tableName);
                                }
                                log.info("테이블 확인 완료: {}", tableName);
                            }
                        }
                    }

                    log.info("=== Step 1: DB 연결 확인 및 테이블 존재 검증 완료 ===");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step verifyJpaCrudStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager) {
        return new StepBuilder("verifyJpaCrudStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    log.info("=== Step 2: JPA CRUD 검증 시작 ===");

                    Merchant merchant = merchantRepository.save(Merchant.builder()
                            .merchantName("검증용 가맹점")
                            .businessNumber("0000000000")
                            .feeRate(new BigDecimal("0.0350"))
                            .build());
                    log.info("Merchant INSERT 성공: id={}", merchant.getId());

                    Order order = orderRepository.save(Order.builder()
                            .merchantId(merchant.getId())
                            .orderNumber("VERIFY-001")
                            .orderDate(LocalDate.now())
                            .orderAmount(new BigDecimal("10000.00"))
                            .build());
                    log.info("Order INSERT 성공: id={}", order.getId());

                    Order found = orderRepository.findById(order.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Order SELECT 실패: id=" + order.getId()));
                    log.info("Order SELECT 성공: id={}, orderNumber={}",
                            found.getId(), found.getOrderNumber());

                    orderRepository.delete(found);
                    log.info("Order DELETE 성공: id={}", found.getId());

                    merchantRepository.delete(merchant);
                    log.info("Merchant DELETE 성공: id={}", merchant.getId());

                    log.info("=== Step 2: JPA CRUD 검증 완료 ===");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}
