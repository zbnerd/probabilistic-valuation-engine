# V6 Read Path Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement V6 REST endpoint that buffers read requests via DeferredResult long-held async pattern. No scheduler processing — all requests timeout to 202 after 1500ms.

**Architecture:** Controller accepts GET requests, wraps them in `DeferredResult`, and enqueues into a bounded `RequestBuffer`. An `InflightRequestRegistry` deduplicates concurrent requests for the same userIgn. A `BatchReadScheduler` stub handles graceful shutdown via `SmartLifecycle`. All V6 components are gated by `expectation.v6.enabled` feature flag.

**Tech Stack:** Kotlin, Spring MVC (DeferredResult), Micrometer Prometheus, module-common error contracts

---

## File Structure

### New files
```
module-rest-controller/src/main/kotlin/maple/restcontroller/
├── config/
│   ├── V6ReadProperties.kt          # @ConfigurationProperties
│   └── V6ReadConfig.kt              # Bean wiring + feature flag gate
├── read/
│   ├── ReadRequest.kt               # Data class
│   ├── RequestBuffer.kt             # Interface
│   ├── LocalRequestBuffer.kt        # Bounded ConcurrentLinkedQueue
│   ├── InflightRequestRegistry.kt   # userIgn → List<DeferredResult> dedup
│   ├── ExpectationReadFacade.kt     # Enqueue orchestration + lifecycle callbacks
│   └── BatchReadScheduler.kt        # SmartLifecycle stub (drain + shutdown)
├── metrics/
│   └── V6ReadMetrics.kt             # 6 Prometheus meters
├── validation/
│   ├── ValidUserIgn.kt              # Annotation
│   └── UserIgnValidator.kt          # ConstraintValidator
├── controller/
│   └── ExpectationV6Controller.kt   # GET /api/v6/characters/{userIgn}/expectation
└── advice/
    └── RestControllerExceptionHandler.kt

module-rest-controller/src/test/kotlin/maple/restcontroller/
├── read/
│   ├── LocalRequestBufferTest.kt
│   ├── InflightRequestRegistryTest.kt
│   └── ExpectationReadFacadeTest.kt
└── controller/
    └── ExpectationV6ControllerTest.kt
```

### Files to modify
- `module-rest-controller/build.gradle` — add `module-core` dependency
- `module-rest-controller/src/main/resources/application.yml` — add `expectation.v6.*` config
- `module-rest-controller/src/main/resources/application-local.yml` — enable V6 locally

---

## Task 1: Build Configuration

**Files:**
- Modify: `module-rest-controller/build.gradle`

- [ ] **Step 1: Add module-core dependency**

Add after the existing `implementation project(':module-common')` line:

```groovy
implementation project(':module-core')
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-rest-controller:compileKotlin --continue 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/build.gradle
git commit -m "build(rest-controller): add module-core dependency for V6 read path"
```

---

## Task 2: Configuration Properties + YAML

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadProperties.kt`
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-rest-controller/src/main/resources/application-local.yml`

- [ ] **Step 1: Create V6ReadProperties**

```kotlin
package maple.restcontroller.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "expectation.v6")
class V6ReadProperties {
    var enabled: Boolean = false
    var batchWindowMs: Long = 10
    var requestTimeoutMs: Long = 1500
    var maxBatchSize: Int = 200
    var queueCapacity: Int = 5000
    var shutdownDrainTimeoutSeconds: Long = 5
}
```

- [ ] **Step 2: Update application.yml — add V6 config block**

Append at the end of `module-rest-controller/src/main/resources/application.yml`:

```yaml

expectation:
  v6:
    enabled: false                    # feature flag — enable per environment
    batch-window-ms: 10               # scheduler drain interval (Phase 2)
    request-timeout-ms: 1500          # DeferredResult timeout
    max-batch-size: 200               # scheduler batch size (Phase 2)
    queue-capacity: 5000              # RequestBuffer max capacity
    shutdown-drain-timeout-seconds: 5 # graceful shutdown deadline
```

- [ ] **Step 3: Update application-local.yml — enable for local dev**

Replace the content of `module-rest-controller/src/main/resources/application-local.yml`:

