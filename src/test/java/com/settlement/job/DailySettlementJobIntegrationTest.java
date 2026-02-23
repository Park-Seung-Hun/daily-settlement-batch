package com.settlement.job;

import com.settlement.DatabaseCleanup;
import com.settlement.domain.entity.DailySettlement;
import com.settlement.domain.entity.Merchant;
import com.settlement.domain.entity.Order;
import com.settlement.domain.entity.Payment;
import com.settlement.domain.entity.SettlementError;
import com.settlement.domain.repository.DailySettlementRepository;
import com.settlement.domain.repository.MerchantRepository;
import com.settlement.domain.repository.OrderRepository;
import com.settlement.domain.repository.PaymentRepository;
import com.settlement.domain.repository.SettlementErrorRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class DailySettlementJobIntegrationTest {

    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 2, 23);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0350");

    @Autowired private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired @Qualifier("dailySettlementJob") private Job dailySettlementJob;

    @Autowired private DatabaseCleanup databaseCleanup;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DailySettlementRepository settlementRepository;
    @Autowired private SettlementErrorRepository errorRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        databaseCleanup.execute();
        jobOperatorTestUtils.setJob(dailySettlementJob);
    }

    // ── 헬퍼 메서드 ─────────────────────────────────────────────────────────────

    private Merchant saveMerchant(String name, String bizNum) {
        return merchantRepository.save(Merchant.builder()
                .merchantName(name).businessNumber(bizNum).feeRate(FEE_RATE).build());
    }

    private Order saveOrder(Long merchantId, String orderNum, BigDecimal amount, String status) {
        Order order = orderRepository.save(Order.builder()
                .merchantId(merchantId).orderNumber(orderNum)
                .orderDate(SETTLEMENT_DATE).orderAmount(amount).build());
        jdbcTemplate.update("UPDATE orders SET status = ? WHERE order_id = ?", status, order.getId());
        return order;
    }

    private void savePayment(Long orderId, String paymentType, BigDecimal amount) {
        paymentRepository.save(Payment.builder()
                .orderId(orderId).paymentType(paymentType)
                .paymentMethod("CREDIT_CARD").paymentAmount(amount).build());
    }

    private JobParameters uniqueJobParameters() {
        return jobOperatorTestUtils.getUniqueJobParametersBuilder()
                .addLocalDate("settlementDate", SETTLEMENT_DATE)
                .toJobParameters();
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 1: 정상 주문-결제 데이터가 입력되면 정산 집계가 생성된다")
    void should_create_settlement_when_valid_order_payment_exists() throws Exception {
        // given
        Merchant merchant = saveMerchant("가맹점A", "1111111111");
        Order o1 = saveOrder(merchant.getId(), "ORD-001", new BigDecimal("50000.00"), "PAID");
        Order o2 = saveOrder(merchant.getId(), "ORD-002", new BigDecimal("30000.00"), "DELIVERED");
        savePayment(o1.getId(), "PAYMENT", new BigDecimal("50000.00"));
        savePayment(o2.getId(), "PAYMENT", new BigDecimal("30000.00"));

        // when
        JobExecution jobExecution = jobOperatorTestUtils.startJob(uniqueJobParameters());

        // then
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<DailySettlement> settlements = settlementRepository.findAll();
        assertThat(settlements).hasSize(1);

        DailySettlement s = settlements.get(0);
        assertThat(s.getMerchantId()).isEqualTo(merchant.getId());
        assertThat(s.getTotalOrderCount()).isEqualTo(2);
        assertThat(s.getTotalOrderAmount()).isEqualByComparingTo("80000.00");
        assertThat(s.getTotalPaymentAmount()).isEqualByComparingTo("80000.00");
        assertThat(s.getFeeAmount()).isEqualByComparingTo("2800.00");   // 80000 * 0.0350
        assertThat(s.getNetAmount()).isEqualByComparingTo("77200.00"); // 80000 - 2800 - 0
        assertThat(s.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(s.getRefundCount()).isZero();
    }

    @Test
    @DisplayName("시나리오 2: 동일 날짜로 재실행하면 UPSERT로 덮어쓰고 중복 생성되지 않는다")
    void should_upsert_settlement_when_job_reruns_with_same_date() throws Exception {
        // given
        Merchant merchant = saveMerchant("가맹점B", "2222222222");
        Order order = saveOrder(merchant.getId(), "ORD-101", new BigDecimal("100000.00"), "PAID");
        savePayment(order.getId(), "PAYMENT", new BigDecimal("100000.00"));

        // when — 2번 실행 (각각 unique params로 별도 JobInstance)
        jobOperatorTestUtils.startJob(uniqueJobParameters());
        JobExecution secondExecution = jobOperatorTestUtils.startJob(uniqueJobParameters());

        // then
        assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(settlementRepository.findAll()).hasSize(1); // UPSERT로 1건만 존재
    }

    @Test
    @DisplayName("시나리오 3: AMOUNT_MISMATCH 검증 실패 건이 Skip되고 settlement_error에 저장된다")
    void should_skip_and_record_error_when_amount_mismatch() throws Exception {
        // given — orderAmount(100000)와 paymentAmount(95000) 차이 = 5000 > 1원
        Merchant merchant = saveMerchant("가맹점C", "3333333333");
        Order order = saveOrder(merchant.getId(), "ORD-201", new BigDecimal("100000.00"), "PAID");
        savePayment(order.getId(), "PAYMENT", new BigDecimal("95000.00"));

        // when
        JobExecution jobExecution = jobOperatorTestUtils.startJob(uniqueJobParameters());

        // then
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(settlementRepository.findAll()).isEmpty();

        List<SettlementError> errors = errorRepository.findAll();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getErrorType()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(errors.get(0).getMerchantId()).isEqualTo(merchant.getId());
        assertThat(errors.get(0).getJobExecutionId()).isNotNull();
    }

    @Test
    @DisplayName("시나리오 4: 환불이 포함된 주문은 refundAmount와 netAmount가 올바르게 집계된다")
    void should_aggregate_refund_amount_correctly() throws Exception {
        // given
        Merchant merchant = saveMerchant("가맹점D", "4444444444");
        Order order = saveOrder(merchant.getId(), "ORD-301", new BigDecimal("100000.00"), "REFUNDED");
        savePayment(order.getId(), "PAYMENT", new BigDecimal("100000.00"));
        savePayment(order.getId(), "REFUND", new BigDecimal("100000.00"));

        // when
        JobExecution jobExecution = jobOperatorTestUtils.startJob(uniqueJobParameters());

        // then
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<DailySettlement> settlements = settlementRepository.findAll();
        assertThat(settlements).hasSize(1);

        DailySettlement s = settlements.get(0);
        assertThat(s.getTotalPaymentAmount()).isEqualByComparingTo("100000.00");
        assertThat(s.getRefundAmount()).isEqualByComparingTo("100000.00");
        assertThat(s.getRefundCount()).isEqualTo(1);
        assertThat(s.getFeeAmount()).isEqualByComparingTo("3500.00");    // 100000 * 0.0350
        assertThat(s.getNetAmount()).isEqualByComparingTo("-3500.00");   // 100000 - 3500 - 100000
    }

    @Test
    @DisplayName("시나리오 5: 처리 대상 주문이 없으면 Job이 COMPLETED되고 정산 데이터가 생성되지 않는다")
    void should_complete_with_no_settlement_when_no_target_orders() throws Exception {
        // given — CANCELLED 상태 주문은 정산 대상 아님
        Merchant merchant = saveMerchant("가맹점E", "5555555555");
        Order order = saveOrder(merchant.getId(), "ORD-401", new BigDecimal("50000.00"), "CANCELLED");
        savePayment(order.getId(), "PAYMENT", new BigDecimal("50000.00"));

        // when
        JobExecution jobExecution = jobOperatorTestUtils.startJob(uniqueJobParameters());

        // then
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(settlementRepository.findAll()).isEmpty();
        assertThat(errorRepository.findAll()).isEmpty();
    }
}