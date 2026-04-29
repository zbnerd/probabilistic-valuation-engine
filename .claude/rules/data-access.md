---
paths:
  - "module-infra/**/*.kt"
  - "module-infra/**/*.java"
  - "module-app/**/*.kt"
  - "module-app/**/*.java"
---

# 데이터 접근 패턴 (Data Access Patterns)

## N+1 쿼리 절대 금지

- loop 안에서 repository/query method 호출 금지
- `findAllByIdIn()` 등 batch query 사용
- 새 service method 추가 시 batch variant를 먼저 설계, single-item은 convenience wrapper로 추가
- 고처리량 경로(>100 items/sec)는 micro-batch 설계: time-window accumulation 또는 `AccumulationBuffer` 사용

## Transaction Scope 명시

- 모든 `@Transactional`에 `readOnly` 명시 필수
- query method는 `readOnly = true` (replica routing 가능)
- write + advisory lock 조합은 반드시 `pg_try_advisory_xact_lock` (transaction scope)
- 기본 transaction propagation에 의존하지 않음

## JSONB 컬럼 매핑

- PostgreSQL JSONB 컬럼에 매핑되는 Hibernate entity field에 `@JdbcTypeCode(SqlTypes.JSON)` 필수
- JSONB 역직렬화에 사용되는 entity class에 `@JsonIgnoreProperties(ignoreUnknown = true)` 필수
- Kotlin data class + Jackson 사용 시 `KotlinModule` 등록 확인

## Bulk 연산

- 개별 INSERT/UPSERT 대신 bulk JDBC operations (`ON CONFLICT`) 사용
- batch size는 YAML config로 외부화