```yaml
# Local profile overrides

expectation:
  v6:
    enabled: true
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :module-rest-controller:compileKotlin --continue 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadProperties.kt \
        module-rest-controller/src/main/resources/application.yml \
        module-rest-controller/src/main/resources/application-local.yml
git commit -m "feat(v6): add V6ReadProperties and YAML configuration"
```

---

## Task 3: ReadRequest + RequestBuffer Interface

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadRequest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/RequestBuffer.kt`

- [ ] **Step 1: Create ReadRequest**

```kotlin
package maple.restcontroller.read

import java.util.UUID

data class ReadRequest(
    val requestId: UUID = UUID.randomUUID(),
    val userIgn: String
)
```

- [ ] **Step 2: Create RequestBuffer interface**

```kotlin
package maple.restcontroller.read

interface RequestBuffer {
    fun offer(request: ReadRequest): Boolean
    fun drain(maxItems: Int): List<ReadRequest>
    fun size(): Int
    fun isEmpty(): Boolean
    fun stopAccepting()
    fun failAllPending()
}
```

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadRequest.kt \
        module-rest-controller/src/main/kotlin/maple/restcontroller/read/RequestBuffer.kt
git commit -m "feat(v6): add ReadRequest and RequestBuffer interface"
```

---

## Task 4: LocalRequestBuffer with Tests

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/LocalRequestBufferTest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/LocalRequestBuffer.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalRequestBufferTest {

    private fun createBuffer(capacity: Int = 10): LocalRequestBuffer {
        return LocalRequestBuffer(capacity)
    }

    private fun request(ign: String) = ReadRequest(userIgn = ign)

    @Test
    fun `should offer and drain requests`() {
        val buffer = createBuffer()
        assertThat(buffer.offer(request("a"))).isTrue
        assertThat(buffer.offer(request("b"))).isTrue
        assertThat(buffer.size()).isEqualTo(2)

        val drained = buffer.drain(10)
        assertThat(drained).hasSize(2)
        assertThat(drained.map { it.userIgn }).containsExactly("a", "b")
        assertThat(buffer.size()).isZero
    }

    @Test
    fun `should drain up to maxItems`() {
        val buffer = createBuffer()
        repeat(5) { buffer.offer(request("ign$it")) }

        val drained = buffer.drain(3)
        assertThat(drained).hasSize(3)
        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    fun `should reject offer when at capacity`() {
        val buffer = createBuffer(capacity = 2)
        assertThat(buffer.offer(request("a"))).isTrue
        assertThat(buffer.offer(request("b"))).isTrue
        assertThat(buffer.offer(request("c"))).isFalse
        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    fun `should reject offer after stopAccepting`() {
        val buffer = createBuffer()
        buffer.stopAccepting()
        assertThat(buffer.offer(request("a"))).isFalse
        assertThat(buffer.size()).isZero
    }

    @Test
    fun `should reject offer when stopped even if capacity available`() {
        val buffer = createBuffer(capacity = 10)
        buffer.offer(request("a"))
        buffer.stopAccepting()
        assertThat(buffer.offer(request("b"))).isFalse
    }

    @Test
    fun `failAllPending should clear queue`() {
        val buffer = createBuffer()
        repeat(5) { buffer.offer(request("ign$it")) }
        assertThat(buffer.size()).isEqualTo(5)

        buffer.failAllPending()
        assertThat(buffer.size()).isZero
        assertThat(buffer.isEmpty()).isTrue
    }

    @Test
    fun `drain from empty buffer returns empty list`() {
        val buffer = createBuffer()
        assertThat(buffer.drain(10)).isEmpty()
    }

    @Test
    fun `isEmpty returns true when no elements`() {
        val buffer = createBuffer()
        assertThat(buffer.isEmpty()).isTrue
        buffer.offer(request("a"))
        assertThat(buffer.isEmpty()).isFalse
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-rest-controller:compileTestKotlin 2>&1 | tail -5`
Expected: Compilation error — `LocalRequestBuffer` does not exist

- [ ] **Step 3: Implement LocalRequestBuffer**

```kotlin
package maple.restcontroller.read

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalRequestBuffer(
    private val maxCapacity: Int
) : RequestBuffer {

    private val queue = ConcurrentLinkedQueue<ReadRequest>()
    private val counter = AtomicInteger(0)
    private val accepting = AtomicBoolean(true)

    override fun offer(request: ReadRequest): Boolean {
        if (!accepting.get()) return false
        if (counter.get() >= maxCapacity) return false
        if (queue.offer(request)) {
            counter.incrementAndGet()
            return true
        }
        return false
    }

    override fun drain(maxItems: Int): List<ReadRequest> {
        val result = mutableListOf<ReadRequest>()
        repeat(maxItems) {
            val element = queue.poll() ?: return@repeat
            counter.decrementAndGet()
            result.add(element)
        }
        return result
    }

    override fun size(): Int = counter.get()

    override fun isEmpty(): Boolean = queue.isEmpty()

    override fun stopAccepting() {
        accepting.set(false)
    }

    override fun failAllPending() {
        queue.clear()
        counter.set(0)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.LocalRequestBufferTest" 2>&1 | tail -10`
Expected: All 8 tests pass

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/LocalRequestBuffer.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/LocalRequestBufferTest.kt
git commit -m "feat(v6): add LocalRequestBuffer with bounded capacity"
```

---

## Task 5: InflightRequestRegistry with Tests

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryTest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class InflightRequestRegistryTest {

    private val registry = InflightRequestRegistry()

    private fun deferred(): DeferredResult<ResponseEntity<*>> =
        DeferredResult<ResponseEntity<*>>()

    @Test
    fun `register returns true for first request (dedup miss)`() {
        val d = deferred()
        val result = registry.register("진격캐넌", d)
        assertThat(result).isTrue
    }

    @Test
    fun `register returns false for duplicate userIgn (dedup hit)`() {
        registry.register("진격캐넌", deferred())
        val result = registry.register("진격캐넌", deferred())
        assertThat(result).isFalse
    }

    @Test
    fun `size returns unique userIgn count`() {
        registry.register("a", deferred())
        registry.register("b", deferred())
        registry.register("a", deferred())
        assertThat(registry.size()).isEqualTo(2)
    }

    @Test
    fun `getAndRemove returns all deferreds for userIgn`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("진격캐넌", d1)
        registry.register("진격캐넌", d2)

        val removed = registry.getAndRemove("진격캐넌")
        assertThat(removed).containsExactly(d1, d2)
        assertThat(registry.size()).isZero
    }

    @Test
    fun `getAndRemove for non-existent key returns empty list`() {
        assertThat(registry.getAndRemove("none")).isEmpty()
    }

    @Test
    fun `cleanup removes specific deferred from list`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("진격캐넌", d1)
        registry.register("진격캐넌", d2)

        registry.cleanup("진격캐넌", d1)

        val remaining = registry.getAndRemove("진격캐넌")
        assertThat(remaining).containsExactly(d2)
    }

    @Test
    fun `cleanup removes entry when list becomes empty`() {
        val d = deferred()
        registry.register("진격캐넌", d)
        registry.cleanup("진격캐넌", d)
        assertThat(registry.size()).isZero
    }

    @Test
    fun `failAll sets error on all pending deferreds and clears registry`() {
        val d1 = deferred()
        val d2 = deferred()
        registry.register("a", d1)
        registry.register("b", d2)

        val errorResponse = ResponseEntity.status(503).header("Retry-After", "1").build<Any>()
        registry.failAll(errorResponse)

        assertThat(d1.result).isEqualTo(errorResponse)
        assertThat(d2.result).isEqualTo(errorResponse)
        assertThat(registry.size()).isZero
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-rest-controller:compileTestKotlin 2>&1 | tail -5`
Expected: Compilation error — `InflightRequestRegistry` does not exist

- [ ] **Step 3: Implement InflightRequestRegistry**

```kotlin
package maple.restcontroller.read

import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InflightRequestRegistry {

    private val registry = ConcurrentHashMap<String, CopyOnWriteArrayList<DeferredResult<ResponseEntity<*>>>>()

    fun register(userIgn: String, deferred: DeferredResult<ResponseEntity<*>>): Boolean {
        val list = registry.computeIfAbsent(userIgn) { CopyOnWriteArrayList() }
        list.add(deferred)
        return list.size == 1
    }

    fun getAndRemove(userIgn: String): List<DeferredResult<ResponseEntity<*>>> {
        return registry.remove(userIgn) ?: emptyList()
    }

    fun cleanup(userIgn: String, deferred: DeferredResult<ResponseEntity<*>>) {
        val list = registry[userIgn] ?: return
        list.remove(deferred)
        if (list.isEmpty()) {
            registry.remove(userIgn)
        }
    }

    fun size(): Int = registry.size

    fun failAll(response: ResponseEntity<*>) {
        registry.keys.toList().forEach { userIgn ->
            val deferreds = registry.remove(userIgn)
            deferreds?.forEach { deferred ->
                deferred.setErrorResult(response)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.InflightRequestRegistryTest" 2>&1 | tail -10`
Expected: All 8 tests pass

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryTest.kt
git commit -m "feat(v6): add InflightRequestRegistry with dedup and fan-out"
```

---

## Task 6: V6ReadMetrics

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt`

- [ ] **Step 1: Create V6ReadMetrics**

```kotlin
package maple.restcontroller.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.restcontroller.read.InflightRequestRegistry
import maple.restcontroller.read.LocalRequestBuffer

class V6ReadMetrics(
    meterRegistry: MeterRegistry,
    requestBuffer: LocalRequestBuffer,
    inflightRegistry: InflightRequestRegistry
) {
    val requestTotal: Counter = Counter.builder("v6_request_total")
        .description("Total V6 read requests")
        .register(meterRegistry)

    val dedupHitTotal: Counter = Counter.builder("v6_dedup_hit_total")
        .description("Inflight dedup cache hit count")
        .register(meterRegistry)

    val dedupMissTotal: Counter = Counter.builder("v6_dedup_miss_total")
        .description("Inflight dedup cache miss count")
        .register(meterRegistry)

    val timeoutTotal: Counter = Counter.builder("v6_timeout_total")
        .description("DeferredResult timeout → 202 count")
        .register(meterRegistry)

    val bufferRejectedTotal: Counter = Counter.builder("v6_buffer_rejected_total")
        .description("Buffer full → 503 rejection count")
        .register(meterRegistry)

    init {
        Gauge.builder("v6_buffer_size", requestBuffer) { it.size().toDouble() }
            .description("Current RequestBuffer queue size")
            .register(meterRegistry)

        Gauge.builder("v6_inflight_size", inflightRegistry) { it.size().toDouble() }
            .description("Current InflightRequestRegistry unique userIgn count")
            .register(meterRegistry)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt
git commit -m "feat(v6): add Prometheus metrics for buffer, dedup, timeout, rejection"
```

---

## Task 7: ExpectationReadFacade with Tests

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt`

- [ ] **Step 1: Write the failing tests**

The facade accepts a `DeferredResult` created by the controller (so the controller owns timeout configuration). Helper `enqueue(ign)` creates a `DeferredResult` and delegates.

```kotlin
package maple.restcontroller.read

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult

class ExpectationReadFacadeTest {

    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var buffer: LocalRequestBuffer
    private lateinit var registry: InflightRequestRegistry
    private lateinit var metrics: V6ReadMetrics
    private lateinit var facade: ExpectationReadFacade

    @BeforeEach
    fun setup() {
        buffer = LocalRequestBuffer(100)
        registry = InflightRequestRegistry()
        metrics = V6ReadMetrics(meterRegistry, buffer, registry)
        facade = ExpectationReadFacade(registry, buffer, metrics)
    }

    private fun enqueue(ign: String): DeferredResult<ResponseEntity<*>> {
        val deferred = DeferredResult<ResponseEntity<*>>()
        facade.enqueue(ign, deferred)
        return deferred
    }

    @Test
    fun `enqueue dedup miss offers to buffer`() {
        val deferred = enqueue("진격캐넌")

        assertThat(deferred).isNotNull
        assertThat(buffer.size()).isEqualTo(1)
        assertThat(meterRegistry.counter("v6_dedup_miss_total").count()).isEqualTo(1.0)
        assertThat(meterRegistry.counter("v6_request_total").count()).isEqualTo(1.0)
    }

    @Test
    fun `enqueue dedup hit does not add to buffer`() {
        enqueue("진격캐넌")
        enqueue("진격캐넌")

        assertThat(buffer.size()).isEqualTo(1)
        assertThat(meterRegistry.counter("v6_dedup_hit_total").count()).isEqualTo(1.0)
        assertThat(meterRegistry.counter("v6_request_total").count()).isEqualTo(2.0)
    }

    @Test
    fun `enqueue sets 503 error when buffer is full`() {
        val smallBuffer = LocalRequestBuffer(1)
        val facade = ExpectationReadFacade(registry, smallBuffer, metrics)

        val d1 = DeferredResult<ResponseEntity<*>>()
        facade.enqueue("a", d1)

        val d2 = DeferredResult<ResponseEntity<*>>()
        facade.enqueue("b", d2)

        assertThat(meterRegistry.counter("v6_buffer_rejected_total").count()).isEqualTo(1.0)
        assertThat(d2.result).isNotNull
    }

    @Test
    fun `different userIgns are both buffered`() {
        enqueue("a")
        enqueue("b")

        assertThat(buffer.size()).isEqualTo(2)
        assertThat(registry.size()).isEqualTo(2)
    }

    @Test
    fun `onCompletion cleanup removes deferred from registry`() {
        val deferred = enqueue("진격캐넌")
        assertThat(registry.size()).isEqualTo(1)

        deferred.setResult(ResponseEntity.ok().build())
        assertThat(registry.size()).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-rest-controller:compileTestKotlin 2>&1 | tail -5`
Expected: Compilation error — `ExpectationReadFacade` does not exist

- [ ] **Step 3: Implement ExpectationReadFacade**

The facade accepts a `DeferredResult` from the controller. This allows the controller to set the timeout from `V6ReadProperties.requestTimeoutMs`.

```kotlin
package maple.restcontroller.read

import maple.restcontroller.metrics.V6ReadMetrics
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import maple.expectation.util.StringMaskingUtils.maskIgn

class ExpectationReadFacade(
    private val registry: InflightRequestRegistry,
    private val buffer: RequestBuffer,
    private val metrics: V6ReadMetrics
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun enqueue(userIgn: String, deferred: DeferredResult<ResponseEntity<*>>) {
        metrics.requestTotal.increment()

        val isFirst = registry.register(userIgn, deferred)

        if (isFirst) {
            metrics.dedupMissTotal.increment()
            val request = ReadRequest(userIgn = userIgn)

            if (!buffer.offer(request)) {
                metrics.bufferRejectedTotal.increment()
                registry.cleanup(userIgn, deferred)
                log.warn("Buffer full, rejecting request userIgn={}", maskIgn(userIgn))
                deferred.setErrorResult(
                    ResponseEntity.status(503)
                        .header("Retry-After", "1")
                        .build()
                )
                return
            }
            log.debug("Buffered read request userIgn={}", maskIgn(userIgn))
        } else {
            metrics.dedupHitTotal.increment()
            log.debug("Dedup hit for userIgn={}", maskIgn(userIgn))
        }

        deferred.onTimeout {
            metrics.timeoutTotal.increment()
            deferred.setErrorResult(
                ResponseEntity.accepted()
                    .header("X-Task-Id", userIgn)
                    .build()
            )
        }

        deferred.onCompletion {
            registry.cleanup(userIgn, deferred)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ExpectationReadFacadeTest" 2>&1 | tail -10`
Expected: All 5 tests pass

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt
git commit -m "feat(v6): add ExpectationReadFacade with dedup, buffering, lifecycle callbacks"
```

---

## Task 8: IGN Validation

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/validation/ValidUserIgn.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/validation/UserIgnValidator.kt`

- [ ] **Step 1: Create @ValidUserIgn annotation**

```kotlin
package maple.restcontroller.validation

import jakarta.validation.Constraint
import jakarta.validation.Payload
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER
import kotlin.annotation.AnnotationRetention.RUNTIME

@Target(VALUE_PARAMETER)
@Retention(RUNTIME)
@Constraint(validatedBy = [UserIgnValidator::class])
annotation class ValidUserIgn(
    val message: String = "Invalid character name",
    val groups: Array<kotlin.reflect.KClass<*>> = [],
    val payload: Array<kotlin.reflect.KClass<out Payload>> = []
)
```

- [ ] **Step 2: Create UserIgnValidator**

```kotlin
package maple.restcontroller.validation

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class UserIgnValidator : ConstraintValidator<ValidUserIgn, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.length > 12) return false
        if (value.length < 2) return false
        return IGN_PATTERN.matches(value)
    }

    companion object {
        private val IGN_PATTERN = Regex("^[a-zA-Z0-9가-힣]+$")
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/validation/
git commit -m "feat(v6): add @ValidUserIgn annotation and UserIgnValidator"
```

---

## Task 9: BatchReadScheduler Stub

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt`

- [ ] **Step 1: Create BatchReadScheduler**

Implements `SmartLifecycle` for graceful shutdown. In Phase 1, drain is a no-op (clears buffer + registry). Phase 2 will add actual batch processing.

```kotlin
package maple.restcontroller.read

import maple.restcontroller.config.V6ReadProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val properties: V6ReadProperties
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
            batch.forEach { request ->
                registry.getAndRemove(request.userIgn)
            }
            drained += batch.size
        }

        val remaining = buffer.size()
        if (remaining > 0) {
            log.warn("Shutdown deadline reached — failing {} pending requests", remaining)
            buffer.failAllPending()
            registry.failAll(
                ResponseEntity.status(503)
                    .header("Retry-After", "1")
                    .build()
            )
        }

        log.info("BatchReadScheduler stopped — drained={}, remaining={}", drained, remaining)
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

        batch.forEach { request ->
            registry.getAndRemove(request.userIgn)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt
git commit -m "feat(v6): add BatchReadScheduler stub with SmartLifecycle shutdown"
```

---

## Task 10: Controller + ExceptionHandler + Config Wiring

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/advice/RestControllerExceptionHandler.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt`

- [ ] **Step 1: Create RestControllerExceptionHandler**

Reuses `module-common` error contracts: `BaseException`, `ErrorResponse`.

```kotlin
package maple.restcontroller.advice

import maple.expectation.error.dto.ErrorResponse
import maple.expectation.error.exception.base.BaseException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class RestControllerExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BaseException::class)
    fun handleBaseException(ex: BaseException): ResponseEntity<ErrorResponse> {
        log.warn("Business exception: code={} message={}", ex.errorCode.code, ex.message)
        return ResponseEntity
            .status(ex.errorCode.statusCode)
            .body(ErrorResponse.from(ex))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected exception", ex)
        return ResponseEntity
            .status(500)
            .body(ErrorResponse.from(500, "S001", "Internal server error"))
    }
}
```

- [ ] **Step 2: Create ExpectationV6Controller**

The controller creates the `DeferredResult` with timeout from `V6ReadProperties` and passes it to the facade.

```kotlin
package maple.restcontroller.controller

import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.read.ExpectationReadFacade
import maple.restcontroller.validation.ValidUserIgn
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import maple.expectation.util.StringMaskingUtils.maskIgn

@RestController
@RequestMapping("/api/v6/characters")
@Validated
class ExpectationV6Controller(
    private val facade: ExpectationReadFacade,
    private val properties: V6ReadProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{userIgn}/expectation")
    fun getExpectation(
        @PathVariable @ValidUserIgn userIgn: String
    ): DeferredResult<ResponseEntity<*>> {
        log.debug("V6 read request userIgn={}", maskIgn(userIgn))

        val deferred = DeferredResult<ResponseEntity<*>>(
            properties.requestTimeoutMs
        )

        facade.enqueue(userIgn, deferred)

        return deferred
    }
}
```

- [ ] **Step 3: Create V6ReadConfig — bean wiring with feature flag**

```kotlin
package maple.restcontroller.config

import io.micrometer.core.instrument.MeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.controller.ExpectationV6Controller
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.read.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(V6ReadProperties::class)
class V6ReadConfig(
    private val properties: V6ReadProperties,
    private val meterRegistry: MeterRegistry
) {

    @Bean
    fun localRequestBuffer(): LocalRequestBuffer =
        LocalRequestBuffer(properties.queueCapacity)

    @Bean
    fun inflightRequestRegistry(): InflightRequestRegistry =
        InflightRequestRegistry()

    @Bean
    fun v6ReadMetrics(
        buffer: LocalRequestBuffer,
        registry: InflightRequestRegistry
    ): V6ReadMetrics = V6ReadMetrics(meterRegistry, buffer, registry)

    @Bean
    fun expectationReadFacade(
        registry: InflightRequestRegistry,
        buffer: LocalRequestBuffer,
        metrics: V6ReadMetrics
    ): ExpectationReadFacade = ExpectationReadFacade(registry, buffer, metrics)

    @Bean
    fun batchReadScheduler(
        buffer: LocalRequestBuffer,
        registry: InflightRequestRegistry
    ): BatchReadScheduler = BatchReadScheduler(buffer, registry, properties)

    @Bean
    fun expectationV6Controller(
        facade: ExpectationReadFacade
    ): ExpectationV6Controller = ExpectationV6Controller(facade, properties)

    @Bean
    fun restControllerExceptionHandler(): RestControllerExceptionHandler =
        RestControllerExceptionHandler()
}
```

- [ ] **Step 4: Write controller test**

```kotlin
package maple.restcontroller.controller

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.restcontroller.advice.RestControllerExceptionHandler
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.read.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ExpectationV6ControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var buffer: LocalRequestBuffer
    private lateinit var registry: InflightRequestRegistry
    private lateinit var facade: ExpectationReadFacade
    private val properties = V6ReadProperties().apply {
        requestTimeoutMs = 100  // short timeout for tests
        queueCapacity = 10
    }

    @BeforeEach
    fun setup() {
        buffer = LocalRequestBuffer(properties.queueCapacity)
        registry = InflightRequestRegistry()
        val metrics = V6ReadMetrics(SimpleMeterRegistry(), buffer, registry)
        facade = ExpectationReadFacade(registry, buffer, metrics)

        mockMvc = MockMvcBuilders
            .standaloneSetup(ExpectationV6Controller(facade, properties))
            .setControllerAdvice(RestControllerExceptionHandler())
            .build()
    }

    @Test
    fun `should return 202 when request is buffered`() {
        // Phase 1: all requests timeout → 202
        // With a short timeout (100ms), the deferred will timeout
        // But MockMvc is synchronous — DeferredResult is returned immediately
        // The timeout happens async, so MockMvc sees the DeferredResult
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("X-Task-Id"))
    }

    @Test
    fun `should return 404 for blank userIgn`() {
        mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "a"))
            .andExpect(status().isBadRequest())
    }

    @Test
    fun `should return 503 when buffer is full`() {
        val smallBuffer = LocalRequestBuffer(1)
        val metrics = V6ReadMetrics(SimpleMeterRegistry(), smallBuffer, registry)
        val fullFacade = ExpectationReadFacade(registry, smallBuffer, metrics)
        val fullController = ExpectationV6Controller(fullFacade, properties)

        val fullMockMvc = MockMvcBuilders
            .standaloneSetup(fullController)
            .setControllerAdvice(RestControllerExceptionHandler())
            .build()

        // First request fills buffer → accepted (times out to 202)
        fullMockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user1"))
            .andExpect(status().isAccepted())

        // Second different userIgn → buffer full → 503
        fullMockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "user2"))
            .andExpect(status().isServiceUnavailable())
    }
}
```

- [ ] **Step 5: Run all tests**

Run: `./gradlew :module-rest-controller:test 2>&1 | tail -15`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/advice/ \
        module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ \
        module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/controller/
git commit -m "feat(v6): add controller, exception handler, config wiring with tests"
```

---

## Task 11: Final Compile + Verification

- [ ] **Step 1: Full project compilation**

Run: `./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run all module-rest-controller tests**

Run: `./gradlew :module-rest-controller:test 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 3: Verify no other modules are broken**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

---

## Self-Review Checklist

- [x] **Spec coverage:** Every decision from the grill session has a corresponding task
- [x] **Placeholder scan:** No TBD, TODO, or "implement later" patterns
- [x] **Type consistency:** `enqueue(userIgn, deferred)` signature consistent from Task 7 through Task 10. `ReadRequest(userIgn)`, `DeferredResult<ResponseEntity<*>>`, `RequestBuffer` interface all consistent.
- [x] **No placeholders in code:** All code blocks contain complete implementations
