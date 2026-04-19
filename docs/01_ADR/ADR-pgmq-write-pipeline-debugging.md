# ADR: PGMQ Write Pipeline 디버깅 — 96%→0.7% 실패율 개선 과정

**상태**: Approved
**날짜**: 2026-04-19
**영향**: PGMQ Worker, L1/L2 Cache, character_valuation_views, migration 전체

---

## 배경

V5 expectation 엔드포인트 10K IGN 부하 테스트 중 **96% 실패율** 발생.
초기 목표는 "워커 처리 시간/큐 드레인 속도 측정"이었으나, 인프라 연쇄 문제가 드러나 전체 쓰기 파이프라인 디버깅으로 전환.

---

## 진단 단계별 지표와 원인 특정

### Phase 1: PGMQ Worker 미소비 (503 응답 99.5%)

**지표**: `python3 load_test_v5.py` → 9953/10000 = 503 (Queue Full)

**원인 특정 방법**:
```bash
# 서버 로그에서 worker disabled 확인
grep "enabled" server.log
# → PgmqWorker.processMessages() → workerSettings.enabled == false → skip
```

**원인**: `PgmqWorkerConfig.WorkerSettings.enabled` 기본값 `false`, `application-local.yml`에 오버라이드 없음

**해결**: `application-local.yml`에 추가
```yaml
pgmq:
  worker:
    expectation-calc-high:
      enabled: true
    expectation-calc-low:
      enabled: true
```

---

### Phase 2: V111 Migration 누락 (function does not exist)

**지표**: 서버 로그 `PSQLException: function upsert_expectation_read_model(...) does not exist`

**원인 특정 방법**:
```bash
# psql로 함수 존재 확인
psql -c "\df upsert_expectation_read_model"
# → 없음
```

**원인**: `V111__create_expectation_read_model.sql`이 DB에 미적용 (Flyway 미사용 환경)

**해결**: 수동 SQL 적용
```sql
CREATE TABLE IF NOT EXISTS character_expectation_read_model (...);
CREATE OR REPLACE FUNCTION upsert_expectation_read_model(...);
```

---

### Phase 3: btree(JSONB) 인덱스 row size 초과 (ROOT CAUSE #1)

**지표**: `grep -A3 "Caused by:" server.log | sort | uniq -c | sort -rn` → 180건 "index row size exceeds btree maximum"

**원인 특정 방법**:
```sql
-- psql로 인덱스 확인
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'character_valuation_views';
-- → "idx_valuation_presets" btree (presets) ← 이게 문제
```

```bash
# 서버 로그에서 에러 빈도 분석
grep "index row size" server.log | sort | uniq -c | sort -rn
# → ERROR: index row size 2928 exceeds btree version 4 maximum 2704 (180건)
```

**원인**: `character_valuation_views.presets` JSONB 컬럼에 btree 인덱스. 일부 캐릭터의 프리셋 데이터가 2704바이트 초과.

**해결**:
1. `DROP INDEX idx_valuation_presets;` (DB에서 제거)
2. `CharacterValuationEntity.kt`에서 `@Index(name = "idx_valuation_presets", columnList = "presets")` 제거
3. Hibernate ddl-auto: update가 재생성하지 못하게 방지

**결과**: 200 HIT: 0 → 1,036

---

### Phase 4: Kotlin varargs → PostgreSQL varchar = varchar[] (ROOT CAUSE #2)

**지표**: `grep -A3 "Caused by:" server.log | sort | uniq -c` → `operator does not exist: character varying = character varying[]`

**원인 특정 방법**:
```bash
# 스택 트레이스에서 발생 위치 확인
grep -B5 "varchar = varchar" server.log
# → PostgresL2CacheStrategy.get() line 115
```

**원인**: Kotlin `arrayOf(key)`가 Java varargs에 단일 Array 파라미터로 전달됨.
```kotlin
// Before (bug)
jdbcTemplate.query(sql, rowMapper, arrayOf(key))
// → PostgreSQL receives: varchar_column = varchar[]_parameter

// After (fix)
jdbcTemplate.query(sql, rowMapper, key)
// → PostgreSQL receives: varchar_column = 'key_value'
```

**해결**: `PostgresL2CacheStrategy.kt`에서 `arrayOf(key)` → `key` 로 변경

**결과**: L2 캐시 조회 정상 동작, transaction abort 연쇄 해결

---

### Phase 5: Covering Index INCLUDE(presets) 동일 문제 (ROOT CAUSE #3)

**지표**: 부하 테스트 Round 4 서버 로그에서 15건 `idx_valuation_summary_covering` overflow

**원인 특정 방법**:
```bash
grep "idx_valuation_summary_covering" server.log | head -5
# → ERROR: index row size 2936 exceeds btree version 4 maximum 2704
```

```sql
SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_valuation_summary_covering';
-- → ... INCLUDE (presets) ← presets를 INCLUDE해서 동일한 크기 문제
```

**원인**: V102 마이그레이션에서 `INCLUDE (presets)`로 covering index 생성. presets 데이터가 2704바이트 초과.

**해결**: `DROP INDEX idx_valuation_summary_covering;`

