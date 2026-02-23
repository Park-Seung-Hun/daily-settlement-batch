package com.settlement.domain.repository;

import com.settlement.config.BlazePersistenceConfig;
import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.Merchant;
import com.settlement.domain.entity.Order;
import com.settlement.domain.entity.Payment;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@Import(BlazePersistenceConfig.class)
class OrderRepositoryTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private EntityManager em;

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 2, 23);
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0300");

    // ── 시나리오 1 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 1: 가맹점 2개 각 2건 주문이면 가맹점별로 정확히 집계된다")
    void should_aggregate_per_merchant_when_multiple_merchants_exist() {
        Merchant m1 = saveMerchant("1000000001", DEFAULT_FEE_RATE);
        Merchant m2 = saveMerchant("1000000002", DEFAULT_FEE_RATE);

        Order o1 = saveOrder(m1.getId(), TEST_DATE, new BigDecimal("10000"), "PAID");
        Order o2 = saveOrder(m1.getId(), TEST_DATE, new BigDecimal("20000"), "PAID");
        Order o3 = saveOrder(m2.getId(), TEST_DATE, new BigDecimal("15000"), "PAID");
        Order o4 = saveOrder(m2.getId(), TEST_DATE, new BigDecimal("25000"), "PAID");

        savePayment(o1.getId(), "PAYMENT", new BigDecimal("10000"));
        savePayment(o2.getId(), "PAYMENT", new BigDecimal("20000"));
        savePayment(o3.getId(), "PAYMENT", new BigDecimal("15000"));
        savePayment(o4.getId(), "PAYMENT", new BigDecimal("25000"));
        flushAndClear();

        Page<MerchantSettlementDto> result = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);

        MerchantSettlementDto dto1 = findByMerchant(result, m1.getId());
        assertThat(dto1.totalOrderCount()).isEqualTo(2);
        assertThat(dto1.totalOrderAmount()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(dto1.totalPaymentAmount()).isEqualByComparingTo(new BigDecimal("30000"));

        MerchantSettlementDto dto2 = findByMerchant(result, m2.getId());
        assertThat(dto2.totalOrderCount()).isEqualTo(2);
        assertThat(dto2.totalOrderAmount()).isEqualByComparingTo(new BigDecimal("40000"));
        assertThat(dto2.totalPaymentAmount()).isEqualByComparingTo(new BigDecimal("40000"));
    }

    // ── 시나리오 2 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 2: 환불 결제가 포함된 주문이면 refundAmount와 refundCount가 정확히 집계된다")
    void should_aggregate_refund_amount_and_count_when_order_has_refund_payment() {
        Merchant merchant = saveMerchant("2000000001", DEFAULT_FEE_RATE);
        Order order = saveOrder(merchant.getId(), TEST_DATE, new BigDecimal("10000"), "REFUNDED");

        savePayment(order.getId(), "PAYMENT", new BigDecimal("10000"));
        savePayment(order.getId(), "REFUND",  new BigDecimal("3000"));
        flushAndClear();

        Page<MerchantSettlementDto> result = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        MerchantSettlementDto dto = result.getContent().get(0);
        assertThat(dto.totalPaymentAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(dto.refundAmount()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(dto.refundCount()).isEqualTo(1);
    }

    // ── 시나리오 3 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 3: merchants 테이블 JOIN으로 feeRate가 DTO에 포함된다")
    void should_include_fee_rate_from_merchant_when_aggregating() {
        BigDecimal feeRate = new BigDecimal("0.0350");
        Merchant merchant = saveMerchant("3000000001", feeRate);
        Order order = saveOrder(merchant.getId(), TEST_DATE, new BigDecimal("10000"), "PAID");
        savePayment(order.getId(), "PAYMENT", new BigDecimal("10000"));
        flushAndClear();

        Page<MerchantSettlementDto> result = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).feeRate()).isEqualByComparingTo(feeRate);
    }

    // ── 시나리오 4 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 4: SUSPENDED 상태 가맹점은 집계에서 제외된다")
    void should_exclude_suspended_merchant_from_aggregation() {
        Merchant active    = saveMerchant("4000000001", DEFAULT_FEE_RATE);
        Merchant suspended = saveMerchant("4000000002", DEFAULT_FEE_RATE, "SUSPENDED");

        Order o1 = saveOrder(active.getId(),    TEST_DATE, new BigDecimal("10000"), "PAID");
        Order o2 = saveOrder(suspended.getId(), TEST_DATE, new BigDecimal("10000"), "PAID");
        savePayment(o1.getId(), "PAYMENT", new BigDecimal("10000"));
        savePayment(o2.getId(), "PAYMENT", new BigDecimal("10000"));
        flushAndClear();

        Page<MerchantSettlementDto> result = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).merchantId()).isEqualTo(active.getId());
    }

    // ── 시나리오 5 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 5: CREATED/CANCELLED 상태 주문은 집계에서 제외된다")
    void should_exclude_created_and_cancelled_orders_from_aggregation() {
        Merchant merchant = saveMerchant("5000000001", DEFAULT_FEE_RATE);

        Order paid      = saveOrder(merchant.getId(), TEST_DATE, new BigDecimal("10000"), "PAID");
        Order created   = saveOrder(merchant.getId(), TEST_DATE, new BigDecimal("99999"), "CREATED");
        Order cancelled = saveOrder(merchant.getId(), TEST_DATE, new BigDecimal("99999"), "CANCELLED");

        savePayment(paid.getId(),      "PAYMENT", new BigDecimal("10000"));
        savePayment(created.getId(),   "PAYMENT", new BigDecimal("99999"));
        savePayment(cancelled.getId(), "PAYMENT", new BigDecimal("99999"));
        flushAndClear();

        Page<MerchantSettlementDto> result = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        MerchantSettlementDto dto = result.getContent().get(0);
        assertThat(dto.totalOrderCount()).isEqualTo(1);
        assertThat(dto.totalOrderAmount()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    // ── 시나리오 6 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 6: pageSize=2이면 첫 페이지 2건, 두 번째 페이지 1건이 반환되고 totalElements는 3이다")
    void should_return_correct_page_when_pageable_applied() {
        for (int i = 1; i <= 3; i++) {
            Merchant m = saveMerchant("600000000" + i, DEFAULT_FEE_RATE);
            Order o    = saveOrder(m.getId(), TEST_DATE, new BigDecimal("10000"), "PAID");
            savePayment(o.getId(), "PAYMENT", new BigDecimal("10000"));
        }
        flushAndClear();

        Page<MerchantSettlementDto> page0 = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 2));
        Page<MerchantSettlementDto> page1 = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(1, 2));

        assertThat(page0.getContent()).hasSize(2);
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page1.getContent()).hasSize(1);
        assertThat(page1.getTotalElements()).isEqualTo(3);
    }

    // ── 시나리오 7 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("시나리오 7: 주문 1건에 결제 2건이면 CTE로 order_amount 뻥튀기가 방지된다")
    void should_prevent_order_amount_inflation_when_order_has_multiple_payments() {
        // 단순 JOIN 시: SUM(order_amount) = 10000 * 2 = 20000 (틀림)
        // CTE 사용 시: 주문별 집계 후 가맹점별 GROUP BY → SUM(order_amount) = 10000 (정확)
        Merchant merchant = saveMerchant("7000000001", DEFAULT_FEE_RATE);
        Order order = saveOrder(merchant.getId(), TEST_DATE, new BigDecimal("10000"), "REFUNDED");

        savePayment(order.getId(), "PAYMENT", new BigDecimal("10000"));
        savePayment(order.getId(), "REFUND",  new BigDecimal("5000"));
        flushAndClear();

        Page<MerchantSettlementDto> result = orderRepository.findSettlementAggregation(TEST_DATE, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        MerchantSettlementDto dto = result.getContent().get(0);
        assertThat(dto.totalOrderCount()).isEqualTo(1);
        assertThat(dto.totalOrderAmount()).isEqualByComparingTo(new BigDecimal("10000")); // 뻥튀기 방지
        assertThat(dto.totalPaymentAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(dto.refundAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(dto.refundCount()).isEqualTo(1);
    }

    // ── 헬퍼 메서드 ─────────────────────────────────────────────────────────

    private Merchant saveMerchant(String businessNumber, BigDecimal feeRate) {
        return merchantRepository.save(Merchant.builder()
                .merchantName("가맹점-" + businessNumber)
                .businessNumber(businessNumber)
                .feeRate(feeRate)
                .build());
    }

    private Merchant saveMerchant(String businessNumber, BigDecimal feeRate, String status) {
        Merchant merchant = saveMerchant(businessNumber, feeRate);
        em.createNativeQuery("UPDATE merchants SET status = :status WHERE merchant_id = :id")
                .setParameter("status", status)
                .setParameter("id", merchant.getId())
                .executeUpdate();
        return merchant;
    }

    private Order saveOrder(Long merchantId, LocalDate date, BigDecimal amount, String status) {
        Order order = orderRepository.save(Order.builder()
                .merchantId(merchantId)
                .orderNumber("ORD-" + UUID.randomUUID())
                .orderDate(date)
                .orderAmount(amount)
                .build());
        em.createNativeQuery("UPDATE orders SET status = :status WHERE order_id = :id")
                .setParameter("status", status)
                .setParameter("id", order.getId())
                .executeUpdate();
        return order;
    }

    private Payment savePayment(Long orderId, String type, BigDecimal amount) {
        return paymentRepository.save(Payment.builder()
                .orderId(orderId)
                .paymentType(type)
                .paymentMethod("CREDIT_CARD")
                .paymentAmount(amount)
                .pgTransactionId("PG-" + UUID.randomUUID())
                .build());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private MerchantSettlementDto findByMerchant(Page<MerchantSettlementDto> page, Long merchantId) {
        return page.getContent().stream()
                .filter(dto -> dto.merchantId().equals(merchantId))
                .findFirst()
                .orElseThrow();
    }
}