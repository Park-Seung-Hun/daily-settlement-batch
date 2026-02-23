# Phase 2: Daily Settlement Job

## Progress

### ADR-005: RepositoryItemReader + QueryDSL Custom Repository
- [x] `MerchantSettlementDto.java` (사전 집계 DTO)
- [x] `OrderRepositoryCustom.java` + `OrderRepositoryImpl.java`
- [x] `OrderRepositoryTest.java`

### ADR-006: UPSERT — JPA save + INSERT ON CONFLICT
- [x] `DailySettlementRepository` UPSERT 네이티브 쿼리
- [x] UPSERT 멱등성 테스트

### ADR-007: Skip on ValidationException
- [x] `ValidationException.java`
- [x] `SettlementSkipListener.java` (skip 건 즉시 DB 저장 — @StepScope)
- [x] errorIsolationStep (SettlementError 저장)

### ADR-008: Exponential Backoff Retry
- [ ] Retry policy 설정 (TransientDataAccessException, 3회)

### Integration
- [x] `SettlementProcessor.java` (검증 + 변환)
- [x] `SettlementStepListener.java` (step 로깅)
- [ ] `OutboxCreationProcessor.java` + outboxCreationStep
- [x] `DailySettlementJobConfig.java` (2 Steps 통합)
- [x] `DailySettlementJobIntegrationTest.java` (5 scenarios GREEN)
- [x] `SettlementProcessorTest.java`

## Goal

핵심 정산 배치 Job을 구현한다. Reader에서 GROUP BY로 사전 집계된 데이터를 Processor에서 검증하고, UPSERT Writer로 멱등성을 보장하며, Outbox Step으로 외부 전송 대기열을 생성한다.

---

## Architecture Decision Records

### ADR-005: RepositoryItemReader + QueryDSL Custom Repository

**Context:** Batch Reader에서 주문-결제 JOIN 데이터를 조회해야 한다. 개별 건 조회 vs GROUP BY 사전 집계의 선택이 필요하다.

**Decision:** `RepositoryItemReader`를 사용하고, QueryDSL custom repository에서 **GROUP BY로 가맹점별 사전 집계된 결과**를 반환한다.

**Rationale:**
- ItemProcessor는 1건씩 처리하므로, Reader에서 이미 집계된 결과를 반환해야 Chunk 패턴에 자연스럽다
- QueryDSL `*RepositoryCustom` + `*RepositoryImpl`에서 `JPAQueryFactory`로 GROUP BY + JOIN 쿼리를 타입 안전하게 작성한다
- DB 레벨 집계로 데이터 전송량이 줄고, Processor는 검증만 담당하여 책임이 명확하다
- DTO Projection으로 집계 결과를 직접 조회하여 Entity 변환 오버헤드가 없다

### ADR-006: UPSERT 전략 — JPA save + Native INSERT ON CONFLICT

**Context:** 동일 정산 일자 + 가맹점에 대해 재실행 시 멱등성을 보장해야 한다.

**Decision:** 기본적으로 JPA `save()`를 사용하고, 대량 UPSERT가 필요한 경우 `@Query`로 PostgreSQL `INSERT ... ON CONFLICT DO UPDATE`를 활용한다.

**Rationale:**
- JPA `save()`는 기존 엔티티 존재 여부에 따라 INSERT/UPDATE를 자동 분기한다
- 대량 데이터 처리 시 native UPSERT가 성능 면에서 우위하다
- `ON CONFLICT (settlement_date, merchant_id)` unique constraint로 멱등성을 DB 수준에서 보장한다
- 두 전략을 상황에 따라 선택할 수 있도록 Repository에 양쪽 모두 준비한다

### ADR-007: Skip은 ValidationException에만 적용

**Context:** Chunk 처리 중 예외 발생 시 전체 Job 실패 vs 개별 건 Skip의 경계를 정해야 한다.

**Decision:** 비즈니스 검증 실패 (`ValidationException`)만 Skip 대상으로 하고, 나머지 예외는 Job을 중단시킨다.

**Rationale:**
- 데이터 품질 이슈(금액 불일치, 필수값 누락 등)는 개별 건 문제이므로 Skip 후 에러 테이블에 격리하는 것이 적합하다
- DB 연결 끊김, 시스템 장애 등은 Skip으로 해결되지 않으므로 즉시 중단하여 운영자 개입을 유도한다
- Skip limit(100건)을 두어 대량 데이터 오류 시 Job이 무한 Skip하지 않도록 방어한다

### ADR-008: TransientDataAccessException에 Exponential Backoff Retry

