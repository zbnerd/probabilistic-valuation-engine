# Plan: Issues #645–#650 Resolution (Consensus Review v2)

## Context

**Current situation**: 6개 P2 이슈가 오픈 상태. 대부분 Reliability/Performance 카테고리.
**Problem**: L2 cache 풀테이블 스캔, DLQ replay 부재, cache stampede gap, shutdown 조율 미비, 기록 보상 트랜잭션 부재, 커넥션 풀 모니터링 부재.
**Key discovery**: Issue #649 (LikeSyncExecutor)는 이미 #664에서 DB Trigger로 대체되어 **Deprecated** 상태. 이슈 클로즈 권장.
**Review**: Architect + Critic + Code-Reviewer 3에이전트 Consensus Review 완료. P0 1개, P1 4개 반영.

---

## Issue별 분석 및 해결 방안

### #645 — L2 Cache LIKE 풀테이블 스캔 → 인덱스 활용 개선

**현황**: `PostgresL2CacheStrategy.kt:266`에서 `DELETE FROM cache_storage WHERE cache_key LIKE ?` 사용.
Key format: `{cacheName}:v1:{actualKey}` (예: `character:v1:zbnerd`)

**문제**: `LIKE 'prefix%'`는 B-tree 인덱스를 타지 못함.
기존 partial index `idx_cache_storage_key_expires (cache_key, expires_at DESC) WHERE expires_at > NOW()`는 expired row를 제외하므로 evictAll에 부적합.

**Consensus P0 수정**: 기존 플랜의 `:` + 1 = `;` 경계값은 multi-part key와 non-ASCII key에서 실패.
actualKey에 `:` 포함 시 (예: `character:v1:user:123`) `user` > `;`이므로 range miss.
한국어 IGN 포함 시 UTF-8 정렬 불일치.

**해결**: `~` (ASCII 126, highest printable ASCII)를 upper bound로 사용 + `generateKey()`에 validation 추가.

**Modified Files:**

#### 1. `PostgresL2CacheStrategy.kt` — evictAll() range query 전환
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt`

```kotlin
// Before (line 265-268):
val keyPrefix = "$cacheName:$KEY_VERSION:"
val sql = "DELETE FROM cache_storage WHERE cache_key LIKE ?"
val deleted = jdbcTemplate.update(sql, "$keyPrefix%")

// After:
val keyPrefix = "$cacheName:$KEY_VERSION:"
val upperBound = "$cacheName:$KEY_VERSION~"
val sql = "DELETE FROM cache_storage WHERE cache_key >= ? AND cache_key < ?"
val deleted = jdbcTemplate.update(sql, keyPrefix, upperBound)
```

**Important**:
- `~` (0x7E)는 모든 printable ASCII보다 크므로 `{cacheName}:v1:{모든 actualKey}` 범위 포함
- actualKey에 `~` 포함 불가 규칙을 `generateKey()`에서 강제 (아래 validation 참조)
- B-tree 인덱스의 range scan 활용 보장

#### 2. `PostgresL2CacheStrategy.kt` — generateKey() validation 추가
**Path:** 동일 파일

```kotlin
// Before (line 314):
fun generateKey(cacheName: String, actualKey: String): String = "$cacheName:$KEY_VERSION:$actualKey"

// After:
fun generateKey(cacheName: String, actualKey: String): String {
    require('~' !in cacheName && '~' !in actualKey) {
        "Cache key must not contain '~' (reserved for range query boundary): cacheName=$cacheName, actualKey=$actualKey"
    }
    return "$cacheName:$KEY_VERSION:$actualKey"
}
```

#### 3. `V106__cache_evict_range_query.sql` — 신규 마이그레이션
**Path:** `module-infra/src/main/resources/db/migration/V106__cache_evict_range_query.sql`

```sql
-- evictAll()이 partial index의 WHERE 조건(expires_at > NOW())에 걸리지 않는
-- expired row도 삭제해야 하므로, cache_key 단일 컬럼 인덱스 추가
-- 기존 idx_cache_storage_key_expires는 partial index이므로 evictAll에 부적합
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cache_storage_key_prefix
    ON cache_storage (cache_key);

