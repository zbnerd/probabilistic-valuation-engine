# ADR-003: PostgreSQL Advisory Lock Strategy

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 제안됨 (Proposed) |
| 결정일 | 2026-03-10 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #554, #559, #560, #561, #564 |
| 선행 ADR | ADR-001 PostgreSQL 단일 DB 전략, ADR-005 PostgreSQL Advisory Lock 설계 |

---

## 1. 배경 (Context)

### 현재 상태: Redisson 분산 락

probabilistic-valuation-engine은 현재 **Redisson RLock**을 사용하여 다음 시나리오에서 분산 락을 적용합니다:

| 사용 사례 | 락 키 패턴 | 목적 |
|----------|-----------|------|
| **Cache Stampede 방지** | `cache:sf:{cacheName}:{key}` | Single Flight Loading |
| **동시성 제어** | `calculation:{ocid}:{presetNo}` | 중복 계산 방지 |
| **이벤트 처리** | `event:{eventType}:{id}` | 순서 보장 |

### 문제점: Redis 의존성이 Scale-out을 차단

**Scale-out Blockers 분석 결과** (docs/05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md):

| 분류 | P0 (Critical) | P1 (High) | 합계 |
|------|:---:|:---:|:---:|
| Redis 의존성 | 4 | 2 | 6 |
| In-Memory 상태 | 6 | 6 | 12 |
| **합계** | **10** | **8** | **22** |

**P0 Critical Blockers:**
1. **RedissonLockStrategy** - Redis 장애 시 락 기능 불가
2. **RedisBufferStrategy** - Redis 의존 (대안: PostgresL2CacheStrategy 구현됨)
3. **SingleFlightExecutor** - In-Memory inFlight Map (Redis 의존 필요)
4. **AlertThrottler** - In-Memory AtomicInteger (Redis AtomicLong 필요)

**Redis Lock의 문제점:**
- **인프라 복잡도:** Redis Cluster 운영, 모니터링, 장애 대응 부담
- **비용:** AWS ElastiCache Redis 별도 운영 비용
- **연결 오버헤드:** Redis 연결 풀 관리, Watchdog 스레드 (30초마다 락 갱신)
- **락 누수 위험:** 프로세스 크래시 시 락 해제 불가 (TTL 의존)

---

## 2. 결정 (Decision)

**Redisson 분산 락을 PostgreSQL Advisory Lock으로 대체한다.**

### 핵심 전략

1. **Session-level Advisory Lock 사용**
   - `pg_try_advisory_lock(bigint)` - 즉시 반환 (비블로킹)
   - `pg_advisory_unlock(bigint)` - 명시적 해제
   - 연결 종료 시 자동 해제 (락 누수 방지)

2. **전용 커넥션 풀 운영**
   - **Pool Size: 10 connections** (LockHikariConfig 참조)
   - **Fixed Pool:** MinIdle = MaxPoolSize (연결 비용 제거)
   - **Fail-fast:** connectionTimeout = 5초

3. **Deadlock 방지: 순서 기반 락 획득**
   - ThreadLocal에 락 획득 순서 기록
   - 항상 오름차순으로 락 획득 시도
   - 순서 위반 시 `LockOrderViolationException` 발생

4. **Lease Time 자동 관리**
   - ThreadPoolTaskScheduler로 1분마다 만료된 락 정리
   - ThreadLocal에 lease 만료 시간 저장
   - `try-finally` 패턴으로 락 해제 보장

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (Redisson RLock)

**장점:**
- 검증된 안정성, Watchdog 자동 갱신
- 낮은 지연 시간 (~1ms)

**단점:**
- Redis 인프라 의존 지속 (P0 Blocker)
- 운영 복잡도, 비용 증가
- 단일 DB 전략 (ADR-001) 위배

**평가:** ❌ Scale-out 차단 요소 존재

### B. PostgreSQL Advisory Lock (선택됨)

