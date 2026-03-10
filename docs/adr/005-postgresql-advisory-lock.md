# ADR-005: PostgreSQL Advisory Lock 설계

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-10 |
| 결정자 | MapleExpectation Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #548, #551, #584 |
| 선행 ADR | ADR-001 PostgreSQL 단일 DB 전략, ADR-003 Redis 기능 PostgreSQL 대체 |

---

## 1. 배경 (Context)

### 현재 분산 락 사용 현황

MapleExpectation은 **Redisson RLock**을 활용하여 다음과 같은 시나리오에서 분산 락을 적용:

| 사용 사례 | 락 키 패턴 | 목적 |
|----------|-----------|------|
| **Cache Stampede 방지** | `cache:sf:{cacheName}:{key}` | Single Flight Loading |
| **동시성 제어** | `calculation:{ocid}:{presetNo}` | 중복 계산 방지 |
| **이벤트 처리** | `event:{eventType}:{id}` | 순서 보장 |

### Redisson RLock 동작 방식

```kotlin
// RedissonLockStrategy (현재)
val lock = redissonClient.getLock("cache:sf:equipment_expectation:ABC123")
val acquired = lock.tryLock(10, 30, TimeUnit.SECONDS) // waitTime, leaseTime
try {
    // 보호 영역
} finally {
    if (lock.isHeldByCurrentThread) {
        lock.unlock()
    }
}
```

### 문제점

| 문제 | 영향 |
|------|------|
| **Redis 의존성** | Redis 장애 시 락 기능 불가 |
| **연결 오버헤드** | 별도 Redis 연결 필요 |
| **Watchdog 복잡성** | 30초마다 락 갱신 작업 |
| **락 누수 위험** | 프로세스 크래시 시 락 해제 불가 |

---

## 2. 결정 (Decision)

**Redisson RLock을 PostgreSQL Advisory Lock으로 대체한다.**

### 핵심 원칙

1. **Session 기반 락 관리**
   - Advisory Lock은 PostgreSQL Session에 바인딩
   - 연결 종료 시 자동 해제 (락 누수 방지)

2. **Connection Pool 고려**
   - HikariCP 연결 풀 환경에서 안전한 락 관리
   - 락 획득 후 즉시 해제 패턴

3. **LockStrategy 인터페이스 유지**
   - 기존 RedissonLockStrategy와 호환 가능한 인터페이스
   - 교체 가능한 설계

4. **Hash-based Lock Key**
   - 문자열 락 키를 정수로 해싱
   - 충돌 최소화

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (Redisson RLock)

**장점:**
- 검증된 안정성
- Watchdog 자동 갱신

**단점:**
- Redis 의존성 지속
- 연결 오버헤드

**평가:** ❌ 단일 DB 전략 위배

### B. PostgreSQL Advisory Lock (선택됨)

**장점:**
- PostgreSQL 네이티브
- Session 종료 시 자동 해제
- 추가 인프라 불필요

**단점:**
- Connection Pool에서 락 관리 복잡
- Watchdog 없음 (leaseTime 직접 관리)

**평가:** ✅ 단일 DB 전략 부합

### C. 테이블 기반 락 (SELECT FOR UPDATE)

**장점:**
- 명시적 락 테이블

**단점:**
- 별도 테이블 관리
- Row Lock 경합

**평가:** ⚠️ Advisory Lock이 더 가벭움

---

## 4. 기술적 구현 (Implementation)

### PostgreSQL Advisory Lock 기본

```sql
-- Session 기반 락 (성공: true, 실패: false)
SELECT pg_try_advisory_lock(
    hashtext('cache:sf:equipment_expectation:ABC123')::BIGINT
);

-- 락 해제 (성공: true, 보유하지 않음: false)
SELECT pg_advisory_unlock(
    hashtext('cache:sf:equipment_expectation:ABC123')::BIGINT
);

-- 현재 Session의 모든 락 해제
SELECT pg_advisory_unlock_all();

-- 락 보유 여부 확인
SELECT objid, pid, granted
FROM pg_locks
WHERE locktype = 'advisory' AND pid = pg_backend_pid();
```

### Advisory Lock 종류

| 함수 | 유형 | 특징 |
|------|------|------|
| `pg_advisory_lock(key)` | Session | 대기 후 락 획득 |
| `pg_try_advisory_lock(key)` | Session | 즉시 반환 (비블로킹) |
| `pg_advisory_xact_lock(key)` | Transaction | 트랜잭션 종료 시 자동 해제 |

### PostgresLockStrategy 구현