COMMENT ON COLUMN cache_storage.cache_key IS 'Key format: {cacheName}:v1:{actualKey}. Range query: >= prefix AND < prefix~. Tilde (~) forbidden in key parts.';
COMMENT ON INDEX idx_cache_storage_key_prefix IS 'Index for cache key prefix range scans (evictAll, pattern matching)';
```

**검증 (구현 시 필수)**:
```sql
EXPLAIN ANALYZE DELETE FROM cache_storage
WHERE cache_key >= 'character:v1:' AND cache_key < 'character:v1~';
-- 반드시 "Index Scan using idx_cache_storage_key_prefix" 확인 필요
```

---

### #646 — DLQ 자동 Replay 메커니즘

**현황**: `PgmqWorker.kt:179`에서 최종 실패 시 `pgmqClient.delete()` 호출로 메시지가 큐에서 제거됨.
현재 flow: 성공 → `archive()`, 최종실패 → `delete()`.

**문제**: delete된 메시지는 복구 불가. 자동 replay 스케줄러 없음.

**Consensus P1 수정**: 멱등성 보장, replay count 추적, 무한 루프 방지 미비.

**해결**: 최종 실패 시 archive로 이동 + replay count 추적 + scheduled replay worker.

**Modified Files:**

#### 1. `PgmqWorker.kt` — 최종 실패 시 delete → archive 전환
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`

```kotlin
// Before (line 178-180):
else -> {
    // 최종 실패: 삭제 (DLQ)
    pgmqClient.delete(queueName, message.messageId)
    log.error("❌ [{}] Deleted message after max retries: ...")
}

// After:
else -> {
    // 최종 실패: 아카이브 (DLQ 대체 — replay 가능)
    pgmqClient.archive(queueName, message.messageId)
    log.error("❌ [{}] Archived message after max retries: msgId={}, readCount={}",
        queueName, message.messageId, message.readCount)
    meterRegistry.counter("pgmq.worker.dlq", "queue", queueName).increment()
}
```

#### 2. `V107__dlq_replay_tracking.sql` — 신규 마이그레이션: replay 추적 스키마
**Path:** `module-infra/src/main/resources/db/migration/V107__dlq_replay_tracking.sql`

```sql
-- PGMQ archive 테이블에 replay 추적 컬럼 추가
-- 각 큐의 archive 테이블: pgmq.a_{queueName}
-- replay_count: 재처리 시도 횟수 (MAX_REPLAY_ATTEMPTS 초과 시 영구 보관)
-- last_replayed_at: 마지막 재처리 시각 (exponential backoff 계산용)

-- 주의: PGMQ archive 테이블은 pgmq 스키마에 자동 생성됨
-- 큐별로 별도 ALTER 필요하거나 공통 메타데이터 테이블 사용

CREATE TABLE IF NOT EXISTS pgmq.dlq_replay_meta (
    queue_name    TEXT NOT NULL,
    message_id    BIGINT NOT NULL,
    replay_count  INT DEFAULT 0,
    first_failed_at TIMESTAMPTZ DEFAULT NOW(),
    last_replayed_at TIMESTAMPTZ,
    PRIMARY KEY (queue_name, message_id)
);

CREATE INDEX idx_dlq_replay_candidates
    ON pgmq.dlq_replay_meta (queue_name, replay_count, last_replayed_at)
    WHERE replay_count < 3;

COMMENT ON TABLE pgmq.dlq_replay_meta IS 'DLQ replay tracking metadata. Prevents infinite replay loops.';
```