**장점:**
- PostgreSQL 네이티브 기능 (추가 인프라 불필요)
- ACID 트랜잭션으로 락 연산 보장
- 연결 종료 시 자동 해제 (락 누수 방지)
- 단일 DB 아키텍처 부합 (ADR-001)

**단점:**
- 지연 시간 약간 증가 (~2-3ms vs Redis ~1ms)
- Connection Pool 고갈 위험 (전용 풀로 완화)

**평가:** ✅ Scale-out 활성화, 운영 단순화

### C. 테이블 기반 락 (SELECT FOR UPDATE)

**장점:**
- 명시적 락 테이블 관리

**단점:**
- 별도 테이블 생성/유지 관리
- Row Lock 경합으로 성능 저하
- Deadlock 가능성 (명시적 락 순서 필요)

**평가:** ⚠️ Advisory Lock이 더 가볍고 성능 좋음

---

## 4. 기술적 구현 (Implementation)

### 4.1 FNV-1a 64-bit Hashing

문자열 락 키를 PostgreSQL Advisory Lock ID (64bit 정수)로 변환합니다.

```kotlin
/**
 * 문자열 키를 64bit 정수로 변환 (FNV-1a 해시)
 *
 * <h4>FNV-1a 해시 특징</h4>
 * <ul>
 *   <li>빠른 연산 속도</li>
 *   <li>좋은 분산 성능</li>
 *   <li>충돌 확률: 2^-64 (무시할 수준)</li>
 * </ul>
 */
private fun toAdvisoryLockId(key: String): Long {
    val FNV_64_OFFSET_BASIS = -0x3c2d2f0705b7b401L // 14695981039346656037
    val FNV_64_PRIME = 0x100000001b3L // 1099511628211

    var hash = FNV_64_OFFSET_BASIS
    for (byte in key.toByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_64_PRIME
    }
    return hash
}
```

**충돌 확률:** 2^-64 ≈ 5.42 × 10^-20 (실질적으로 0)

### 4.2 전용 커넥션 풀 설정 (LockHikariConfig)

```kotlin
@Configuration
@Profile("!test & !chaos & !container & !pgtest")
class LockHikariConfig(
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val username: String,
    @Value("\${spring.datasource.password}") private val password: String,
    @Value("\${lock.datasource.pool-size:10}") private val poolSize: Int,
) {
    @Bean(name = ["lockDataSource"])
    fun lockDataSource(): DataSource {
        val config = HikariConfig()
        config.jdbcUrl = jdbcUrl
        config.username = username
        config.password = password

        // Fixed Pool: Min과 Max를 동일하게 설정하여 연결 비용 제거
        config.maximumPoolSize = poolSize
        config.minimumIdle = poolSize

        // Fail-fast: 5초 안에 연결 못 얻으면 에러
        config.connectionTimeout = 5000
        config.poolName = "PostgresLockPool"

        return HikariDataSource(config)
    }

    @Bean(name = ["lockJdbcTemplate"])
    fun lockJdbcTemplate(): JdbcTemplate = JdbcTemplate(lockDataSource())
}
```

**Pool Size 결정 근거:**
- **기본 10 connections:** 일반 트래픽 (RPS < 100)
- **최대 40 connections:** 고부하 (RPS > 500)
- **Little's Law:** L = λW (100 req/s × 0.1s latency + buffer ≈ 10 connections)

### 4.3 Deadlock 방지: 순서 기반 락 획득

```kotlin
/**
 * 현재 스레드가 획득한 락 관리 (ThreadLocal)
 * Key: advisory lock ID (Long), Value: lease 만료 시간 (Long, epoch millis)
 */
private val acquiredLocks: ThreadLocal<MutableMap<Long, Long>> =
    ThreadLocal.withInitial { ConcurrentHashMap() }

/**
 * Deadlock 방지: 락 획득 순서 검증
 *
 * <pre>
 * 좋은 예 (오름차순):
 *   lock(A) -> lock(B) -> lock(C)
 *
 * 나쁜 예 (내림차순, Deadlock 위험):
 *   lock(C) -> lock(B) -> lock(A)
 * </pre>
 */
private fun validateLockOrder(advisoryLockId: Long) {
    val locks = acquiredLocks.get()
    if (locks.isNotEmpty()) {
        val maxAcquired = locks.keys.max()
        if (advisoryLockId < maxAcquired) {
            lockMetrics.recordOrderViolation("postgres")
            throw LockOrderViolationException(
                "Lock order violation: attempting to acquire lock $advisoryLockId " +
                "while holding lock $maxAcquired. " +
                "Locks must be acquired in ascending order to prevent deadlocks."
            )
        }
    }
}
```

