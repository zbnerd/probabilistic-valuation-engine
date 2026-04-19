# Two-Phase Batch UPSERT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split PGMQ worker processing into parallel calculation (Phase 1) + batch write (Phase 2) to reduce DB connection demand from ~300 to ~23 per batch.

**Architecture:** PgmqWorker gets `calculateOnly()` / `batchWrite()` open methods. Subclasses override for two-phase. Phase 1 runs without `@Transactional`, Phase 2 uses batch SQL (archive, cache putAll, view upsert).

**Tech Stack:** Kotlin, Java, PGMQ (PostgreSQL), JdbcTemplate, Virtual Threads

---

## Task 1: CalculationResult Data Class

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/CalculationResult.kt`

- [ ] **Step 1: Create data class**

```kotlin
package maple.expectation.infrastructure.pgmq

import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4

data class CalculationResult(
    val message: PgmqMessage<ExpectationCalcMessage>,
    val response: EquipmentExpectationResponseV4,
    val character: GameCharacter,
)
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :module-infra:compileKotlin --quiet`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/CalculationResult.kt
git commit -m "feat(pgmq): add CalculationResult data class for two-phase batch UPSERT"
```

---

## Task 2: PgmqClient.archiveBatch()

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqClient.kt:140-147` (after existing `archive()`)
- Test: `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqClientBatchArchiveTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.pgmq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.jdbc.core.JdbcTemplate

@ExtendWith(MockitoExtension::class)
@DisplayName("PgmqClient batch archive tests")
class PgmqClientBatchArchiveTest {

    @Mock
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("archiveBatch calls SQL with message ID array")
    fun `archiveBatch archives multiple messages`() {
        // Given
        val objectWriter = com.fasterxml.jackson.databind.ObjectMapper().writer()
        whenever(jdbcTemplate.queryForObject(anyString(), eq(Int::class.java), any(), any()))
            .thenReturn(2)

        // When
        val result = createClient().archiveBatch("test_queue", listOf(1L, 2L))

        // Then
        assertThat(result).isEqualTo(2)
    }

    @Test
    @DisplayName("archiveBatch returns 0 for empty list")
    fun `archiveBatch returns 0 for empty list`() {
        val result = createClient().archiveBatch("test_queue", emptyList())
        assertThat(result).isEqualTo(0)
    }

    private fun createClient(): PgmqClient {
        val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        // P1-8 FIX: PgmqClient constructor signature verified:
        // (jdbcTemplate, objectMapper, executor, config) - from PgmqClient.kt:42-47
        // StubLogicExecutor no-arg constructor from L2CacheMicroBatchAdapterTest.kt:95
        return PgmqClient(jdbcTemplate, objectMapper, StubLogicExecutor(), PgmqConfig())
    }
}
```

> Note: `StubLogicExecutor` already exists in `L2CacheMicroBatchAdapterTest.kt`. Extract to shared test utility if needed, or duplicate inline.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "PgmqClientBatchArchiveTest" --quiet 2>&1 | tail -5`
Expected: FAIL (archiveBatch not defined)

- [ ] **Step 3: Implement archiveBatch()**

Add to `PgmqClient.kt` after `archive()` method:

```kotlin
/**
 * Batch archive multiple messages (BS4)
 *
 * Moves messages from queue table to archive table in a single query.
 * Uses parameterized IN clause to prevent SQL injection.
 *
 * @param queueName Queue name (validated against injection)
 * @param messageIds List of message IDs to archive
 * @return Number of messages archived
 */
@CircuitBreaker(name = "pgmq", fallbackMethod = "archiveBatchFallback")
fun archiveBatch(queueName: String, messageIds: List<Long>): Int {
    if (messageIds.isEmpty()) return 0
    validateQueueName(queueName)
    val context = TaskContext.of("PgmqClient", "ArchiveBatch", "$queueName:${messageIds.size}")
    return executor.executeOrDefault(
        {
            val queueTable = "pgmq.q_$queueName"
            val archiveTable = "pgmq.a_$queueName"
            // P0-1 FIX: Use parameterized placeholders instead of string concatenation
            val placeholders = messageIds.map { "?" }.joinToString(",")
            jdbcTemplate.queryForObject(
                """
                WITH deleted AS (
                    DELETE FROM $queueTable WHERE msg_id IN ($placeholders)
                    RETURNING *
                )
                SELECT COUNT(*) FROM $archiveTable
                """.trimIndent(),
                Int::class.java,
                *messageIds.toTypedArray(),
            ) ?: 0
        },
        0,
        context,
    )
}

private fun archiveBatchFallback(queueName: String, messageIds: List<Long>, e: Throwable): Int {
    log.error("[PGMQ] Circuit Breaker OPEN - archiveBatch fallback: queue={}, count={}", queueName, messageIds.size, e)
    return 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "PgmqClientBatchArchiveTest" --quiet 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqClient.kt module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqClientBatchArchiveTest.kt
git commit -m "feat(pgmq): add archiveBatch() for batch message archival (BS4)"
```

