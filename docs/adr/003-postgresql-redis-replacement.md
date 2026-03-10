# ADR-003: Redis 기능 PostgreSQL 대체 전략

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-10 |
| 결정자 | MapleExpectation Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #548, #551, #582 |
| 선행 ADR | ADR-001 PostgreSQL 단일 DB 전략 |

---

## 1. 배경 (Context)

### 현재 Redis 사용 현황

MapleExpectation 프로젝트는 Redis를 다음과 같은 목적으로 활용:

| 기능 | 사용 패턴 | Redis 데이터 구조 |
|------|----------|-------------------|
| **분산 락** | Cache Stampede 방지, 동시성 제어 | RLock (Redisson) |
| **캐시 계층** | L2 캐시 (TieredCache) | String, Hash |
| **Pub/Sub** | 캐시 무효화 이벤트 전파 | Redis Pub/Sub |
| **데이터 버퍼** | 일시적 데이터 저장 | RBucket, RSet, RScoredSortedSet |
| **Rate Limiting** | API 요청 제한 | Counter + TTL |
| **세션 저장소** | 사용자 세션 | Redis Session |

### 문제점

| 문제 | 영향 |
|------|------|
| **이중 데이터베이스 운영** | PostgreSQL + Redis 동시 관리 복잡성 |
| **인프라 비용** | 저사양 서버에서 Redis 메모리 부담 |
| **데이터 일관성** | 캐시와 DB 간 일관성 유지 오버헤드 |
| **장애 복구** | Redis 장애 시 캐시만으로 복구 불가 |
| **스케일아웃 제약** | Redis Cluster는 과도한 복잡성 |

### 트래픽 패턴 분석

| 시나리오 | 분산 락 호출/초 | Pub/Sub 메시지/초 | 캐시 조회/초 |
|----------|------------------|-------------------|--------------|
| 일반 | 1-5 | 1-2 | 10-50 |
| 패치데이 | 50-200 | 10-20 | 500-2,000 |
| 버럴 | 500+ | 100+ | 10,000+ |

---

## 2. 결정 (Decision)

**Redis 기능을 PostgreSQL 네이티브 기능으로 대체한다. (단계적 마이그레이션)**

### 핵심 원칙

1. **PostgreSQL 네이티브 기능 활용**
   - Advisory Lock: 분산 락
   - LISTEN/NOTIFY: Pub/Sub
   - UNLOGGED TABLE: 버퍼 데이터
   - 일반 테이블 + 인덱스: 캐시 저장소

2. **성능 유지**
   - 단일 PostgreSQL 연결로 다중 기능 수행
   - 연결 풀 효율화
   - 캐시 적중률 모니터링

3. **단계적 마이그레이션**
   - Phase 1: Advisory Lock (분산 락)
   - Phase 2: LISTEN/NOTIFY (Pub/Sub)
   - Phase 3: 테이블 기반 캐시
   - Phase 4: Rate Limiting 대체

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (Redis 유지)

**장점:**
- 변경 비용 없음
- 검증된 성능

**단점:**
- 이중 DB 운영 복잡성 지속
- 메모리 리소스 낭비
- 장애 복구 복잡성

**평가:** ❌ 기술 부채 지속

### B. 완전 대체 (PostgreSQL 네이티브)

**장점:**
- 단일 DB 운영
- ACID 트랜잭션 보장
- 장애 복구 단순화

**단점:**
- 캐시 성능 저하 가능성
- 구현 복잡도

**평가:** ✅ 장기적 관점에서 최적

### C. 하이브리드 (Redis 캐시만 유지)

**장점:**
- 캐시 성능 유지
- 락/Pub/Sub만 PostgreSQL 이전

**단점:**
- 여전히 2개 DB 운영

**평가:** ⚠️ 과도기적 솔루션

---

## 4. 기술적 구현 (Implementation)

### Redis 기능 → PostgreSQL 매핑