**Context:** 일시적 DB 장애(커넥션 풀 고갈, 네트워크 순단 등)에 대한 대응 전략이 필요하다.

**Decision:** `TransientDataAccessException`에 대해 최대 3회, Exponential Backoff로 재시도한다.

**Rationale:**
- 일시적 장애는 재시도로 복구되는 경우가 많다
- Exponential Backoff는 DB 부하를 가중시키지 않으면서 복구 시간을 확보한다
- 3회 초과 시 Job 실패로 전환하여 운영자에게 에스컬레이션한다

---

## Job Structure

**JobParameters:** `settlementDate` (LocalDate, 미지정 시 전일 기본값)

```
DailySettlementJob (param: settlementDate)
├── Step 1: settlementAggregationStep (Chunk)
│   ├── Reader: RepositoryItemReader
│   │   └── OrderRepositoryCustom.findAggregatedByDate(settlementDate)
│   │       (QueryDSL: orders + payments JOIN + GROUP BY merchant_id, order_date)
│   │       → MerchantSettlementDto (사전 집계된 가맹점별 결과)
│   ├── Processor: SettlementProcessor
│   │   └── MerchantSettlementDto → 검증 (총액 > 0, 건수 정합성) → DailySettlement
│   ├── Writer: RepositoryItemWriter
│   │   └── DailySettlementRepository UPSERT (ON CONFLICT 멱등성)
│   ├── Skip: ValidationException (limit 100)
│   ├── Retry: TransientDataAccessException (limit 3, exponential backoff)
│   └── Listeners:
│       ├── SettlementStepListener (step 시작/종료 로깅, 메트릭)
│       └── SettlementSkipListener (skip 건 로깅, 에러 데이터 수집)
│
├── Step 2: errorIsolationStep (Chunk or Tasklet)
│   └── Skip된 건을 SettlementError entity로 저장
│       (SettlementSkipListener가 수집한 데이터 기반)
│
└── Step 3: outboxCreationStep (Chunk)
    ├── Reader: RepositoryItemReader<DailySettlement>
    │   └── settlementDate 기준 신규/갱신된 정산 건 조회
    ├── Processor: Outbox 존재 여부 확인 → 없으면 SyncOutbox 생성
    └── Writer: RepositoryItemWriter<SyncOutbox>
```

---

## QueryDSL Integration

### Custom Repository 구조

```java
// OrderRepositoryCustom.java
public interface OrderRepositoryCustom {
    Page<MerchantSettlementDto> findAggregatedByDate(
        LocalDate settlementDate, Pageable pageable);
}

// OrderRepositoryImpl.java
public class OrderRepositoryImpl implements OrderRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<MerchantSettlementDto> findAggregatedByDate(
            LocalDate settlementDate, Pageable pageable) {
        // QOrder, QPayment JOIN
        // GROUP BY merchant_id, order_date
        // Projections.constructor로 집계 DTO 직접 생성
        // SUM(payment_amount), COUNT(*) 등 집계 함수 사용
    }
}

// OrderRepository.java — 두 인터페이스 확장
public interface OrderRepository
    extends JpaRepository<Order, Long>, OrderRepositoryCustom { }
```

### DTO Projection (사전 집계 결과)

```java
// Reader가 반환하는 가맹점별 사전 집계 DTO
public record MerchantSettlementDto(
    Long merchantId,
    LocalDate orderDate,
    Long totalCount,          // COUNT(*)
    BigDecimal totalAmount,   // SUM(payment_amount)
    BigDecimal totalOrderAmount // SUM(order_amount) — 검증용
) { }
```

---

## Processing Logic

### SettlementProcessor

Reader가 GROUP BY로 사전 집계한 `MerchantSettlementDto`를 받아 검증 후 `DailySettlement` Entity로 변환한다.

1. **검증 단계:**
   - `totalAmount > 0`, `totalCount > 0`
   - `totalOrderAmount == totalAmount` (주문-결제 금액 정합성, 불일치 시 ValidationException)
   - `merchantId`, `orderDate` null 체크
2. **변환 단계:**
   - 수수료 계산: `totalAmount * feeRate`
   - 정산 금액: `totalAmount - fee`
   - `DailySettlement` Entity 생성 (settlementDate, merchantId, totalCount, totalAmount, totalFee, settlementAmount)

### UPSERT Writer

