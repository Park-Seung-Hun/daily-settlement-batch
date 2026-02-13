# Phase 3: External Synchronization and Report Export

## Goal

Outbox 패턴으로 정산 결과를 외부 시스템과 동기화하고, DLQ로 실패 건을 관리하며, CSV 리포트를 생성한다.

---

## Architecture Decision Records

### ADR-009: Outbox 패턴으로 정산-동기화 디커플링

**Context:** 정산 집계 완료 후 외부 시스템(정산 플랫폼, ERP 등)에 결과를 전송해야 한다. 동기 호출 vs 비동기 패턴의 선택이 필요하다.

**Decision:** Outbox 테이블(`sync_outbox`)을 중간 버퍼로 두고, 별도 SyncJob이 Outbox를 폴링하여 외부 전송을 수행한다.

**Rationale:**
- 정산 Job과 동기화 Job을 분리하여, 외부 API 장애가 정산 집계에 영향을 주지 않는다
- Outbox 상태 머신(PENDING → SENT / FAILED)으로 전송 상태를 추적할 수 있다
- 재시도 횟수/시각 관리로 점진적 백오프가 가능하다
- DailySettlementJob의 outboxCreationStep(Phase 2)에서 Outbox가 생성되어, 정산과 동기화의 데이터 일관성이 보장된다

### ADR-010: JobExecutionDecider로 조건부 DLQ 이관

**Context:** 재시도 횟수를 초과한 Outbox 건의 처리 방식을 결정해야 한다.

**Decision:** `JobExecutionDecider`를 사용하여, 재시도 초과 건이 존재할 때만 DLQ 이관 Step을 실행한다.

**Rationale:**
- 조건부 Step 실행으로 불필요한 처리를 방지한다
- DLQ 이관 로직이 명시적 Step으로 분리되어 모니터링/디버깅이 용이하다
- DLQ 테이블은 원본 데이터(JSON)를 포함하여 수동 복구 시 참고할 수 있다

### ADR-011: FlatFileItemWriter로 CSV 생성

**Context:** 정산 리포트 파일 포맷과 생성 방식을 결정해야 한다.

**Decision:** Spring Batch의 `FlatFileItemWriter`로 CSV 파일을 생성한다.

**Rationale:**
- `FlatFileItemWriter`는 Spring Batch의 표준 파일 출력 메커니즘으로, Chunk 처리와 자연스럽게 통합된다
- CSV는 범용 포맷으로, 후속 시스템(BI, 회계)과의 연동이 용이하다
- 헤더/푸터 콜백으로 요약 정보(총건수, 합계)를 포함할 수 있다

---

## Job Structures

### SyncJob

```
SyncJob
├── Step 1: syncOutboxStep (Chunk)
│   ├── Reader: RepositoryItemReader<SyncOutbox>
│   │   └── status=PENDING, next_retry_at <= now()
│   ├── Processor: SyncProcessor
│   │   └── ExternalSettlementClient 호출
│   │   └── 성공 → status=SENT, 실패 → retry_count++, next_retry_at 갱신
│   ├── Writer: RepositoryItemWriter<SyncOutbox>
│   └── Listener: SyncStepListener
│
├── Decider: DlqMigrationDecider
│   └── retry_count > max_retries인 FAILED 건 존재 여부 확인
│
└── Step 2: dlqMigrationStep (Chunk, conditional)
    ├── Reader: RepositoryItemReader<SyncOutbox>
    │   └── status=FAILED, retry_count > max_retries
    ├── Processor: DlqMigrationProcessor
    │   └── SyncOutbox → SyncDlq 변환 (원본 데이터 JSON 직렬화)
    └── Writer: RepositoryItemWriter<SyncDlq>
```

### DlqRetryJob

```
DlqRetryJob
└── Step 1: dlqRetryStep (Chunk)
    ├── Reader: RepositoryItemReader<SyncDlq>
    │   └── 수동 재처리 대상 (status 필터)
    ├── Processor: DlqRetryProcessor
    │   └── JSON 역직렬화 → ExternalClient 재호출
    └── Writer: 성공 시 DLQ에서 제거, Outbox 상태 갱신
```

### ReportExportJob

```
ReportExportJob
└── Step 1: reportExportStep (Chunk)
    ├── Reader: RepositoryItemReader<DailySettlement>
    │   └── 날짜 범위 기준 조회
    ├── Processor: ReportRowProcessor
    │   └── DailySettlement → ReportRowDto 변환 (포맷팅)
    └── Writer: FlatFileItemWriter<ReportRowDto>
        └── CSV 파일 출력 (헤더 + 데이터 + 푸터 요약)
```

---

## Outbox State Machine

```
PENDING ──(API 성공)──→ SENT
   │
   └──(API 실패)──→ PENDING (retry_count++, next_retry_at 갱신)
                        │
                        └──(retry_count > max)──→ FAILED
                                                    │
                                                    └──(DLQ 이관)──→ SyncDlq 테이블
```