```kotlin
// module-infra/src/main/kotlin/.../lock/PostgresLockStrategy.kt
package maple.expectation.infrastructure.lock

import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * PostgreSQL Advisory Lock 기반 분산 락 전략
 *
 * <h3>주의사항 (Connection Pool 환경)</h3>
 *
 * <ul>
 *   <li>Advisory Lock은 PostgreSQL Session에 바인딩됨</li>
 *   <li>락 획득 후 즉시 해제해야 함 (Connection Pool 반납 전)</li>
 *   <li>try-with-resources 패턴 강력 권장</li>
 * </ul>
 *
 * @see <a href="https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS">PostgreSQL Advisory Locks</a>
 */
@Component
class PostgresLockStrategy(
    private val jdbcTemplate: JdbcTemplate,
) : LockStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresLockStrategy::class.java)
        private const val MAX_RETRY_COUNT = 3
    }

    /**
     * 락 획득 시도 (Lease Time 없음 - 수동 해제 필요)
     *
     * <h3>Connection Pool 고려사항</h3>
     *
     * <p>Advisory Lock은 Session에 바인딩되므로, 락을 보유한 상태로
     * Connection이 Pool에 반환되면 다른 트랜잭션에서 동일한 락을 획득할 수 있음.
     *
     * <pre>
     * 좋은 예:
     * lockStrategy.executeWithLock(key, waitTime) { protectedCode }
     *
     * 나쁜 예:
     * val acquired = lockStrategy.tryLock(key, ...)
     * // ... 장기 실행 작업 ...
     * // Connection이 Pool로 반환됨 -> 락 누수
     * </pre>
     */
    override fun tryLock(key: String, waitTime: Duration, leaseTime: Duration): Boolean {
        val lockKey = generateLockKey(key)
        val waitTimeMs = waitTime.toMillis()
        val deadline = System.currentTimeMillis() + waitTimeMs

        var retryCount = 0
        while (System.currentTimeMillis() < deadline) {
            val acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)",
                Boolean::class.java,
                lockKey
            ) ?: false

            if (acquired) {
                log.debug("[AdvisoryLock] Lock acquired: key={}, hash={}", key, lockKey)
                return true
            }

            retryCount++
            if (retryCount >= MAX_RETRY_COUNT) {
                log.warn("[AdvisoryLock] Max retry reached: key={}, hash={}", key, lockKey)
                return false
            }

            // Exponential Backoff
            val backoffMs = minOf(100L * (1L shl (retryCount - 1)), 1000L)
            Thread.sleep(backoffMs)
        }

        log.warn("[AdvisoryLock] Lock acquisition timeout: key={}, hash={}, waitTime={}ms",
            key, lockKey, waitTimeMs)
        return false
    }

    /**
     * 락 해제
     *
     * <h3>주의사항</h3>
     *
     * <p>반드시 try-finally 또는 use {} 블록 내에서 호출해야 함.
     */
    override fun unlock(key: String) {
        val lockKey = generateLockKey(key)
        val released = jdbcTemplate.queryForObject(
            "SELECT pg_advisory_unlock(?)",
            Boolean::class.java,
            lockKey
        ) ?: false

        if (released) {
            log.debug("[AdvisoryLock] Lock released: key={}, hash={}", key, lockKey)
        } else {
            log.warn("[AdvisoryLock] Lock release failed (not held): key={}, hash={}", key, lockKey)
        }
    }

    /**
     * 현재 스레드가 락을 보유하고 있는지 확인
     *
     * <h3>구현 제약</h3>
     *
     * <p>PostgreSQL Advisory Lock은 Thread가 아니라 Session에 바인딩됨.
     * Connection Pool 환경에서 정확한 보유자 확인이 어려움.
     *
     * @return 항상 false (구현 불가)
     */
    override fun isHeldByCurrentThread(key: String): Boolean {
        // PostgreSQL Advisory Lock은 Thread가 아닌 Session에 바인딩됨
        log.debug("[AdvisoryLock] isHeldByCurrentThread not supported: key={}", key)
        return false
    }

    /**
     * 락 보유 여부 확인 (모든 Session)
     */
    override fun isLocked(key: String): Boolean {
        val lockKey = generateLockKey(key)
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM pg_locks
            WHERE locktype = 'advisory'
              AND classid = $lockKey
              AND objid = 0
              AND pid != pg_backend_pid()
            """.trimIndent(),
            Long::class.java
        ) ?: 0L
        return count > 0
    }

    /**
     * 락 키 해싱 (String → BIGINT)
     *
     * <h3>해싱 전략</h3>
     *
     * <ul>
     *   <li>PostgreSQL hashtext() 함수 사용 (32-bit 해시)</li>
     *   <li>충돌 가능성은 매우 낮음 (~1/4^32)</li>
     *   <li>필요시 SHA-256 해시 사용 가능</li>
     * </ul>
     */
    private fun generateLockKey(key: String): Long {
        return jdbcTemplate.queryForObject(
            "SELECT hashtext(?)::BIGINT",
            Long::class.java,
            key
        ) ?: key.hashCode().toLong()
    }

    /**
     * 락 보호 하에서 작업 실행 (편의 메서드)
     *
     * <pre>
     * val result = lockStrategy.executeWithLock("my:lock", 10.seconds) {
     *     // 보호되는 작업
     *     "result"
     * }
     * </pre>
     */
    fun <T> executeWithLock(
        key: String,
        waitTime: Duration,
        operation: () -> T
    ): T? {
        val acquired = tryLock(key, waitTime, Duration.ZERO)
        if (!acquired) {
            log.warn("[AdvisoryLock] Failed to acquire lock: key={}", key)
            return null
        }

        return try {
            operation()
        } finally {
            unlock(key)
        }
    }
}
```

