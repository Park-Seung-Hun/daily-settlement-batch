# Phase 3-2: DlqRetryJob

## Progress

### ADR-010 확장: DLQ 재처리
- [ ] `DlqRetryProcessor.java`
- [ ] `DlqRetryJobConfig.java`

---

## Job Structure

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

---

## Deliverables

| File | Package / Path |
|------|----------------|
| `DlqRetryProcessor.java` | `com.settlement.processor` |
| `DlqRetryJobConfig.java` | `com.settlement.job.sync` |

---

## Verification Criteria

- [ ] DlqRetryJob이 DLQ 건을 재처리하고 성공 시 상태를 갱신한다