| Redis 기능 | PostgreSQL 대체 | 구현 방식 |
|-----------|-----------------|----------|
| **분산 락** | Advisory Lock | `pg_try_advisory_lock()`, `pg_advisory_unlock()` |
| **Pub/Sub** | LISTEN/NOTIFY | `LISTEN channel`, `NOTIFY channel` |
| **String** | 일반 테이블 | `key_value_store` 테이블 |
| **Hash** | JSONB 컬럼 | `metadata` JSONB 컬럼 |
| **Set** | ARRAY / 배열 | `TEXT[]` 또는 연결 테이블 |
| **Sorted Set** | NUMBER + 인덱스 | `score` 컬럼 + B-Tree 인덱스 |
| **TTL** | DELETE 트리거 | `pg_cron` 또는 배치 정리 |

### 1. Advisory Lock (분산 락)

```sql
-- 락 획득 시도
SELECT pg_try_advisory_lock(
    hashtext('cache:sf:equipment_expectation:ABC123')::bigint
); -- Returns: boolean

-- 락 해제
SELECT pg_advisory_unlock(
    hashtext('cache:sf:equipment_expectation:ABC123')::bigint
); -- Returns: boolean
```

**구현 클래스:**
```kotlin
// module-infra/src/main/kotlin/.../lock/PostgresLockStrategy.kt
@Component
class PostgresLockStrategy(
    private val jdbcTemplate: JdbcTemplate
) : LockStrategy {
    override fun tryLock(key: String, waitTime: Duration, leaseTime: Duration): Boolean {
        val lockKey = key.hashCode().toLong()
        return jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_lock(?)",
            Boolean::class.java,
            lockKey
        ) ?: false
    }

    override fun unlock(key: String) {
        val lockKey = key.hashCode().toLong()
        jdbcTemplate.update("SELECT pg_advisory_unlock(?)", lockKey)
    }
}
```

### 2. LISTEN/NOTIFY (Pub/Sub)

```sql
-- 발행 (NOTIFY)
NOTIFY 'cache_invalidation', 'evict:equipment_expectation:ABC123';

-- 구독 (LISTEN)
LISTEN 'cache_invalidation';
```

**구현 클래스:**
```kotlin
@Component
class PostgresPubSubContainer(
    private val dataSource: DataSource
) {
    private val listeners = ConcurrentHashMap<String, Connection>()

    fun subscribe(channel: String, callback: (String) -> Unit) {
        val conn = dataSource.connection
        conn.createStatement().execute("LISTEN $channel")
        // Async notification listening...
    }

    fun publish(channel: String, message: String) {
        val conn = dataSource.connection
        conn.createStatement().execute("NOTIFY $channel, '$message'")
    }
}
```

### 3. 테이블 기반 캐시

```sql
-- 캐시 저장소 테이블
CREATE TABLE cache_store (
    key VARCHAR(500) PRIMARY KEY,
    value JSONB NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- GIN 인덱스 (JSONB 쿼리 최적화)
CREATE INDEX idx_cache_value_gin ON cache_store USING gin(value);

-- TTL 자동 정리 (pg_cron)
SELECT cron.schedule('cache-cleanup', '*/5 * * * *',
    $$DELETE FROM cache_store WHERE expires_at < NOW()$$
);
```

### 4. 데이터 구조 매핑

| Redis RScoredSortedSet | PostgreSQL 대체 |
|------------------------|-----------------|
| `zAdd(key, member, score)` | `INSERT INTO sorted_set (key, member, score) VALUES (...) ON CONFLICT (key, member) DO UPDATE SET score = EXCLUDED.score` |
| `zRange(key, 0, -1)` | `SELECT member FROM sorted_set WHERE key = ? ORDER BY score ASC` |
| `zScore(key, member)` | `SELECT score FROM sorted_set WHERE key = ? AND member = ?` |

```sql
-- Sorted Set 테이블
CREATE TABLE sorted_set_store (
    key VARCHAR(255) NOT NULL,
    member VARCHAR(500) NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (key, member)
);

CREATE INDEX idx_sorted_set_score ON sorted_set_store(key, score);
```

### 5. Rate Limiting 대체