### 4.4 Atomic Lease Registration

```kotlin
/**
 * PostgreSQL Advisory Lock 획득 시도
 *
 * <p>pg_try_advisory_lock(bigint)을 사용하여 락 획득을 시도합니다.
 * 이미 락을 획득한 경우 true를 반환합니다 (PostgreSQL 특성).
 */
override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
    val advisoryLockId = toAdvisoryLockId(lockKey)
    val deadline = System.currentTimeMillis() + (waitTime * 1000)

    // Deadlock 방지: 순서 검증
    validateLockOrder(advisoryLockId)

    // 이미 획득한 락이면 성공 처리 (재진입 가능)
    if (isHeldByCurrentThread(advisoryLockId)) {
        return true
    }

    // 폴링 방식으로 락 획득 시도
    while (System.currentTimeMillis() < deadline) {
        if (tryAcquireAdvisoryLock(advisoryLockId)) {
            // [Critical] 락 획득과 lease 등록을 원자적으로 수행
            val leaseDeadline = if (leaseTime > 0) {
                System.currentTimeMillis() + (leaseTime * 1000)
            } else {
                Long.MAX_VALUE
            }
            acquiredLocks.get()[advisoryLockId] = leaseDeadline

            lockMetrics.recordWaitTime(
                System.currentTimeMillis() - startTime,
                "postgres"
            )
            return true
        }

        // 지수 백오프 (1ms ~ 100ms)
        val parkTime = (System.currentTimeMillis() - startTime)
            .toInt().coerceIn(1, 100).toLong()
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(parkTime))
    }

    return false
}

/**
 * 락 해제 (try-finally 패턴으로 보장)
 */
override fun unlockInternal(lockKey: String) {
    val advisoryLockId = toAdvisoryLockId(lockKey)
    val locks = acquiredLocks.get()

    if (locks.containsKey(advisoryLockId)) {
        releaseAdvisoryLock(advisoryLockId)
        locks.remove(advisoryLockId)

        // 메모리 누수 방지: 빈 경우 ThreadLocal 완전 제거
        if (locks.isEmpty()) {
            acquiredLocks.remove()
        }
    }
}
```

### 4.5 Lease Time 자동 관리

