# Phase 3-1: SyncJob

## Progress

### ADR-009: Outbox 패턴 디커플링
- [ ] `ExternalSettlementClient.java` (인터페이스)
- [ ] `StubExternalSettlementClient.java`
- [ ] `SyncProcessor.java`
- [ ] `SyncStepListener.java`
- [ ] syncOutboxStep 구현

### ADR-010: 조건부 DLQ 이관
- [ ] `DlqMigrationDecider.java`
- [ ] `DlqMigrationProcessor.java`
- [ ] dlqMigrationStep 구현

### Integration
- [ ] `SyncJobConfig.java` (Step + Decider 통합)
- [ ] `SyncJobIntegrationTest.java`
- [ ] `SyncProcessorTest.java`
- [ ] `DlqMigrationDeciderTest.java`

---

## Job Structure

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
| `SyncResult.java` | `com.settlement.domain.dto` |
| `ExternalSettlementClient.java` | `com.settlement.job.sync` |
| `StubExternalSettlementClient.java` | `com.settlement.job.sync` |
| `SyncJobConfig.java` | `com.settlement.job.sync` |
| `SyncProcessor.java` | `com.settlement.processor` |
| `DlqMigrationProcessor.java` | `com.settlement.processor` |
| `DlqMigrationDecider.java` | `com.settlement.job.sync` |
| `SyncStepListener.java` | `com.settlement.listener` |
| `SyncJobIntegrationTest.java` | test: `com.settlement.job.sync` |
| `SyncProcessorTest.java` | test: `com.settlement.processor` |
| `DlqMigrationDeciderTest.java` | test: `com.settlement.job.sync` |

---

## Verification Criteria

- [ ] SyncJob이 PENDING Outbox를 처리하고 SENT 상태로 갱신한다
- [ ] 외부 API 실패 시 retry_count가 증가하고 next_retry_at이 exponential backoff로 설정된다
- [ ] 재시도 초과 건이 DLQ 테이블로 이관된다 (원본 데이터 JSON 포함)
- [ ] DlqMigrationDecider가 조건부로 DLQ Step을 실행/건너뛴다
- [ ] ExternalSettlementClient 인터페이스 Mock으로 테스트가 독립적으로 실행된다
- [ ] 모든 테스트가 통과한다 (단위: Embedded PG/Mock, 통합: Testcontainers)
