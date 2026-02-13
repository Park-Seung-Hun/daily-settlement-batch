# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

- **Build**: `./gradlew build`
- **Run**: `./gradlew bootRun`
- **Test (all)**: `./gradlew test`
- **Test (single class)**: `./gradlew test --tests "com.settlement.SomeTestClass"`
- **Test (single method)**: `./gradlew test --tests "com.settlement.SomeTestClass.methodName"`
- **Clean**: `./gradlew clean`
- **Docker**: `docker-compose up -d` / `docker-compose down -v`

## Tech Stack

- Java 21 / Spring Boot 4.0.2
- Gradle 9.3.0 (use `./gradlew`, not system Gradle)
- Spring Batch 6.0 (`spring-boot-starter-batch`)
- Spring Data JPA + QueryDSL 5.x (jakarta)
- Flyway (schema migration)
- PostgreSQL 16 (Docker)
- Embedded PostgreSQL — zonky (unit/slice tests)
- Testcontainers — PostgreSQL (integration tests)
- JUnit 5 (Jupiter) + AssertJ + BDDMockito

## Project Structure

- Entry point: `src/main/java/com/settlement/DailySettlementBatchApplication.java`
- Config: `src/main/resources/application.yaml`
- Base package: `com.settlement`

```
src/main/java/com/settlement/
├── config/           # Job, Step, DataSource, JPA/QueryDSL configuration
├── domain/
│   ├── entity/       # JPA Entity (@Entity)
│   ├── repository/   # Spring Data JPA Repository + QueryDSL custom
│   └── dto/          # DTO (Record)
├── job/
│   ├── settlement/   # DailySettlementJob
│   ├── sync/         # SyncJob, DlqRetryJob
│   └── report/       # ReportExportJob
├── processor/        # ItemProcessor implementations
├── listener/         # Job/Step/Chunk/Skip Listener
├── partitioner/      # Partitioning
├── scheduler/        # @Scheduled scheduling
└── web/              # REST API controllers

src/main/resources/
├── db/migration/     # Flyway migration scripts (V1__, V2__, ...)
└── application.yaml
```

## Conventions

### Naming

- Job config: `*JobConfig.java`
- Processor: `*Processor.java`
- Listener: `*Listener.java`
- Entity: `@Entity` class, singular noun (`Order`, `Payment`, `DailySettlement`)
- Repository: `*Repository.java` (interface), `*RepositoryCustom.java` + `*RepositoryImpl.java` (QueryDSL)
- DTO: `*Dto.java` (Java Record)
- Bean names: Job → camelCase (`dailySettlementJob`), Step → `<prefix><Description>Step`

### Spring Batch 6.0 API Rules

1. `JobBuilderFactory`, `StepBuilderFactory` removed → use `new JobBuilder(name, jobRepository)` directly
2. `@EnableBatchProcessing` back-offs Spring Boot auto-configuration since Batch 5.0+ — verify behavior in Boot 4.x before using. Prefer Boot auto-config; use `@EnableBatchProcessing` only when manual control is needed
3. Chunk step: `.chunk(size).transactionManager(txManager)` pattern
4. `JobRepository` extends `JobExplorer` → no separate `JobExplorer` injection needed
5. `JobOperator` extends `JobLauncher` → prefer `JobOperator`
6. Transaction manager is optional at Step configuration level
7. Immutable domain model: constructor injection
8. Package relocation: Listener → `o.s.batch.core.listener`, Step → `o.s.batch.core.step`
9. Jackson 3.x (do not use Jackson 2.x API)
10. Metadata sequence rename: `BATCH_JOB_SEQ` → `BATCH_JOB_INSTANCE_SEQ`

### Spring Data JPA + QueryDSL Rules

- Reader: use `RepositoryItemReader` or `JpaCursorItemReader` (do not use `JdbcCursorItemReader`)
- Writer: use `RepositoryItemWriter` or JPA repository methods
- UPSERT: JPA `saveAll()` + `@Query` native query (`INSERT ... ON CONFLICT`) or merge logic
- QueryDSL: complex queries in `*RepositoryCustom` + `*RepositoryImpl` using `JPAQueryFactory`
- Q-classes: generated in `build/generated/sources/annotationProcessor`, included in `.gitignore`

### Database Conventions

- PostgreSQL 16, `snake_case` table/column names
- Entity ↔ table mapping: `@Table(name = "...")` explicit
- PK: `@Id @GeneratedValue(strategy = IDENTITY)` + `Long` type
- Timestamps: `@Column(name = "created_at")` + `LocalDateTime`
- Schema management: Flyway (`src/main/resources/db/migration/V{N}__description.sql`)
- Batch meta tables: `spring.batch.jdbc.initialize-schema=always` (dev)

### Testing Conventions

**Method naming & structure:**
- Method name: `should_action_when_condition` (English snake_case)
- `@DisplayName("scenario N: Korean description")` required
- Example:
  ```java
  @Test
  @DisplayName("시나리오 1: 정상 주문-결제 데이터가 입력되면 정산 집계가 생성된다")
  void should_create_settlement_when_valid_order_payment_exists() { ... }
  ```