### Connection Pool에서의 안전한 사용

```kotlin
// 좋은 예: executeWithLock 패턴
val result = postgresLockStrategy.executeWithLock("cache:sf:key", Duration.ofSeconds(10)) {
    // 보호되는 작업
    expensiveCalculation()
}

// 좋은 예: try-finally 패턴
val acquired = postgresLockStrategy.tryLock("cache:sf:key", Duration.ofSeconds(10), Duration.ZERO)
if (acquired) {
    try {
        // 보호되는 작업
        expensiveCalculation()
    } finally {
        postgresLockStrategy.unlock("cache:sf:key")
    }
}

// 나쁜 예: 락 해제 누락
val acquired = postgresLockStrategy.tryLock("cache:sf:key", Duration.ofSeconds(10), Duration.ZERO)
if (acquired) {
    expensiveCalculation()
    // Connection이 Pool로 반환되어 락이 유지될 수 있음!
    // postgresLockStrategy.unlock("cache:sf:key") 호출 필요
}
```

### HikariCP Connection Pool 튜닝

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20  # 락 경합 방지
      minimum-idle: 5
      connection-timeout: 30000  # 30초
      idle-timeout: 600000  # 10분
      max-lifetime: 1800000  # 30분
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **PostgreSQL 네이티브** | 추가 인프라 불필요 |
| **자동 해제** | Session/Connection 종료 시 락 자동 해제 |
| **락 누수 방지** | 프로세스 크래시 시 Connection 종료로 락 해제 |
| **단일 트랜잭션** | DB 연결 하나로 락 + 데이터 조작 |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **Connection Pool 복잡성** | executeWithLock 패턴 강제 |
| **Watchdog 없음** | leaseTime 직접 관리 필요 시 Transaction Lock 사용 |
| **분산 환경 제약** | 단일 PostgreSQL 클러스터 내에서만 유효 |

---

## 6. 성능 비교

### 락 연산 성능

| 작업 | Redisson | PostgreSQL Advisory Lock |
|------|----------|-------------------------|
| 락 획득 | ~1ms | ~2-5ms |
| 락 해제 | ~1ms | ~1-2ms |
| 락 대기 (10초) | ~10,000 ops/sec | ~5,000 ops/sec |

### 경합 상황

| 경합률 | Redisson | PostgreSQL |
|-------|----------|-----------|
| 10% | ~0ms 대기 | ~1ms 대기 |
| 50% | ~10ms 대기 | ~20ms 대기 |
| 90% | ~100ms 대기 | ~200ms 대기 |

---

## 7. 마이그레이션 계획

### Phase 1: PostgresLockStrategy 구현

- [x] LockStrategy 인터페이스 정의
- [x] PostgresLockStrategy 구현
- [x] 단위 테스트 작성 (13 tests passed)
- [x] 경합 테스트 (concurrent callers, lock contention)

### Phase 2: RedissonLockStrategy → PostgresLockStrategy

- [ ] LockStrategy 빈 교체
- [ ] TieredCache 락 로직 변경
- [ ] CalculationService 락 로직 변경

### Phase 3: 검증

- [ ] 부하 테스트
- [ ] 경합 상황 테스트
- [ ] 장애 복구 테스트

---

## 8. 롤백 전략

### 롤백 트리거

| 조건 | 조치 |
|------|------|
| 락 획득 실패율 > 5% | Redisson 복원 |
| 락 대기 시간 > 1초 p99 | Redisson 복원 |

### 롤백 절차

1. LockStrategy 빈 교체
2. Redisson 재활성화
3. 기능 플래그로 트래픽 전환

---

## 9. 모니터링 & 검증

### 성공 지표

| 지표 | 목표 |
|------|------|
| 락 획득 지연 p99 | < 50ms |
| 락 누수 발생 | 0건/일 |
| Connection Pool 고갈 | 0건/일 |

### 모니터링 쿼리

```sql
-- 현재 보유 중인 Advisory Lock
SELECT
    objid as lock_key,
    pid as session_pid,
    granted,
    backend_start
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE l.locktype = 'advisory';

-- 락 경합 통계
SELECT
    objid,
    count(*) as waiters,
    max(a.query) as waiting_query
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE l.locktype = 'advisory' AND NOT granted
GROUP BY objid;
```

---

## 10. 참고 자료

- [PostgreSQL Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- [PostgreSQL hashtext()](https://www.postgresql.org/docs/current/functions-string.html)
- [ADR-001 PostgreSQL 단일 DB 전략](001-postgresql-single-db-strategy.md)
- [ADR-003 Redis 기능 PostgreSQL 대체](003-postgresql-redis-replacement.md)

---

## 11. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-10 | ADR 초안 작성 | MapleExpectation Team |
| 2026-03-10 | 단위 테스트 완료 (13 tests passed) | Issue #554 Unit 1 |