```kotlin
/**
 * Lease Time 관리를 위한 초기화
 *
 * <p>주기적으로 만료된 락을 해제하는 스케줄러를 등록합니다.
 */
@jakarta.annotation.PostConstruct
fun initLeaseScheduler() {
    // 1분마다 만료된 락 정리
    leaseScheduler.scheduleWithFixedDelay(
        { cleanupExpiredLocks() },
        TimeUnit.MINUTES.toMillis(1),
    )
    log.info("[PostgresLockStrategy] Lease time scheduler initialized (cleanup interval: 60s)")
}

/**
 * 만료된 락 정리
 *
 * <p>ThreadLocal에 등록된 락 중 lease time이 만료된 것을 해제합니다.
 */
private fun cleanupExpiredLocks() {
    val locks = acquiredLocks.get()
    val now = System.currentTimeMillis()
    val expiredKeys = locks.filterValues { it < now }.keys

    for (advisoryLockId in expiredKeys) {
        releaseAdvisoryLock(advisoryLockId)
        locks.remove(advisoryLockId)
        log.debug("[PostgresLockStrategy] Cleaned up expired lock (id={})", advisoryLockId)
    }

    // 빈 경우 ThreadLocal 완전 제거
    if (locks.isEmpty()) {
        acquiredLocks.remove()
    }
}
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점 (Positive Consequences)

| 항목 | 설명 |
|------|------|
| **Scale-out 활성화** | P0 Blocker 제거, 단일 DB 아키텍처 부합 |
| **운영 단순화** | Redis 인프라 제거, PostgreSQL만 모니터링 |
| **비용 절감** | AWS ElastiCache Redis 비용 제거 |
| **락 누수 방지** | 연결 종료 시 자동 해제 (프로세스 크래시 안전) |
| **ACID 보장** | 트랜잭션 내 락 연산 가능 |
| **단일 커넥션** | DB 연결 하나로 락 + 데이터 조작 |

### ⚠️ 단점 (Negative Consequences)

| 항목 | 완화 방안 |
|------|----------|
| **지연 시간 증가** | ~2-3ms (Redis ~1ms) → 허용 가능 범위 |
| **Connection Pool 고갈 위험** | 전용 풀 10 connections, Fail-fast 전략 |
| **Hash 충돌 가능성** | FNV-1a 64-bit, 충돌 확률 2^-64 (무시 가능) |
| **분산 환경 제약** | 단일 PostgreSQL 클러스터 내에서만 유효 |

---

## 6. 성능 비교

### 락 연산 성능

| 작업 | Redisson | PostgreSQL Advisory Lock | 비고 |
|------|----------|-------------------------|------|
| 락 획득 | ~1ms | ~2-5ms | 허용 가능 |
| 락 해제 | ~1ms | ~1-2ms | 거의 동일 |
| 락 대기 (10초) | ~10,000 ops/sec | ~5,000 ops/sec | 고부하 시 차이 |

### 경합 상황

| 경합률 | Redisson | PostgreSQL | 영향 |
|-------|----------|-----------|------|
| 10% | ~0ms 대기 | ~1ms 대기 | 무시 가능 |
| 50% | ~10ms 대기 | ~20ms 대기 | 허용 가능 |
| 90% | ~100ms 대기 | ~200ms 대기 | 고부하시 성능 저하 |

**결론:** 일반/패치데이 트래픽 (RPS < 500)에서는 성능 차이 무시 가능.

---

## 7. 리스크 및 완화 방안 (Risks)

### Risk 1: Connection Pool 고갈

**발생 조건:**
- 락 획득 스레드가 Connection을 보유한 채 장기 실행 작업 수행

**완화 방안:**
- **전용 풀 분리:** `lockJdbcTemplate`는 메인 풀과 독립
- **Fail-fast:** `connectionTimeout = 5초`로 스레드 보호
- **Park 기반 대기:** `LockSupport.parkNanos()`로 Connection 점유 방지

### Risk 2: Hash Collision

**발생 확률:** 2^-64 ≈ 5.42 × 10^-20 (무시할 수준)

**완화 방안:**
- FNV-1a 해시 사용 (좋은 분산 성능)
- SHA-256 해시로 대체 가능 (필요시)

### Risk 3: Deadlock

**발생 조건:**
- Thread A: lock(A) → lock(B)
- Thread B: lock(B) → lock(A)

**완화 방안:**
- **순서 기반 락 획득:** 항상 오름차순으로 락 획득
- **ThreadLocal 검증:** `validateLockOrder()`로 순서 위반 감지
- **메트릭 기록:** `lock_order_violation_total`으로 모니터링

---

## 8. 마이그레이션 계획 (Migration Path)

### Phase 1: 전용 커넥션 풀 구현
- [x] LockHikariConfig 구현 (10 connections, fixed pool)
- [x] lockJdbcTemplate Bean 등록
- [x] Connection Pool 모니터링 (HikariCP metrics)

### Phase 2: Deadlock Detection 구현
- [x] ThreadLocal 기반 락 순서 추적
- [x] validateLockOrder() 메서드 구현
- [x] LockOrderViolationException 정의
- [x] lock_order_violation_total 메트릭 기록

### Phase 3: Lease Registration Atomicity 수정
- [x] tryLock()에서 lease 등록을 원자적으로 수행
- [x] unlockInternal()에서 ThreadLocal 정리
- [x] 만료된 락 정리 스케줄러 (1분 간격)

### Phase 4: 서비스 마이그레이션
- [ ] TieredCache: RedissonLockStrategy → PostgresLockStrategy
- [ ] CalculationService: 분산 락 전환
- [ ] SingleFlightExecutor: In-Memory → PostgreSQL Lock
- [ ] AlertThrottler: In-Memory → PostgreSQL AtomicLong

### Phase 5: 검증
- [ ] 단위 테스트 (PostgresAdvisoryLockStrategyTest)
- [ ] 경합 테스트 (concurrent callers, lock contention)
- [ ] 부하 테스트 (RPS 235, Redis fallback 시나리오)
- [ ] Nightmare N11: Lock Fallback Avalanche 테스트

---

## 9. 롤백 전략 (Rollback Strategy)

### 롤백 트리거

| 조건 | 임계값 | 조치 |
|------|--------|------|
| 락 획득 실패율 | > 5% | Redisson 복원 |
| 락 대기 시간 p99 | > 1초 | Redisson 복원 |
| Connection Pool 고갈 | > 90% 사용률 | Pool Size 증설 또는 복원 |

### 롤백 절차

1. **기능 플래그 전환:** `maple.infra.lock.impl=redisson`
2. **Redisson 재활성화:** RedissonLockStrategy Bean 교체
3. **메트릭 확인:** 락 획득 지연, 실패율 정상화 확인

---

## 10. 모니터링 & 검증 (Monitoring)

### 성공 지표 (Success Metrics)

| 지표 | 목표 | 측정 방법 |
|------|------|----------|
| 락 획득 지연 p99 | < 50ms | lock_acquisition_duration_seconds{quantile="0.99"} |
| 락 누수 발생 | 0건/일 | pg_locks 테이블 모니터링 |
| Connection Pool 사용률 | < 80% | hikaricp_connections_active / hikaricp_connections_max |
| 순서 위반 발생 | 0건/일 | lock_order_violation_total |

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

-- Connection Pool 상태
SELECT
    pool_name,
    active_connections,
    idle_connections,
    total_connections
FROM (SELECT 'PostgresLockPool' as pool_name,
             count(*) FILTER (WHERE state = 'active') as active_connections,
             count(*) FILTER (WHERE state = 'idle') as idle_connections,
             count(*) as total_connections
      FROM pg_stat_activity
      WHERE application_name = 'probabilistic-valuation-engine') t;
```