**Assertion & Mocking:**
- **AssertJ** as default (`assertThat(...).isEqualTo(...)`)
- **BDDMockito** (`given(...).willReturn(...)`, `then(...).should().method()`)
- Do not use JUnit 5 basic assert

**Test layer priority:**
1. **Slice Test first** — `@DataJpaTest`, `@JsonTest`, `@WebMvcTest` (load only required layer)
2. **`@SpringBatchTest`** — for Batch Job/Step tests
3. **`@SpringBootTest`** — only when full integration test is unavoidable

**DB test dual strategy:**
- **Unit/Slice tests** (`*Test.java`, `@DataJpaTest`, etc.):
  - **Embedded PostgreSQL (zonky)** — no Docker required, fast execution
  - `@AutoConfigureEmbeddedDatabase` annotation
  - Real PostgreSQL binary, PG-specific syntax compatible
- **Integration tests** (`*IntegrationTest.java`, `@SpringBatchTest`, `@SpringBootTest`):
  - **PostgreSQL Testcontainers**
  - Schema init: **Flyway** runs migration scripts
  - Cleanup between tests: **TRUNCATE** based (`@Sql` or `@BeforeEach` full table TRUNCATE)
  - `@Transactional` rollback does not work with Batch Job tests — use TRUNCATE
  - Testcontainers config: `src/test/resources/application-test.yaml` with TC datasource URL

**Test file distinction:**
- Unit tests: `*Test.java` (Embedded PostgreSQL)
- Integration tests: `*IntegrationTest.java` (Testcontainers + Flyway + TRUNCATE)

### Pre-Implementation Confirmation Rules

The following items **must be confirmed with the user** before implementation (do not proceed unilaterally):

1. **Spring Batch Job/Step structure** — Job flow, Step composition, chunk size, skip/retry policy
2. **Core business logic** — settlement aggregation rules, validation conditions, UPSERT strategy, state transitions
3. **DB schema changes** — table/column additions/modifications, Flyway migration scripts
4. **External integration design** — API call patterns, Outbox/DLQ state machine, retry policy

Infrastructure setup, boilerplate, and test code may proceed autonomously following conventions.

### Progress Tracking

- 프로젝트 계획: `plans/` 디렉토리 참조
- 각 Phase plan 파일 상단에 `## Progress` 섹션이 ADR 단위 체크리스트로 존재한다
- **작업 완료 시** 해당 ADR 아래 체크박스를 `[x]`로 업데이트한다
- ADR에 직접 매핑되지 않는 통합 작업은 `### Integration` 아래에서 추적한다
- Phase 내 모든 체크박스가 완료되면 Phase 파일 상단에 완료 표시를 추가한다:
  `**Status: COMPLETED** (yyyy-MM-dd)`

### Code Style

- Constructor injection only (no field `@Autowired`)
- Use Record for DTO
- Use `final` aggressively
- Slf4j logging: `private static final Logger log = LoggerFactory.getLogger(ClassName.class)`
- Log levels: INFO (job/step start/end), WARN (skip), ERROR (failure)

### Docker

- `docker-compose.yml` — PostgreSQL 16, port 5432, DB/user/password: `settlement`

### Git Convention

**Branch Strategy:**
- `main` — 안정 브랜치, 직접 커밋 금지
- `feature/<phase>-<description>` — Phase별 작업 브랜치
- 작업 완료 후 `main`으로 `--no-ff` 머지 (머지 커밋 유지)

```
main ← feature/phase1-infra-setup
     ← feature/phase2-settlement-job
     ← feature/phase3-sync-and-report
     ← feature/phase4-production
```

**Merge:**
```bash
git checkout main
git merge --no-ff feature/phase1-infra-setup
```

**Commit Convention (Conventional Commits):**

```
<type>(<scope>): <subject>

<body>
```

- subject: 소문자, 현재형, 마침표 없음, 50자 이내
- body: 빈 줄 후 작성, **무엇을 왜 변경했는지** 설명, 72자 줄바꿈

**Type:**

| Type       | 용도                                  |
|------------|---------------------------------------|
| `feat`     | 새 기능 (Job, Step, Entity, API 등)   |
| `fix`      | 버그 수정                             |
| `refactor` | 동작 변경 없는 구조 개선              |
| `test`     | 테스트 추가/수정                      |
| `docs`     | 문서 변경 (CLAUDE.md, plans/, README) |
| `chore`    | 빌드 설정, 의존성, CI/CD             |
| `schema`   | Flyway 마이그레이션, DB 스키마 변경   |

**Scope (선택):**

| Scope        | 대상                                         |
|--------------|----------------------------------------------|
| `settlement` | DailySettlementJob 관련                      |
| `sync`       | SyncJob, DLQ 관련                            |
| `report`     | ReportExportJob 관련                         |
| `entity`     | JPA Entity, Repository                       |
| `config`     | 설정 (application.yaml, Docker, Batch config) |
| `infra`      | Gradle, Docker, Flyway, 테스트 인프라        |

**Body 작성 규칙:**
- subject만으로 변경 의도가 충분하면 body 생략 가능
- body가 필요한 경우: 설계 결정 이유, 대안 선택 근거, 주의사항
- `schema` 타입은 body에 변경되는 테이블/컬럼 목록 필수 기재
- Breaking change가 있으면 body에 `BREAKING CHANGE:` 접두사로 명시
