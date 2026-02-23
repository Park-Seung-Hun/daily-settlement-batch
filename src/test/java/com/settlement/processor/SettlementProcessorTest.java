package com.settlement.processor;

import com.settlement.domain.dto.MerchantSettlementDto;
import com.settlement.domain.entity.DailySettlement;
import com.settlement.domain.exception.ValidationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementProcessorTest {

    private SettlementProcessor processor;

    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 2, 23);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0350");

    @BeforeEach
    void setUp() {
        processor = new SettlementProcessor();
    }

    @Test
    @DisplayName("시나리오 1: 정상 데이터가 입력되면 feeAmount와 netAmount가 올바르게 계산된다")
    void should_calculate_fee_and_net_when_valid_dto() throws Exception {
        // given
        MerchantSettlementDto dto = new MerchantSettlementDto(
                1L, ORDER_DATE, FEE_RATE, 10,
                new BigDecimal("100000.00"), new BigDecimal("100000.00"),
                null, null
        );

        // when
        DailySettlement result = processor.process(dto);

        // then
        assertThat(result.getMerchantId()).isEqualTo(1L);
        assertThat(result.getSettlementDate()).isEqualTo(ORDER_DATE);
        assertThat(result.getTotalOrderCount()).isEqualTo(10);
        assertThat(result.getFeeAmount()).isEqualByComparingTo("3500.00");   // 100000 * 0.0350
        assertThat(result.getNetAmount()).isEqualByComparingTo("96500.00"); // 100000 - 3500 - 0
        assertThat(result.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(result.getRefundCount()).isZero();
    }

    @Test
    @DisplayName("시나리오 2: 환불이 포함된 경우 refundAmount와 refundCount가 null-safe하게 처리된다")
    void should_handle_refund_when_refund_exists() throws Exception {
        // given
        MerchantSettlementDto dto = new MerchantSettlementDto(
                1L, ORDER_DATE, FEE_RATE, 5,
                new BigDecimal("100000.00"), new BigDecimal("100000.00"),
                new BigDecimal("30000.00"), 2
        );

        // when
        DailySettlement result = processor.process(dto);

        // then
        assertThat(result.getFeeAmount()).isEqualByComparingTo("3500.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("66500.00"); // 100000 - 3500 - 30000
        assertThat(result.getRefundAmount()).isEqualByComparingTo("30000.00");
        assertThat(result.getRefundCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("시나리오 3: totalPaymentAmount가 null이면 MISSING_PAYMENT 예외가 발생한다")
    void should_throw_missing_payment_when_payment_is_null() {
        // given
        MerchantSettlementDto dto = new MerchantSettlementDto(
                2L, ORDER_DATE, FEE_RATE, 1,
                new BigDecimal("100000.00"), null, null, null
        );

        // when & then
        assertThatThrownBy(() -> processor.process(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getErrorType()).isEqualTo("MISSING_PAYMENT");
                    assertThat(ve.getMerchantId()).isEqualTo(2L);
                });
    }

    @Test
    @DisplayName("시나리오 4: totalPaymentAmount가 0이면 MISSING_PAYMENT 예외가 발생한다")
    void should_throw_missing_payment_when_payment_is_zero() {
        // given
        MerchantSettlementDto dto = new MerchantSettlementDto(
                3L, ORDER_DATE, FEE_RATE, 1,
                new BigDecimal("100000.00"), BigDecimal.ZERO, null, null
        );

        // when & then
        assertThatThrownBy(() -> processor.process(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getErrorType()).isEqualTo("MISSING_PAYMENT");
                    assertThat(ve.getMerchantId()).isEqualTo(3L);
                });
    }

    @Test
    @DisplayName("시나리오 5: 주문금액과 결제금액 차이가 1원 초과이면 AMOUNT_MISMATCH 예외가 발생한다")
    void should_throw_amount_mismatch_when_diff_exceeds_tolerance() {
        // given — diff = 2000원
        MerchantSettlementDto dto = new MerchantSettlementDto(
                4L, ORDER_DATE, FEE_RATE, 1,
                new BigDecimal("100000.00"), new BigDecimal("98000.00"),
                null, null
        );

        // when & then
        assertThatThrownBy(() -> processor.process(dto))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    ValidationException ve = (ValidationException) ex;
                    assertThat(ve.getErrorType()).isEqualTo("AMOUNT_MISMATCH");
                    assertThat(ve.getMerchantId()).isEqualTo(4L);
                    assertThat(ve.getErrorDetail()).contains("\"diff\": \"2000.00\"");
                });
    }

    @Test
    @DisplayName("시나리오 6: 주문금액과 결제금액 차이가 정확히 1원이면 허용 오차 내로 정상 처리된다")
    void should_accept_when_diff_equals_tolerance() throws Exception {
        // given — diff = 1원 (경계값)
        MerchantSettlementDto dto = new MerchantSettlementDto(
                5L, ORDER_DATE, FEE_RATE, 1,
                new BigDecimal("100000.00"), new BigDecimal("99999.00"),
                null, null
        );

        // when
        DailySettlement result = processor.process(dto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getMerchantId()).isEqualTo(5L);
        assertThat(result.getTotalPaymentAmount()).isEqualByComparingTo("99999.00");
    }
}
