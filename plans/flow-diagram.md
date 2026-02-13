# Batch Job Flow Diagram

> 기존 Phase 계획 문서(Phase 2~4)를 기준으로 작성한 Mermaid 다이어그램이다.
> Job/Step 구조가 변경되면 이 다이어그램도 함께 업데이트한다.

---

## 1. Job Orchestration (SettlementScheduler)

매일 02:00에 순차 실행되며, 선행 Job 실패 시 후속 Job을 중단한다.

```mermaid
flowchart TD
    CRON["@Scheduled cron 02:00"]
    JOB1["DailySettlementJob"]
    JOB2["SyncJob"]
    JOB3["ReportExportJob"]
    ALERT["알림 + 중단"]
    DONE["종료"]

    CRON --> JOB1
    JOB1 -->|COMPLETED| JOB2
    JOB1 -->|FAILED| ALERT
    JOB2 -->|COMPLETED| JOB3
    JOB2 -->|FAILED| ALERT
    JOB3 -->|COMPLETED| DONE
    JOB3 -->|FAILED| ALERT
```

---

## 2. DailySettlementJob

JobParameters: `settlementDate` (미지정 시 D-1)

```mermaid
flowchart TD
    subgraph DailySettlementJob
        S1["Step 1: settlementAggregationStep\n(Chunk)"]
        S2["Step 2: errorIsolationStep\n(Chunk/Tasklet)"]
        S3["Step 3: outboxCreationStep\n(Chunk)"]
    end

    S1 --> S2 --> S3

    subgraph S1_detail["settlementAggregationStep 상세"]
        R1["Reader: RepositoryItemReader\nOrderRepositoryCustom\n(QueryDSL GROUP BY)\n→ MerchantSettlementDto"]
        P1["Processor: SettlementProcessor\n검증 (총액>0, 건수 정합성)\n→ DailySettlement 변환"]
        W1["Writer: RepositoryItemWriter\nDailySettlementRepository\nUPSERT (ON CONFLICT)"]
        SKIP1["Skip: ValidationException\n(limit 100)"]
        RETRY1["Retry: TransientDataAccessException\n(limit 3, exponential backoff)"]
        LISTEN1["Listeners:\nSettlementStepListener\nSettlementSkipListener"]
    end

    R1 --> P1 --> W1
    S1 -.- S1_detail

    subgraph S2_detail["errorIsolationStep 상세"]
        ERR["SettlementSkipListener가\n수집한 Skip 건\n→ SettlementError 저장"]
    end

    S2 -.- S2_detail

    subgraph S3_detail["outboxCreationStep 상세"]
        R3["Reader: RepositoryItemReader\nDailySettlement\n(신규/갱신 건 조회)"]
        P3["Processor: OutboxCreationProcessor\nOutbox 존재 확인\n→ SyncOutbox 생성"]
        W3["Writer: RepositoryItemWriter\nSyncOutbox"]
    end

    R3 --> P3 --> W3
    S3 -.- S3_detail
```

---

## 3. SyncJob

```mermaid
flowchart TD
    subgraph SyncJob
        SS1["Step 1: syncOutboxStep\n(Chunk)"]
        DEC{"Decider:\nDlqMigrationDecider\n(FAILED 건 존재?)"}
        SS2["Step 2: dlqMigrationStep\n(Chunk, conditional)"]
        SKIP_DLQ["DLQ Step 건너뜀"]
    end

    SS1 --> DEC
    DEC -->|MIGRATE| SS2
    DEC -->|SKIP| SKIP_DLQ

    subgraph SS1_detail["syncOutboxStep 상세"]
        SR1["Reader: RepositoryItemReader\nSyncOutbox\n(status=PENDING,\nnext_retry_at <= now)"]
        SP1["Processor: SyncProcessor\nExternalSettlementClient 호출\n성공→SENT / 실패→retry_count++"]
        SW1["Writer: RepositoryItemWriter\nSyncOutbox"]
        SL1["Listener: SyncStepListener"]
    end

    SR1 --> SP1 --> SW1
    SS1 -.- SS1_detail

    subgraph SS2_detail["dlqMigrationStep 상세"]
        SR2["Reader: RepositoryItemReader\nSyncOutbox\n(status=FAILED,\nretry_count > max)"]
        SP2["Processor: DlqMigrationProcessor\nSyncOutbox → SyncDlq\n(원본 데이터 JSON 직렬화)"]
        SW2["Writer: RepositoryItemWriter\nSyncDlq"]
    end

    SR2 --> SP2 --> SW2
    SS2 -.- SS2_detail
```

---

## 4. DlqRetryJob (수동 트리거)

```mermaid
flowchart TD
    subgraph DlqRetryJob
        DS1["Step 1: dlqRetryStep\n(Chunk)"]
    end

    subgraph DS1_detail["dlqRetryStep 상세"]
        DR1["Reader: RepositoryItemReader\nSyncDlq\n(수동 재처리 대상)"]
        DP1["Processor: DlqRetryProcessor\nJSON 역직렬화\n→ ExternalClient 재호출"]
        DW1["Writer:\n성공 → DLQ 제거, Outbox 갱신"]
    end

    DR1 --> DP1 --> DW1
    DS1 -.- DS1_detail
```

---

## 5. ReportExportJob

```mermaid
flowchart TD
    subgraph ReportExportJob
        RS1["Step 1: reportExportStep\n(Chunk)"]
    end

    subgraph RS1_detail["reportExportStep 상세"]
        RR1["Reader: RepositoryItemReader\nDailySettlement\n(날짜 범위 조회)"]
        RP1["Processor: ReportRowProcessor\nDailySettlement → ReportRowDto\n(포맷팅)"]
        RW1["Writer: FlatFileItemWriter\nCSV 파일 출력\n(헤더 + 데이터 + 푸터 요약)"]
    end

    RR1 --> RP1 --> RW1
    RS1 -.- RS1_detail
```

---

## 6. Outbox State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING : outboxCreationStep에서 생성

    PENDING --> SENT : API 호출 성공 (SyncProcessor)
    PENDING --> PENDING : API 실패 (retry_count++, next_retry_at 갱신)
    PENDING --> FAILED : retry_count > max_retries

    FAILED --> SyncDlq : DlqMigrationProcessor 이관
    SyncDlq --> SENT : DlqRetryJob 재처리 성공

    SENT --> [*]
```

---

## 7. Phase 4: Partitioning (DailySettlementJob 확장)

Phase 4에서 settlementAggregationStep에 파티셔닝이 적용된다.

```mermaid
flowchart TD
    MGR["Manager Step:\nDateRangePartitioner\n(settlement_date 기준 분할)"]
    EXE["TaskExecutor:\nSimpleAsyncTaskExecutor\n(Virtual Thread, concurrencyLimit=10)"]

    W1["Worker 1:\nsettlementAggregationStep\n(날짜 범위 A)"]
    W2["Worker 2:\nsettlementAggregationStep\n(날짜 범위 B)"]
    WN["Worker N:\nsettlementAggregationStep\n(날짜 범위 N)"]

    MGR --> EXE
    EXE --> W1
    EXE --> W2
    EXE --> WN

    W1 --> MERGE["다음 Step으로 진행\n(errorIsolationStep)"]
    W2 --> MERGE
    WN --> MERGE
```