#### 3. `DlqReplayWorker.kt` — 신규: DLQ Replay 스케줄러
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt`

```kotlin
@Component
class DlqReplayWorker(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(DlqReplayWorker::class.java)
        private const val MAX_REPLAY_ATTEMPTS = 3
        private const val BACKOFF_BASE_HOURS = 1L  // exponential: 1h, 2h, 4h
    }

    @Scheduled(fixedDelayString = "\${pgmq.dlq.replay-interval-ms:3600000}") // 기본 1시간
    fun replayDeadLetters() {
        val context = TaskContext.of("DlqReplayWorker", "Replay")

        executor.executeVoid({
            // 1. dlq_replay_meta에서 replay_count < MAX인 후보 조회
            val candidates = findReplayCandidates()

            if (candidates.isEmpty()) return@executeVoid

            // 2. Exponential backoff: last_replayed_at + 2^replay_count * BASE_HOURS 이후만 대상
            val eligible = candidates.filter { isBackoffElapsed(it) }

            // 3. Archive에서 원본 메시지 읽어서 원본 큐로 재발행
            eligible.forEach { candidate ->
                replayMessage(candidate)
            }

            // 4. MAX_REPLAY_ATTEMPTS 초과 메시지 알림
            notifyPermanentFailures()
        }, context)
    }

    private fun findReplayCandidates(): List<ReplayCandidate> {
        // SELECT FROM pgmq.dlq_replay_meta WHERE replay_count < MAX
        // ORDER BY first_failed_at ASC (FIFO 보장)
    }

    private fun isBackoffElapsed(candidate: ReplayCandidate): Boolean {
        // last_replayed_at + 2^replay_count * BACKOFF_BASE_HOURS < NOW()
    }

    private fun replayMessage(candidate: ReplayCandidate) {
        // 1. pgmq.a_{queue_name}에서 원본 payload 조회
        // 2. pgmq.send(queue_name, payload)로 재발행 (새 messageId)
        // 3. dlq_replay_meta의 replay_count 증분, last_replayed_at 갱신
        // 4. metric: pgmq.worker.replay counter increment
    }

    private fun notifyPermanentFailures() {
        // replay_count >= MAX_REPLAY_ATTEMPTS인 메시지 조회
        // Discord webhook 또는 로그로 영구 실패 알림
    }
}
```

#### 4. `application.yml` — DLQ replay 설정 추가
```yaml
pgmq:
  dlq:
    replay-interval-ms: 3600000  # 1시간
    max-replay-attempts: 3
    backoff-base-hours: 1
```

---

### #647 — Cache Stampede Protection

**현황**: `TieredCache.kt`에 이미 `LeaderElectionStrategy` 기반 single-flight 구현 존재.
`executeWithDistributedLock()` → Leader는 계산 후 L2 저장, Follower는 L2 폴링 (최대 5초).

**문제 분석**:

| 시나리오 | 현재 상태 | 개선 필요 |
|----------|-----------|-----------|
| 단일 인스턴스 동시 요청 | Single-flight로 보호됨 | No |
| Multi-instance 동시 요청 | pg_try_advisory_xact_lock으로 보호됨 | No |
| Leader 장애 (lock 해제 안 됨) | xact_lock이므로 트랜잭션 종료 시 해제됨 | No |
| Follower 타임아웃 후 cache miss | valueLoader 직접 호출 → stampede 가능 | **Yes** |

**Consensus P1 수정**: 기존 플랜의 `null` 반환은 Spring Cache API 계약 위반 (LSP).
`get(key, valueLoader)`는 null을 반환하면 안 됨. downstream NPE 유발.

**해결**: Follower 타임아웃 시 `CacheStampedeTimeoutException` throw. 호출부에서 catch 후 재시도.

**Modified Files:**

#### 1. `CacheStampedeTimeoutException.kt` — 신규
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/CacheStampedeTimeoutException.kt`

```kotlin
class CacheStampedeTimeoutException(
    cacheName: String,
    key: String,
) : RuntimeException("Cache stampede timeout: follower could not retrieve value within wait time. cacheName=$cacheName, key=$key")
```

#### 2. `TieredCache.kt` — Follower fallback 수정
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/TieredCache.kt`

```kotlin
// Before: followerTask = ThrowingSupplier { executeDoubleCheckAndLoad(key, valueLoader) }

// After: Follower는 L2만 폴링. 타임아웃 시 계산 수행하지 않고 예외 throw
followerTask = ThrowingSupplier {
    val result = pollL2UntilAvailable(keyStr, lockWaitSeconds)
    if (result != null) {
        result
    } else {
        // 다음 요청이 Leader 역할 수행
        // metric: stampede timeout counter increment
        throw CacheStampedeTimeoutException(l2.name, keyStr)
    }
}
```

**Important**:
- `CacheStampedeTimeoutException`은 `RuntimeException`이므로 호출부 catch 가능
- 호출부 패턴: `catch (CacheStampedeTimeoutException) { valueLoader.call() }` 또는 재시도
- TieredCache 외부에서는 `@Retryable` 또는 fallback으로 처리
- metric (`cache.stampede.timeout`, tag: cacheName) 추가로 프로덕션 발생 빈도 모니터링

---

### #648 — Graceful Shutdown 조율

**현황**: `ShutdownCoordinator.kt`에 4-phase SmartLifecycle shutdown 이미 구현.
`GracefulShutdownHook.kt`도 존재 (phase = `Integer.MAX_VALUE`).

**문제**: `@Scheduled` 태스크들이 SmartLifecycle에 등록되지 않아 shutdown 시 mid-task interruption 가능.
코드베이스에 **16개** `@Scheduled` 클래스가 있으나 플랜에서 2개만 언급.

**Consensus P1 수정**: 기존 `ScheduledTaskLifecycleWrapper`에 TOCTOU race condition 존재.
`beforeTask()`의 check-then-act 사이에 `stop()`이 끼어들면 active task가 누락됨.
`ShutdownCoordinator`의 `Thread.sleep(100)`도 project rule 위반.

**해결**: CAS 기반 race condition 수정 + 전체 @Scheduled 조사 + `LockSupport.parkNanos()` 적용.

**Modified Files:**

#### 1. `ScheduledTaskLifecycleWrapper.kt` — 신규 (CAS 기반)
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/ScheduledTaskLifecycleWrapper.kt`