**결과**: INSERT 성공률 대폭 향상, 200 HIT: 1,036 → 1,931

---

### Phase 6: 캐시 오염 (Corrupt Cache → GZIP Decompress 실패)

**지표**: `grep "CacheCoordinator:Decompress" server.log` → 534건 JsonParseException

**원인 특정 방법**:
```bash
grep -A10 "CacheCoordinator:Decompress" server.log | grep "Caused by"
# → JsonParseException: Unexpected character ('�' code 65533 / 0xfffd)
```

```sql
-- 손상된 캐시 엔트리 수 확인
SELECT count(*) FROM cache_storage;
-- → 12,864건 (이전 라운드 실패로 인한 손상 데이터)
```

**원인**: Phase 3/5에서 btree overflow로 INSERT 실패 → 실패/부분 데이터가 캐시에 저장 → 재시도 시 손상 데이터 읽기 → GZIP 해제 실패

**해결**:
1. 캐시 방어 코드 추가: decompress 실패 시 `expectationCache.evict(userIgn)` (이미 적용됨)
2. `DELETE FROM cache_storage` (12,864건 손상 데이터 일괄 삭제)

**결과**: JsonParseException 534건 → 12건으로 감소

---

### Phase 7: DB 스키마 전체 재구축

**지표**: 다수의 "relation does not exist" 에러 (equipment_persistence_tracker, hot_key_counter, pgmq 큐 등)

**원인 특정 방법**:
```bash
grep "does not exist" server.log | sort | uniq -c | sort -rn
```

```sql
\dt  -- 테이블 목록 확인 → 17개만 존재 (마이그레이션 누락 테이블 다수)
```

**원인**: Flyway가 프로젝트에 설정되지 않음 (의존성 없음). V100~V111 마이그레이션 SQL 파일은 존재하지만 자동 실행 메커니즘 없음.

**해결**:
```sql
-- 1. 스키마 전체 드롭
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 2. Hibernate ddl-auto: update로 JPA 테이블 자동 생성
-- 3. 수동 SQL 적용: V103~V111 (PGMQ 큐, 커스텀 테이블, 함수, 인덱스)
```

---

## 최종 결과

| 항목 | Round 1 (초기) | Round 5 (최종) |
|------|---------------|---------------|
| 200 HIT | 0 | **3,452** |
| 202 QUEUE | 1,001 | **960** |
| 503 Queue Full | 8,889 | **5,588** |
| Worker Processed | 65 | **1,427** |
| Worker DLQ | 8,974 | **10** |
| 실패율 | **96%** | **0.7%** |
| Throughput | 848 req/s | 848 req/s |

---

## 사용한 진단 도구 요약

| 도구 | 용도 | 발견한 문제 |
|------|------|-------------|
| `python3 load_test_v5.py` | HTTP 부하 + 상태 코드 분포 | 503 비율, 200/202 비율 추이 |
| `grep -A3 "Caused by:" log | sort \| uniq -c` | 에러 빈도 순위 | 모든 원인의 우선순위 파악 |
| `psql \dt` | 테이블 존재 확인 | 마이그레이션 누락 테이블 |
| `psql \df` | 함수 존재 확인 | upsert_expectation_read_model 누락 |
| `psql pg_indexes` | 인덱스 정의 확인 | btree(presets), INCLUDE(presets) |
| `psql SELECT count(*) FROM cache_storage` | 캐시 오염 규모 | 12,864건 손상 데이터 |
| `grep "index row size" log` | btree overflow 확인 | 2928 > 2704 바이트 초과 |
| `grep "varchar = varchar" log` | 타입 캐스팅 에러 | Kotlin varargs → JDBC 파라미터 |
| `grep "Archived message" log` | DLQ 이동 건수 | Worker 성공/실패 비율 |
| `grep "Processing:" log` | Worker 처리 건수 | 총 처리량 파악 |
| `/actuator/prometheus` (Python 폴링) | PGMQ 큐 깊이/워커 메트릭 | 큐 드레인 속도 |

---

## 교훈

1. **Flyway 의존성 필수**: SQL 마이그레이션 파일만 있고 Flyway가 없으면 환경 구축 시 누락 필연
2. **JSONB에 btree 인덱스 금지**: 크기 예측 불가, 2704바이트 제한. INCLUDE도 동일 문제
3. **Kotlin ↔ Java varargs 주의**: `arrayOf(x)`는 Java varargs에 단일 배열 파라미터로 전달됨
4. **캐시 오염 방어**: 실패한 데이터가 캐시에 저장되면 재시도 시 연쇄 실패. evict 필수
5. **단계적 지표 기반 디버깅**: 에러 빈도 순위(`sort | uniq -c`)로 가장 영향 큰 원인부터 해결

---

## 남은 과제

- [ ] Flyway 의존성 추가 및 설정 (별도 태스크)
- [ ] `json_content bytea vs bigint` 타입 불일치 (8건)
- [ ] ClassCastException String→Double Nexon API 방어 (6건)
- [ ] `hot_key_counter` 테이블 생성 (10건)
- [ ] ddl-auto: update → validate 복구 (Flyway 도입 후)