---

## Task 3: L2CacheStrategy.putAll()

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/L2CacheStrategy.kt:47` (after `put()`)
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt:240` (after `put()`)

- [ ] **Step 1: Add putAll() to L2CacheStrategy interface**

Add after `put()` method in `L2CacheStrategy.kt`:

```kotlin
/**
 * Store multiple values in L2 cache (batch write)
 *
 * Default implementation iterates single puts for backward compatibility.
 * Implementations should override for optimized batch writes.
 *
 * @param entries List of (key, value) pairs
 * @param ttlMinutes Time-to-live in minutes
 */
fun putAll(entries: List<Pair<String, Any>>, ttlMinutes: Long) {
    entries.forEach { (key, value) -> put(key, value, ttlMinutes) }
}
```

- [ ] **Step 2: Implement putAll() in PostgresL2CacheStrategy**

Add after `put()` method:

```kotlin
/**
 * Batch put using JdbcTemplate.batchUpdate() (BS5)
 * P1-8 FIX: Use batchUpdate with single-row INSERT instead of
 * multi-value INSERT with update() — avoids parameter array issues.
 */
override fun putAll(entries: List<Pair<String, Any>>, ttlMinutes: Long) {
    if (disableL2Writes.get() == true) return
    if (entries.isEmpty()) return

    val context = TaskContext.of("PostgresL2Strategy", "PutAll", "${entries.size}")

    executor.executeVoidJava({
        putCounter.increment()

        val expiresAt = Timestamp.from(Instant.now().plusSeconds(ttlMinutes * 60))

        val sql = """
            INSERT INTO cache_storage (cache_key, cache_value, expires_at)
            VALUES (?, ?, ?)
            ON CONFLICT (cache_key)
            DO UPDATE SET
                cache_value = EXCLUDED.cache_value,
                expires_at = EXCLUDED.expires_at
        """.trimIndent()

        val batchArgs = entries.map { (key, value) ->
            val typedValue = TypedValue(value)
            val valueBytes: ByteArray = objectMapper.writeValueAsBytes(typedValue)
            arrayOf(key, valueBytes, expiresAt)
        }.toList()

        jdbcTemplate.batchUpdate(sql, batchArgs)

        log.debug("[PostgresL2] PutAll: {} entries, ttl={}min", entries.size, ttlMinutes)
    }, context)
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :module-infra:compileKotlin --quiet`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/L2CacheStrategy.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt
git commit -m "feat(cache): add putAll() batch write to L2CacheStrategy (BS5)"
```

---

## Task 4: ExpectationV4Port.calculateExpectationWriteOnly()

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/inbound/ExpectationV4Port.kt` (add method)
- Modify: `module-app/src/main/java/maple/expectation/application/usecase/ExpectationV4PortAdapter.java` (implement)
- Modify: `module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java` (add method)

- [ ] **Step 1: Add method to ExpectationV4Port interface**

```kotlin
/**
 * Calculate expectation without DB writes (two-phase batch UPSERT Phase 1)
 *
 * Performs: character lookup + Nexon API + preset calculation.
 * Skips: persistence save, view table write, L2 cache write.
 * P2-13 FIX: Typed return instead of Any.
 */
fun calculateExpectationWriteOnly(userIgn: String, force: Boolean, taskId: String?): EquipmentExpectationResponseV4
```

