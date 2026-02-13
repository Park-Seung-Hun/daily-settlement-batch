# Phase 4: Production-Grade Enhancements

## Progress

### ADR-012: settlement_date 기반 파티셔닝
- [ ] `DateRangePartitioner.java`
- [ ] `DailySettlementJobConfig.java` 파티셔닝 적용 (Manager Step)
- [ ] `DateRangePartitionerTest.java`
- [ ] `PartitionedSettlementJobIntegrationTest.java`

### ADR-013: Virtual Thread Executor
- [ ] `BatchExecutorConfig.java` (SimpleAsyncTaskExecutor + virtualThreads)

### ADR-014: @Scheduled + JobOperator 오케스트레이션
- [ ] `SettlementScheduler.java` (cron + 순차 실행)
- [ ] `SettlementSchedulerIntegrationTest.java`

### ADR-015: Actuator + 커스텀 REST
- [ ] `build.gradle` web/actuator 의존성 추가
- [ ] `BatchMonitoringController.java`
- [ ] `application.yaml` actuator/settlement 설정
- [ ] `BatchMonitoringControllerIntegrationTest.java`

## Goal

파티셔닝으로 처리 성능을 확장하고, Virtual Thread executor로 I/O 병렬성을 높이며, 스케줄링과 모니터링 API로 운영 자동화를 완성한다.

---

## Architecture Decision Records

### ADR-012: settlement_date 기반 파티셔닝

**Context:** 데이터 볼륨이 증가하면 단일 Step의 처리 시간이 선형 증가한다. 병렬 처리 전략이 필요하다.

**Decision:** `settlement_date` 기준으로 날짜 범위를 분할하는 Partitioner를 적용하여 DailySettlementJob의 집계 Step을 병렬 실행한다.

**Rationale:**
- 정산 데이터는 날짜 기준으로 자연스럽게 분할된다 (파티션 키로 적합)
- 파티션 간 데이터 의존성이 없어 병렬 처리가 안전하다
- Partitioner + Worker Step 구조로 기존 Chunk 로직 변경 없이 병렬화할 수 있다
- 파티션 수를 설정으로 조절하여 리소스 상황에 맞게 튜닝 가능하다

### ADR-013: Virtual Thread Executor

**Context:** Partitioner의 Worker Step 실행에 사용할 TaskExecutor를 결정해야 한다. 전통적 ThreadPoolTaskExecutor vs Virtual Thread의 선택이 필요하다.

**Decision:** Java 21 Virtual Thread 기반 `SimpleAsyncTaskExecutor`를 사용한다.

**Rationale:**
- Virtual Thread는 I/O 바운드 작업(DB 조회/쓰기)에서 높은 동시성을 저비용으로 제공한다
- `SimpleAsyncTaskExecutor`에 `setVirtualThreads(true)` 설정으로 간단히 적용 가능하다
- ThreadPool 크기 튜닝이 불필요하여 운영 복잡도가 낮다
- Spring Boot 4.x / Java 21 환경에서 공식 지원되는 방식이다

### ADR-014: @Scheduled + JobOperator로 순차 오케스트레이션

**Context:** 여러 Batch Job(정산 → 동기화 → 리포트)의 실행 순서와 스케줄링을 관리해야 한다.

**Decision:** `@Scheduled` 메서드에서 `JobOperator`를 사용하여 Job들을 순차 실행한다.

**Rationale:**
- Spring Batch 6.0에서 `JobOperator`가 `JobLauncher`를 확장하므로 단일 인터페이스로 Job 실행이 가능하다
- `@Scheduled` + cron으로 실행 시점을 선언적으로 관리한다
- Job 간 의존성(정산 완료 후 동기화)을 코드 레벨에서 명시적으로 제어한다
- 외부 스케줄러(Jenkins, Airflow 등) 없이도 자체 오케스트레이션이 가능하다

### ADR-015: Actuator + 커스텀 REST 엔드포인트

**Context:** 배치 실행 상태 모니터링과 수동 실행 트리거가 필요하다.

**Decision:** Spring Boot Actuator에 커스텀 REST 엔드포인트를 추가하여 배치 모니터링/제어 API를 제공한다.

**Rationale:**
- Actuator의 health, metrics 엔드포인트로 기본 모니터링을 확보한다
- 커스텀 REST API로 Job 실행 이력 조회, 수동 트리거, 실행 상태 확인 등 배치 전용 기능을 제공한다
- `JobRepository`(= `JobExplorer`)를 활용하여 메타데이터를 직접 조회한다
- 운영 대시보드나 알림 시스템과 연동할 수 있는 인터페이스를 확보한다

---

## Implementation Tasks

### 1. DateRangePartitioner

```java
public class DateRangePartitioner implements Partitioner {
    // settlement_date 범위를 N개 파티션으로 분할
    // ExecutionContext에 startDate, endDate 주입
    // 각 Worker Step이 해당 날짜 범위만 처리
}
```

**DailySettlementJobConfig 수정:**
- 기존 `settlementAggregationStep`을 Worker Step으로 전환
- Manager Step: `DateRangePartitioner` + `SimpleAsyncTaskExecutor` (Virtual Thread)
- `gridSize` 설정으로 파티션 수 조절

### 2. Virtual Thread Executor Configuration

```java
@Bean
public TaskExecutor batchTaskExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("batch-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(10); // 동시 파티션 수 제한
    return executor;
}
```

### 3. SettlementScheduler

