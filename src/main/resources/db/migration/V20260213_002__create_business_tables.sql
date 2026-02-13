-- ============================================================================
-- Daily Settlement Batch System — DDL (v3)
-- 기술 스택: PostgreSQL 16+ / Spring Boot 4.0.2 / Spring Batch 6.0
-- ============================================================================
-- 변경 이력:
--   v1 → v2: settlement_staging 테이블 추가 (Step 간 데이터 전달용)
--   v2 → v3:
--     [구조 변경]
--       - payments: payment_type 컬럼 추가 (PAYMENT/REFUND 구분)
--       - payments: pg_transaction_id Partial Unique Index 추가
--       - settlement_staging: 명시적 SEQUENCE + CACHE 50 적용
--       - settlement_error: 명시적 SEQUENCE + CACHE 50, job_execution_id 인덱스 추가
--       - sync_outbox: 명시적 SEQUENCE + CACHE 50 적용
--     [인덱스 수정]
--       - daily_settlement: idx_settlement_date 제거 (유니크 제약과 중복)
--       - sync_outbox: Partial Index 조건에서 컬럼 간 비교 제거
--     [환불 모델링 변경]
--       - 기존: 결제 status를 REFUNDED로 변경 (원래 기록 소실)
--       - 변경: 원결제(PAYMENT)는 COMPLETED 유지, 환불(REFUND)은 별도 행으로 INSERT
--       - 이유: "얼마를 받았고, 얼마를 돌려줬는가"가 분리되어야 정산 집계가 정확함
--
-- 설계 원칙:
--   1. 배치 Reader 성능을 위해 조회 조건에 맞는 인덱스를 선제적으로 설계한다.
--   2. UPSERT(멱등성)를 위한 복합 유니크 제약을 명시적으로 건다.
--   3. 각 테이블에 created_at / updated_at을 두어 데이터 추적성을 확보한다.
--   4. ENUM 대신 VARCHAR + CHECK 제약을 사용한다.
--   5. 도메인 경계를 존중한다.
--   6. 대량 INSERT 대상 테이블에는 명시적 SEQUENCE + CACHE를 적용한다.
--
-- 테이블 목록:
--   [주문 도메인]  merchants, orders, payments
--   [정산 도메인]  settlement_staging, daily_settlement, settlement_error
--   [동기화 도메인] sync_outbox, sync_dlq
-- ============================================================================


-- ============================================================================
-- SEQUENCE 정의 (대량 INSERT 대상 테이블용)
-- ============================================================================
-- [설계 결정] 왜 명시적 SEQUENCE를 선언하는가?
--
--   BIGSERIAL은 암묵적으로 시퀀스를 생성하지만, 기본 설정이 CACHE 1이다.
--   이는 INSERT마다 시퀀스에서 nextval()을 호출한다는 뜻이며,
--   배치에서 수만 건을 INSERT하면 시퀀스 호출 자체가 병목이 된다.
--   특히 Phase 4의 파티셔닝에서 멀티스레드 INSERT 시 락 경합이 발생한다.
--
--   CACHE 50을 설정하면 각 세션이 50개의 번호를 미리 확보하므로,
--   시퀀스 호출 빈도가 1/50로 줄어든다. 세션 비정상 종료 시 번호 gap이
--   생길 수 있지만, PK는 고유성만 보장하면 되므로 문제없다.
--
--   merchants, orders, payments 등 운영 테이블은 INSERT 빈도가 낮고
--   실시간 서비스에서 개별 INSERT하므로, BIGSERIAL 기본(CACHE 1)으로 충분하다.
--   아래 시퀀스는 배치에서 대량 INSERT가 발생하는 테이블에만 적용한다.
-- ============================================================================

-- settlement_staging: Step 1에서 검증 통과 건을 대량 INSERT (하루 수만 건)
CREATE SEQUENCE settlement_staging_id_seq
    AS BIGINT INCREMENT BY 50 START WITH 1 CACHE 50;