- [ ] **Step 2: Add method to EquipmentExpectationServiceV4**

Add new method (no `@Transactional`):

```java
/**
 * 계산만 수행 (DB write 없음, Phase 1 전용)
 *
 * <p>character 조회 + Nexon API + 프리셋 계산. persistence, view table, L2 cache write 제외.
 * P0-4 FIX: Returns response only. Character lookup done separately in worker.
 */
public EquipmentExpectationResponseV4 calculateExpectationWriteOnly(
    String userIgn, boolean force, @Nullable String taskId) {
  validateInitialized();
  GameCharacter character = findCharacterBypassingWorker(userIgn);
  byte[] equipmentData = loadEquipmentDataAsync(character).join();
  List<PresetExpectation> presetResults =
      calculateAllPresets(equipmentData, character.getCharacterClass());
  PresetExpectation maxPreset = findMaxPreset(presetResults);
  return buildResponse(userIgn, maxPreset, presetResults, false);
}
```

- [ ] **Step 3: Implement in ExpectationV4PortAdapter**

```java
@Override
public EquipmentExpectationResponseV4 calculateExpectationWriteOnly(String userIgn, boolean force, String taskId) {
  return expectationService.calculateExpectationWriteOnly(userIgn, force, taskId);
}
```

- [ ] **Step 4: Compile check**

Run: `./gradlew compileKotlin compileJava --continue --quiet 2>&1 | tail -5`
Expected: SUCCESS (no errors)

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/inbound/ExpectationV4Port.kt module-app/src/main/java/maple/expectation/application/usecase/ExpectationV4PortAdapter.java module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java
git commit -m "feat(expectation): add calculateExpectationWriteOnly() for Phase 1 (BS2)"
```

---

## Task 5: PgmqWorker Two-Phase Orchestration

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`

This is the core change. Add `calculateOnly()` / `batchWrite()` open methods and modify `processMessages()` for two-phase.

- [ ] **Step 1: Add open methods to PgmqWorker**

Add after `preWarmBatch()`:

```kotlin
/**
 * Whether this worker supports two-phase batch processing.
 * P1-9 FIX: Explicit opt-in via property instead of runtime probe.
 */
protected open val supportsTwoPhase: Boolean = false

/**
 * Phase 1: Calculate without DB writes (BS2)
 *
 * Override in subclasses to enable two-phase batch processing.
 * Default: returns null → falls back to single-phase process() per message.
 */
protected open fun calculateOnly(message: PgmqMessage<T>): Any? = null

/**
 * Phase 2: Batch write calculated results (BS4/BS5)
 *
 * Override in subclasses to batch persist results from Phase 1.
 * Default: no-op → single-phase fallback.
 */
protected open fun batchWrite(results: List<CalculationResult>) {}
```

- [ ] **Step 2: Modify processMessages() for two-phase**

Replace the parallel processing block inside `processMessages()`:

```kotlin
@Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
fun processMessages() {
    if (!lifecycleWrapper.beforeTask()) return
    if (!workerSettings.enabled) {
        lifecycleWrapper.afterTask()
        return
    }

    val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)

    executor.executeVoid({
        val batchSize = workerSettings.batchSize ?: config.common.batchSize
        val visibilityTimeout = config.common.visibilityTimeoutSec

        val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)

        metrics.updateQueueDepth(pgmqClient.queueLength(queueName))

        if (messages.isEmpty()) {
            lifecycleWrapper.afterTask()
            return@executeVoid
        }

        log.debug("[{}] Processing {} messages", queueName, messages.size)

        messages.forEach { message ->
            metrics.inflightIncrement()
            metrics.recordWaitDuration(message.enqueuedAt)
        }

        preWarmBatch(messages)

        // P1-9 FIX: Use explicit property instead of runtime probe
        if (supportsTwoPhase) {
            processBatchTwoPhase(messages)
        } else {
            processBatchSinglePhase(messages)
        }
    }, context)
}

/**
 * P0-2 FIX: Thread-safe result collection.
 * P1-5 FIX: No try-catch — LogicExecutor handles exceptions.
 * P1-7 FIX: Lambda extracted to private method.
 */
private fun processBatchTwoPhase(messages: List<PgmqMessage<T>>) {
    // Phase 1: Parallel calculation — collect futures, no shared mutable state
    val futures = messages.map { message ->
        CompletableFuture.supplyAsync(
            { executePhaseOne(message) },
            workerPool,
        )
    }

    CompletableFuture.allOf(*futures.toTypedArray())
        .thenRun {
            // P0-2 FIX: Collect results AFTER all futures complete (thread-safe)
            val (successes, failures) = futures
                .map { it.join() }
                .partition { it.first != null }

            val successResults = successes.mapNotNull { it.first as? CalculationResult }
            val failedMessages = failures.map { it.second }

            // Phase 2: Batch write
            if (successResults.isNotEmpty()) {
                batchWrite(successResults)
                successResults.forEach {
                    metrics.success.increment()
                    metrics.inflightDecrement()
                }
            }

            // Handle failures with existing retry/DLQ logic
            failedMessages.forEach { message ->
                processSingleMessage(message)
            }

            lifecycleWrapper.afterTask()
        }
        .exceptionally { ex ->
            log.warn("[{}] Batch completion error: {}", queueName, ex.message)
            lifecycleWrapper.afterTask()
            null
        }
}

/** P1-5 FIX: LogicExecutor wraps calculation. P1-7 FIX: Extracted private method. P0-1 FIX(R3): executeWithFinally for concurrentDecrement (Zero Try-Catch compliance). */
private fun executePhaseOne(message: PgmqMessage<T>): Pair<Any?, PgmqMessage<T>> {
    val context = TaskContext.of("PgmqWorker", "CalculateOnly", "$queueName:${message.messageId}")
    return executor.executeWithFinally(
        task = {
            metrics.concurrentIncrement()
            val result = executor.executeOrDefault(
                { calculateOnly(message) },
                null,
                context,
            )
            result to message
        },
        finallyBlock = { metrics.concurrentDecrement() },
        context = context,
    )
}

private fun processBatchSinglePhase(messages: List<PgmqMessage<T>>) {
    val futures = messages.map { message ->
        CompletableFuture.supplyAsync({
            processSingleMessage(message)
        }, workerPool)
    }
    CompletableFuture.allOf(*futures.toTypedArray())
        .exceptionally { ex ->
            log.warn("[{}] Batch completion error: {}", queueName, ex.message)
            null
        }
        .thenRun { lifecycleWrapper.afterTask() }
}
```

> Note: `calculateOnly()` returns `Any?` because PgmqWorker is generic (`T`). Subclasses return `CalculationResult`. The `as? CalculationResult` safe cast in `executePhaseOne()` handles type checking at runtime. Only `AbstractExpectationCalcWorker` overrides this method.

- [ ] **Step 3: Compile check**

Run: `./gradlew :module-infra:compileKotlin --quiet 2>&1 | tail -5`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt
git commit -m "feat(pgmq): add two-phase batch processing to PgmqWorker (BS1/BS2)"
```

---

## Task 6: AbstractExpectationCalcWorker Overrides

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`

- [ ] **Step 0: Add constructor dependencies (P0-5, P1-6, P0-2)**

P0-5 FIX: Add required dependencies for two-phase batch processing to constructor:
P1-6 FIX: Add `expectationCache` for L1 population after L2 write:
P0-2 FIX(R3): Use CacheManager instead of direct Cache injection. Cache instances are created dynamically by TieredCacheManager, not as named beans. Matches ExpectationCacheCoordinator pattern: cacheManagerPort.getCache(CACHE_NAME)

```kotlin
abstract class AbstractExpectationCalcWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val expectationPort: ExpectationV4Port,
    private val characterOcidPort: CharacterOcidPort,
    private val equipmentFanOutPort: EquipmentFanOutPort,
    private val preWarmExecutor: Executor,
    // P0-5 FIX: Two-phase batch processing dependencies
    private val gameCharacterFacade: GameCharacterFacade,
    private val l2CacheStrategy: L2CacheStrategy,
    private val cacheProperties: CacheProperties,
    // P0-2 FIX(R4): Use CacheManagerPort for DIP compliance (not Spring CacheManager).
    // Matches ExpectationCacheCoordinator pattern: cacheManagerPort.getCache(CACHE_NAME)
    private val cacheManagerPort: CacheManagerPort,
) : PgmqWorker<ExpectationCalcMessage>(...)
```

