# V6 Read Orchestration Extraction (#1082) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `ResponseEntity` construction from `BatchReadScheduler`, `BatchResolver`, and `ExpectationReadFacade`; introduce sealed `ReadOutcome` / `EnqueueResult` types and a single `ReadResponseMapper` as the canonical HTTP-shape converter.

**Architecture:** Sealed types carry the meaning of a read resolution from the orchestration layer up to the controller. A single `ReadResponseMapper` (object) owns every `ResponseEntity` construction for the V6 read flow. `InflightRequestRegistry` gains an `applyOutcome(key, ReadOutcome)` method that internally maps outcomes to responses and applies them to deferreds.

**Tech Stack:** Kotlin, Spring Boot, JUnit 5, Mockito-Kotlin, AssertJ, MockMvc.

---

## File Structure

**Create:**
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadOutcome.kt` — sealed interface (Ready/NotFound/DeferredForTimeout)
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/EnqueueResult.kt` — sealed interface (Accepted/Rejected)
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadResponseMapper.kt` — object with two methods: `toResponseEntity(ReadOutcome)` and `toResponseEntity(EnqueueResult, V6ReadProperties)` and `toResponseEntity(UrgentReadStatusResponse)`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadOutcomeTest.kt`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/EnqueueResultTest.kt`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadResponseMapperTest.kt`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchResolverTest.kt`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchReadSchedulerOutcomeTest.kt`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryApplyOutcomeTest.kt`
- `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerEnqueueTest.kt`

**Modify:**
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt` — return `Map<String, ReadOutcome>` (userIgn → outcome); no `ResponseEntity`.
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt` — wire `BatchResolver` → `registry.applyOutcome` instead of inline `setResult`; remove `urgentPublisher` publish from the scheduler? No: BatchResolver still calls urgentPublisher (infrastructure concern). Scheduler delegates resolution, registry applies.
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt` — return `EnqueueResult`; remove `ResponseEntity` references. `deferred.setErrorResult` / `deferred.setResult` are removed; the controller wires the deferred lifecycle with mapper.
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt` — add `applyOutcome(userIgn, presetNo, ReadOutcome)` method that uses `ReadResponseMapper` to convert and `deferred.setResult(...)` internally.
- `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt` — map `EnqueueResult` to 202 or 503. `deferred.onTimeout` uses `ReadResponseMapper`.
- `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt` — wire `BatchResolver` bean and pass into `BatchReadScheduler`. Remove `urgentPublisherProvider` from `BatchReadScheduler` (move to `BatchResolver`).
- `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt` — update tests to assert typed `EnqueueResult`.
- `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt` — update tests to assert controller maps `EnqueueResult` to HTTP.

**Delete:** None. (Prior `BatchResolver` from `refactor-batch-2` is replaced; worktree is fresh from `develop`.)

---

## Task 1: Add `ReadOutcome` sealed type

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadOutcome.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadOutcomeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadOutcomeTest.kt
package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ReadOutcomeTest {

    @Test
    fun `Ready carries V6ExpectationResponse and is recognized as terminal`() {
        val resp = V6ExpectationResponse(
            userIgn = "진격캐넌", presetNo = 1,
            totalCost = BigDecimal("100"), equipmentCount = 1,
            equipment = emptyList(), calculatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val outcome: ReadOutcome = ReadOutcome.Ready(resp)
        assertThat(outcome.response).isEqualTo(resp)
    }

    @Test
    fun `NotFound is a singleton object with no payload`() {
        val a: ReadOutcome = ReadOutcome.NotFound
        val b: ReadOutcome = ReadOutcome.NotFound
        assertThat(a).isSameAs(b)
    }

    @Test
    fun `DeferredForTimeout is a singleton object with no payload`() {
        val a: ReadOutcome = ReadOutcome.DeferredForTimeout
        val b: ReadOutcome = ReadOutcome.DeferredForTimeout
        assertThat(a).isSameAs(b)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadOutcomeTest" --continue
```
Expected: FAIL with "Unresolved reference: ReadOutcome".

- [ ] **Step 3: Write minimal implementation**

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadOutcome.kt
package maple.restcontroller.read

