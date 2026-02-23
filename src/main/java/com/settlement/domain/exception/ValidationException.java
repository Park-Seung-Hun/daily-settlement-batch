package com.settlement.domain.exception;

/**
 * 정산 데이터 검증 실패 시 발생하는 예외.
 * Spring Batch Skip 정책 대상이며, SettlementSkipListener에서 에러 정보 추출에 사용됨.
 */
public class ValidationException extends RuntimeException {

    private final String errorType;
    private final Long merchantId;
    private final String errorDetail;

    public ValidationException(String errorType, Long merchantId, String errorDetail) {
        super(String.format("[%s] merchantId=%d, detail=%s", errorType, merchantId, errorDetail));
        this.errorType = errorType;
        this.merchantId = merchantId;
        this.errorDetail = errorDetail;
    }

    public String getErrorType() {
        return errorType;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getErrorDetail() {
        return errorDetail;
    }
}