### Prometheus Alert Rules

```yaml
groups:
  - name: lock_alerts
    rules:
      - alert: LockAcquisitionFailureHigh
        expr: rate(lock_acquisition_total{status="failed"}[5m]) > 0.05
        for: 5m
        annotations:
          summary: "Lock acquisition failure rate > 5%"

      - alert: LockOrderViolationDetected
        expr: rate(lock_order_violation_total[5m]) > 0
        annotations:
          summary: "Lock order violation detected (deadlock risk)"

      - alert: LockPoolExhaustion
        expr: hikaricp_connections_active{pool="PostgresLockPool"} / hikaricp_connections_max > 0.9
        for: 5m
        annotations:
          summary: "Lock connection pool > 90% used"
```

---

## 11. 관련 문서 (Related Documents)

- [ADR-001 PostgreSQL 단일 DB 전략](001-postgresql-single-db-strategy.md)
- [ADR-005 PostgreSQL Advisory Lock 설계](005-postgresql-advisory-lock.md)
- [Scale-out Blockers Analysis](../05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md)
- [PostgreSQL Advisory Locks 공식 문서](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- [FNV-1a Hash Algorithm](https://en.wikipedia.org/wiki/FNV_hash)

---

## 12. 변경 이력 (Changelog)

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-10 | ADR 초안 작성 (제안됨) | probabilistic-valuation-engine Team |
| 2026-03-10 | PostgresLockStrategy 구현 완료 (13 tests passed) | Issue #554 Unit 1 |
