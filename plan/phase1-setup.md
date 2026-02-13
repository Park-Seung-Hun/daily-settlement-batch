# Phase 1: Infrastructure Setup and Batch Baseline

## Goal

Docker PostgreSQL + Flyway + JPA Entity + Spring Batch 연동을 완료하고, 검증 Job으로 전체 파이프라인이 정상 동작하는지 확인한다.

---

## Architecture Decision Records

### ADR-001: PostgreSQL 단일 DB for Business + Metadata

**Context:** Spring Batch 메타데이터 테이블과 비즈니스 테이블을 분리할지, 단일 DB에 배치할지 결정이 필요하다.

**Decision:** PostgreSQL 단일 DB에 비즈니스 테이블과 Batch 메타데이터 테이블을 함께 운영한다.

**Rationale:**
- 정산 배치는 단일 도메인 서비스로, DB 분리에 따른 운영 복잡도 증가가 이점보다 크다
- 트랜잭션 일관성 확보가 용이하다 (Job 상태와 비즈니스 데이터가 동일 트랜잭션 경계)
- 스키마 네임스페이스로 논리적 분리가 가능하다 (Batch 메타테이블은 `BATCH_` 접두사)

### ADR-002: Spring Data JPA + QueryDSL 채택

**Context:** 데이터 액세스 계층의 기술 선택이 필요하다. 순수 JDBC, Spring JDBC Template, JPA, JPA + QueryDSL 등의 옵션이 있다.

**Decision:** Spring Data JPA를 기본으로 하고, 복잡한 동적 쿼리에는 QueryDSL 5.x (jakarta)를 사용한다.

**Rationale:**
- Spring Data JPA Repository 패턴은 Spring Batch의 `RepositoryItemReader`/`RepositoryItemWriter`와 자연스럽게 통합된다
- QueryDSL은 타입 안전한 동적 쿼리를 제공하여, 날짜 범위/상태 필터 등 복잡한 조건 조합에 적합하다
- Entity 중심 도메인 모델링으로 비즈니스 로직 표현력이 높다
- `*RepositoryCustom` + `*RepositoryImpl` 패턴으로 표준 Repository와 QueryDSL을 깔끔하게 분리한다

### ADR-003: Flyway 스키마 마이그레이션 관리

**Context:** DB 스키마 변경 관리 전략이 필요하다. JPA `ddl-auto`, Flyway, Liquibase 등의 옵션이 있다.

**Decision:** Flyway로 스키마 마이그레이션을 관리하고, JPA `ddl-auto`는 `validate` 모드로만 사용한다.

**Rationale:**
- 버전 관리되는 마이그레이션 스크립트로 스키마 변경 이력을 추적할 수 있다
- `validate` 모드는 Entity와 실제 테이블 간 불일치를 빌드 타임에 감지한다
- 프로덕션 환경에서 안전한 스키마 변경을 보장한다
- PostgreSQL 전용 DDL (ENUM, partial index 등)을 자유롭게 활용할 수 있다

### ADR-004: DB 테스트 이중 전략

**Context:** DB 의존 테스트에서 속도와 신뢰성을 모두 확보할 전략이 필요하다.

**Decision:** 단위/Slice 테스트는 Embedded PostgreSQL(zonky), 통합 테스트는 Testcontainers + TRUNCATE 클린업을 사용한다.

**Rationale:**
- **Embedded PostgreSQL (zonky):** Docker 없이 실행되어 빠르고, 실제 PG 바이너리이므로 PG 전용 문법 호환성을 보장한다. `@DataJpaTest` 등 Slice 테스트에 적합하다.
- **Testcontainers:** Flyway 마이그레이션 + 실제 PG 컨테이너로 프로덕션과 동일한 환경을 재현한다. Spring Batch Job은 자체 트랜잭션을 관리하므로 `@Transactional` 롤백이 동작하지 않아, TRUNCATE 기반 클린업이 필수다.
- 이중 전략으로 개발 주기에서는 빠른 피드백, CI에서는 높은 신뢰성을 동시에 달성한다.

---

## Implementation Tasks

### 1. Docker Compose

`docker-compose.yml` 생성:
- PostgreSQL 16
- Port: 5432
- Database / User / Password: `settlement`
- Volume: `pgdata` (data persistence)

### 2. Gradle Dependencies (`build.gradle`)

```groovy
// Spring Batch
implementation 'org.springframework.boot:spring-boot-starter-batch'

// Spring Data JPA
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

// QueryDSL
implementation 'com.querydsl:querydsl-jpa:5.x.x:jakarta'
annotationProcessor 'com.querydsl:querydsl-apt:5.x.x:jakarta'
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'

// Flyway
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'

// PostgreSQL
runtimeOnly 'org.postgresql:postgresql'

// Test — Embedded PostgreSQL (unit/slice)
testImplementation 'io.zonky.test:embedded-database-spring-test'

// Test — Testcontainers (integration)
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:postgresql'
testImplementation 'org.testcontainers:junit-jupiter'
```

### 3. Application Configuration (`application.yaml`)

