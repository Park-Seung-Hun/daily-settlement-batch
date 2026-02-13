# Phase 3-3: ReportExportJob

## Progress

### ADR-011: FlatFileItemWriter CSV
- [ ] `ReportRowDto.java`
- [ ] `ReportRowProcessor.java`
- [ ] `ReportExportJobConfig.java` (FlatFileItemWriter + 헤더/푸터)
- [ ] `ReportExportJobIntegrationTest.java`

---

## Job Structure

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

## Test Plan

### Integration Tests (`*IntegrationTest.java`)

```java
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

---

## Deliverables

| File | Package / Path |
|------|----------------|
| `ReportRowDto.java` | `com.settlement.domain.dto` |
| `ReportRowProcessor.java` | `com.settlement.processor` |
| `ReportExportJobConfig.java` | `com.settlement.job.report` |
| `ReportExportJobIntegrationTest.java` | test: `com.settlement.job.report` |

---

## Verification Criteria

- [ ] ReportExportJob이 CSV 파일을 정상 생성한다 (헤더, 데이터, 푸터 요약)
- [ ] 모든 테스트가 통과한다 (통합: Testcontainers)
