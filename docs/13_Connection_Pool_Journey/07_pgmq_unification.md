# 7장: PGMQ 통합 — 하나의 풀로 모든 것을

> "42개의 파일이 삭제되었다. 그 자리에 PGMQ 하나가 들어왔다."

## 2026년 3월 31일 ~ 4월 1일, 5 Phase 마이그레이션

Outbox 3개를 PGMQ로 통합하는 작업을 5 Phase에 걸쳐 수행했다. 각 Phase는 독립적으로 배포 가능하도록 설계되었다.

```
Phase 0: 사전 준비        (PR #685) — 3월 31일
Phase 1: Event Outbox 제거 (PR #686) — 3월 31일
Phase 2: Donation Outbox 제거 (PR #687) — 3월 31일
Phase 3: Nexon API Outbox 제거 (PR #688) — 4월 1일
Phase 4: 정리 (Cleanup)    (PR #689) — 4월 1일
Phase 5: LikeSyncWorker 제거 (PR #690) — 4월 1일
```

## Phase 0: 사전 준비 (PR #685)

가장 중요한 작업이었다. **PGMQ가 호출자의 트랜잭션에 참여하는지 검증**해야 했다.

### 트랜잭션 원자성 검증

```kotlin
// PgmqTransactionAtomicityTest.kt
@Test
fun `pgmq_send가_호출자_TX에_참여하여_ROLLBACK시_메시지가_사라짐`() {
    // TX 내에서 send 후 강제 롤백
    try {
        transactionTemplate.execute { status ->
            pgmqClient.send("tx_test_queue", """{"test":"rollback_case"}""")
            status.setRollbackOnly()
            null
        }
    } catch (_: Exception) {}

    // 롤백된 메시지는 큐에 없어야 함
    val messages = pgmqClient.read<String>("tx_test_queue", String::class.java, 10, 0)
    assertThat(messages.none { it.payload?.contains("rollback_case") == true }).isTrue
}
```

**결과: 통과.** `pgmq.send()`는 호출자의 트랜잭션에 참여했다. ROLLBACK 시 메시지도 함께 사라졌다.

→ Outbox 없이도 원자성이 보장됨. Outbox 제거 가능!

### TX 보장: 인라인 체크

`PgmqClient.send()`에 트랜잭션 활성 검증을 추가했다:

```kotlin
// PgmqClient.kt
fun <T : Any> send(queueName: String, message: T): Long {
    // TX 활성 검증 — send() 내부에서 직접 확인 (AOP는 우회 가능)
    if (config.transactionCheckEnabled &&
        !TransactionSynchronizationManager.isActualTransactionActive()) {
        throw PgmqPublishException(
            "pgmqClient.send('$queueName') must be called within @Transactional."
        )
    }
    // ... send 로직
}
```

AOP가 아닌 **send() 메서드 내부**에서 검증. self-invocation, 람다, Kotlin inline 함수에서도 우회되지 않는다.

### setVisibilityTimeout 추가

Exponential Backoff 재시도를 위해 필요:

```kotlin
fun setVisibilityTimeout(queueName: String, messageId: Long, timeoutSeconds: Long): Boolean {
    val safeSeconds = timeoutSeconds.coerceIn(1, 86400) // 최대 1일
    return jdbcTemplate.queryForObject(
        "SELECT pgmq.set_visibility_timeout(?, ?, ? * interval '1 second')",
        Boolean::class.java,
        queueName, messageId, safeSeconds
    ) ?: false
}
```

## Phase 1: Event Outbox 제거 (PR #686)

가장 단순했다. Event Outbox는 결국 PGMQ에 발행하는 브릿지 역할만 했다.

```
Before:
  Service → @Transactional { INSERT INTO event_outbox }
  → EventOutboxScheduler (10s) poll
  → EventOutboxProcessor → PgmqStreamPublisher → PGMQ

After:
  Service → @Transactional {
      INSERT INTO business_table;
      pgmqClient.send("v5_event_queue", msg);  ← 직접 발행!
  }
```

삭제 파일: ~10개 (Entity, Repository, Processor, FetchFacade, Scheduler, Metrics)

**커넥션 절감**: 10초마다 폴링에 사용하던 1~3개 커넥션 제거. 대신 비즈니스 트랜잭션 내에서 `pgmq.send()`를 호출하므로 **추가 커넥션 없음**.

## Phase 2: Donation Outbox 제거 (PR #687)

DonationWorker가 이미 PGMQ 기반으로 존재했다. Service만 변경.