---

## External Client

```java
public interface ExternalSettlementClient {
    SyncResult send(DailySettlement settlement);
}
```

- 초기 구현: stub/mock (HTTP 호출 없이 성공/실패 시뮬레이션)
- 프로덕션: `RestClient` 기반 HTTP 호출로 교체
- 인터페이스 분리로 테스트 시 Mock 주입 가능

---

## Test Plan

### Integration Tests (`*IntegrationTest.java`)

```java
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SyncJob 통합 테스트")
class SyncJobIntegrationTest {

    @Test
    @DisplayName("시나리오 1: PENDING 상태의 Outbox가 외부 전송되고 SENT로 갱신된다")
    void should_send_and_mark_sent_when_pending_outbox_exists() { ... }

    @Test
    @DisplayName("시나리오 2: 외부 API 실패 시 retry_count가 증가하고 next_retry_at이 갱신된다")
    void should_increment_retry_when_external_api_fails() { ... }

    @Test
    @DisplayName("시나리오 3: 재시도 초과 건이 DLQ 테이블로 이관된다")
    void should_migrate_to_dlq_when_retry_exceeded() { ... }
}

@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ReportExportJob 통합 테스트")
class ReportExportJobIntegrationTest {

    @Test
    @DisplayName("시나리오 1: 정산 데이터가 CSV 파일로 정상 출력된다")
    void should_export_csv_when_settlement_data_exists() { ... }

    @Test
    @DisplayName("시나리오 2: 데이터 없는 날짜 범위에서도 헤더만 포함된 CSV가 생성된다")
    void should_create_header_only_csv_when_no_data() { ... }
}
```

### Unit Tests (`*Test.java`)

```java
@DisplayName("SyncProcessor 단위 테스트")
class SyncProcessorTest {

    @Test
    @DisplayName("시나리오 1: 외부 전송 성공 시 상태가 SENT로 변경된다")
    void should_mark_sent_when_external_call_succeeds() { ... }

    @Test
    @DisplayName("시나리오 2: 외부 전송 실패 시 retry_count가 증가한다")
    void should_increment_retry_when_external_call_fails() { ... }
}

@DisplayName("DlqMigrationDecider 단위 테스트")
class DlqMigrationDeciderTest {

    @Test
    @DisplayName("시나리오 1: 재시도 초과 건이 있으면 MIGRATE 상태를 반환한다")
    void should_return_migrate_when_exceeded_retries_exist() { ... }

    @Test
    @DisplayName("시나리오 2: 재시도 초과 건이 없으면 SKIP 상태를 반환한다")
    void should_return_skip_when_no_exceeded_retries() { ... }
}
```

---

## Deliverables

| File | Package / Path |
|------|----------------|
| `ReportRowDto.java` | `com.settlement.domain.dto` |
| `SyncResult.java` | `com.settlement.domain.dto` |
| `ExternalSettlementClient.java` | `com.settlement.job.sync` |
| `StubExternalSettlementClient.java` | `com.settlement.job.sync` |
| `SyncJobConfig.java` | `com.settlement.job.sync` |
| `DlqRetryJobConfig.java` | `com.settlement.job.sync` |
| `ReportExportJobConfig.java` | `com.settlement.job.report` |
| `SyncProcessor.java` | `com.settlement.processor` |
| `DlqMigrationProcessor.java` | `com.settlement.processor` |
| `DlqRetryProcessor.java` | `com.settlement.processor` |
| `ReportRowProcessor.java` | `com.settlement.processor` |
| `DlqMigrationDecider.java` | `com.settlement.job.sync` |
| `SyncStepListener.java` | `com.settlement.listener` |
| `SyncJobIntegrationTest.java` | test: `com.settlement.job.sync` |
| `ReportExportJobIntegrationTest.java` | test: `com.settlement.job.report` |
| `SyncProcessorTest.java` | test: `com.settlement.processor` |
| `DlqMigrationDeciderTest.java` | test: `com.settlement.job.sync` |

---

## Verification Criteria

- [ ] SyncJob이 PENDING Outbox를 처리하고 SENT 상태로 갱신한다
- [ ] 외부 API 실패 시 retry_count가 증가하고 next_retry_at이 exponential backoff로 설정된다
- [ ] 재시도 초과 건이 DLQ 테이블로 이관된다 (원본 데이터 JSON 포함)
- [ ] DlqMigrationDecider가 조건부로 DLQ Step을 실행/건너뛴다
- [ ] DlqRetryJob이 DLQ 건을 재처리하고 성공 시 상태를 갱신한다
- [ ] ReportExportJob이 CSV 파일을 정상 생성한다 (헤더, 데이터, 푸터 요약)
- [ ] ExternalSettlementClient 인터페이스 Mock으로 테스트가 독립적으로 실행된다
- [ ] 모든 테스트가 통과한다 (단위: Embedded PG/Mock, 통합: Testcontainers)
