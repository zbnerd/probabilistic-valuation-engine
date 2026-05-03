---
paths:
  - "**/src/test/**/*.kt"
  - "**/src/test/**/*.java"
---

# 테스트 컨벤션 (Testing Conventions)

## Test Base Class 계층 (필수 준수)

```
IntegrationTestBase (RANDOM_PORT, @Tag("integration"), @ActiveProfiles("pgtest"), Testcontainers, DatabaseCleaner)
├── ServiceIntegrationTestBase (WebEnvironment.NONE, @Transactional)
├── ServiceTestTemplate (JPA helpers: flushAndClear(), persistAndFlush())
├── UsecaseTestTemplate (Awaitility helpers: awaitUntil(), awaitCompletion())
└── ControllerContractTestTemplate
```

- 모든 통합 테스트는 위 계층 중 하나를 상속
- 독립적인 test configuration 금지

## DatabaseCleaner 규칙

- `@BeforeEach`에 `DatabaseCleaner.clean()` 사용, `@AfterEach` 금지
- **이유**: 테스트 실패 시 `@AfterEach`가 실행되지 않아 다음 테스트에 dirty state 남김
- `@DirtiesContext` 절대 금지 (context reload storm 발생, 10-30초 소요)

## Test Tag Taxonomy

| Tag | 용도 | 기본 실행 |
|-----|------|-----------|
| `integration` | Testcontainers 기반 통합 테스트 | 제외 |
| `sentinel` | sentinel 테스트 | 제외 |
| `quarantine` | 격리된 불안정 테스트 | 제외 |
| `flaky` | flaky 테스트 | 제외 |
| `chaos` | 카오스 엔지니어링 테스트 | 제외 |
| `pgmq` | PGMQ 관련 테스트 | 제외 |

- `./gradlew test` 기본 실행에서 `sentinel`, `quarantine`, `flaky`, `integration`, `pgmq` 제외
- 빠른 실행: `-PfastTest`

## H2 금지

- DB 의존 테스트에 H2 in-memory DB 사용 금지
- 반드시 Testcontainers PostgreSQL 사용
- **이유**: H2/PostgreSQL dialect 차이로 prod에서 실패하는 케이스 반복 (#715, #663)
