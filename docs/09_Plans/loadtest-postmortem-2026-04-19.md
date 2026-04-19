# Load Test Postmortem: V5 Expectation Endpoint (2026-04-19)

## Summary

10K IGN 부하 테스트 중 **96% 실패율** 발생. 단일 문제가 아닌 **연쇄 붕괴 구조**였음.

---

## Timeline

| 시간 | 이벤트 |
|------|--------|
| T+0 | 계측 코드 구현 완료 (3개 모듈: Worker Timing, Queue Metrics, HTTP API Metrics) |
| T+1 | 서버 기동 → Kotlin init order NPE (`queueName`이 `by lazy` 없이 접근) |
| T+2 | 수정: `val metrics by lazy { queueMetrics.forQueue(queueName) }` |
| T+3 | 서버 재기동, 1차 load test → 9953/10000 = 503 (Queue Full) |
| T+4 | 원인: PGMQ Worker `enabled` 기본값이 `false`, local 설정에 `pgmq.worker` 없음 |
| T+5 | 수정: `application-local.yml`에 `pgmq.worker.expectation-calc-high.enabled: true` 추가 |
| T+6 | 서버 재기동, 2차 load test → 720 failures, 0 success |
| T+7 | 원인: `upsert_expectation_read_model` 함수 없음 (V111 마이그레이션 미적용) |
| T+8 | 수정: V111 SQL 수동 적용 |
| T+9 | 3차 load test → 45 success, 994 DLQ (4% 성공률) |
| T+10 | 원인 분석: L2 캐시 GZIP 해제 실패 + transaction rollback |
| T+11 | 원인의 원인: `idx_valuation_presets` btree(JSONB) row size 2952 > 2704 제한 |
| T+12 | cache_storage 만료 데이터 600,484건 삭제 |
| T+13 | 4차 load test → 20 success, ~999 DLQ (여전히 낮은 성공률) |
| T+14 | 진짜 원인 확정: btree 인덱스 + 캐시 오염 연쇄 붕괴 |

---

## Root Cause Chain

```
[idx_valuation_presets btree(JSONB) row size 초과 (2952 > 2704)]
        ↓
[character_valuation_views INSERT 실패]
        ↓
[ExpectationCacheCoordinator.executeCalculatorWithAdmission() 실패]
        ↓
[1차: EquipmentDataProcessingException "Calculation failed with admission control"]
        ↓
[캐시에 실패/부분 데이터 저장됨]
        ↓
[2차 재시도: GZIP 압축 해제 실패 (손상된 캐시 데이터 읽기)]
        ↓
[max retries 초과 → DLQ]
        ↓
[시스템 전체 96% 실패율]
```

---

## Error Catalog

### Error 1: PGMQ Worker not consuming

```
PgmqWorker.processMessages() → workerSettings.enabled == false → skip
```

**원인**: `PgmqWorkerConfig.WorkerSettings.enabled` 기본값 `false`, local 설정에 오버라이드 없음

**수정**: `application-local.yml`에 추가

```yaml
pgmq:
  worker:
    expectation-calc-high:
      enabled: true
    expectation-calc-low:
      enabled: true
```

---

### Error 2: V111 Migration missing

```
PSQLException: function upsert_expectation_read_model(character varying, bytea, unknown) does not exist
```

**원인**: `V111__create_expectation_read_model.sql`이 DB에 적용되지 않음 (Flyway 미사용 환경)

**수정**: 수동 SQL 적용

```sql
-- V111__create_expectation_read_model.sql
CREATE TABLE IF NOT EXISTS character_expectation_read_model (...);
CREATE OR REPLACE FUNCTION upsert_expectation_read_model(...);
```

---

### Error 3: PostgreSQL btree index row size exceeded (ROOT CAUSE)

```
JpaSystemException: could not execute statement
ERROR: index row size 2952 exceeds btree version 4 maximum 2704
for index "idx_valuation_presets"
Detail: Values larger than 1/3 of a buffer page cannot be indexed.
```