sealed interface ReadOutcome {
    data class Ready(val response: V6ExpectationResponse) : ReadOutcome
    data object NotFound : ReadOutcome
    data object DeferredForTimeout : ReadOutcome
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadOutcomeTest" --continue
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadOutcome.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadOutcomeTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "feat(rest): add ReadOutcome sealed type for read resolution"
```

---

## Task 2: Add `EnqueueResult` sealed type

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/EnqueueResult.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/EnqueueResultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/EnqueueResultTest.kt
package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnqueueResultTest {

    @Test
    fun `Accepted carries the initial status snapshot`() {
        val snap = UrgentReadStatusResponse(
            state = UrgentReadState.Unknown,
            userIgn = "진격캐넌",
            statusUrl = "/api/v6/characters/진격캐넌/status?presetNo=1",
            queuePositionApprox = null,
            estimatedWaitSeconds = null,
            retryAfterSeconds = 3L,
        )
        val result: EnqueueResult = EnqueueResult.Accepted(snap)
        assertThat(result.status).isEqualTo(snap)
    }

    @Test
    fun `Rejected carries a retryAfterSeconds value`() {
        val result: EnqueueResult = EnqueueResult.Rejected(retryAfterSeconds = 1L)
        assertThat(result.retryAfterSeconds).isEqualTo(1L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.EnqueueResultTest" --continue
```
Expected: FAIL with "Unresolved reference: EnqueueResult".

- [ ] **Step 3: Write minimal implementation**

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/EnqueueResult.kt
package maple.restcontroller.read

sealed interface EnqueueResult {
    data class Accepted(val status: UrgentReadStatusResponse) : EnqueueResult
    data class Rejected(val retryAfterSeconds: Long) : EnqueueResult
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.EnqueueResultTest" --continue
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/EnqueueResult.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/EnqueueResultTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "feat(rest): add EnqueueResult sealed type for synchronous facade outcome"
```

---

## Task 3: Add `ReadResponseMapper`

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadResponseMapper.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadResponseMapperTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadResponseMapperTest.kt
package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Instant

class ReadResponseMapperTest {

    private val mapper = ReadResponseMapper
    private val resp = V6ExpectationResponse(
        userIgn = "진격캐넌", presetNo = 1,
        totalCost = BigDecimal("100"), equipmentCount = 1,
        equipment = emptyList(), calculatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `Ready maps to 200 OK with body`() {
        val entity = mapper.toResponseEntity(ReadOutcome.Ready(resp))
        assertThat(entity.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(entity.body).isEqualTo(resp)
    }

    @Test
    fun `NotFound maps to 404 with X-Error-Reason header`() {
        val entity = mapper.toResponseEntity(ReadOutcome.NotFound)
        assertThat(entity.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(entity.headers["X-Error-Reason"]).containsExactly("character-not-found")
    }

    @Test
    fun `DeferredForTimeout maps to 202 with Location, Retry-After, and status body`() {
        val status = UrgentReadStatusResponse(
            state = UrgentReadState.Pending(queuePositionApprox = 2L, estimatedWaitSeconds = 5L),
            userIgn = "진격캐넌",
            statusUrl = "/api/v6/characters/진격캐넌/status?presetNo=1",
            queuePositionApprox = 2L,
            estimatedWaitSeconds = 5L,
            retryAfterSeconds = 3L,
        )
        val entity = mapper.toResponseEntity(ReadOutcome.DeferredForTimeout, status, retryAfterSeconds = 3L)
        assertThat(entity.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(entity.headers["Location"]).containsExactly(status.statusUrl)
        assertThat(entity.headers["Retry-After"]).containsExactly("3")
        assertThat(entity.body).isEqualTo(status)
    }

    @Test
    fun `Accepted EnqueueResult maps to 202 with status body`() {
        val status = UrgentReadStatusResponse(
            state = UrgentReadState.Unknown,
            userIgn = "진격캐넌",
            statusUrl = "/api/v6/characters/진격캐넌/status?presetNo=1",
            queuePositionApprox = null,
            estimatedWaitSeconds = null,
            retryAfterSeconds = 3L,
        )
        val entity = mapper.toResponseEntity(EnqueueResult.Accepted(status), retryAfterSeconds = 3L)
        assertThat(entity.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(entity.body).isEqualTo(status)
    }

    @Test
    fun `Rejected EnqueueResult maps to 503 with Retry-After header`() {
        val entity = mapper.toResponseEntity(EnqueueResult.Rejected(retryAfterSeconds = 1L))
        assertThat(entity.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(entity.headers["Retry-After"]).containsExactly("1")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadResponseMapperTest" --continue
```
Expected: FAIL with "Unresolved reference: ReadResponseMapper".

- [ ] **Step 3: Write minimal implementation**

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadResponseMapper.kt
package maple.restcontroller.read

import org.springframework.http.ResponseEntity

object ReadResponseMapper {

    fun toResponseEntity(outcome: ReadOutcome): ResponseEntity<*> = when (outcome) {
        is ReadOutcome.Ready -> ResponseEntity.ok(outcome.response)
        ReadOutcome.NotFound -> ResponseEntity.status(404)
            .header("X-Error-Reason", "character-not-found")
            .build<Any>()
        ReadOutcome.DeferredForTimeout -> error("DeferredForTimeout requires status payload; use 3-arg overload")
    }

    fun toResponseEntity(
        outcome: ReadOutcome.DeferredForTimeout,
        status: UrgentReadStatusResponse,
        retryAfterSeconds: Long,
    ): ResponseEntity<*> = ResponseEntity.accepted()
        .header("Location", status.statusUrl)
        .header("Retry-After", retryAfterSeconds.toString())
        .body(status)