```kotlin
@Component
class ScheduledTaskLifecycleWrapper : SmartLifecycle {
    companion object {
        private val log = LoggerFactory.getLogger(ScheduledTaskLifecycleWrapper::class.java)
        private const val DRAIN_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_NS = 50_000_000L  // 50ms
    }

    // 1 = running, 0 = stopping
    private val state = AtomicInteger(1)
    private val activeTasks = AtomicInteger(0)
    private val completionLatch = CountDownLatch(1)

    fun beforeTask(): Boolean {
        // CAS로 atomic check-and-increment
        while (true) {
            if (state.get() == 0) return false  // stopping
            if (state.compareAndSet(1, 1)) {
                activeTasks.incrementAndGet()
                return true
            }
        }
    }

    fun afterTask() {
        val remaining = activeTasks.decrementAndGet()
        if (remaining == 0 && state.get() == 0) {
            completionLatch.countDown()
        }
    }

    override fun stop() {
        state.set(0)

        // activeTasks가 0이 될 때까지 polling (Virtual Thread friendly)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_TIMEOUT_MS)
        while (activeTasks.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(this, POLL_INTERVAL_NS)
        }

        if (activeTasks.get() > 0) {
            log.warn("[ScheduledTaskLifecycle] Drain timeout: {} tasks still active", activeTasks.get())
        }
    }

    override fun isRunning(): Boolean = state.get() == 1

    /**
     * Phase 1: Scheduled tasks drain BEFORE buffer flush (MAX_VALUE - 500) and coordinator (MAX_VALUE).
     * Order: Phase 1 (scheduled drain) → Phase MAX-500 (buffer flush) → Phase MAX (coordinator)
     */
    override fun getPhase(): Int = 1
}
```

#### 2. `PgmqWorker.kt` — lifecycle guard 추가
```kotlin
// processMessages() 진입 시:
if (!lifecycleWrapper.beforeTask()) return
try {
    // 기존 로직
} finally {
    lifecycleWrapper.afterTask()
}
```

#### 3. 전체 @Scheduled 클래스에 동일 패턴 적용 (구현 시 조사)
```
조사 대상 (grep "Scheduled" 기준):
- PgmqWorker.processMessages() (300ms)
- ExpectationBatchWriteScheduler
- HotKeyDetector
- NexonApiCollectorScheduler
- MonitoringReportJob
- PgmqArchiveCleanupScheduler
- 기타 @Scheduled 클래스 (총 ~16개)
```

#### 4. `ShutdownCoordinator.kt` — Thread.sleep → LockSupport.parkNanos
**Path:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/ShutdownCoordinator.kt`

```kotlin
// Before (line 86-88):
while (bean.isRunning && System.currentTimeMillis() < deadline) {
    Thread.sleep(100)
}

// After:
import java.util.concurrent.locks.LockSupport

while (bean.isRunning && System.currentTimeMillis() < deadline) {
    LockSupport.parkNanos(this, 100_000_000L)  // 100ms, Virtual Thread friendly
}
```

---

### #649 — LikeSyncExecutor mid-batch 실패 시 보상 트랜잭션

**현황**: `LikeSyncExecutor.kt:16`는 이미 `@Deprecated("#664")` 상태.
DB Trigger `fn_like_count_trigger`가 `character_like` INSERT/DELETE 시 `like_count`를 자동 증감.
모든 메서드가 no-op.

**결론**: 이슈 클로즈. LikeSyncExecutor는 더 이상 실제 동작을 수행하지 않음.
Compensating transaction은 DB Trigger의 atomicity로 보장됨.

**Action**: GitHub issue comment로 deprecated 사유 명시 후 Close.

---

### #650 — DB Connection Pool 모니터링 경고

**현황**: HikariCP metrics는 Micrometer로 이미 수집.
`docker/prometheus/rules/alert_rules.yml`에 **이미 유사 rule 존재**:
- `DatabaseConnectionPoolExhausted` (> 90%)
- `DatabaseConnectionPoolHighUtilization` (> 70%)
- `DatabaseConnectionTimeout` (rate > 0)
- `DatabaseConnectionPoolPending` (> 10)
- `DatabaseConnectionAcquireSlow` (P99 > 100ms)

**해결**: 신규 파일 생성 대신 기존 파일에 누락된 rule만 추가.

**Modified Files:**

#### 1. `alert_rules.yml` — 기존 파일에 rule 추가
**Path:** `docker/prometheus/rules/alert_rules.yml`

```yaml
# 기존 maple-expectation-alerts 그룹에 추가:

- alert: HikariCPIdleConnectionsLow
  expr: hikaricp_connections_idle < 2
  for: 5m
  labels:
    severity: warning
    category: database
  annotations:
    summary: "HikariCP idle connections critically low"
    description: "Only {{ $value }} idle connections in pool {{ $labels.pool }}"
    runbook_url: "https://github.com/zbnerd/probabilistic-valuation-engine/blob/master/docs/11_Observability/runbooks/hikaricp-pool.md"
```

**Note**: Near-exhaustion (85%), Exhaustion (100%), Timeout rule은 기존 rule로 커버됨.
`HikariCPIdleConnectionsLow`만 새로 추가. 중복 경고 방지.

---

## 우선순위 및 의존성

```
#645 (L2 Cache)      → P0. 독립, 즉시 착수. 성능+정확성 영향.
#650 (Pool Alert)    → 독립, 즉시 착수 가능. 운영 안전망.
#646 (DLQ Replay)    → P1. 스키마 변경(V107) 선행 필요.
#648 (Shutdown)      → P1. 전체 @Scheduled 조사 선행.
#647 (Stampede)      → P1. TieredCache deep 분석 필요. 신중 접근.
#649 (LikeSync)      → Close only. Deprecated.
```

## DoD

- [ ] ADR 문서 작성 (`docs/01_ADR/`) — #645, #646, #647, #648 구현 시
- [ ] Unit 테스트 통과 (`./gradlew test`)
- [ ] CLAUDE.md 원칙 준수 (Zero Try-Catch, LogicExecutor 위임, LockSupport 대체 Thread.sleep)
- [ ] #649 GitHub issue close
- [ ] #645 EXPLAIN ANALYZE로 Index Scan 확인
- [ ] #646 replay_count 스키마 마이그레이션
- [ ] 브랜치 생성 후 PR (develop base)

## 검증 명령어

```bash
# #645: Range query 인덱스 사용 확인 (Index Scan이어야 함)
EXPLAIN ANALYZE DELETE FROM cache_storage WHERE cache_key >= 'character:v1:' AND cache_key < 'character:v1~';

# #645: generateKey validation 확인
# actualKey에 '~' 포함 시 IllegalArgumentException 발생 테스트

# #646: DLQ archive → replay 플로우
# Unit test: archive → replay_count 증분 → MAX 초과 시 알림

# #647: Stampede 시나리오
# Multi-thread 동시 cache miss → CacheStampedeTimeoutException 발생 테스트

# #648: Shutdown 조율
# SIGTERM → Scheduled 태스크 drain + race condition 없음 확인

# #650: Alert rule dry-run
promtool check rules docker/prometheus/rules/alert_rules.yml
```

## Consensus Review 참조

| 심각도 | 이슈 | 동의 에이전트 | 반영 |
|--------|------|-------------|------|
| P0 | #645 range query 경계값 (`;` → `~`) | A+C+CR | 반영 |
| P1 | #645 partial index로 expired row 누락 | A+CR | V106 신규 인덱스 추가 |
| P1 | #646 DLQ 멱등성/무한루프 방지 | A+C+CR | dlq_replay_meta 테이블 추가 |
| P1 | #647 null → CacheStampedeTimeoutException | A+C+CR | 반영 |
| P1 | #648 TOCTOU race → CAS 기반 수정 | A+C+CR | 반영 |
| P1 | #648 전체 @Scheduled 조사 (16개) | C | 반영 |
| P2 | #650 기존 alert_rules.yml 중복 | CR | 기존 파일에 누락 rule만 추가 |
| P2 | #648 Thread.sleep → LockSupport | CR | 반영 |