- [ ] **Step 1: Override calculateOnly()**

P0-4 FIX: Port returns `EquipmentExpectationResponseV4`. Character resolved separately via `GameCharacterFacade`.
Since `calculateExpectationWriteOnly()` already called `findCharacterBypassingWorker()` internally,
the character is cached in L1 — the second lookup hits cache (0 DB cost).

```kotlin
override val supportsTwoPhase: Boolean = true

override fun calculateOnly(message: PgmqMessage<ExpectationCalcMessage>): Any? {
    val request = message.payload
    val context = TaskContext.of(workerName, "CalculateOnly", request.userIgn)

    return executor.executeOrDefault({
        workerLog.info("[{}] Phase 1 calculateOnly: userIgn={}", workerName, request.userIgn)

        val response = expectationPort.calculateExpectationWriteOnly(
            request.userIgn,
            request.forceRecalculation,
            message.messageId.toString(),
        )

        // P0-1 FIX: Character was already loaded by calculateExpectationWriteOnly()
        // and cached in L1. Use findCharacterByUserIgn() for L1 cache hit (0 DB cost).
        val character = gameCharacterFacade.findCharacterByUserIgn(request.userIgn)

        CalculationResult(
            message = message,
            response = response,
            character = character,
        )
    }, null, context)
}
```

> **Dependency:** `AbstractExpectationCalcWorker` constructor needs `GameCharacterFacade` injection added (Step 0 참조).
> `gameCharacterFacade.findCharacterByUserIgn()` returns cached `GameCharacter` (L1 hit after service call).

- [ ] **Step 2: Override batchWrite() — P0-3 FIX: @Transactional for atomicity**

P0-3 FIX: `batchWrite()` wrapped in `@Transactional`. If view upsert or L2 cache write fails,
the entire batch rolls back. Archive is last — only succeeds if all writes succeed.

```kotlin
@Transactional("transactionManager")
override fun batchWrite(results: List<CalculationResult>) {
    if (results.isEmpty()) return

    val context = TaskContext.of(workerName, "BatchWrite", "${results.size}")
    // P1-7 FIX: executor.executeVoid() propagates exceptions (does not swallow).
    // @Transactional proxy sees the exception and triggers rollback.
    // LogicExecutor contract: executeVoid throws on failure.
    executor.executeVoid({
        workerLog.info("[{}] Phase 2 batchWrite: {} results", workerName, results.size)

        // P0-3 FIX: Order matters — archive LAST so failures roll back writes
        // 1. Batch view table upsert
        batchViewUpsert(results)

        // 2. Batch L2 cache put
        batchL2CachePut(results)

        // 3. Batch PGMQ archive (last — only if all writes succeed)
        val messageIds = results.map { it.message.messageId }
        val archived = pgmqClient.archiveBatch(queueName, messageIds)
        workerLog.info("[{}] Batch archived: {}/{}", workerName, archived, messageIds.size)
    }, context)
}
```

- [ ] **Step 3: Add helper methods**

```kotlin
private fun batchViewUpsert(results: List<CalculationResult>) {
    val viewService = viewQueryServiceProvider?.getIfAvailable() ?: return
    results.forEach { result ->
        val entity = viewTransformer.toEntityFromResponse(
            result.message.payload.userIgn,
            result.character,
            result.response,
            result.message.messageId.toString(),
        )
        viewService.upsert(entity)
    }
}

private fun batchL2CachePut(results: List<CalculationResult>) {
    // P0-3 FIX(R4): PostgresL2CacheStrategy.put() stores key as-is (no auto-prefix).
    // evictAll() depends on "{cacheName}:v1:" prefix — must use full key format.
    // generateKey() returns "{cacheName}:{keyVersion}:{actualKey}".
    val entries = results.map { result ->
        val cacheKey = "expectationV4:${cacheProperties.keyVersion}:${result.message.payload.userIgn}"
        cacheKey to result.response
    }
    val spec = cacheProperties.specs["expectationV4"]
        ?: throw IllegalStateException("Cache spec 'expectationV4' not configured in cache.specs")
    l2CacheStrategy.putAll(entries, spec.l2TtlMinutes.toLong())

    // P1-2 FIX(R4): Skip L1 populate. Raw EquipmentExpectationResponseV4 causes ClassCastException
    // when HTTP path reads via ExpectationCacheCoordinator (expects compressed Base64 String).
    // L2→L1 promotion handles it: next HTTP read hits L2, TieredCache populates L1 correctly.
}
```