```
Before:
  DonationService → @Transactional {
      save Donation;
      save DonationOutbox;  ← Outbox INSERT (커넥션 추가 사용)
  }
  → OutboxScheduler (15s) poll
  → OutboxProcessor → sendNotification()

After:
  DonationService → @Transactional {
      save Donation;
      pgmqClient.send("donation_queue", msg);  ← 직접 발행!
  }
  → DonationWorker (PgmqWorker) → alertPublisher.sendInfo()
```

### 무결성 검증 추가

Outbox의 Content Hash 검증을 Worker로 이관:

```kotlin
// DonationWorker.process()
override fun process(message: PgmqMessage<DonationRequest>): Boolean {
    return executor.executeOrDefault({
        val canonicalPayload = "${request.donationId}|${request.userId}|${request.amount}"
        val expectedHash = ContentHashUtil.computeV1(
            request.donationId.toString(), "DONATION_ALERT", canonicalPayload
        )
        if (request.contentHash != null && request.contentHash != expectedHash) {
            log.error("[DonationWorker] Content hash mismatch")
            return@executeOrDefault false
        }
        alertPublisher.sendInfo("☕ 새로운 후원", messageText)
        true
    }, false, context)
}
```

## Phase 3: Nexon API Outbox 제거 (PR #688)

가장 복잡했다. 외부 API 재시도 로직이 Outbox Entity에 밀착되어 있었다.

### Port 구현체 교체 전략

```
Before:
  NexonApiOutboxScheduler → NexonApiOutboxProcessorPort
                              ↑ 구현
                         NexonApiOutboxProcessor (Outbox 테이블 폴링)
                           ├── NexonApiOutboxFetchFacade (JPA SKIP LOCKED)
                           ├── NexonApiRetryClient (Entity 파라미터)
                           └── NexonApiDlqHandler

After:
  NexonApiPgmqProcessor (독립 @Component, PgmqWorker 비상속)
    ├── PgmqClient (visibility timeout으로 Exponential Backoff)
    ├── NexonApiClient (기존 재사용)
    └── NexonApiPgmqMetrics
```

Kotlin은 단일 상속만 지원하므로 `PgmqWorker`를 상속하지 않고, 대신 `PgmqClient`를 직접 호출:

```kotlin
@Component
@ConditionalOnProperty(name = ["nexon.retry.backend"], havingValue = "pgmq", matchIfMissing = true)
class NexonApiPgmqProcessor(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
    private val nexonApiClient: NexonApiClient,
    private val metrics: NexonApiPgmqMetrics,
) : NexonApiOutboxProcessorPort {

    @Scheduled(fixedDelayString = "\${nexon.retry.polling-interval-ms:5000}")
    override fun pollAndProcess() {
        // PgmqClient.read() → 처리 → archive/delete
    }

    // Exponential Backoff: visibility timeout 활용
    fun handleRetry(message: PgmqMessage<NexonRetryMessage>, error: String) {
        val retryCount = message.payload.retryCount + 1
        val backoffSeconds = min(2.0.pow(retryCount.toDouble()).toLong() * 30, 3600)
        pgmqClient.setVisibilityTimeout(queueName, message.messageId, backoffSeconds)
    }
}
```

### Entity 로직 이관

| Entity 로직 | PGMQ 대체 |
|-------------|----------|
| `markFailed()` → `retryCount++` + `2^retryCount * 30s` | `setVisibilityTimeout()` |
| `shouldMoveToDlq()` → maxRetries(10) 초과 | Worker에서 `retryCount >= 10` 체크 |
| `verifyIntegrity()` → SHA-256 content hash | `ContentHashUtil.verify()` |
| `forceDeadLetter()` → DLQ 테이블 | File backup → Discord alert → `pgmq.delete()` |

## Phase 4: 정리 (PR #689)

### Port Deprecation

```kotlin
@Deprecated("Outbox 제거 완료. PGMQ Worker로 대체됨. 다음 릴리즈에서 삭제 예정.")
interface OutboxProcessorPort { ... }

@Deprecated("PGMQ 통합 완료. 다음 릴리즈에서 삭제 예정.")
interface NexonApiOutboxProcessorPort { ... }
```

### Outbox 테이블 삭제

```sql
DROP TABLE IF EXISTS event_outbox;
DROP TABLE IF EXISTS donation_outbox;
DROP TABLE IF EXISTS nexon_api_outbox;
```

### PGMQ Archive 보관 정책