```java
// DailySettlementRepository.java
@Query(value = """
    INSERT INTO daily_settlement (settlement_date, merchant_id, total_count,
        total_amount, total_fee, settlement_amount, created_at, updated_at)
    VALUES (:date, :merchantId, :count, :amount, :fee, :settlementAmount, now(), now())
    ON CONFLICT (settlement_date, merchant_id)
    DO UPDATE SET
        total_count = EXCLUDED.total_count,
        total_amount = EXCLUDED.total_amount,
        total_fee = EXCLUDED.total_fee,
        settlement_amount = EXCLUDED.settlement_amount,
        updated_at = now()
    """, nativeQuery = true)
void upsert(@Param("date") LocalDate date, ...);
```

---

## Test Plan

### Integration Tests (`*IntegrationTest.java`)

Testcontainers + Flyway + TRUNCATE 클린업:

```java
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("DailySettlementJob 통합 테스트")
class DailySettlementJobIntegrationTest {

    @Test
    @DisplayName("시나리오 1: 정상 주문-결제가 있으면 정산 집계가 생성된다")
    void should_create_settlement_when_valid_orders_exist() { ... }

    @Test
    @DisplayName("시나리오 2: 검증 실패 건은 Skip되고 에러 테이블에 격리된다")
    void should_skip_and_isolate_when_validation_fails() { ... }

    @Test
    @DisplayName("시나리오 3: 동일 날짜 재실행 시 UPSERT로 멱등성이 보장된다")
    void should_be_idempotent_when_rerun_for_same_date() { ... }

    @Test
    @DisplayName("시나리오 4: Skip limit 초과 시 Job이 FAILED 상태로 종료된다")
    void should_fail_when_skip_limit_exceeded() { ... }
}
```

### Unit Tests (`*Test.java`)

Embedded PostgreSQL (zonky):

```java
// 순수 Java 로직 테스트 — JPA 컨텍스트 불필요, plain unit test
@DisplayName("SettlementProcessor 단위 테스트")
class SettlementProcessorTest {

    @Test
    @DisplayName("시나리오 1: 유효한 MerchantSettlementDto가 DailySettlement로 변환된다")
    void should_convert_to_settlement_when_valid_dto() { ... }

    @Test
    @DisplayName("시나리오 2: 금액 불일치 시 ValidationException이 발생한다")
    void should_throw_validation_exception_when_amount_mismatch() { ... }
}

@DataJpaTest
@AutoConfigureEmbeddedDatabase
@DisplayName("OrderRepository QueryDSL 테스트")
class OrderRepositoryTest {

    @Test
    @DisplayName("시나리오 1: 날짜 기준으로 주문-결제 JOIN 데이터가 조회된다")
    void should_find_order_payments_when_date_given() { ... }
}
```

---

## Deliverables

| File | Package / Path |
|------|----------------|
| `MerchantSettlementDto.java` | `com.settlement.domain.dto` |
| `OutboxCreationProcessor.java` | `com.settlement.processor` |
| `ValidationException.java` | `com.settlement.domain` |
| `OrderRepositoryCustom.java` | `com.settlement.domain.repository` |
| `OrderRepositoryImpl.java` | `com.settlement.domain.repository` |
| `DailySettlementJobConfig.java` | `com.settlement.job.settlement` |
| `SettlementProcessor.java` | `com.settlement.processor` |
| `SettlementStepListener.java` | `com.settlement.listener` |
| `SettlementSkipListener.java` | `com.settlement.listener` |
| `DailySettlementJobIntegrationTest.java` | test: `com.settlement.job.settlement` |
| `SettlementProcessorTest.java` | test: `com.settlement.processor` |
| `OrderRepositoryTest.java` | test: `com.settlement.domain.repository` |

---

## Verification Criteria

- [ ] DailySettlementJob이 `COMPLETED` 상태로 종료된다
- [ ] 정상 주문-결제 데이터에 대해 `daily_settlement` 테이블에 집계 결과가 저장된다
- [ ] QueryDSL JOIN 쿼리가 정상 동작하고 DTO Projection이 올바르게 매핑된다
- [ ] 검증 실패 건이 Skip되고 `settlement_error` 테이블에 격리된다
- [ ] Skip limit(100) 초과 시 Job이 `FAILED`로 종료된다
- [ ] 동일 날짜 재실행 시 UPSERT로 기존 데이터가 갱신된다 (멱등성)
- [ ] Retry 대상 예외 발생 시 최대 3회 재시도 후 실패 처리된다
- [ ] outboxCreationStep이 정산 완료 건에 대해 SyncOutbox를 생성한다
- [ ] JobParameters로 `settlementDate`를 전달하고, 미지정 시 전일(D-1)이 기본값으로 사용된다
- [ ] 모든 테스트가 통과한다 (단위: Embedded PG, 통합: Testcontainers)