**원인**: `character_valuation_views` 테이블의 `presets` JSONB 컬럼에 btree 인덱스 생성. 일부 캐릭터의 프리셋 데이터가 2704바이트 초과.

**현재 상태**: 인덱스 존재 확인됨

```sql
-- 확인
\d+ character_valuation_views
-- "idx_valuation_presets" btree (presets)  ← 이게 문제
```

**해결 방안**:

```sql
-- 방법 A: 인덱스 제거 (presets는 검색 조건이 아님)
DROP INDEX idx_valuation_presets;

-- 방법 B: MD5 hash 인덱스 (필요 시)
CREATE INDEX idx_valuation_presets_hash ON character_valuation_views (md5(presets::text));
```

---

### Error 4: Cache pollution (설계 버그)

```
1차: EquipmentDataProcessingException: Calculation failed with admission control
2차: EquipmentDataProcessingException: GZIP 압축 해제 실패 [CacheCoordinator:Decompress]
```

**원인**: 계산 실패 후에도 캐시에 부분/잘못된 데이터가 저장됨. 재시도 시 이 손상된 캐시를 읽어서 GZIP 해제 실패.

**해결 방안**:

```java
// 1. decompress 실패 시 캐시에서 제거
try {
    decompress(cachedValue)
} catch (Exception e) {
    cache.evict(key)  // 손상된 캐시 제거
}

// 2. 계산 성공 시에만 캐시에 저장
if (result.isSuccess() && isValid(result)) {
    cache.put(key, compressedResult)
}
```

---

### Error 5: Kotlin init order NPE

```
NullPointerException at PgmqWorker.<init>
→ queueMetrics.forQueue(queueName) where queueName == null
```

**원인**: `queueName`이 abstract val인데, 생성자 시점에서 하위 클래스의 override 값이 아직 초기화 안 됨.

**수정**: `val metrics = queueMetrics.forQueue(queueName)` → `val metrics by lazy { queueMetrics.forQueue(queueName) }`

---

### Error 6: Cache MISS logged as ERROR

```
EmptyResultDataAccessException: Incorrect result size: expected 1, actual 0
```

**원인**: `PostgresL2CacheStrategy.get()`에서 `jdbcTemplate.queryForObject()`가 캐시 미스 시 `EmptyResultDataAccessException` throw. 이게 `executor.executeOrDefault()`에서 ERROR 레벨로 로깅됨.

**영향**: 기능적 문제 없음. 로그가 오염될 뿐. DEBUG로 변경 필요.

---

## Load Test Metrics

| 항목 | 값 |
|------|-----|
| Total requests | 10,000 |
| Concurrency | 50 |
| Throughput | 1,148 req/s |
| Cache HIT (200) | 92 |
| Queued (202) | 1,019 |
| Queue Full (503) | 8,889 |
| Worker Success | 65 (누적) |
| Worker Failure | 8,974 (누적) |
| Worker Retry | 5,988 (누적) |
| Worker DLQ | 2,987 (누적) |
| Queue drain time | ~45s (999→0) |

---

## Action Items

- [x] `DROP INDEX idx_valuation_presets` (btree(JSONB) 제거)
- [x] Cache defense: decompress 실패 시 `cache.evict(key)`
- [x] `EmptyResultDataAccessException` → `queryForObject`를 `query`로 변경
- [x] V108 마이그레이션 수동 적용 (`dlq_replay_meta` 테이블)
- [x] V109 마이그레이션 수동 적용 (`rate_limit_counter` 테이블)
- [x] V111 마이그레이션 수동 적용 (`upsert_expectation_read_model` 함수)
- [x] cache_storage 만료 데이터 600,484건 삭제
- [ ] `varchar = varchar[]` 연산자 에러 (Hibernate IN 절 타입 캐스팅)
- [ ] ClassCastException (String→Double) Nexon API 스키마 방어
- [ ] Flyway 또는 마이그레이션 자동화 (수동 적용 방지)