- DataSource: PostgreSQL 연결 정보
- JPA: `hibernate.ddl-auto=validate`, `show-sql=true` (dev), dialect
- Flyway: `enabled=true`, `locations=classpath:db/migration`
- Batch: `spring.batch.jdbc.initialize-schema=always` (dev), job 자동 실행 비활성화

### 4. Flyway Migration

`src/main/resources/db/migration/V1__create_business_tables.sql`:

| Entity | Table | Description |
|--------|-------|-------------|
| `Order` | `orders` | 주문 원본 데이터 |
| `Payment` | `payments` | 결제 원본 데이터 |
| `DailySettlement` | `daily_settlement` | 정산 집계 결과 (UPSERT 대상) |
| `SettlementError` | `settlement_error` | 검증 실패 건 격리 |
| `SyncOutbox` | `sync_outbox` | 외부 전송 대기열 |
| `SyncDlq` | `sync_dlq` | 전송 실패 DLQ |

### 5. JPA Entities (6개)

`com.settlement.domain.entity` 패키지:

- `Order` — 주문 ID, 가맹점 ID, 주문 금액, 주문 일자, 상태, 생성 시각
- `Payment` — 결제 ID, 주문 ID (FK), 결제 금액, 결제 방식, 결제 일자, 상태, 생성 시각
- `DailySettlement` — 정산 일자, 가맹점 ID, 총 주문 건수, 총 결제 금액, 총 수수료, 정산 금액, 생성/수정 시각 (**UNIQUE(settlement_date, merchant_id)** — UPSERT 멱등성 보장)
- `SettlementError` — 원본 참조 ID, 에러 유형, 에러 메시지, 원본 데이터(JSON), 발생 시각
- `SyncOutbox` — 정산 ID (FK), 상태 (PENDING/SENT/FAILED), 재시도 횟수, 다음 재시도 시각, 생성/수정 시각
- `SyncDlq` — Outbox ID, 정산 ID, 실패 사유, 원본 데이터(JSON), 이관 시각

### 6. Spring Data Repositories (6개)

`com.settlement.domain.repository` 패키지:
- `OrderRepository`
- `PaymentRepository`
- `DailySettlementRepository`
- `SettlementErrorRepository`
- `SyncOutboxRepository`
- `SyncDlqRepository`

### 7. Verification Job

`VerificationJobConfig.java`:
- Step 1 (Tasklet): DB 연결 확인 + Flyway 마이그레이션 상태 출력
- Step 2 (Chunk): 샘플 Entity 1건 INSERT → SELECT → 검증 (Reader → Processor → Writer 파이프라인 검증)
- 전체 파이프라인이 정상 동작하는지 확인하는 스모크 테스트 역할

### 8. Test Infrastructure

- `src/test/resources/application-test.yaml` — Testcontainers TC datasource URL 설정
- `DatabaseCleanup.java` — EntityManager 기반 전체 테이블 TRUNCATE 유틸리티
- `VerificationJobIntegrationTest.java`:
  - Testcontainers PostgreSQL 기반
  - Verification Job 실행 → `COMPLETED` 상태 확인
  - 테이블 생성 여부 검증

---

## Deliverables

| File | Package / Path |
|------|----------------|
| `docker-compose.yml` | project root |
| `build.gradle` | project root (update) |
| `application.yaml` | `src/main/resources/` (update) |
| `V1__create_business_tables.sql` | `src/main/resources/db/migration/` |
| `Order.java` | `com.settlement.domain.entity` |
| `Payment.java` | `com.settlement.domain.entity` |
| `DailySettlement.java` | `com.settlement.domain.entity` |
| `SettlementError.java` | `com.settlement.domain.entity` |
| `SyncOutbox.java` | `com.settlement.domain.entity` |
| `SyncDlq.java` | `com.settlement.domain.entity` |
| `OrderRepository.java` | `com.settlement.domain.repository` |
| `PaymentRepository.java` | `com.settlement.domain.repository` |
| `DailySettlementRepository.java` | `com.settlement.domain.repository` |
| `SettlementErrorRepository.java` | `com.settlement.domain.repository` |
| `SyncOutboxRepository.java` | `com.settlement.domain.repository` |
| `SyncDlqRepository.java` | `com.settlement.domain.repository` |
| `VerificationJobConfig.java` | `com.settlement.config` |
| `application-test.yaml` | `src/test/resources/` |
| `DatabaseCleanup.java` | `com.settlement` (test) |
| `VerificationJobIntegrationTest.java` | `com.settlement` (test) |

---

## Verification Criteria

- [ ] `docker-compose up -d`로 PostgreSQL 16 컨테이너가 정상 기동된다
- [ ] `./gradlew build`가 성공하고, QueryDSL Q클래스가 생성된다
- [ ] 애플리케이션 기동 시 Flyway가 `V1` 마이그레이션을 실행하고, 6개 비즈니스 테이블이 생성된다
- [ ] JPA `validate` 모드에서 Entity ↔ 테이블 매핑 불일치 에러가 없다
- [ ] Verification Job 실행 시 `COMPLETED` 상태로 종료된다
- [ ] `VerificationJobIntegrationTest`가 Testcontainers 환경에서 통과한다
- [ ] Batch 메타데이터 테이블 (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION` 등)이 자동 생성된다