-- settlement_error: Step 1 SkipListener에서 에러 건 INSERT (건수는 적지만 배치 내 발생)
CREATE SEQUENCE settlement_error_id_seq
    AS BIGINT INCREMENT BY 50 START WITH 1 CACHE 50;

-- sync_outbox: 주문 상태 변경 시 Outbox INSERT (서비스에서도 사용하지만 배치 재처리도 있음)
CREATE SEQUENCE sync_outbox_id_seq
    AS BIGINT INCREMENT BY 50 START WITH 1 CACHE 50;


-- ============================================================================
-- 1. 상점(가맹점) 테이블
-- ============================================================================
CREATE TABLE IF NOT EXISTS merchants (
    merchant_id     BIGSERIAL       PRIMARY KEY,
    merchant_name   VARCHAR(200)    NOT NULL,
    business_number VARCHAR(20)     NOT NULL UNIQUE,
    fee_rate        NUMERIC(5, 4)   NOT NULL DEFAULT 0.0350,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  merchants IS '가맹점 마스터. 정산 집계의 기준 단위.';
COMMENT ON COLUMN merchants.fee_rate IS '수수료율. 0.0350 = 3.5%. 정산 시 주문 금액에서 차감.';


-- ============================================================================
-- 2. 주문 테이블
-- ============================================================================
CREATE TABLE IF NOT EXISTS orders (
    order_id        BIGSERIAL       PRIMARY KEY,
    merchant_id     BIGINT          NOT NULL REFERENCES merchants(merchant_id),
    order_number    VARCHAR(40)     NOT NULL UNIQUE,
    order_date      DATE            NOT NULL,
    order_amount    NUMERIC(15, 2)  NOT NULL CHECK (order_amount >= 0),
    status          VARCHAR(20)     NOT NULL DEFAULT 'CREATED'
                    CHECK (status IN (
                        'CREATED',
                        'PAID',
                        'SHIPPED',
                        'DELIVERED',
                        'CANCELLED',
                        'REFUND_REQUESTED',
                        'REFUNDED'
                    )),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_date_status ON orders (order_date, status);

CREATE INDEX idx_orders_sync_target
    ON orders (order_id)
    WHERE status IN ('DELIVERED', 'REFUND_REQUESTED');

COMMENT ON TABLE  orders IS '주문 원본. 정산 Job Step 1의 Reader가 읽는 핵심 원천 데이터.';
COMMENT ON INDEX  idx_orders_date_status IS '정산 배치용. order_date + status 복합 조건 최적화.';
COMMENT ON INDEX  idx_orders_sync_target IS '동기화 배치용. Partial Index로 대상 상태만 인덱싱.';


-- ============================================================================
-- 3. 결제 테이블
-- ============================================================================
-- [v3 변경] payment_type 컬럼 추가
--
-- [설계 결정] 왜 환불을 별도 행으로 분리하는가?
--
--   v2 문제: 환불 시 기존 결제의 status를 REFUNDED로 변경했다.
--   이렇게 하면 원결제 기록(COMPLETED)이 사라져서,
--   정산 Reader에서 SUM(FILTER WHERE status='COMPLETED')로 집계할 때
--   paid_amount가 0이 되어 정상 환불 건이 AMOUNT_MISMATCH 에러로 분류된다.
--
--   v3 해결: payment_type으로 원결제(PAYMENT)와 환불(REFUND)을 구분한다.
--   원결제는 status=COMPLETED로 영원히 유지되고,
--   환불은 별도 행(type=REFUND, status=COMPLETED)으로 INSERT한다.
--   이렇게 하면:
--     paid_amount    = SUM(amount) WHERE type='PAYMENT' AND status='COMPLETED'
--     refunded_amount = SUM(amount) WHERE type='REFUND'  AND status='COMPLETED'
--   "35,000원을 받았고, 35,000원을 돌려줬다"가 명확히 분리된다.
--
--   실무에서도 PG사 정산 대사(Reconciliation) 시
--   원거래와 취소거래를 별도 행으로 관리하는 것이 표준이다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS payments (
    payment_id      BIGSERIAL       PRIMARY KEY,
    order_id        BIGINT          NOT NULL REFERENCES orders(order_id),

    -- [v3 추가] 결제 유형: 원결제 vs 환불
    payment_type    VARCHAR(10)     NOT NULL DEFAULT 'PAYMENT'
                    CHECK (payment_type IN ('PAYMENT', 'REFUND')),

    payment_method  VARCHAR(30)     NOT NULL
                    CHECK (payment_method IN ('CREDIT_CARD', 'BANK_TRANSFER', 'VIRTUAL_ACCOUNT', 'POINT')),
    payment_amount  NUMERIC(15, 2)  NOT NULL CHECK (payment_amount >= 0),
    pg_transaction_id VARCHAR(60),
    status          VARCHAR(20)     NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('COMPLETED', 'CANCELLED', 'FAILED')),
                    -- [v3 변경] 'REFUNDED' 상태 제거.
                    -- 환불은 status 변경이 아니라 REFUND 타입의 별도 행으로 처리한다.
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- PostgreSQL은 FK에 자동 인덱스를 생성하지 않으므로 명시적으로 건다.
-- Step 1 Reader에서 orders와 LEFT JOIN 시 이 인덱스가 사용된다.
CREATE INDEX idx_payments_order_id ON payments (order_id);

-- [v3 추가] 정산 배치 Step 1 Reader에서 payment_type + status 조합으로 집계한다.
-- paid_amount: WHERE payment_type='PAYMENT' AND status='COMPLETED'
-- refunded_amount: WHERE payment_type='REFUND' AND status='COMPLETED'
CREATE INDEX idx_payments_type_status ON payments (payment_type, status);

-- [v3 추가] PG사 거래번호 중복 방지
-- pg_transaction_id는 NULL일 수 있으므로(가상계좌 등),
-- IS NOT NULL 조건의 Partial Unique Index를 사용한다.
-- PostgreSQL의 UNIQUE는 NULL 중복을 허용하기 때문에
-- 일반 UNIQUE 제약으로는 NULL 건이 다수 존재하는 경우를 처리할 수 없지만,
-- Partial Unique Index는 NULL이 아닌 건에 대해서만 고유성을 강제한다.
-- 이를 통해 "같은 PG 거래번호가 두 번 기록되는" 사고를 DB 레벨에서 방지한다.
CREATE UNIQUE INDEX idx_payments_pg_txn_unique
    ON payments (pg_transaction_id)
    WHERE pg_transaction_id IS NOT NULL;

COMMENT ON TABLE  payments IS '결제 내역. 주문 1:N 결제. payment_type으로 원결제/환불 구분.';
COMMENT ON COLUMN payments.payment_type IS 'PAYMENT=원결제, REFUND=환불. 환불은 별도 행으로 INSERT.';
COMMENT ON COLUMN payments.pg_transaction_id IS 'PG사 거래번호. Partial Unique Index로 중복 방지.';


-- ============================================================================
-- 4. 정산 스테이징 테이블 (Step 간 데이터 전달)
-- ============================================================================
-- [v3 변경] 명시적 SEQUENCE + CACHE 50 적용 (BIGSERIAL → BIGINT + DEFAULT)
--
-- [설계 결정] PK를 유지하는 이유:
--   JPA 엔티티에는 @Id가 필수이므로 PK가 필요하다.
--   복합 자연키(settlement_date + order_id)는 JPA에서 @IdClass/@EmbeddedId가
--   필요해져 코드가 번거롭고, 이 테이블의 "DELETE → INSERT" 멱등성 패턴과
--   충돌하여 ON CONFLICT 처리가 추가로 필요해진다.
--   UUID는 128비트로 인덱스 크기가 2배이고, 랜덤 삽입으로 B-Tree 최적화를 못 받는다.
--   따라서 BIGINT + 명시적 SEQUENCE(CACHE 50)가 가장 적합하다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS settlement_staging (
    staging_id          BIGINT          NOT NULL DEFAULT nextval('settlement_staging_id_seq') PRIMARY KEY,
    settlement_date     DATE            NOT NULL,

    order_id            BIGINT          NOT NULL,
    merchant_id         BIGINT          NOT NULL,

    order_amount        NUMERIC(15, 2)  NOT NULL,
    paid_amount         NUMERIC(15, 2)  NOT NULL,
    refunded_amount     NUMERIC(15, 2)  NOT NULL DEFAULT 0,

    order_status        VARCHAR(20)     NOT NULL,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 시퀀스 소유권 설정: 테이블 삭제 시 시퀀스도 함께 삭제되도록 한다.
ALTER SEQUENCE settlement_staging_id_seq OWNED BY settlement_staging.staging_id;

CREATE INDEX idx_staging_date_merchant
    ON settlement_staging (settlement_date, merchant_id);

COMMENT ON TABLE  settlement_staging IS 'Step 간 데이터 전달용 중간 테이블. Step1→INSERT, Step2→SELECT, Step3→DELETE.';
COMMENT ON COLUMN settlement_staging.staging_id IS 'BIGINT + 명시적 SEQUENCE(CACHE 50). JPA @Id 매핑용.';
COMMENT ON COLUMN settlement_staging.order_id IS '원본 주문 ID. FK 없음 (느슨한 결합). Reader JOIN에서 유효성 보장.';


-- ============================================================================
-- 5. 일일 정산 테이블
-- ============================================================================
-- [v3 변경] idx_settlement_date 제거
--
-- [설계 결정] 왜 중복 인덱스인가?
--   UNIQUE 제약 uq_settlement_date_merchant는 내부적으로
--   (settlement_date, merchant_id) 복합 B-Tree 인덱스를 생성한다.
--   복합 인덱스의 선두 컬럼이 settlement_date이므로,
--   WHERE settlement_date = ? 조건의 조회에도 이 유니크 인덱스가 활용된다.
--   따라서 settlement_date 단독 인덱스는 기능적으로 완전히 중복이며,
--   INSERT/UPDATE마다 두 개의 인덱스를 유지하는 불필요한 비용만 발생한다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS daily_settlement (
    settlement_id       BIGSERIAL       PRIMARY KEY,
    settlement_date     DATE            NOT NULL,
    merchant_id         BIGINT          NOT NULL REFERENCES merchants(merchant_id),

    total_order_count   INTEGER         NOT NULL DEFAULT 0,
    total_order_amount  NUMERIC(15, 2)  NOT NULL DEFAULT 0,
    total_payment_amount NUMERIC(15, 2) NOT NULL DEFAULT 0,
    fee_rate            NUMERIC(5, 4)   NOT NULL,
    fee_amount          NUMERIC(15, 2)  NOT NULL DEFAULT 0,
    net_amount          NUMERIC(15, 2)  NOT NULL DEFAULT 0,
    refund_amount       NUMERIC(15, 2)  NOT NULL DEFAULT 0,
    refund_count        INTEGER         NOT NULL DEFAULT 0,

    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_settlement_date_merchant UNIQUE (settlement_date, merchant_id)
);

-- [v3] idx_settlement_date 제거됨. uq_settlement_date_merchant가 커버.

COMMENT ON TABLE  daily_settlement IS '일일 정산 집계 결과. Step 2 Writer가 UPSERT. 리포트 Job Reader 소스.';
COMMENT ON COLUMN daily_settlement.fee_rate IS '정산 시점 수수료율 스냅샷. merchants.fee_rate 변경과 무관하게 추적 가능.';
COMMENT ON COLUMN daily_settlement.net_amount IS '가맹점 지급 순 정산 금액 = total_payment_amount - fee_amount.';
COMMENT ON CONSTRAINT uq_settlement_date_merchant ON daily_settlement
    IS '멱등성 보장 + WHERE settlement_date=? 조회 커버. 별도 단독 인덱스 불필요.';


-- ============================================================================
-- 6. 정산 에러 테이블
-- ============================================================================
-- [v3 변경] 명시적 SEQUENCE + CACHE 50, job_execution_id 인덱스 추가
-- ============================================================================
CREATE TABLE IF NOT EXISTS settlement_error (
    error_id            BIGINT          NOT NULL DEFAULT nextval('settlement_error_id_seq') PRIMARY KEY,
    settlement_date     DATE            NOT NULL,
    merchant_id         BIGINT          REFERENCES merchants(merchant_id),
    order_id            BIGINT          REFERENCES orders(order_id),

    error_type          VARCHAR(50)     NOT NULL
                        CHECK (error_type IN (
                            'AMOUNT_MISMATCH',
                            'MISSING_PAYMENT',
                            'DUPLICATE_PAYMENT',
                            'INVALID_STATUS',
                            'UNKNOWN'
                        )),
    error_message       TEXT            NOT NULL,
    error_detail        JSONB,

    resolved            BOOLEAN         NOT NULL DEFAULT FALSE,
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),

    job_execution_id    BIGINT,
    step_execution_id   BIGINT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

ALTER SEQUENCE settlement_error_id_seq OWNED BY settlement_error.error_id;

CREATE INDEX idx_settlement_error_unresolved
    ON settlement_error (settlement_date)
    WHERE resolved = FALSE;

CREATE INDEX idx_settlement_error_order ON settlement_error (order_id);

-- [v3 추가] 특정 배치 실행에서 발생한 에러를 모아보기 위한 인덱스.
-- 운영에서 "이번 배치 실행에서 에러가 몇 건 발생했는가?"를 조회할 때 사용.
-- job_execution_id는 Spring Batch 메타테이블의 BATCH_JOB_EXECUTION.JOB_EXECUTION_ID와 대응.
CREATE INDEX idx_settlement_error_job_exec ON settlement_error (job_execution_id);

COMMENT ON TABLE  settlement_error IS '정산 검증 실패 건 격리. Step 1 SkipListener에서 적재.';
COMMENT ON COLUMN settlement_error.error_id IS 'BIGINT + 명시적 SEQUENCE(CACHE 50).';
COMMENT ON COLUMN settlement_error.error_detail IS 'JSONB. 예: {"order_amount": 50000, "payment_sum": 45000, "diff": 5000}';
COMMENT ON INDEX  idx_settlement_error_job_exec IS '배치 실행 단위 에러 조회용. 운영 모니터링에서 활용.';


-- ============================================================================
-- 7. 외부 동기화 Outbox 테이블
-- ============================================================================
-- [v3 변경]
--   1) 명시적 SEQUENCE + CACHE 50
--   2) Partial Index 조건 개선: 컬럼 간 비교 제거
--
-- [설계 결정] Partial Index에서 retry_count < max_retries를 왜 제거하는가?
--
--   v2의 인덱스 조건: WHERE status IN (...) AND retry_count < max_retries
--   문제: retry_count < max_retries는 "컬럼 간 비교"이다.
--   PostgreSQL 쿼리 플래너가 이 Partial Index를 선택하려면,
--   쿼리의 WHERE 절이 인덱스 조건을 "내포(imply)"해야 하는데,
--   컬럼 간 비교는 플래너가 내포 관계를 판단하기 어렵다.
--   즉, Reader SQL에 동일한 조건을 넣어도 인덱스가 무시될 수 있다.
--
--   v3 해결: max_retries의 기본값이 3이고, 행마다 다른 값을 가질
--   가능성이 낮으므로, 재시도 초과 여부는 배치 애플리케이션 레벨에서
--   판단하도록 하고, Partial Index 조건은 status 기반으로 단순화한다.
--   재시도 초과 건은 SyncJob Step 2에서 DLQ로 이관되어 outbox에서
--   제거되므로, 결과적으로 Partial Index에는 처리 대상 건만 남게 된다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS sync_outbox (
    outbox_id       BIGINT          NOT NULL DEFAULT nextval('sync_outbox_id_seq') PRIMARY KEY,
    order_id        BIGINT          NOT NULL REFERENCES orders(order_id),
    event_type      VARCHAR(30)     NOT NULL
                    CHECK (event_type IN ('DELIVERY_COMPLETED', 'REFUND_REQUESTED')),

    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    retry_count     INTEGER         NOT NULL DEFAULT 0,
    max_retries     INTEGER         NOT NULL DEFAULT 3,
    last_error      TEXT,

    payload         JSONB           NOT NULL,

    scheduled_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    next_retry_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

ALTER SEQUENCE sync_outbox_id_seq OWNED BY sync_outbox.outbox_id;

-- [v3 변경] 컬럼 간 비교 제거. status 기반으로 단순화.
-- 재시도 초과 건은 DLQ로 이관되어 outbox에서 사라지므로,
-- PENDING/FAILED 상태의 건 = 아직 처리 가능한 건이다.
CREATE INDEX idx_outbox_pending
    ON sync_outbox (scheduled_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_outbox_order_event ON sync_outbox (order_id, event_type);

COMMENT ON TABLE  sync_outbox IS 'Outbox 패턴. 외부 전송 대기열. SyncJob Reader 소스.';
COMMENT ON COLUMN sync_outbox.outbox_id IS 'BIGINT + 명시적 SEQUENCE(CACHE 50).';
COMMENT ON COLUMN sync_outbox.next_retry_at IS 'Exponential Backoff: 1분 → 4분 → 16분.';
COMMENT ON INDEX  idx_outbox_pending IS 'v3: 컬럼 간 비교 제거. 플래너 안정성 확보.';


-- ============================================================================
-- 8. Dead Letter Queue (DLQ) 테이블
-- ============================================================================
CREATE TABLE IF NOT EXISTS sync_dlq (
    dlq_id          BIGSERIAL       PRIMARY KEY,
    outbox_id       BIGINT          NOT NULL,
    order_id        BIGINT          NOT NULL REFERENCES orders(order_id),
    event_type      VARCHAR(30)     NOT NULL,
    payload         JSONB           NOT NULL,

    total_attempts  INTEGER         NOT NULL,
    last_error      TEXT            NOT NULL,
    error_history   JSONB,
    failure_reason  VARCHAR(50)
                    CHECK (failure_reason IS NULL OR failure_reason IN (
                        'TIMEOUT', 'SERVER_ERROR', 'CLIENT_ERROR',
                        'CONNECTION_REFUSED', 'UNKNOWN'
                    )),

    status          VARCHAR(20)     NOT NULL DEFAULT 'DEAD'
                    CHECK (status IN ('DEAD', 'RETRYING', 'RESOLVED', 'DISCARDED')),
    resolved_at     TIMESTAMPTZ,
    resolved_by     VARCHAR(100),

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_dlq_unresolved
    ON sync_dlq (created_at)
    WHERE status = 'DEAD';

CREATE INDEX idx_dlq_failure_reason ON sync_dlq (failure_reason);

COMMENT ON TABLE  sync_dlq IS 'Dead Letter Queue. 재시도 초과 건 격리.';
COMMENT ON COLUMN sync_dlq.error_history IS '[{"attempt":1,"error":"timeout","at":"..."}]';


-- ============================================================================
-- updated_at 자동 갱신 트리거
-- ============================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- UPDATE가 발생하는 테이블에만 적용
-- settlement_staging: UPDATE 없음 (INSERT → SELECT → DELETE)
-- settlement_error: UPDATE는 resolved 처리 시에만 발생하나,
--   resolved_at을 직접 SET하므로 updated_at 트리거가 불필요
DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOR tbl IN
        SELECT unnest(ARRAY[
            'merchants', 'orders', 'payments',
            'daily_settlement', 'sync_outbox', 'sync_dlq'
        ])
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%s_updated_at
             BEFORE UPDATE ON %I
             FOR EACH ROW EXECUTE FUNCTION update_updated_at_column()',
            tbl, tbl
        );
    END LOOP;
END;
$$;