> Note: `batchViewUpsert` currently iterates individual upserts. A true batch upsert requires a new method on `CharacterViewQueryServicePostgres`. This is acceptable for the first iteration — the main connection savings come from `archiveBatch()` and removing `@Transactional` from the calculation path.

> **P0-2/P1-6 Dependency (R4):** `AbstractExpectationCalcWorker` needs `CacheManagerPort` injection added (not Spring `CacheManager`). L1 populate removed — rely on L2→L1 promotion on next read.
>
> **Dependency:** `AbstractExpectationCalcWorker` needs `L2CacheStrategy` and `CacheProperties` injection added.

- [ ] **Step 4: Compile check**

Run: `./gradlew compileKotlin compileJava --continue --quiet 2>&1 | tail -5`
Expected: SUCCESS (may need adjustments for missing dependencies)

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt
git commit -m "feat(worker): override calculateOnly/batchWrite for two-phase (BS6)"
```

---

## Task 7: Metrics Timing (BS7)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` (metrics in processBatchTwoPhase)

- [ ] **Step 1: Verify metrics timing in processBatchTwoPhase()**

P0-4 FIX: Already handled in Task 5 Step 2. Verify:
- `concurrent` incremented/decremented in Phase 1 (calculation scope) via try-finally
- `inflight` decremented in Phase 2 (after batch write) in processBatchTwoPhase()
- `success` incremented in Phase 2 (after batch write) in processBatchTwoPhase()

The implementation in Task 5 already handles all metrics correctly:
- Phase 1: `concurrentIncrement()` / `concurrentDecrement()` in the try-finally
- Phase 2: `success.increment()` and `inflightDecrement()` after batch write
- Failed messages: `inflightDecrement()` happens in `processSingleMessage()`

No additional changes needed.

- [ ] **Step 2: Compile + existing test check**