```sql
-- Rate Limit 카운터 테이블
CREATE TABLE rate_limit_counter (
    identifier VARCHAR(255) PRIMARY KEY,
    count BIGINT NOT NULL DEFAULT 0,
    window_start TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

-- 요청 시도 (UPSERT + 조건부 증가)
INSERT INTO rate_limit_counter (identifier, count, window_start, expires_at)
VALUES ('user:123', 1, NOW(), NOW() + INTERVAL '1 minute')
ON CONFLICT (identifier) DO UPDATE SET
    count = rate_limit_counter.count + 1
WHERE rate_limit_counter.window_start > NOW() - INTERVAL '1 minute';
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **운영 단순화** | 단일 PostgreSQL만 운영 |
| **비용 절감** | Redis 인스턴스 제거 |
| **일관성 보장** | ACID 트랜잭션으로 데이터 무결성 |
| **장애 복구** | DB 백업으로 모든 데이터 복구 |
| **연결 풀 최적화** | 단일 커넥션 풀로 다중 기능 |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **캐시 성능 저하** | Caffeine L1 캐시 강화, 쿼리 최적화 |
| **Advisory Lock 성능** | Connection Pool 튜닝, 락 최적화 |
| **LISTEN/NOTIFY 제한** | 8KB 페이로드 제한, 참조 ID 전파 |
| **구현 복잡도** | Port 인터페이스 추상화 유지 |

---

## 6. 성능 비교

### 분산 락 성능

| 작업 | Redis | PostgreSQL Advisory Lock |
|------|-------|--------------------------|
| 락 획득 | ~1ms | ~2-5ms |
| 락 해제 | ~1ms | ~1-2ms |
| 동시 락 처리 | 10,000+ ops/sec | 5,000+ ops/sec |

### Pub/Sub 성능

| 작업 | Redis Pub/Sub | PostgreSQL LISTEN/NOTIFY |
|------|---------------|-------------------------|
| 발행 지연 | ~1ms | ~2-5ms |
| 전파 지연 | ~5ms | ~10-20ms |
| 페이로드 제한 | 없음 | 8KB |

---

## 7. 마이그레이션 계획

### Phase 1: Advisory Lock 구현 (ADR-005)

- [x] PostgresLockStrategy 설계
- [ ] 단위 테스트 작성
- [ ] RedissonLockStrategy → PostgresLockStrategy 교체
- [ ] 부하 테스트

### Phase 2: LISTEN/NOTIFY 구현 (ADR-006)

- [ ] PostgresPubSubContainer 구현
- [ ] CacheInvalidationEvent Listener 연결
- [ ] Reconnect 로직 구현
- [ ] 8KB 제약 우회 설계

### Phase 3: 데이터 구조 마이그레이션

- [ ] KeyValueStore 테이블 생성
- [ ] SortedSetStore 테이블 생성
- [ ] RedisOperationPort → PostgresOperationPort 구현
- [ ] 데이터 마이그레이션 스크립트

### Phase 4: Rate Limiting 대체

- [ ] RateLimitCounter 테이블 생성
- [ ] RateLimitFilter PostgreSQL 버전 구현
- [ ] 성능 검증

---

## 8. 롤백 전략

### 롤백 트리거

| 조건 | 조치 |
|------|------|
| 락 획득 실패율 > 5% | Redis 복원 |
| Pub/Sub 지연 > 1초 | Redis 복원 |
| 캐시 적중률 저하 > 20% | Redis 복원 |

### 롤백 절차

1. LockStrategy 구현체 교체
2. PubSubContainer 구현체 교체
3. RedisOperationPort 복원
4. 기능 플래그로 트래픽 전환

---

## 9. 모니터링 & 검증

### 성공 지표

| 지표 | 목표 |
|------|------|
| 분산 락 지연 p99 | < 50ms |
| Pub/Sub 전파 지연 p99 | < 500ms |
| 캐시 적중률 | > 80% |
| DB CPU 사용량 | < 70% |

### 모니터링 쿼리

```sql
-- Advisory Lock 현황
SELECT locktype, database, pid, mode, granted
FROM pg_locks
WHERE locktype = 'advisory';

-- LISTEN/NOTIFY 통계
SELECT channel, count(*) as listeners
FROM pg_listening_channels()
GROUP BY channel;
```

---

## 10. 참고 자료

- [PostgreSQL Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- [PostgreSQL LISTEN/NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)
- [ADR-001 PostgreSQL 단일 DB 전략](001-postgresql-single-db-strategy.md)
- [ADR-005 PostgreSQL Advisory Lock](005-postgresql-advisory-lock.md)
- [ADR-006 PostgreSQL LISTEN/NOTIFY](006-postgresql-listen-notify.md)

---

## 11. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-10 | ADR 초안 작성 | MapleExpectation Team |
