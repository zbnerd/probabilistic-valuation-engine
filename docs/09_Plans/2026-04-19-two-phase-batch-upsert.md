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
        val executor = maple.expectation.infrastructure.executor.LogicExecutor.Companion
        // Use reflection or test config to create PgmqClient with mock jdbcTemplate
        // Minimal: just test the SQL generation logic
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
 * Much more efficient than calling archive() individually.
 *
 * @param queueName Queue name
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
            val ids = messageIds.joinToString(",")
            jdbcTemplate.queryForObject(
                """
                WITH deleted AS (
                    DELETE FROM $queueTable WHERE msg_id IN ($ids)
                    RETURNING *
                )
                SELECT COUNT(*) FROM $archiveTable
                """.trimIndent(),
                Int::class.java,
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
 * Batch put using multi-value UPSERT (BS5)
 */
override fun putAll(entries: List<Pair<String, Any>>, ttlMinutes: Long) {
    if (disableL2Writes.get() == true) return
    if (entries.isEmpty()) return

    val context = TaskContext.of("PostgresL2Strategy", "PutAll", "${entries.size}")

    executor.executeVoidJava({
        putCounter.increment()

        val expiresAt = Timestamp.from(Instant.now().plusSeconds(ttlMinutes * 60))

        // Use batch INSERT ... ON CONFLICT for efficiency
        val placeholders = entries.map { "(?, ?, ?)" }.joinToString(", ")
        val sql = """
            INSERT INTO cache_storage (cache_key, cache_value, expires_at)
            VALUES $placeholders
            ON CONFLICT (cache_key)
            DO UPDATE SET
                cache_value = EXCLUDED.cache_value,
                expires_at = EXCLUDED.expires_at
        """.trimIndent()

        val args = entries.flatMap { (key, value) ->
            val typedValue = TypedValue(value)
            val valueBytes: ByteArray = objectMapper.writeValueAsBytes(typedValue)
            listOf(key, valueBytes, expiresAt)
        }

        jdbcTemplate.update(sql, *args.toTypedArray())

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
 * Returns raw calculation result for batch write in Phase 2.
 */
fun calculateExpectationWriteOnly(userIgn: String, force: Boolean, taskId: String?): Any
```

- [ ] **Step 2: Add method to EquipmentExpectationServiceV4**

Add new method (no `@Transactional`):

```java
/**
 * 계산만 수행 (DB write 없음, Phase 1 전용)
 *
 * <p>character 조회 + Nexon API + 프리셋 계산. persistence, view table, L2 cache write 제외.
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
public Object calculateExpectationWriteOnly(String userIgn, boolean force, String taskId) {
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

        // Check if subclass supports two-phase
        val supportsTwoPhase = calculateOnly(messages.first()) != null || messages.isEmpty()

        if (supportsTwoPhase && messages.isNotEmpty()) {
            processBatchTwoPhase(messages)
        } else {
            processBatchSinglePhase(messages)
        }
    }, context)
}

private fun processBatchTwoPhase(messages: List<PgmqMessage<T>>) {
    val successes = mutableListOf<CalculationResult>()
    val failures = mutableListOf<PgmqMessage<T>>()

    // Phase 1: Parallel calculation (no DB writes)
    val futures = messages.map { message ->
        CompletableFuture.supplyAsync({
            metrics.concurrentIncrement()
            try {
                val result = calculateOnly(message)
                if (result != null) {
                    successes.add(result as CalculationResult)
                    true
                } else {
                    failures.add(message)
                    false
                }
            } catch (e: Exception) {
                log.warn("[{}] calculateOnly failed: msgId={}, error={}", queueName, message.messageId, e.message)
                failures.add(message)
                false
            } finally {
                metrics.concurrentDecrement()
            }
        }, workerPool)
    }

    CompletableFuture.allOf(*futures.toTypedArray())
        .thenRun {
            // Phase 2: Batch write
            if (successes.isNotEmpty()) {
                batchWrite(successes)
                successes.forEach { metrics.success.increment() }
            }

            // Handle failures with existing retry/DLQ logic
            failures.forEach { message ->
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

> Note: `CalculationResult` is typed as `Any` in `calculateOnly()` because PgmqWorker is generic (`T`). The cast to `CalculationResult` is safe because only `AbstractExpectationCalcWorker` (which works with `ExpectationCalcMessage`) overrides `calculateOnly()` and returns `CalculationResult`.

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

- [ ] **Step 1: Override calculateOnly()**

```kotlin
@Suppress("UNCHECKED_CAST")
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

        // Extract character from response for Phase 2 view table write
        // Use the same character resolution as the normal path
        val character = resolveCharacter(request.userIgn)

        CalculationResult(
            message = message,
            response = response as maple.expectation.web.dto.v4.EquipmentExpectationResponseV4,
            character = character,
        )
    }, null, context)
}

private fun resolveCharacter(userIgn: String): maple.expectation.core.domain.model.character.GameCharacter {
    return characterOcidPort.resolveCharacter(userIgn)
        ?: throw maple.expectation.error.exception.CharacterNotFoundException(userIgn)
}
```

> Note: `resolveCharacter()` needs a method on `CharacterOcidPort` or equivalent. Check what's available. If `GameCharacterFacade.getOrFetch()` exists, use that. The exact method depends on what `findCharacterBypassingWorker()` calls internally. Adjust accordingly.

- [ ] **Step 2: Override batchWrite()**

```kotlin
override fun batchWrite(results: List<CalculationResult>) {
    if (results.isEmpty()) return

    val context = TaskContext.of(workerName, "BatchWrite", "${results.size}")
    executor.executeVoid({
        workerLog.info("[{}] Phase 2 batchWrite: {} results", workerName, results.size)

        // 1. Batch view table upsert
        batchViewUpsert(results)

        // 2. Batch L2 cache put
        batchL2CachePut(results)

        // 3. Batch PGMQ archive
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
    // L2 cache write via TieredCache — use cache coordinator or direct L2
    // This depends on how TieredCache exposes batch put
    // For now, use individual puts within a single connection scope
    results.forEach { result ->
        expectationPort.cacheResult(result.message.payload.userIgn, result.response)
    }
}
```

> Note: `batchViewUpsert` currently iterates individual upserts. A true batch upsert requires a new method on `CharacterViewQueryServicePostgres`. This is acceptable for the first iteration — the main connection savings come from `archiveBatch()` and removing `@Transactional` from the calculation path. Batch view upsert can be optimized in a follow-up.

> Note: `cacheResult()` doesn't exist on `ExpectationV4Port` yet. Either add it, or use `CacheManagerPort` directly. The simplest approach is to call the existing `calculateExpectation()` which handles caching, but that defeats the purpose. Instead, inject `CacheManagerPort` into `AbstractExpectationCalcWorker` and write to cache directly.

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

- [ ] **Step 1: Adjust metrics timing in processBatchTwoPhase()**

Already partially handled in Task 5. Verify:
- `concurrent` incremented/decremented in Phase 1 (calculation scope)
- `inflight` decremented in Phase 2 (after batch write)
- `success` incremented in Phase 2 (after batch write)

The implementation in Task 5 already handles this:
- Phase 1: `concurrentIncrement()` / `concurrentDecrement()` in the try/finally
- Phase 2: `success.increment()` after batch write
- Failed messages: `inflightDecrement()` happens in `processSingleMessage()`
- Successful messages: need explicit `inflightDecrement()` after Phase 2

Add after `successes.forEach { metrics.success.increment() }`:

```kotlin
successes.forEach { metrics.inflightDecrement() }
```

- [ ] **Step 2: Compile + existing test check**

Run: `./gradlew :module-infra:compileKotlin --quiet 2>&1 | tail -5`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt
git commit -m "fix(pgmq): add inflight decrement for successful batch results (BS7)"
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