Run: `./gradlew :module-infra:compileKotlin --quiet 2>&1 | tail -5`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
# No commit needed — Task 5 already handles metrics timing correctly
```

---

## Task 8: Full Compile + Test Verification

- [ ] **Step 1: Full compile**

Run: `./gradlew compileKotlin compileJava --continue --quiet 2>&1 | tail -5`
Expected: SUCCESS

- [ ] **Step 2: Run all tests**

Run: `./gradlew test --quiet 2>&1 | tail -10`
Expected: All tests pass (excluding integration tests tagged `pgmq`)

- [ ] **Step 3: Spotless check**

Run: `./gradlew spotlessCheck --quiet 2>&1 | tail -5`
Expected: SUCCESS (if fails, run `./gradlew spotlessApply`)

---

## Summary of Changes

| File | Change | BS |
|------|--------|----|
| `CalculationResult.kt` | NEW data class | BS6 |
| `PgmqClient.kt` | +`archiveBatch()` | BS4 |
| `L2CacheStrategy.kt` | +`putAll()` interface method | BS5 |
| `PostgresL2CacheStrategy.kt` | +`putAll()` implementation | BS5 |
| `ExpectationV4Port.kt` | +`calculateExpectationWriteOnly()` | BS2 |
| `ExpectationV4PortAdapter.java` | implement new method | BS2 |
| `EquipmentExpectationServiceV4.java` | +`calculateExpectationWriteOnly()` | BS2 |
| `PgmqWorker.kt` | +`calculateOnly()`, +`batchWrite()`, two-phase orchestration | BS1/BS2/BS7 |
| `AbstractExpectationCalcWorker.kt` | override both methods | BS6 |

**Connection demand: 300 → ~23 per batch of 50 messages.**

---

## Consensus Review Fixes (2026-04-19)

Architect, Critic, Code-Reviewer 3-agent review. 15 issues identified.

### P0 Fixes Applied (Round 2)

| ID | Issue | Fix |
|----|-------|-----|
| P0-1 | SQL injection in archiveBatch | Parameterized `IN (?, ?, ...)` with spread args |
| P0-2 | MutableList ConcurrentModification | `allOf().join()` + `partition` — no shared mutable state |
| P0-2 | Missing try-finally in executePhaseOne() | Added `try { ... } finally { concurrentDecrement() }` |
| P0-3 | Phase 2 partial write | `@Transactional` on `batchWrite()`, archive last |
| P0-3 | Cache key format confusion | Documented: `put()` expects full key format `{cacheName}:v1:{actualKey}` from `generateKey()` |
| P0-4 | Wrong facade method call | Changed `getOrFetch()` to `findCharacterByUserIgn()` for L1 cache hit |
| P0-4 | Duplicate inflightDecrement in Task 7 | Removed — already handled in Task 5 `processBatchTwoPhase()` |
| P0-5 | Missing constructor dependencies | Added `GameCharacterFacade`, `L2CacheStrategy`, `CacheProperties` to constructor |

### P0 Fixes Applied (Round 3)

| ID | Issue | Fix |
|----|-------|-----|
| P0-1(R3) | try-finally violates Zero Try-Catch Policy | Changed to `executor.executeWithFinally()` for proper LogicExecutor pattern |
| P0-2(R3) | Direct Cache bean injection (@Qualifier) | Changed to `CacheManager` injection; caches created dynamically by TieredCacheManager |
| P0-3(R3) | Cache key prefix duplication | Changed to bare `userIgn`; CacheManager handles namespacing via cache name |
| P0-4(R3) | L1 cache raw object ClassCastException risk | Documented: validate during implementation; may skip L1 populate and rely on L2→L1 promotion |

### P1 Fixes Applied

| ID | Issue | Fix |
|----|-------|-----|
| P1-5 | Try-catch in lambda | LogicExecutor `executeOrDefault()` wraps calculation |
| P1-6 | L1 cache 미기록 → 다음 HTTP 요청 cache miss | Added `expectationCache.put()` to populate L1 after L2 write |
| P1-7 | @Transactional + executor.executeVoid() 상호작용 검증 | Added comment: executeVoid propagates exceptions, @Transactional sees them |
| P1-8 | StubLogicExecutor 테스트 생성자 수정 | Verified PgmqClient constructor: (jdbcTemplate, objectMapper, executor, config) |
| P1-9 | TTL 출처 명확화 | Changed `cacheProperties.ttlMinutes` to `cacheProperties.specs["expectationV4"]!!.l2TtlMinutes.toLong()` |
| P1-10 | DIP violation (unsafe cast) | `supportsTwoPhase` property + `as? CalculationResult` safe cast |
| P1-11 | Lambda Hell (20-line lambda) | Extracted to `executePhaseOne()` private method |
| P1-12 | putAll() uses update() | Changed to `jdbcTemplate.batchUpdate()` with single-row INSERT |
| P1-13(R3) | !! assertion on cache spec | Changed to safe check with `IllegalStateException` |
| P1-13 | supportsTwoPhase detection | Explicit `supportsTwoPhase: Boolean` property |

### P2 Deferred

| ID | Issue | Status |
|----|-------|--------|
| P2-10 | BS1 batch cache check missing from plan | Acceptable: preWarmBatch partially covers; dedicated task in follow-up |
| P2-11 | Server crash between phases = wasted compute | Acceptable: PGMQ visibility timeout ensures reprocessing (at-least-once) |
| P2-12 | Metrics inflight decrement timing | Handled in `executePhaseOne()` + Phase 2 callback |
| P2-13 | Return type `Any` vs typed | Port returns typed `EquipmentExpectationResponseV4`; `calculateOnly()` keeps `Any?` for generic compatibility |
