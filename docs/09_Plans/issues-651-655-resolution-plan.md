# Plan: Issues #651–#655 Resolution (Consensus Review v2)

## Context

**현재 상황**: 5개 P2 이슈가 오픈 상태. Reliability, Security, Architecture, Configuration, Cleanup 카테고리.
**문제**: Leader election 메트릭 부재, 인증 엔드포인트 Rate Limiting 미적용, @Volatile 스레드 안전성, 하드코딩 매직 넘버, Redis 잔여 코드.
**목표**: 5개 이슈를 일괄 해결하여 관측성, 보안, 동시성 안전성, 설정 외부화, 코드베이스 정리 달성.
**Review**: Architect + Critic + Code-Reviewer 3에이전트 Consensus Review 완료. P0 5개, P1 4개 반영.

---

## Issue별 분석 및 해결 방안

### #651 — Leader Election 관측 가능성 (메트릭 부재)

**현황**: `PostgresAdvisoryLockStrategy.kt`는 leader/follower 로그만 출력하고 메트릭이 전무함.
`LockMetrics.kt`는 존재하나 `"redis"`, `"mysql"` 태그만 지원. `"postgres"` 태그 누락.

**Consensus P0 수정 (Code-Reviewer)**: LockMetrics의 when절 확장은 OCP 위반. Map 기반으로 리팩토링.

**해결 방안**:

#### 1. `LockMetrics.kt` — Map 기반 동적 태그로 리팩토링 (OCP 준수)
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockMetrics.kt`

```kotlin
// Before: redis/mysql/postgres 개별 필드 + when절 (God Object)
// After: ConcurrentHashMap<String, ImplMetrics> 기반

private data class ImplMetrics(
    val activeLocks: AtomicLong = AtomicLong(0),
    var failureCounter: io.micrometer.core.instrument.Counter? = null
)

private val implementations = ConcurrentHashMap<String, ImplMetrics>()

private fun getOrCreate(name: String): ImplMetrics =
    implementations.computeIfAbsent(name.lowercase()) {
        val metrics = ImplMetrics()
        metrics.failureCounter = io.micrometer.core.instrument.Counter.builder("lock.acquisition.failure.total")
            .description("Total lock acquisition failures")
            .tag("implementation", it)
            .register(registry)
        Gauge.builder("lock.active.current", metrics.activeLocks) { obj -> obj.get().toDouble() }
            .description("Currently active locks")
            .tag("implementation", it)
            .register(registry)
        metrics
    }

fun recordFailure(implementation: String) { getOrCreate(implementation).failureCounter?.increment() }
fun recordLockAcquired(implementation: String) { getOrCreate(implementation).activeLocks.incrementAndGet() }
fun recordLockReleased(implementation: String) { getOrCreate(implementation).activeLocks.decrementAndGet() }
```

- 기존 `redisActiveLocks`, `mysqlActiveLocks`, when절 모두 제거
- `"postgres"`, `"mysql"` 모두 동적 등록 (호출 시 자동 생성)

#### 2. `PostgresAdvisoryLockStrategy.kt` — LockMetrics 연동
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategy.kt`

```kotlin
class PostgresAdvisoryLockStrategy(
    @Qualifier("lockJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    @Qualifier("lockTransactionTemplate")
    private val lockTransactionTemplate: TransactionTemplate,
    private val lockMetrics: LockMetrics,  // ADD
) : LockStrategy, LeaderElectionStrategy {
```

- `executeWithLock()`: startTime 기록, 획득 후 `recordWaitTime()` + `recordLockAcquired()`, finally에서 `recordLockReleased()`
- `executeWithLeaderElection()`: leader/follower 메트릭 기록, timeout 시 `recordFailure()`
- `tryLockImmediately()`: 획득 성공/실패 메트릭

---

### #652 — 인증 엔드포인트 Rate Limiting 미적용

**현황**: `RateLimitingFilter`, `RateLimitingFacade`, `PostgresRateLimiter` 모두 구현 완료.
하지만 `SecurityConfig.kt`에 필터가 **등록되지 않음**. `RateLimitingFilter`에 `@Component` 없음.