```kotlin
// PgmqArchiveCleanupScheduler.kt
@Scheduled(cron = "\${pgmq.archive.cleanup.cron:0 0 3 * * *}")  // 매일 새벽 3시
fun cleanupArchived() {
    val queues = listOf("calculation_queue", "donation_queue", "nexon_retry_queue")
    queues.forEach { queue ->
        jdbcTemplate.update(
            "DELETE FROM pgmq.a_${queue} WHERE created_at < NOW() - INTERVAL '30 days'"
        )
    }
}
```

## Phase 5: LikeSyncWorker 제거 (PR #690)

DB Trigger(`fn_like_count_trigger`)가 `like_count`를 자동 증감하므로 Worker가 필요 없어졌다.

```kotlin
// LikeSyncWorker.process() — 제거 직전 상태
// #664: DB Trigger가 자동 증감하므로 app-level increment는 불필요
log.info("[LikeSyncWorker] Acknowledged stale message (trigger handles count)...")
return true
```

Worker, Producer, Request DTO, 큐 정의 모두 삭제.

## 최종 아키텍처

```
Before (Outbox + PGMQ 병존):
┌─────────────────────────────────────────────────┐
│  Service Layer                                   │
│    @Transactional {                              │
│      businessRepo.save(data)                     │ ← HikariCP conn 1
│      outboxRepo.save(Outbox(status=PENDING))     │ ← HikariCP conn 1 (same)
│    }                                             │
│                                                  │
│  3 Outbox Schedulers (@Scheduled 10s/15s):       │
│    EventOutboxScheduler ──→ SELECT SKIP LOCKED   │ ← HikariCP conn 2
│    DonationOutboxScheduler ──→ SELECT SKIP LOCKED│ ← HikariCP conn 3
│    NexonApiOutboxScheduler ──→ SELECT SKIP LOCKED│ ← HikariCP conn 4
│    └── UPDATE status, pgmq.send()                │ ← conn 2/3/4 (same TX)
│                                                  │
│  5 PGMQ Workers:                                 │
│    CalculationWorker ──→ pgmq.read()             │ ← HikariCP conn 5
│    DonationWorker ──→ pgmq.read()                │ ← HikariCP conn 6
│    LikeSyncWorker ──→ pgmq.read()                │ ← HikariCP conn 7
│    ExpectationCalcWorker ──→ pgmq.read()         │ ← HikariCP conn 8
│    ExpectationCalcLowWorker ──→ pgmq.read()      │ ← HikariCP conn 9
│                                                  │
│  HikariCP: 9+ connections 상시 점유 (of 25)     │
│  → 36%를 스케줄러/워커가 소비                    │
└─────────────────────────────────────────────────┘

After (PGMQ only):
┌─────────────────────────────────────────────────┐
│  Service Layer                                   │
│    @Transactional {                              │
│      businessRepo.save(data)                     │ ← HikariCP conn 1
│      pgmqClient.send("queue", message)           │ ← HikariCP conn 1 (same TX!)
│    }                                             │
│                                                  │
│  PGMQ Workers (모두 PgmqWorker 상속):            │
│    CalculationWorker                             │
│    DonationWorker                                │ ← 동일 HikariCP 풀
│    ExpectationCalcWorker                         │    폴링 간 유휴 커넥션
│    ExpectationCalcLowWorker                      │    다른 요청에 재사용 가능
│                                                  │
│  NexonApiPgmqProcessor (독립 @Component):       │
│    pgmqClient.read → 처리 → archive/delete      │ ← 동일 HikariCP 풀
│                                                  │
│  HikariCP: 25 connections 중                     │
│  → Worker 폴링은 fixedDelay (이전 처리 완료 후) │
│  → 유휴 시 모든 커넥션을 비즈니스 요청에 사용   │
└─────────────────────────────────────────────────┘
```

## 삭제된 파일 총계

| Phase | 삭제 파일 | 커넥션 절감 |
|-------|----------|------------|
| Phase 1 (Event) | ~10개 | ~2-3 connections |
| Phase 2 (Donation) | ~10개 | ~2-3 connections |
| Phase 3 (Nexon API) | ~12개 | ~2-3 connections |
| Phase 4 (Cleanup) | ~6개 | (정리) |
| Phase 5 (LikeSync) | ~4개 | ~1-2 connections |
| **합계** | **~42개** | **~7-11 connections** |

---

**다음 장**: [8장 — 코드로 보는 여정: 스케줄러 통합 과정](./08_code_story.md)