```java
@Component
public class SettlementScheduler {
    // cron: 매일 02:00 실행
    // 순서: DailySettlementJob → SyncJob → ReportExportJob
    // 각 Job 완료 상태 확인 후 다음 Job 실행
    // 실패 시 로깅 + 알림 (후속 Job 실행 중단)
}
```

**실행 흐름:**
```
02:00 ──→ DailySettlementJob
              │
              ├── COMPLETED ──→ SyncJob
              │                    │
              │                    ├── COMPLETED ──→ ReportExportJob
              │                    │                    │
              │                    │                    └── COMPLETED ──→ 종료
              │                    │
              │                    └── FAILED ──→ 알림, 중단
              │
              └── FAILED ──→ 알림, 중단
```

### 4. BatchMonitoringController

```java
@RestController
@RequestMapping("/api/batch")
public class BatchMonitoringController {

    // GET /api/batch/jobs — 전체 Job 실행 이력
    // GET /api/batch/jobs/{jobName}/latest — 최근 실행 상태
    // GET /api/batch/jobs/{jobName}/executions — 실행 이력 목록
    // POST /api/batch/jobs/{jobName}/run — 수동 실행 트리거
    // GET /api/batch/health — 배치 시스템 헬스 체크
}
```

### 5. Gradle Dependencies 추가

```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

### 6. Application Configuration 추가

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, batch
  endpoint:
    health:
      show-details: when-authorized

# Settlement
settlement:
  scheduler:
    cron: "0 0 2 * * *"
    enabled: true
  partition:
    grid-size: 7  # 기본 7일 = 7 파티션
```

---

## Test Plan

### Integration Tests (`*IntegrationTest.java`)

```java
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Partitioned DailySettlementJob 통합 테스트")
class PartitionedSettlementJobIntegrationTest {

    @Test
    @DisplayName("시나리오 1: 파티셔닝된 Job이 전체 날짜 범위를 병렬 처리한다")
    void should_process_all_dates_when_partitioned() { ... }

    @Test
    @DisplayName("시나리오 2: 개별 파티션 실패가 전체 Job에 올바르게 전파된다")
    void should_propagate_partition_failure_to_job() { ... }
}

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SettlementScheduler 통합 테스트")
class SettlementSchedulerIntegrationTest {

    @Test
    @DisplayName("시나리오 1: 스케줄러가 Job들을 순차 실행한다")
    void should_execute_jobs_sequentially() { ... }

    @Test
    @DisplayName("시나리오 2: 선행 Job 실패 시 후속 Job이 실행되지 않는다")
    void should_stop_chain_when_preceding_job_fails() { ... }
}

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("BatchMonitoringController 통합 테스트")
class BatchMonitoringControllerIntegrationTest {

    @Test
    @DisplayName("시나리오 1: Job 실행 이력이 정상 조회된다")
    void should_return_job_execution_history() { ... }

    @Test
    @DisplayName("시나리오 2: 수동 트리거로 Job이 실행된다")
    void should_trigger_job_manually() { ... }
}
```

### Unit Tests (`*Test.java`)

```java
@DisplayName("DateRangePartitioner 단위 테스트")
class DateRangePartitionerTest {

    @Test
    @DisplayName("시나리오 1: 7일 범위가 7개 파티션으로 분할된다")
    void should_create_partitions_for_each_date() { ... }

    @Test
    @DisplayName("시나리오 2: 시작일과 종료일이 같으면 1개 파티션이 생성된다")
    void should_create_single_partition_when_same_date() { ... }

    @Test
    @DisplayName("시나리오 3: gridSize보다 날짜 범위가 작으면 날짜 수만큼 파티션이 생성된다")
    void should_limit_partitions_to_date_range() { ... }
}
```

---

## Deliverables

| File | Package / Path |
|------|----------------|
| `DateRangePartitioner.java` | `com.settlement.partitioner` |
| `BatchExecutorConfig.java` | `com.settlement.config` |
| `SettlementScheduler.java` | `com.settlement.scheduler` |
| `BatchMonitoringController.java` | `com.settlement.web` |
| `DailySettlementJobConfig.java` | `com.settlement.job.settlement` (update) |
| `build.gradle` | project root (update) |
| `application.yaml` | `src/main/resources/` (update) |
| `PartitionedSettlementJobIntegrationTest.java` | test: `com.settlement.job.settlement` |
| `SettlementSchedulerIntegrationTest.java` | test: `com.settlement.scheduler` |
| `BatchMonitoringControllerIntegrationTest.java` | test: `com.settlement.web` |
| `DateRangePartitionerTest.java` | test: `com.settlement.partitioner` |

---

## Verification Criteria

- [ ] 파티셔닝된 DailySettlementJob이 날짜 범위를 병렬 처리하고 `COMPLETED`로 종료된다
- [ ] Virtual Thread executor가 정상 동작한다 (Thread 이름에 `virtual` 포함 확인)
- [ ] 개별 파티션 실패 시 전체 Job 상태에 올바르게 반영된다
- [ ] SettlementScheduler가 cron 표현식에 따라 Job 체인을 순차 실행한다
- [ ] 선행 Job 실패 시 후속 Job이 실행되지 않고 알림 로그가 출력된다
- [ ] `GET /api/batch/jobs/{name}/latest`로 최근 실행 상태를 조회할 수 있다
- [ ] `POST /api/batch/jobs/{name}/run`으로 수동 Job 실행이 가능하다
- [ ] Actuator health 엔드포인트에 배치 시스템 상태가 포함된다
- [ ] 모든 테스트가 통과한다