**Consensus P0 수정 (3/3 동의)**: Filter Bean 등록 + SecurityConfig 연동 + DDL 마이그레이션.

**해결 방안**:

#### 1. `RateLimitingFilter.kt` — @Component 추가
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/filter/RateLimitingFilter.kt`

```kotlin
@Component  // ADD
open class RateLimitingFilter(
```

#### 2. `SecurityConfig.kt` — RateLimitingFilter 등록
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/config/SecurityConfig.kt`

```kotlin
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val rateLimitingFilter: RateLimitingFilter,  // ADD
) {
    // securityFilterChain()에 추가 (JWT 앞에 배치 → 무단 요청 조기 차단):
    .addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter::class.java)
    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
```

**Filter 순서**: RateLimiting → JWT → UsernamePasswordAuthentication
- IP 기반 제한은 JWT 인증 전에 작동 (올바름)
- 사용자별 제한은 JWT 후이므로 미작동 → IP 기반만으로 충분 (보안 강화가 목적)

#### 3. `application.yml` — rate limiting 활성화 (기존 app: 블록에 merge)
**Path:** `module-app/src/main/resources/application.yml`

```yaml
ratelimit:
  enabled: true
```

**⚠️ CLAUDE.md Section 13**: 기존 YAML에 merge. 새 root key가 아닌 기존 ratelimit 블록에 추가.

#### 4. `rate_limit` 테이블 DDL 마이그레이션 (Consensus P0-4)
**New file:** `module-infra/src/main/resources/db/migration/V109__rate_limit_table.sql`

```sql
CREATE TABLE IF NOT EXISTS rate_limit (
    key VARCHAR(255) PRIMARY KEY,
    count BIGINT NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rate_limit_expires_at ON rate_limit(expires_at);
```

---

### #653 — @Volatile → AtomicReference (PostgresNotifySubscriber + CharacterCreationListener)

**현황**: `PostgresNotifySubscriber.kt:73-77`에서 `@Volatile var` 사용.
`@Volatile`은 가시성만 보장하고 원자성(Compound operation)은 보장하지 않음.

**Consensus P0 수정 (2/3 동의)**: 두 개별 AtomicReference는 여전히 비원자적 → 단일 AtomicReference + 데이터 클래스 튜플 필요.

**Consensus P1 수정 (Architect)**: `CharacterCreationListener.kt`도 동일한 패턴 → 범위에 포함.

**Consensus P0 수정 (Code-Reviewer)**: `closeConnectionInternal()`의 try-catch는 CLAUDE.md Zero Try-Catch 위반 → executor.executeVoid()로 래핑.

**해결 방안**:

#### 1. `PostgresNotifySubscriber.kt` — AtomicReference 튜플 전환
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifySubscriber.kt`

```kotlin
// Before (lines 73-77):
@Volatile
private var listenConnection: java.sql.Connection? = null
@Volatile
private var pgConnection: PGConnection? = null

// After: 단일 AtomicReference로 원자성 보장
private data class PgConnections(
    val listen: java.sql.Connection,
    val pg: PGConnection
)
private val connections = AtomicReference<PgConnections>(null)
```

- `establishConnection()`: 연결 생성 후 `connections.set(PgConnections(conn, pgConn))` (원자적)
- `pollNotifications()`: `connections.get()?.pg?.notifications` 사용
- `closeConnectionInternal()`: `connections.getAndSet(null)` 후 close (원자적 null + close)
- try-catch 제거 → `executor.executeVoid()`로 래핑 (CLAUDE.md Section 1 준수)

#### 2. `CharacterCreationListener.kt` — 동일 패턴 적용
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/character/notify/CharacterCreationListener.kt:44-48`

동일한 `@Volatile var` 패턴 → 동일한 AtomicReference 튜플 전환 적용.

---

### #654 — 하드코딩 매직 넘버 설정 외부화

**현황**: PostgresNotifySubscriber에 `POLL_INTERVAL_MS = 100L`, `RECONNECT_DELAY_MS = 5000L`.
ExpectationCalculationQueue에 `HIGH_PRIORITY_CAPACITY = 1_000`, `MAX_QUEUE_SIZE = 10_000`.

**Consensus P0 수정 (2/3 동의)**: Java `@Value` field injection은 생성자 주입 필요.

**해결 방안**:

#### 1. `application.yml` — 기존 app: 블록에 merge (line 232 기준)
**Path:** `module-app/src/main/resources/application.yml`

```yaml
app:
  cache:
    notify:
      poll-interval-ms: 100
      reconnect-delay-ms: 5000
  queue:
    expectation:
      max-queue-size: 10000
      high-priority-capacity: 1000
```

**⚠️ CLAUDE.md Section 13**: 새 root key 생성 금지. 기존 `app:` 블록에 merge.

#### 2. `PostgresNotifySubscriber.kt` — @Value로 외부화
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifySubscriber.kt`

```kotlin
// companion object에서 제거:
// private const val POLL_INTERVAL_MS = 100L
// private const val RECONNECT_DELAY_MS = 5000L

// 생성자에 추가:
@Value("\${app.cache.notify.poll-interval-ms:100}") private val pollIntervalMs: Long,
@Value("\${app.cache.notify.reconnect-delay-ms:5000}") private val reconnectDelayMs: Long
```

#### 3. `ExpectationCalculationQueue.java` — 생성자 주입으로 전환
**Path:** `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java`

```java
// Before: static final 상수
// private static final int MAX_QUEUE_SIZE = 10_000;
// private static final int HIGH_PRIORITY_CAPACITY = 1_000;

// After: 생성자 주입 (field injection 금지 — 초기화 시점 문제)
private final int maxQueueSize;
private final int highPriorityCapacity;

public ExpectationCalculationQueue(
    PgmqClient pgmqClient,
    LogicExecutor executor,
    @Value("${app.queue.expectation.max-queue-size:10000}") int maxQueueSize,
    @Value("${app.queue.expectation.high-priority-capacity:1000}") int highPriorityCapacity
) {
    this.pgmqClient = pgmqClient;
    this.executor = executor;
    this.maxQueueSize = maxQueueSize;
    this.highPriorityCapacity = highPriorityCapacity;
}
```

---

### #655 — ADR-022 Redis 제거 잔여물 정리

**현황**: ADR-022 Redis 제거는 대부분 완료. 하지만 잔여 코드 존재.

**Consensus P1 수정 (Architect)**: `BufferLuaScripts.kt`도 dead code → 삭제 범위에 포함.

**해결 방안**:

#### 1. Redis 잔여 파일 삭제
**Delete:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/redis/script/LuaScripts.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/redis/script/LikeAtomicOperations.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/redis/` 디렉토리 (위 두 파일이 유일)
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/BufferLuaScripts.kt` (Consensus P1-2)

삭제 전 참조 확인 필수:
```bash
grep -r "LuaScripts\|LikeAtomicOperations\|BufferLuaScripts" --include="*.kt" --include="*.java" module-*/src/main/
```

#### 2. LockMetrics.kt — redis/mysql when절 제거 (#651 Map 리팩토링에 포함)
Map 기반 동적 등록으로 전환하면 자연스럽게 redis/mysql 개별 필드 제거됨.

#### 3. application.yml — Redis 잔여 설정 정리
- `buffer.redis.enabled: false` → 해당 블록 제거
- 주석의 "Redis removed" 참조는 유지 (히스토리)

---

## 구현 순서 (의존성 고려)

| Step | Issue | 파일 | 의존성 | P0/P1 반영 |
|------|-------|------|--------|------------|
| 1 | #655 | Redis 잔여 + BufferLuaScripts 삭제 | 없음 | P1-2 |
| 2 | #651 | LockMetrics.kt Map 기반 리팩토링 | Step 1 | P0 (OCP) |
| 3 | #651 | PostgresAdvisoryLockStrategy.kt LockMetrics 연동 | Step 2 | |
| 4 | #653 | PostgresNotifySubscriber AtomicReference 튜플 + try-catch 제거 | 없음 | P0-2, P0-5 |
| 5 | #653 | CharacterCreationListener 동일 전환 | 없음 | P1-1 |
| 6 | #654 | PostgresNotifySubscriber @Value 외부화 | Step 4 먼저 | |
| 7 | #654 | ExpectationCalculationQueue 생성자 @Value | 없음 | P0-3 |
| 8 | #652 | RateLimitingFilter @Component 추가 | 없음 | P0-1 |
| 9 | #652 | SecurityConfig Filter 등록 | Step 8 | P0-1 |
| 10 | #652 | application.yml rate limiting + rate_limit DDL | Step 9 | P0-4 |
| 11 | #654, #652 | application.yml 설정 merge (app.* + ratelimit) | Step 6, 7 | P1-3 |

**권장 브랜치**: `fix/issues-651-655`

---

## Modified Files Summary

| File | Issue | Action |
|------|-------|--------|
| `module-infra/.../redis/script/LuaScripts.kt` | #655 | DELETE |
| `module-infra/.../redis/script/LikeAtomicOperations.kt` | #655 | DELETE |
| `module-infra/.../redis/` (directory) | #655 | DELETE |
| `module-infra/.../queue/BufferLuaScripts.kt` | #655 | DELETE (P1-2) |
| `module-infra/.../lock/LockMetrics.kt` | #651, #655 | EDIT — Map 기반 리팩토링 |
| `module-infra/.../lock/PostgresAdvisoryLockStrategy.kt` | #651 | EDIT — LockMetrics 생성자 주입 + 연동 |
| `module-infra/.../cache/invalidation/impl/PostgresNotifySubscriber.kt` | #653, #654 | EDIT — AtomicReference 튜플 + @Value |
| `module-infra/.../character/notify/CharacterCreationListener.kt` | #653 | EDIT — AtomicReference 튜플 (P1-1) |
| `module-app/.../queue/ExpectationCalculationQueue.java` | #654 | EDIT — 생성자 @Value (P0-3) |
| `module-infra/.../ratelimit/filter/RateLimitingFilter.kt` | #652 | EDIT — @Component 추가 (P0-1) |
| `module-infra/.../security/config/SecurityConfig.kt` | #652 | EDIT — Filter 등록 (P0-1) |
| `module-app/.../resources/application.yml` | #652, #654 | EDIT — ratelimit + app.* merge |
| `module-infra/.../db/migration/V109__rate_limit_table.sql` | #652 | NEW — DDL (P0-4) |

---

## Verification

```bash
# 1. 컴파일 확인
./gradlew compileKotlin compileJava --continue

# 2. 전체 테스트
./gradlew test

# 3. Redis 잔여 참조 확인
grep -r "redis/script\|BufferLuaScripts" module-infra/src/main/ → 결과 없어야 함

# 4. 메트릭 확인 (서버 기동 후)
curl localhost:8080/actuator/prometheus | grep 'lock.*implementation=' → postgres 태그 확인
```

---

## Consensus Review 반영 사항

| ID | 심각도 | 이슈 | 동의 | 반영 |
|----|--------|------|------|------|
| P0-1 | CRITICAL | RateLimitingFilter @Component + 등록 | 3/3 | Step 8-9 |
| P0-2 | CRITICAL | AtomicReference 튜플 패턴 | 2/3 | Step 4 |
| P0-3 | CRITICAL | Java @Value 생성자 주입 | 2/3 | Step 7 |
| P0-4 | CRITICAL | rate_limit DDL 마이그레이션 | 1/3 | Step 10 |
| P0-5 | CRITICAL | closeConnectionInternal try-catch 위반 | 1/3 | Step 4 |
| P1-1 | HIGH | CharacterCreationListener 포함 | 1/3 | Step 5 |
| P1-2 | HIGH | BufferLuaScripts.kt 삭제 | 1/3 | Step 1 |
| P1-3 | HIGH | application.yml merge 명시 | 1/3 | Step 11 |
| P1-4 | HIGH | Thread.sleep → parkNanos | 1/3 | 별도 이슈 권장 |