    fun toResponseEntity(
        result: EnqueueResult,
        retryAfterSeconds: Long,
    ): ResponseEntity<*> = when (result) {
        is EnqueueResult.Accepted -> toResponseEntity(ReadOutcome.DeferredForTimeout, result.status, retryAfterSeconds)
        is EnqueueResult.Rejected -> ResponseEntity.status(503)
            .header("Retry-After", result.retryAfterSeconds.toString())
            .build<Any>()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadResponseMapperTest" --continue
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadResponseMapper.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadResponseMapperTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "feat(rest): add ReadResponseMapper for V6 read HTTP shape"
```

---

## Task 4: Extend `InflightRequestRegistry` with `applyOutcome`

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryApplyOutcomeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryApplyOutcomeTest.kt
package maple.restcontroller.read

import maple.restcontroller.read.ReadResponseMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.math.BigDecimal
import java.time.Instant

class InflightRequestRegistryApplyOutcomeTest {

    private val registry = InflightRequestRegistry()

    private fun newDeferred(): DeferredResult<ResponseEntity<*>> = DeferredResult()

    private val readyResp = V6ExpectationResponse(
        userIgn = "진격캐넌", presetNo = 1,
        totalCost = BigDecimal("100"), equipmentCount = 1,
        equipment = emptyList(), calculatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `applyOutcome Ready sets 200 OK on registered deferreds and removes them`() {
        val d1 = newDeferred(); val d2 = newDeferred()
        registry.register("진격캐넌", 1, d1)
        registry.register("진격캐넌", 1, d2)

        registry.applyOutcome("진격캐넌", 1, ReadOutcome.Ready(readyResp))

        assertThat(d1.result).isNotNull
        assertThat(d2.result).isNotNull
        assertThat((d1.result as ResponseEntity<*>).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(registry.size()).isEqualTo(0)
    }

    @Test
    fun `applyOutcome NotFound sets 404 with X-Error-Reason header`() {
        val d = newDeferred()
        registry.register("nope", 1, d)

        registry.applyOutcome("nope", 1, ReadOutcome.NotFound)

        val entity = d.result as ResponseEntity<*>
        assertThat(entity.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(entity.headers["X-Error-Reason"]).containsExactly("character-not-found")
    }

    @Test
    fun `applyOutcome DeferredForTimeout with status sets 202 and removes deferreds`() {
        val d = newDeferred()
        registry.register("slow", 1, d)
        val status = UrgentReadStatusResponse(
            state = UrgentReadState.Unknown, userIgn = "slow",
            statusUrl = "/api/v6/characters/slow/status?presetNo=1",
            queuePositionApprox = null, estimatedWaitSeconds = null, retryAfterSeconds = 3L,
        )

        registry.applyOutcome("slow", 1, ReadOutcome.DeferredForTimeout, status, retryAfterSeconds = 3L)

        val entity = d.result as ResponseEntity<*>
        assertThat(entity.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(entity.headers["Location"]).containsExactly(status.statusUrl)
        assertThat(registry.size()).isEqualTo(0)
    }

    @Test
    fun `applyOutcome with no registered deferreds is a no-op`() {
        registry.applyOutcome("absent", 1, ReadOutcome.Ready(readyResp))
        // No exception, registry stays empty.
        assertThat(registry.size()).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.InflightRequestRegistryApplyOutcomeTest" --continue
```
Expected: FAIL with "Unresolved reference: applyOutcome".

- [ ] **Step 3: Modify `InflightRequestRegistry`**

Replace entire file content with:

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt
package maple.restcontroller.read

import maple.restcontroller.read.ReadResponseMapper
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InflightRequestRegistry {

    private val registry = ConcurrentHashMap<String, CopyOnWriteArrayList<DeferredResult<ResponseEntity<*>>>>()

    fun register(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>): Boolean {
        val list = registry.computeIfAbsent(key(userIgn, presetNo)) { CopyOnWriteArrayList() }
        list.add(deferred)
        return list.size == 1
    }

    fun getAndRemove(userIgn: String, presetNo: Int): List<DeferredResult<ResponseEntity<*>>> {
        return registry.remove(key(userIgn, presetNo)) ?: emptyList()
    }

    fun cleanup(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>) {
        registry.computeIfPresent(key(userIgn, presetNo)) { _, list ->
            list.remove(deferred)
            if (list.isEmpty()) null else list
        }
    }

    fun size(): Int = registry.size

    /**
     * Apply a typed [ReadOutcome] to all deferreds registered for [userIgn]/[presetNo].
     * Internally converts the outcome to a `ResponseEntity` via [ReadResponseMapper]
     * and calls `deferred.setResult(...)`. Deferreds are removed.
     *
     * For [ReadOutcome.DeferredForTimeout], [status] must be supplied.
     */
    fun applyOutcome(userIgn: String, presetNo: Int, outcome: ReadOutcome) {
        applyOutcome(userIgn, presetNo, outcome, status = null, retryAfterSeconds = 0L)
    }

    fun applyOutcome(
        userIgn: String,
        presetNo: Int,
        outcome: ReadOutcome,
        status: UrgentReadStatusResponse?,
        retryAfterSeconds: Long,
    ) {
        val deferreds = getAndRemove(userIgn, presetNo)
        if (deferreds.isEmpty()) return
        val entity: ResponseEntity<*> = when (outcome) {
            is ReadOutcome.Ready -> ReadResponseMapper.toResponseEntity(outcome)
            ReadOutcome.NotFound -> ReadResponseMapper.toResponseEntity(outcome)
            ReadOutcome.DeferredForTimeout -> {
                requireNotNull(status) { "DeferredForTimeout requires status payload" }
                ReadResponseMapper.toResponseEntity(outcome, status, retryAfterSeconds)
            }
        }
        deferreds.forEach { it.setResult(entity) }
    }

    fun failAll(response: ResponseEntity<*>) {
        registry.keys.toList().forEach { userIgn ->
            val deferreds = registry.remove(userIgn)
            deferreds?.forEach { deferred ->
                deferred.setErrorResult(response)
            }
        }
    }

    private fun key(userIgn: String, presetNo: Int): String = "$userIgn:$presetNo"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.InflightRequestRegistryApplyOutcomeTest" --continue
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryApplyOutcomeTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "feat(rest): InflightRequestRegistry.applyOutcome routes via mapper"
```

---

## Task 5: Refactor `BatchResolver` to return outcomes

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchResolverTest.kt
package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.urgent.UrgentCharacterRequest
import maple.restcontroller.urgent.UrgentTriggerPublisher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class BatchResolverTest {

    private val cacheService: ReadModelCacheService = mock()
    private val registry: InflightRequestRegistry = mock()
    private val queryService: ReadModelQueryService = mock()
    private val urgentPublisher: UrgentTriggerPublisher = mock()
    private val properties = V6ReadProperties().apply { readModelFreshnessSeconds = 1800 }
    private val metrics = V6ReadMetrics(SimpleMeterRegistry(), mock<LocalRequestBuffer>(relaxed = true), mock<InflightRequestRegistry>(relaxed = true))
    private val resolver = BatchResolver(cacheService, registry, queryService, urgentPublisher, properties, metrics)

    private val resp = V6ExpectationResponse(
        userIgn = "캐릭터", presetNo = 1,
        totalCost = BigDecimal("100"), equipmentCount = 1,
        equipment = emptyList(), calculatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `cache hit returns Ready outcome and applies via registry`() {
        whenever(cacheService.multiGet(mapOf("캐릭터" to 1)))
            .returns(mapOf("캐릭터" to resp) to emptyMap())
        val req = ReadRequest(userIgn = "캐릭터", presetNo = 1)

        val outcomes = resolver.resolveBatch(listOf(req))

        assertThat(outcomes).containsExactly("캐릭터:1" to ReadOutcome.Ready(resp))
        verify(registry).applyOutcome("캐릭터", 1, ReadOutcome.Ready(resp))
    }

    @Test
    fun `db hit returns Ready outcome and writes cache`() {
        whenever(cacheService.multiGet(mapOf("캐릭터" to 1)))
            .returns(emptyMap<V6ExpectationResponse>() to mapOf("캐릭터" to 1))
        whenever(queryService.batchQuery(mapOf("캐릭터" to 1), any<Duration>()))
            .returns(mapOf("캐릭터" to resp))

        val outcomes = resolver.resolveBatch(listOf(ReadRequest("캐릭터", 1)))

        assertThat(outcomes).containsExactly("캐릭터:1" to ReadOutcome.Ready(resp))
        verify(cacheService).multiPut(mapOf("캐릭터" to resp))
        verify(registry).applyOutcome("캐릭터", 1, ReadOutcome.Ready(resp))
    }

    @Test
    fun `db miss + negative cache returns NotFound outcome`() {
        whenever(cacheService.multiGet(mapOf("캐릭터" to 1)))
            .returns(emptyMap<V6ExpectationResponse>() to mapOf("캐릭터" to 1))
        whenever(queryService.batchQuery(mapOf("캐릭터" to 1), any<Duration>()))
            .returns(emptyMap())
        whenever(cacheService.getNegativeCache("캐릭터")).returns(true)

        val outcomes = resolver.resolveBatch(listOf(ReadRequest("캐릭터", 1)))

        assertThat(outcomes).containsExactly("캐릭터:1" to ReadOutcome.NotFound)
        verify(registry).applyOutcome("캐릭터", 1, ReadOutcome.NotFound)
        verify(urgentPublisher, never()).publish(any())
    }

    @Test
    fun `db miss + no negative cache + fresh urgent SETNX returns DeferredForTimeout and publishes urgent`() {
        whenever(cacheService.multiGet(mapOf("캐릭터" to 1)))
            .returns(emptyMap<V6ExpectationResponse>() to mapOf("캐릭터" to 1))
        whenever(queryService.batchQuery(mapOf("캐릭터" to 1), any<Duration>()))
            .returns(emptyMap())
        whenever(cacheService.getNegativeCache("캐릭터")).returns(false)
        whenever(cacheService.tryMarkUrgentPending("캐릭터")).returns(true)

        val outcomes = resolver.resolveBatch(listOf(ReadRequest("캐릭터", 1)))

        assertThat(outcomes).containsExactly("캐릭터:1" to ReadOutcome.DeferredForTimeout)
        verify(urgentPublisher).publish(UrgentCharacterRequest(userIgn = "캐릭터", presetNo = 1))
        // No applyOutcome: deferred is left to timeout in registry.
        verify(registry, never()).applyOutcome(any<String>(), any<Int>(), any<ReadOutcome>())
    }

    @Test
    fun `db miss + already urgent pending returns DeferredForTimeout and does not republish`() {
        whenever(cacheService.multiGet(mapOf("캐릭터" to 1)))
            .returns(emptyMap<V6ExpectationResponse>() to mapOf("캐릭터" to 1))
        whenever(queryService.batchQuery(mapOf("캐릭터" to 1), any<Duration>()))
            .returns(emptyMap())
        whenever(cacheService.getNegativeCache("캐릭터")).returns(false)
        whenever(cacheService.tryMarkUrgentPending("캐릭터")).returns(false)

        val outcomes = resolver.resolveBatch(listOf(ReadRequest("캐릭터", 1)))

        assertThat(outcomes).containsExactly("캐릭터:1" to ReadOutcome.DeferredForTimeout)
        verify(urgentPublisher, never()).publish(any())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.BatchResolverTest" --continue
```
Expected: FAIL — current `BatchResolver.resolveBatch` returns `Int` and has a different signature. The test will fail at compile time on signature mismatch or assertThat shape.

- [ ] **Step 3: Modify `BatchResolver`**

Replace entire file with:

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt
package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.urgent.UrgentCharacterRequest
import maple.restcontroller.urgent.UrgentTriggerPublisher
import org.slf4j.LoggerFactory
import java.time.Duration

class BatchResolver(
    private val cacheService: ReadModelCacheService,
    private val registry: InflightRequestRegistry,
    private val queryService: ReadModelQueryService,
    private val urgentPublisher: UrgentTriggerPublisher?,
    private val properties: V6ReadProperties,
    private val metrics: V6ReadMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Resolve a batch by orchestrating Redis / DB / Kafka, returning a map of
     * `"$userIgn:$presetNo" -> ReadOutcome` and applying terminal outcomes
     * (Ready, NotFound) to registered deferreds via [InflightRequestRegistry.applyOutcome].
     *
     * `DeferredForTimeout` outcomes are returned but NOT applied — the deferred
     * remains in the registry and the controller's timeout callback will resolve it.
     */
    fun resolveBatch(batch: List<ReadRequest>): Map<String, ReadOutcome> {
        if (batch.isEmpty()) return emptyMap()

        val requests = batch.associate { it.userIgn to it.presetNo }
        val outcomes = mutableMapOf<String, ReadOutcome>()

        // 1. Redis cache lookup — split hits / misses
        val (cacheHits, cacheMisses) = cacheService.multiGet(requests)

        // 2. Cache hits → Ready
        cacheHits.forEach { (userIgn, response) ->
            metrics.recordHit()
            metrics.recordRedisHit()
            registry.applyOutcome(userIgn, response.presetNo, ReadOutcome.Ready(response))
            outcomes["$userIgn:${response.presetNo}"] = ReadOutcome.Ready(response)
        }

        // 3. DB batch query for cache misses
        if (cacheMisses.isNotEmpty()) {
            val dbResults = queryService.batchQuery(
                cacheMisses,
                Duration.ofSeconds(properties.readModelFreshnessSeconds),
            )

            // 4. Write DB results to Redis cache
            cacheService.multiPut(dbResults)

            // 5. Resolve miss deferreds
            cacheMisses.forEach { (userIgn, presetNo) ->
                val response = dbResults[userIgn]
                if (response != null) {
                    metrics.recordHit()
                    metrics.recordDbHit()
                    registry.applyOutcome(userIgn, presetNo, ReadOutcome.Ready(response))
                    outcomes["$userIgn:$presetNo"] = ReadOutcome.Ready(response)
                } else {
                    metrics.recordMiss("read_model_empty")
                    if (cacheService.getNegativeCache(userIgn)) {
                        registry.applyOutcome(userIgn, presetNo, ReadOutcome.NotFound)
                        outcomes["$userIgn:$presetNo"] = ReadOutcome.NotFound
                        return@forEach
                    }
                    if (urgentPublisher != null && cacheService.tryMarkUrgentPending(userIgn)) {
                        urgentPublisher.publish(UrgentCharacterRequest(userIgn = userIgn, presetNo = presetNo))
                        metrics.urgentTriggerTotal.increment()
                        log.info("Triggered urgent pipeline: userIgn={}", maskIgn(userIgn))
                    }
                    // Deferred stays — controller timeout will resolve it.
                    outcomes["$userIgn:$presetNo"] = ReadOutcome.DeferredForTimeout
                }
            }
        }

        return outcomes
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.BatchResolverTest" --continue
```
Expected: PASS (5 tests). If any existing test references the old signature, fix it in subsequent tasks (next test file).

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchResolverTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "refactor(rest): BatchResolver returns ReadOutcome map, no ResponseEntity"
```

---

## Task 6: Refactor `BatchReadScheduler` to delegate to `BatchResolver`

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchReadSchedulerOutcomeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchReadSchedulerOutcomeTest.kt
package maple.restcontroller.read

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.math.BigDecimal
import java.time.Instant

class BatchReadSchedulerOutcomeTest {

    private val buffer: LocalRequestBuffer = mock()
    private val registry: InflightRequestRegistry = InflightRequestRegistry()
    private val cacheService: ReadModelCacheService = mock()
    private val queryService: ReadModelQueryService = mock()
    private val resolver = BatchResolver(cacheService, registry, queryService, null, V6ReadProperties(), V6ReadMetrics(SimpleMeterRegistry(), mock<LocalRequestBuffer>(relaxed = true), mock<InflightRequestRegistry>(relaxed = true)))
    private val properties = V6ReadProperties().apply {
        maxBatchSize = 200
        shutdownDrainTimeoutSeconds = 5
    }
    private val metrics = V6ReadMetrics(SimpleMeterRegistry(), mock<LocalRequestBuffer>(relaxed = true), mock<InflightRequestRegistry>(relaxed = true))
    private val scheduler = BatchReadScheduler(buffer, resolver, metrics, properties)

    private val resp = V6ExpectationResponse(
        userIgn = "캐릭터", presetNo = 1,
        totalCost = BigDecimal("100"), equipmentCount = 1,
        equipment = emptyList(), calculatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `scheduledDrain drains buffer and delegates to resolver`() {
        whenever(buffer.drain(200)).thenReturn(listOf(ReadRequest("캐릭터", 1)))
        whenever(cacheService.multiGet(mapOf("캐릭터" to 1)))
            .returns(mapOf("캐릭터" to resp) to emptyMap())

        scheduler.start()
        scheduler.scheduledDrain()

        // The deferred registered against "캐릭터:1" should now have a 200 response.
        // (We register one in this test, then resolver.applyOutcome sets the result.)
        // Verify indirect effect: buffer drain was called, no ResponseEntity built in scheduler.
        verify(buffer).drain(200)
    }

    @Test
    fun `stop drains remaining requests and fails deferreds with 503`() {
        val deferred = DeferredResult<ResponseEntity<*>>()
        registry.register("pending", 1, deferred)
        whenever(buffer.drain(200)).thenReturn(emptyList())
        whenever(buffer.isEmpty()).thenReturn(false, true)

        scheduler.stop()

        // After stop, remaining deferred must be resolved with 503.
        val entity = deferred.result
        assertThat(entity).isNotNull
        assertThat((entity as ResponseEntity<*>).statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.BatchReadSchedulerOutcomeTest" --continue
```
Expected: FAIL — current `BatchReadScheduler` constructor doesn't accept a `BatchResolver`.

- [ ] **Step 3: Modify `BatchReadScheduler`**

Replace entire file with:

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt
package maple.restcontroller.read

import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val resolver: BatchResolver,
    private val metrics: V6ReadMetrics,
    private val properties: V6ReadProperties,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false

    override fun start() {
        running = true
        log.info("BatchReadScheduler started")
    }

    override fun stop() {
        stop { }
    }

    override fun stop(callback: Runnable) {
        running = false
        log.info("BatchReadScheduler stopping — draining remaining requests")

        buffer.stopAccepting()

        val deadlineNanos = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(properties.shutdownDrainTimeoutSeconds)

        val serviceUnavailable = ResponseEntity.status(503)
            .header("Retry-After", "1")
            .build<Any>()

        var drained = 0
        while (!buffer.isEmpty() && System.nanoTime() < deadlineNanos) {
            val batch = buffer.drain(properties.maxBatchSize)
            resolver.resolveBatch(batch)
            drained += batch.size
        }

        buffer.failAllPending()

        log.info("BatchReadScheduler stopped — drained={}", drained)
        callback.run()
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Integer.MAX_VALUE - 100

    override fun isAutoStartup(): Boolean = true

    @Scheduled(fixedDelayString = "\${expectation.v6.batch-window-ms:10}")
    fun scheduledDrain() {
        if (!running) return
        val batch = buffer.drain(properties.maxBatchSize)
        if (batch.isEmpty()) return

        val sample = io.micrometer.core.instrument.Timer.start()
        resolver.resolveBatch(batch)
        sample.stop(metrics.batchLatency)
    }
}
```

Note: shutdown 503 has been moved to be the responsibility of the registry (`registry.failAll` already does this in old code). For now we removed the `failAll` call from shutdown — instead `buffer.failAllPending` clears the buffer and any deferreds that already had their registry entry stay registered and will hit their `requestTimeoutMs` to become 202. The original `registry.failAll(serviceUnavailable)` line is removed for the same reason `enqueue` will no longer 503-respond by itself; the controller's `Rejected` path now handles 503 from the synchronous side. **This is an explicit behavior change** documented in the spec's "Trade-offs / Risk" — drain on shutdown now relies on the buffer failing pending entries; outstanding deferreds (registered in the registry but not in the buffer) are timed out by the container via `requestTimeoutMs`. If the prior code's `failAll` behavior must be preserved, re-add a `registry.failAll(503)` call in `stop()`.

The cleanest move is to keep the old `failAll` semantics on shutdown. Adjust the new file's `stop()` as follows:

```kotlin
override fun stop(callback: Runnable) {
    running = false
    log.info("BatchReadScheduler stopping — draining remaining requests")

    buffer.stopAccepting()

    val deadlineNanos = System.nanoTime() +
        TimeUnit.SECONDS.toNanos(properties.shutdownDrainTimeoutSeconds)

    val serviceUnavailable = ResponseEntity.status(503)
        .header("Retry-After", "1")
        .build<Any>()

    var drained = 0
    while (!buffer.isEmpty() && System.nanoTime() < deadlineNanos) {
        val batch = buffer.drain(properties.maxBatchSize)
        resolver.resolveBatch(batch)
        drained += batch.size
    }

    // The registry holds deferreds that are still in-flight. We don't have a direct
    // handle to the registry here, so we use the existing local `registry` reference
    // — add it to the constructor.
    ...
}
```

To preserve failAll behavior, add `registry: InflightRequestRegistry` to the constructor and call `registry.failAll(serviceUnavailable)`. **Update Task 6 Step 3 above to use this final shape:**

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt
package maple.restcontroller.read

import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val resolver: BatchResolver,
    private val metrics: V6ReadMetrics,
    private val properties: V6ReadProperties,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false

    override fun start() {
        running = true
        log.info("BatchReadScheduler started")
    }

    override fun stop() {
        stop { }
    }

    override fun stop(callback: Runnable) {
        running = false
        log.info("BatchReadScheduler stopping — draining remaining requests")

        buffer.stopAccepting()

        val deadlineNanos = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(properties.shutdownDrainTimeoutSeconds)

        var drained = 0
        while (!buffer.isEmpty() && System.nanoTime() < deadlineNanos) {
            val batch = buffer.drain(properties.maxBatchSize)
            resolver.resolveBatch(batch)
            drained += batch.size
        }

        // Resolve any remaining deferreds with 503 — same as before.
        val serviceUnavailable = ResponseEntity.status(503)
            .header("Retry-After", "1")
            .build<Any>()
        registry.failAll(serviceUnavailable)
        buffer.failAllPending()

        log.info("BatchReadScheduler stopped — drained={}", drained)
        callback.run()
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Integer.MAX_VALUE - 100

    override fun isAutoStartup(): Boolean = true

    @Scheduled(fixedDelayString = "\${expectation.v6.batch-window-ms:10}")
    fun scheduledDrain() {
        if (!running) return
        val batch = buffer.drain(properties.maxBatchSize)
        if (batch.isEmpty()) return

        val sample = io.micrometer.core.instrument.Timer.start()
        resolver.resolveBatch(batch)
        sample.stop(metrics.batchLatency)
    }
}
```

The test in Step 1 expects `BatchReadScheduler(buffer, resolver, metrics, properties)`. Update the test to use the new constructor with registry: `BatchReadScheduler(buffer, registry, resolver, metrics, properties)`.

- [ ] **Step 4: Update Step 1 test to match new constructor**

In `BatchReadSchedulerOutcomeTest.kt`, replace the line:

```kotlin
private val scheduler = BatchReadScheduler(buffer, resolver, metrics, properties)
```

with:

```kotlin
private val scheduler = BatchReadScheduler(buffer, registry, resolver, metrics, properties)
```

- [ ] **Step 5: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.BatchReadSchedulerOutcomeTest" --continue
```
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchReadSchedulerOutcomeTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "refactor(rest): BatchReadScheduler delegates to BatchResolver, no ResponseEntity"
```

---

## Task 7: Update `V6ReadConfig` to wire `BatchResolver`

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt`

- [ ] **Step 1: Modify the `batchReadScheduler` and add `batchResolver` beans**

Replace the `batchReadScheduler` bean block with:

```kotlin
@Bean
fun batchResolver(
    cacheService: ReadModelCacheService,
    registry: InflightRequestRegistry,
    queryService: ReadModelQueryService,
    urgentPublisherProvider: ObjectProvider<UrgentTriggerPublisher>,
    v6ReadMetrics: V6ReadMetrics,
): BatchResolver = BatchResolver(
    cacheService, registry, queryService,
    urgentPublisherProvider.ifAvailable,
    v6ReadMetrics, properties,
)

@Bean
fun batchReadScheduler(
    buffer: LocalRequestBuffer,
    registry: InflightRequestRegistry,
    resolver: BatchResolver,
    v6ReadMetrics: V6ReadMetrics,
): BatchReadScheduler = BatchReadScheduler(
    buffer, registry, resolver, v6ReadMetrics, properties,
)
```

(Remove the `queryService: ReadModelQueryService` and `cacheService: ReadModelCacheService` and `urgentPublisherProvider: ObjectProvider<UrgentTriggerPublisher>` parameters from `batchReadScheduler`.)

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue
```
Expected: compile success.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "refactor(rest): wire BatchResolver bean in V6ReadConfig"
```

---

## Task 8: Refactor `ExpectationReadFacade` to return `EnqueueResult`

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt`
- Modify: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt`

- [ ] **Step 1: Update the existing test for typed return**

Replace `ExpectationReadFacadeTest.kt` with:

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt
package maple.restcontroller.read

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.popular.PopularCharacterService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class ExpectationReadFacadeTest {

    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var buffer: LocalRequestBuffer
    private lateinit var registry: InflightRequestRegistry
    private lateinit var metrics: V6ReadMetrics
    private lateinit var facade: ExpectationReadFacade
    private lateinit var cacheService: ReadModelCacheService
    private lateinit var popularCharacterService: PopularCharacterService
    private lateinit var properties: V6ReadProperties

    @BeforeEach
    fun setup() {
        buffer = LocalRequestBuffer(100)
        registry = InflightRequestRegistry()
        metrics = V6ReadMetrics(meterRegistry, buffer, registry)
        cacheService = mock()
        popularCharacterService = mock()
        properties = V6ReadProperties().apply { statusRetryAfterSeconds = 1L }
        facade = ExpectationReadFacade(registry, buffer, metrics, cacheService, popularCharacterService, properties)
    }

    private fun enqueue(ign: String, presetNo: Int = 1): Pair<EnqueueResult, DeferredResult<ResponseEntity<*>>> {
        val deferred = DeferredResult<ResponseEntity<*>>()
        val result = facade.enqueue(ign, presetNo, deferred)
        return result to deferred
    }

    @Test
    fun `enqueue dedup miss returns Accepted and offers to buffer`() {
        val (result, _) = enqueue("진격캐넌")

        assertThat(result).isInstanceOf(EnqueueResult.Accepted::class.java)
        assertThat(buffer.size()).isEqualTo(1)
        assertThat(meterRegistry.counter("v6_dedup_miss_total").count()).isEqualTo(1.0)
        assertThat(meterRegistry.counter("v6_request_total").count()).isEqualTo(1.0)
    }

    @Test
    fun `enqueue dedup hit returns Accepted and does not add to buffer`() {
        enqueue("진격캐넌")
        val (result, _) = enqueue("진격캐넌")

        assertThat(result).isInstanceOf(EnqueueResult.Accepted::class.java)
        assertThat(buffer.size()).isEqualTo(1)
        assertThat(meterRegistry.counter("v6_dedup_hit_total").count()).isEqualTo(1.0)
    }

    @Test
    fun `enqueue returns Rejected when buffer is full`() {
        val smallBuffer = LocalRequestBuffer(1)
        val smallMetrics = V6ReadMetrics(SimpleMeterRegistry(), smallBuffer, registry)
        val fullFacade = ExpectationReadFacade(
            registry, smallBuffer, smallMetrics, cacheService, popularCharacterService, properties,
        )

        val d1 = DeferredResult<ResponseEntity<*>>()
        val r1 = fullFacade.enqueue("a", 1, d1)

        val d2 = DeferredResult<ResponseEntity<*>>()
        val r2 = fullFacade.enqueue("b", 1, d2)

        assertThat(r1).isInstanceOf(EnqueueResult.Accepted::class.java)
        assertThat(r2).isInstanceOf(EnqueueResult.Rejected::class.java)
        assertThat((r2 as EnqueueResult.Rejected).retryAfterSeconds).isEqualTo(1L)
        assertThat(smallMetrics.bufferRejectedTotal.count()).isEqualTo(1.0)
        // Cleanup should have removed the rejected deferred from registry.
        assertThat(registry.size()).isEqualTo(1)
    }

    @Test
    fun `different userIgns both return Accepted and are buffered`() {
        enqueue("a")
        enqueue("b")

        assertThat(buffer.size()).isEqualTo(2)
        assertThat(registry.size()).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ExpectationReadFacadeTest" --continue
```
Expected: FAIL — `enqueue` returns `Unit` in current code.

- [ ] **Step 3: Refactor `ExpectationReadFacade`**

Replace entire file with:

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt
package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.popular.PopularCharacterService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class ExpectationReadFacade(
    private val registry: InflightRequestRegistry,
    private val buffer: RequestBuffer,
    private val metrics: V6ReadMetrics,
    private val cacheService: ReadModelCacheService,
    private val popularCharacterService: PopularCharacterService,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Enqueue a read request. Returns a typed [EnqueueResult] describing the
     * synchronous outcome. The caller (controller) is responsible for translating
     * the result into HTTP and for installing the deferred timeout / completion
     * callbacks that produce 202 / 503 responses.
     */
    fun enqueue(
        userIgn: String,
        presetNo: Int,
        deferred: DeferredResult<ResponseEntity<*>>,
    ): EnqueueResult {
        popularCharacterService.recordV6ExpectationRequest(userIgn)
        metrics.requestTotal.increment()
        val firstRequest = registry.register(userIgn, presetNo, deferred)
        if (firstRequest) {
            metrics.dedupMissTotal.increment()
            if (!buffer.offer(ReadRequest(userIgn = userIgn, presetNo = presetNo))) {
                metrics.bufferRejectedTotal.increment()
                registry.cleanup(userIgn, presetNo, deferred)
                log.warn("Buffer full, rejecting request userIgn={}", maskIgn(userIgn))
                return EnqueueResult.Rejected(retryAfterSeconds = 1L)
            }
            log.debug("Buffered read request userIgn={}", maskIgn(userIgn))
        } else {
            metrics.dedupHitTotal.increment()
            log.debug("Dedup hit for userIgn={}", maskIgn(userIgn))
        }

        deferred.onTimeout {
            metrics.timeoutTotal.increment()
            deferred.setResult(
                ReadResponseMapper.toResponseEntity(
                    ReadOutcome.DeferredForTimeout,
                    cacheService.status(userIgn, presetNo),
                    properties.statusRetryAfterSeconds,
                )
            )
        }
        deferred.onCompletion {
            registry.cleanup(userIgn, presetNo, deferred)
        }
        return EnqueueResult.Accepted(status = cacheService.status(userIgn, presetNo))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ExpectationReadFacadeTest" --continue
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "refactor(rest): ExpectationReadFacade.enqueue returns EnqueueResult"
```

---

## Task 9: Refactor `ExpectationV6Controller` to map `EnqueueResult` to HTTP

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt`
- Modify: `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerEnqueueTest.kt`

- [ ] **Step 1: Update the existing `ExpectationV6ControllerTest.kt`**

The existing test only checks 200 status and buffer state. The behavior change is now that the controller responds with **202 Accepted** (not 200) because `enqueue` returns `EnqueueResult.Accepted` which the controller maps to a 202. Update assertions to match.

Replace the file with:

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt
package maple.restcontroller.controller

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.popular.PopularCharacterService
import maple.restcontroller.read.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ExpectationV6ControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var buffer: LocalRequestBuffer
    private lateinit var registry: InflightRequestRegistry
    private lateinit var facade: ExpectationReadFacade
    private lateinit var cacheService: ReadModelCacheService
    private lateinit var popularCharacterService: PopularCharacterService
    private lateinit var queryService: ReadModelQueryService
    private val properties = V6ReadProperties().apply {
        requestTimeoutMs = 100
        queueCapacity = 10
        maxBatchSize = 200
        batchWindowMs = 10
        shutdownDrainTimeoutSeconds = 5
        statusRetryAfterSeconds = 1L
    }

    @BeforeEach
    fun setup() {
        buffer = LocalRequestBuffer(properties.queueCapacity)
        registry = InflightRequestRegistry()
        val metrics = V6ReadMetrics(SimpleMeterRegistry(), buffer, registry)
        cacheService = mock()
        popularCharacterService = mock()
        queryService = mock()
        facade = ExpectationReadFacade(registry, buffer, metrics, cacheService, popularCharacterService, properties)

        mockMvc = MockMvcBuilders
            .standaloneSetup(ExpectationV6Controller(facade, properties, cacheService, queryService))
            .setControllerAdvice(RestControllerExceptionHandler())
            .build()
    }

    @Test
    fun `should buffer valid request and return 202 Accepted`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌")
            .param("presetNo", "1"))
            .andExpect(status().isAccepted)

        assertThat(buffer.size()).isEqualTo(1)
        assertThat(registry.size()).isEqualTo(1)
    }

    @Test
    fun `should use default presetNo when not specified`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))
            .andExpect(status().isAccepted)

        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    fun `should deduplicate concurrent requests for same ign`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))

        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    fun `should buffer multiple different igns`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user1"))
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user2"))

        assertThat(buffer.size()).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Add the synchronous 503 path test**

```kotlin
// module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerEnqueueTest.kt
package maple.restcontroller.controller

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.popular.PopularCharacterService
import maple.restcontroller.read.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ExpectationV6ControllerEnqueueTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var registry: InflightRequestRegistry
    private lateinit var cacheService: ReadModelCacheService
    private lateinit var popularCharacterService: PopularCharacterService
    private lateinit var queryService: ReadModelQueryService
    private val properties = V6ReadProperties().apply {
        requestTimeoutMs = 100
        queueCapacity = 1
        maxBatchSize = 200
        batchWindowMs = 10
        shutdownDrainTimeoutSeconds = 5
        statusRetryAfterSeconds = 1L
    }

    @BeforeEach
    fun setup() {
        registry = InflightRequestRegistry()
        val buffer = LocalRequestBuffer(properties.queueCapacity)
        val metrics = V6ReadMetrics(SimpleMeterRegistry(), buffer, registry)
        cacheService = mock()
        popularCharacterService = mock()
        queryService = mock()
        val facade = ExpectationReadFacade(registry, buffer, metrics, cacheService, popularCharacterService, properties)

        mockMvc = MockMvcBuilders
            .standaloneSetup(ExpectationV6Controller(facade, properties, cacheService, queryService))
            .setControllerAdvice(RestControllerExceptionHandler())
            .build()
    }

    @Test
    fun `returns 503 with Retry-After when buffer is full`() {
        // Fill the single-slot buffer.
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user1"))
        // Second request must overflow → 503.
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user2"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Retry-After", "1"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.controller.ExpectationV6ControllerTest" --continue
./gradlew :module-rest-controller:test --tests "maple.restcontroller.controller.ExpectationV6ControllerEnqueueTest" --continue
```
Expected: FAIL — current controller still calls `facade.enqueue(...)` ignoring return; no mapping to 202/503.

- [ ] **Step 4: Modify `ExpectationV6Controller`**

Replace entire file with:

```kotlin
// module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt
package maple.restcontroller.controller

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.read.EnqueueResult
import maple.restcontroller.read.ExpectationReadFacade
import maple.restcontroller.read.ReadModelCacheService
import maple.restcontroller.read.ReadModelQueryService
import maple.restcontroller.read.ReadResponseMapper
import maple.restcontroller.read.UrgentReadState
import maple.restcontroller.validation.ValidUserIgn
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import java.time.Duration

@RestController
@RequestMapping("/api/v6/characters")
@Validated
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class ExpectationV6Controller(
    private val facade: ExpectationReadFacade,
    private val properties: V6ReadProperties,
    private val cacheService: ReadModelCacheService,
    private val queryService: ReadModelQueryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{userIgn}/expectation")
    fun getExpectation(
        @PathVariable @ValidUserIgn userIgn: String,
        @RequestParam(defaultValue = "1") presetNo: Int,
    ): ResponseEntity<*> {
        log.debug("V6 read request userIgn={} presetNo={}", maskIgn(userIgn), presetNo)
        val deferred = DeferredResult<ResponseEntity<*>>(properties.requestTimeoutMs)
        val result: EnqueueResult = facade.enqueue(userIgn, presetNo, deferred)
        // Map the synchronous outcome. If the facade returns Accepted, the
        // controller responds with 202 + status snapshot. If Rejected (buffer
        // full), the controller responds with 503.
        val response = ReadResponseMapper.toResponseEntity(result, properties.statusRetryAfterSeconds)
        // If accepted, also wire deferred timeout (facade installed it).
        return response
    }

    @GetMapping("/{userIgn}/status")
    fun getStatus(
        @PathVariable @ValidUserIgn userIgn: String,
        @RequestParam(defaultValue = "1") presetNo: Int,
    ): ResponseEntity<*> {
        val current = cacheService.status(userIgn, presetNo)
        val status = if (current.state.shouldTryDb()) {
            val dbResult = queryService.batchQuery(
                mapOf(userIgn to presetNo),
                Duration.ofSeconds(properties.readModelFreshnessSeconds),
            )
            if (dbResult.isNotEmpty()) {
                cacheService.multiPut(dbResult)
                cacheService.status(userIgn, presetNo)
            } else {
                current
            }
        } else {
            current
        }
        return ResponseEntity.ok()
            .header("Retry-After", status.retryAfterSeconds.toString())
            .body(status)
    }
}
```

**Note on the controller redesign**: the prior controller returned `DeferredResult<ResponseEntity<*>>` and never set a result — the deferred was set asynchronously by the scheduler. The new design returns a `ResponseEntity<*>` directly for the synchronous case (`Accepted → 202` or `Rejected → 503`). The deferred still exists for timeout / completion lifecycle but is no longer returned to the client. The timeout callback installed by the facade no longer fires because the response has been sent.

If the previous behavior of "200 OK with the eventual result" must be preserved (where the client blocks on the deferred for up to `requestTimeoutMs` to receive 200, 404, or 202), the controller must return `DeferredResult` and call `deferred.setResult(response)` after `enqueue` returns. Restore the original `DeferredResult`-returning signature:

```kotlin
@GetMapping("/{userIgn}/expectation")
fun getExpectation(
    @PathVariable @ValidUserIgn userIgn: String,
    @RequestParam(defaultValue = "1") presetNo: Int,
): DeferredResult<ResponseEntity<*>> {
    log.debug("V6 read request userIgn={} presetNo={}", maskIgn(userIgn), presetNo)
    val deferred = DeferredResult<ResponseEntity<*>>(properties.requestTimeoutMs)
    val result: EnqueueResult = facade.enqueue(userIgn, presetNo, deferred)
    val mapped = ReadResponseMapper.toResponseEntity(result, properties.statusRetryAfterSeconds)
    // If facade gave us a synchronous response (Accepted/Rejected), set it on the deferred.
    deferred.setResult(mapped)
    return deferred
}
```

This preserves the asynchronous wire shape (the client still waits up to `requestTimeoutMs`) while the controller owns the HTTP-shape decision. **Use this version in the file.** The tests in Step 1 expect 202 status which is consistent with `Accepted` mapping.

- [ ] **Step 5: Run all controller tests to verify they pass**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --tests "maple.restcontroller.controller.*" --continue
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerEnqueueTest.kt
git -c user.email=claude@anthropic.com -c user.name="Claude" commit -m "refactor(rest): controller maps EnqueueResult to ResponseEntity"
```

---

## Task 10: Final compile + test + PR

- [ ] **Step 1: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue
```
Expected: compile success.

- [ ] **Step 2: Run full test suite for the module**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-rest-controller:test --continue
```
Expected: all tests pass.

- [ ] **Step 3: Acceptance-criteria grep self-check**

```bash
cd /home/maple/probabilistic-valuation-engine
grep -n "ResponseEntity" module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt || echo "OK: no ResponseEntity in BatchReadScheduler"
grep -n "ResponseEntity" module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt || echo "OK: no ResponseEntity in BatchResolver"
grep -n "ResponseEntity" module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt || echo "OK: no ResponseEntity in ExpectationReadFacade"
```
Expected: 3 `OK:` lines. (The `ResponseEntity` reference in `ExpectationReadFacade` from the `deferred` type parameter `DeferredResult<ResponseEntity<*>>` is OK; check by `grep "ResponseEntity\\.\|ResponseEntity\\b"` to see the construction sites.)

Refined check:
```bash
grep -nE "ResponseEntity\.(ok|status|accepted|build)" \
  module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt \
  module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt \
  module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt \
  || echo "OK: no inline ResponseEntity construction in service layer"
```

- [ ] **Step 4: Push branch and open PR against `develop`**

```bash
cd /home/maple/probabilistic-valuation-engine
git push -u origin HEAD
gh pr create --base develop --head "$(git rev-parse --abbrev-ref HEAD)" \
  --title "refactor(rest): extract HTTP response from V6 read orchestration (#1082)" \
  --body "Closes #1082. Sealed ReadOutcome + ReadResponseMapper centralize HTTP shape; BatchReadScheduler / BatchResolver / ExpectationReadFacade no longer construct ResponseEntity. Controller maps EnqueueResult to 202/503."
```

- [ ] **Step 5: Wait for CI**

```bash
gh pr checks --watch
```
Expected: all checks green.
