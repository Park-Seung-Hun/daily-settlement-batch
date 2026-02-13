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
