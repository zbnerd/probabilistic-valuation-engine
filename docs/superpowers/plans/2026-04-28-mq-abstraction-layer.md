# MQ Abstraction Layer Implementation Plan (Phase 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create MQ abstraction layer for 3 job-pipeline queues with 3 workers migrated. PGMQ stays as runtime. Kafka migration door opens through clean interfaces.

**Architecture:** `MQTopicGroup` interface with `publish()` + `subscribe(handler)`. `PgmqTopicGroup` abstract class encapsulates poll/archive/retry/metrics. Workers register business-logic callbacks via `@PostConstruct`. Services use `DomainEventAppender` for TX-safe publishing. `IntegrationEvent` extended with schemaVersion/jobId/traceId.

**Tech Stack:** Kotlin, Spring Boot, PGMQ (runtime), Micrometer (metrics)

---

## Scope

### In Scope (Phase 1)
- 3 queues with subscribe + publish: `ocid_resolve_queue`, `nexon_api_request_queue`, `nexon_api_response_queue`
- 3 workers migrated: `OcidResolveWorker`, `NexonApiWorker`, `ApiResponseWorker`
- `CalculationJobService`: 6 `pgmqClient.send()` → `eventAppender.append()`
- 3 EventFactory objects replacing inline message construction

### Out of Scope (Phase 2+)
- `nexon_retry_queue` → `NexonApiPgmqProcessor` stays as-is
- `nexon_fanout_queue` → `NexonFanOutWorker` + `FanOutQueueProducer` (`FanOutQueuePort` impl) stay
- `donation_queue` → `DonationWorker` + `DonationQueueProducer` + `DonationPortAdapter` stay
- `calculation_queue` → `CalculationWorker` + `NexonDataQueueProducer` + `NexonApiCollectorScheduler` stay
- `expectation_calc_high/low` → `AbstractExpectationCalcWorker` (two-phase pipeline) stays on `PgmqWorker`
- **No files deleted** — `PgmqWorker`, all Producers, `PgmqWorkerConfig`, `PipelineBuffer`, `AccumulationBuffer` remain

---

## Key Design Decisions

1. **Extend existing `IntegrationEvent<T>`** — add schemaVersion, jobId, traceId with defaults. No parallel envelope.
2. **`MQTopicGroup`** — single interface for work-queue pattern (distinct from existing `MessageTopic<T>` pub/sub).
3. **`DomainEventAppender` + `PgmqEventAppender`** — `@Transactional` on appender ensures TX safety for all callers.
4. **Callback delegation** — Workers pass `(IntegrationEvent<*>, MessageHandle) -> ConsumeResult` to `subscribe()`. PgmqTopicGroup handles poll/archive/retry/metrics.
5. **Sequential processing** — Phase 1 workers are sequential (matching current behavior). Parallel processing added when `AbstractExpectationCalcWorker` migrates in Phase 2.
6. **Retry tracking** — `msg.readCount > config.maxRetries` → auto-Fail before handler invocation. Prevents infinite retries.
7. **Handler safety** — `AtomicReference` for handler storage. Null check in `pollLoop()`. Spring lifecycle guarantees `@PostConstruct` before `@Scheduled`.
8. **LegacyMessageAdapter** — converts old DTO format to `IntegrationEvent` during migration. Dual-reader for backward compatibility.
9. **Always-ack pattern** — Workers return `Ack` for both success and business failures. Retries happen at job level (via `CalculationJobService`). Queue-level `Retry` only for infrastructure failures.
10. **Payload as Map** — `IntegrationEvent<Map<String, Any>>`. Workers access fields via Map keys. Trade-off: loses compile-time DTO safety but gains Kafka migration flexibility.

---

## Reviewer Issue Resolution

| Issue | Resolution |
|-------|-----------|
| C1: Missing workers/queues | Phase 1 scoped to 3 workers + 3 queues only. Others deferred. |
| C2: PgmqWorker premature deletion | No deletions. `PgmqWorker` and all Producers stay. |
| C3: FanOutQueuePort impl | `FanOutQueueProducer` stays. Not migrated. |
| H1: Type safety loss | Deliberate trade-off for Kafka readiness. Documented in Decision 10. |
| H2: Parallel processing regression | N/A — these 3 workers are sequential today. Added in Phase 2. |
| H3: Retry count tracking | `readCount > maxRetries` check added to `PgmqTopicGroup`. |
| H4: @Transactional missing | `@Transactional` added to `PgmqEventAppender.append()`. |
| H5: Handler race condition | `AtomicReference` + Spring lifecycle guarantee (all @PostConstruct before @Scheduled). |
| M2: DonationPortAdapter | Not migrated. `DonationQueueProducer` stays. |
| M3: NexonApiCollectorScheduler | Not migrated. `NexonDataQueueProducer` stays. |

---

## File Structure

### New Files (core interfaces — module-core)

| File | Responsibility |
|------|---------------|
| `core/port/out/mq/MQTopicGroup.kt` | Topic interface: publish + subscribe |
| `core/port/out/mq/DomainEventAppender.kt` | TX-bound event publishing |
| `core/port/out/mq/ConsumeResult.kt` | Sealed class: Ack / Retry / Fail |
| `core/port/out/mq/MessageHandle.kt` | Opaque message handle (id + raw) |

### Modified Files (core)

| File | Change |
|------|--------|
| `core/domain/event/IntegrationEvent.kt` | Add schemaVersion, jobId, traceId fields |

### New Files (PGMQ implementation — module-infra)

| File | Responsibility |
|------|---------------|
| `infra/mq/pgmq/PgmqTopicGroup.kt` | Abstract PGMQ topic: poll, ack/retry/fail, metrics |
| `infra/mq/pgmq/PgmqTopicConfig.kt` | Per-topic config data class |
| `infra/mq/pgmq/PgmqEventAppender.kt` | @Transactional wrapper delegating to topic.publish() |
| `infra/mq/pgmq/LegacyMessageAdapter.kt` | Convert old DTO format to IntegrationEvent |
| `infra/mq/pgmq/topic/OcidResolveTopic.kt` | ocid_resolve_queue |
| `infra/mq/pgmq/topic/NexonApiRequestTopic.kt` | nexon_api_request_queue |
| `infra/mq/pgmq/topic/NexonApiResponseTopic.kt` | nexon_api_response_queue |
| `infra/mq/event/OcidResolveEventFactory.kt` | Factory for OCID resolve events |
| `infra/mq/event/NexonApiRequestEventFactory.kt` | Factory for API request events |
| `infra/mq/event/NexonApiResponseEventFactory.kt` | Factory for API response events |

### Modified Files (infra + app)

| File | Change |
|------|--------|
| `infra/job/CalculationJobService.kt` | Replace pgmqClient + 6 send() calls with eventAppender + EventFactory |
| `infra/worker/OcidResolveWorker.kt` | Remove @Scheduled + pgmqClient, use topic.subscribe() |
| `infra/worker/NexonApiWorker.kt` | Remove @Scheduled + pgmqClient, use topic.subscribe() |
| `app/worker/ApiResponseWorker.kt` | Remove @Scheduled + pgmqClient, use topic.subscribe() |

---

## Task 1: Core Domain Types

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/ConsumeResult.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/MessageHandle.kt`
- Modify: `module-core/src/main/kotlin/maple/expectation/core/domain/event/IntegrationEvent.kt`

- [ ] **Step 1: Create ConsumeResult sealed class**

```kotlin
package maple.expectation.core.port.out.mq

import java.time.Duration

sealed class ConsumeResult {
    data object Ack : ConsumeResult()
    data class Retry(val delay: Duration) : ConsumeResult()
    data object Fail : ConsumeResult()
}
```

- [ ] **Step 2: Create MessageHandle data class**

```kotlin
package maple.expectation.core.port.out.mq

data class MessageHandle(
    val id: Any,
    val raw: Any,
)
```

- [ ] **Step 3: Extend IntegrationEvent with schemaVersion, jobId, traceId**

Replace entire `IntegrationEvent.kt`:

```kotlin
package maple.expectation.core.domain.event

import java.time.Instant
import java.util.UUID

data class IntegrationEvent<T>(
    val eventId: String,
    val eventType: String,
    val timestamp: Long,
    val payload: T,
    val schemaVersion: Int = 1,
    val jobId: String? = null,
    val traceId: String? = null,
) {
    companion object {
        @JvmStatic
        fun <T> of(type: String, payload: T): IntegrationEvent<T> = IntegrationEvent(
            eventId = UUID.randomUUID().toString(),
            eventType = type,
            timestamp = Instant.now().toEpochMilli(),
            payload = payload,
        )
    }
}
```

All new fields have defaults — existing callers unaffected.

- [ ] **Step 4: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/mq/
git add module-core/src/main/kotlin/maple/expectation/core/domain/event/IntegrationEvent.kt
git commit -m "feat(mq): add core domain types — ConsumeResult, MessageHandle, extend IntegrationEvent"
```

---

## Task 2: MQTopicGroup + DomainEventAppender Interfaces

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/MQTopicGroup.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/DomainEventAppender.kt`

- [ ] **Step 1: Create MQTopicGroup interface**

```kotlin
package maple.expectation.core.port.out.mq

import maple.expectation.core.domain.event.IntegrationEvent

interface MQTopicGroup {
    val name: String

    fun publish(message: IntegrationEvent<*>): MessageHandle

    fun subscribe(handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult)
}
```

- [ ] **Step 2: Create DomainEventAppender interface**

```kotlin
package maple.expectation.core.port.out.mq

import maple.expectation.core.domain.event.IntegrationEvent

interface DomainEventAppender {
    fun append(topic: MQTopicGroup, message: IntegrationEvent<*>)
}
```

- [ ] **Step 3: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/mq/MQTopicGroup.kt
git add module-core/src/main/kotlin/maple/expectation/core/port/out/mq/DomainEventAppender.kt
git commit -m "feat(mq): add MQTopicGroup and DomainEventAppender interfaces"
```

---

## Task 3: PgmqTopicGroup + PgmqTopicConfig + LegacyMessageAdapter

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqTopicConfig.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/LegacyMessageAdapter.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqTopicGroup.kt`

- [ ] **Step 1: Create PgmqTopicConfig**

```kotlin
package maple.expectation.infrastructure.mq.pgmq

data class PgmqTopicConfig(
    val batchSize: Int = 10,
    val visibilityTimeoutSec: Int = 120,
    val maxRetries: Int = 3,
)
```

- [ ] **Step 2: Create LegacyMessageAdapter**

Dual reader: detects IntegrationEvent format vs legacy DTO, produces `IntegrationEvent<Map<String, Any>>` in both cases.

```kotlin
package maple.expectation.infrastructure.mq.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.domain.event.IntegrationEvent
import java.time.Instant
import java.util.UUID

class LegacyMessageAdapter(private val objectMapper: ObjectMapper) {

    fun adapt(rawPayload: Any, topicName: String): IntegrationEvent<*> {
        val tree = objectMapper.valueToTree(rawPayload)

        if (tree.has("eventId") && tree.has("eventType")) {
            return objectMapper.treeToValue(tree, IntegrationEvent::class.java)
        }

        return wrapLegacy(tree, topicName)
    }

    private fun wrapLegacy(tree: com.fasterxml.jackson.databind.JsonNode, topicName: String): IntegrationEvent<Map<String, Any>> {
        val payload = objectMapper.treeToValue(tree, Map::class.java) as Map<String, Any>
        return IntegrationEvent(
            eventId = UUID.randomUUID().toString(),
            eventType = topicName.uppercase().replace("-", "_"),
            timestamp = Instant.now().toEpochMilli(),
            payload = payload,
            schemaVersion = 1,
            jobId = payload["jobId"]?.toString(),
        )
    }
}
```

- [ ] **Step 3: Create PgmqTopicGroup**

```kotlin
package maple.expectation.infrastructure.mq.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PreDestroy
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.core.port.out.mq.MQTopicGroup
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

abstract class PgmqTopicGroup(
    private val pgmqClient: PgmqClient,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val queueMetrics: WorkerQueueMetrics,
    private val config: PgmqTopicConfig,
) : MQTopicGroup {

    private val log = LoggerFactory.getLogger(javaClass)
    private val metrics by lazy { queueMetrics.forQueue(name) }
    private val adapter by lazy { LegacyMessageAdapter(objectMapper) }
    private val handlerRef = AtomicReference<(IntegrationEvent<*>, MessageHandle) -> ConsumeResult>()

    override fun publish(message: IntegrationEvent<*>): MessageHandle {
        val context = TaskContext.of("PgmqTopic", "Publish", name)
        return executor.executeOrDefault({
            val msgId = pgmqClient.send(name, message)
            MessageHandle(id = msgId, raw = msgId)
        }, MessageHandle(-1L, -1L), context)
    }

    override fun subscribe(handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult) {
        handlerRef.set(handler)
    }

    @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
    fun pollLoop() {
        if (!lifecycleWrapper.beforeTask()) return
        val handler = handlerRef.get()
        if (handler == null) { lifecycleWrapper.afterTask(); return }

        val context = TaskContext.of("PgmqTopic", "Poll", name)
        executor.executeWithFinally({
            val messages = pgmqClient.read(name, Map::class.java, config.batchSize, config.visibilityTimeoutSec)
            metrics.updateQueueDepth(pgmqClient.queueLength(name))

            if (messages.isEmpty()) return@executeWithFinally

            messages.forEach { msg ->
                metrics.inflightIncrement()
                metrics.recordWaitDuration(msg.enqueuedAt)
            }

            messages.forEach { msg -> processMessage(msg, handler) }
        }, { lifecycleWrapper.afterTask() }, context)
    }

    private fun processMessage(
        msg: maple.expectation.infrastructure.pgmq.PgmqMessage<*>,
        handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult,
    ) {
        val context = TaskContext.of("PgmqTopic", "Process", name)
        val result = executor.executeOrDefault({
            if (msg.readCount > config.maxRetries) {
                log.warn("[{}] Max retries exceeded: msgId={}, readCount={}", name, msg.messageId, msg.readCount)
                return@executeOrDefault ConsumeResult.Fail
            }
            val envelope = adapter.adapt(msg.payload, name)
            val handle = MessageHandle(id = msg.messageId, raw = msg)
            handler.invoke(envelope, handle)
        }, ConsumeResult.Retry(Duration.ofSeconds(30)), context)

        applyResult(msg, result)
        metrics.inflightDecrement()
    }

    private fun applyResult(msg: maple.expectation.infrastructure.pgmq.PgmqMessage<*>, result: ConsumeResult) {
        when (result) {
            is ConsumeResult.Ack -> {
                pgmqClient.archive(name, msg.messageId)
                metrics.success.increment()
            }
            is ConsumeResult.Retry -> {
                pgmqClient.setVisibilityTimeout(name, msg.messageId, result.delay.seconds)
                metrics.retry.increment()
            }
            is ConsumeResult.Fail -> {
                pgmqClient.archive(name, msg.messageId)
                metrics.failure.increment()
            }
        }
    }

    @PreDestroy
    fun onShutdown() {
        handlerRef.set(null)
    }
}
```

- [ ] **Step 4: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/
git commit -m "feat(mq): add PgmqTopicGroup abstract class with retry tracking and LegacyMessageAdapter"
```

---

## Task 4: PgmqEventAppender

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqEventAppender.kt`

- [ ] **Step 1: Create PgmqEventAppender**

`@Transactional` ensures TX safety for all callers — joins existing TX or creates new one (REQUIRED propagation).

```kotlin
package maple.expectation.infrastructure.mq.pgmq

import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.core.port.out.mq.MQTopicGroup
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PgmqEventAppender : DomainEventAppender {

    @Transactional
    override fun append(topic: MQTopicGroup, message: IntegrationEvent<*>) {
        topic.publish(message)
    }
}
```

- [ ] **Step 2: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqEventAppender.kt
git commit -m "feat(mq): add PgmqEventAppender with @Transactional for same-TX publishing"
```

---

## Task 5: 3 Concrete Topic Classes

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/OcidResolveTopic.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/NexonApiRequestTopic.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/NexonApiResponseTopic.kt`

- [ ] **Step 1: Create OcidResolveTopic**

```kotlin
package maple.expectation.infrastructure.mq.pgmq.topic

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicConfig
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicGroup
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.springframework.stereotype.Component

@Component
class OcidResolveTopic(
    pgmqClient: PgmqClient,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    queueMetrics: WorkerQueueMetrics,
) : PgmqTopicGroup(
    pgmqClient, objectMapper, executor, lifecycleWrapper, queueMetrics,
    PgmqTopicConfig(batchSize = 10, visibilityTimeoutSec = 120),
) {
    override val name: String = "ocid_resolve_queue"
}
```

- [ ] **Step 2: Create NexonApiRequestTopic**

```kotlin
package maple.expectation.infrastructure.mq.pgmq.topic

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicConfig
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicGroup
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.springframework.stereotype.Component

@Component
class NexonApiRequestTopic(
    pgmqClient: PgmqClient,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    queueMetrics: WorkerQueueMetrics,
) : PgmqTopicGroup(
    pgmqClient, objectMapper, executor, lifecycleWrapper, queueMetrics,
    PgmqTopicConfig(batchSize = 5, visibilityTimeoutSec = 120),
) {
    override val name: String = "nexon_api_request_queue"
}
```

- [ ] **Step 3: Create NexonApiResponseTopic**

```kotlin
package maple.expectation.infrastructure.mq.pgmq.topic

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicConfig
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicGroup
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.springframework.stereotype.Component

@Component
class NexonApiResponseTopic(
    pgmqClient: PgmqClient,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    queueMetrics: WorkerQueueMetrics,
) : PgmqTopicGroup(
    pgmqClient, objectMapper, executor, lifecycleWrapper, queueMetrics,
    PgmqTopicConfig(batchSize = 10, visibilityTimeoutSec = 120),
) {
    override val name: String = "nexon_api_response_queue"
}
```

- [ ] **Step 4: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/
git commit -m "feat(mq): add 3 concrete PGMQ topic classes with per-topic config"
```

---

## Task 6: 3 EventFactory Classes

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/OcidResolveEventFactory.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/NexonApiRequestEventFactory.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/NexonApiResponseEventFactory.kt`

- [ ] **Step 1: Create OcidResolveEventFactory**

```kotlin
package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object OcidResolveEventFactory {
    fun create(jobId: String, userIgn: String, presetNo: Int): IntegrationEvent<Map<String, Any>> {
        return IntegrationEvent.of("OCID_RESOLVE", mapOf(
            "jobId" to jobId,
            "userIgn" to userIgn,
            "presetNo" to presetNo,
        )).copy(schemaVersion = 1, jobId = jobId)
    }
}
```

- [ ] **Step 2: Create NexonApiRequestEventFactory**

```kotlin
package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object NexonApiRequestEventFactory {
    fun create(jobId: String, ocid: String, userIgn: String, presetNo: Int, eventType: String = "FETCH_EQUIPMENT"): IntegrationEvent<Map<String, Any>> {
        return IntegrationEvent.of("NEXON_API_REQUEST", mapOf(
            "jobId" to jobId,
            "ocid" to ocid,
            "userIgn" to userIgn,
            "presetNo" to presetNo,
            "eventType" to eventType,
        )).copy(schemaVersion = 1, jobId = jobId)
    }
}
```

- [ ] **Step 3: Create NexonApiResponseEventFactory**

```kotlin
package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object NexonApiResponseEventFactory {
    fun create(jobId: String, snapshotId: String, objectKey: String, characterId: String, userIgn: String, presetNo: Int): IntegrationEvent<Map<String, Any>> {
        return IntegrationEvent.of("NEXON_API_RESPONSE", mapOf(
            "jobId" to jobId,
            "snapshotId" to snapshotId,
            "objectKey" to objectKey,
            "characterId" to characterId,
            "userIgn" to userIgn,
            "presetNo" to presetNo,
        )).copy(schemaVersion = 1, jobId = jobId)
    }
}
```

- [ ] **Step 4: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/
git commit -m "feat(mq): add EventFactory objects for OCID resolve, API request, API response"
```

---

## Task 7: Refactor CalculationJobService

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`

Replace `PgmqClient` + message DTOs with `DomainEventAppender` + typed topics + EventFactory.

**Current state (6 `pgmqClient.send()` calls):**

| Method | Queue | Message DTO |
|--------|-------|-------------|
| `requestOcidResolve()` | OCID_RESOLVE | OcidResolveMessage |
| `resolveOcidAndEnqueueApiData()` | NEXON_API_REQUEST | NexonApiRequestMessage |
| `requestApiData()` | NEXON_API_REQUEST | NexonApiRequestMessage |
| `markSnapshotReadyInternal()` | NEXON_API_RESPONSE | NexonApiResponseMessage |
| `handleApiFailure()` | NEXON_API_REQUEST | NexonApiRequestMessage |
| `handleOcidFailure()` | OCID_RESOLVE | OcidResolveMessage |

- [ ] **Step 1: Replace constructor dependencies**

```kotlin
@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
)
```

Remove: `pgmqClient: PgmqClient`, imports for `QueueNames`, `OcidResolveMessage`, `NexonApiRequestMessage`, `NexonApiResponseMessage`, `PgmqClient`.

Add imports for `DomainEventAppender`, topic classes, and EventFactory classes.

- [ ] **Step 2: Replace requestOcidResolve() send call**

```kotlin
// Before:
pgmqClient.send(QueueNames.OCID_RESOLVE, OcidResolveMessage(jobId = jobId, userIgn = userIgn, presetNo = presetNo))

// After:
eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), userIgn, presetNo))
```

- [ ] **Step 3: Replace resolveOcidAndEnqueueApiData() send call**

```kotlin
// Before:
pgmqClient.send(QueueNames.NEXON_API_REQUEST, NexonApiRequestMessage(jobId = job.jobId, ocid = ocid, userIgn = job.userIgn, presetNo = job.presetNo, eventType = "FETCH_EQUIPMENT", requestedAt = Instant.now().toString()))

// After:
eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), ocid, job.userIgn, job.presetNo))
```

- [ ] **Step 4: Replace requestApiData() send call**

```kotlin
// Before:
pgmqClient.send(QueueNames.NEXON_API_REQUEST, NexonApiRequestMessage(...))

// After:
eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), job.ocid ?: return, job.userIgn, job.presetNo))
```

- [ ] **Step 5: Replace markSnapshotReadyInternal() send call**

```kotlin
// Before:
pgmqClient.send(QueueNames.NEXON_API_RESPONSE, NexonApiResponseMessage(eventType = "SNAPSHOT_READY", jobId = jobId, snapshotId = snapshotId, objectKey = objectKey, characterId = job.ocid ?: return false, userIgn = job.userIgn, presetNo = job.presetNo))

// After:
eventAppender.append(nexonApiResponseTopic, NexonApiResponseEventFactory.create(jobId.toString(), snapshotId.toString(), objectKey, job.ocid ?: return false, job.userIgn, job.presetNo))
```

- [ ] **Step 6: Replace handleApiFailure() send call**

```kotlin
// Before:
pgmqClient.send(QueueNames.NEXON_API_REQUEST, NexonApiRequestMessage(jobId = job.jobId, ocid = job.ocid ?: return, userIgn = job.userIgn, presetNo = job.presetNo, eventType = "RETRY_FETCH", requestedAt = Instant.now().toString()))

// After:
eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), job.ocid ?: return, job.userIgn, job.presetNo, eventType = "RETRY_FETCH"))
```

- [ ] **Step 7: Replace handleOcidFailure() send call**

```kotlin
// Before:
pgmqClient.send(QueueNames.OCID_RESOLVE, OcidResolveMessage(jobId = job.jobId, userIgn = job.userIgn, presetNo = job.presetNo))

// After:
eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(job.jobId.toString(), job.userIgn, job.presetNo))
```

- [ ] **Step 8: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt
git commit -m "refactor(mq): replace pgmqClient direct calls with DomainEventAppender in CalculationJobService"
```

---

## Task 8: Refactor 3 Workers

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OcidResolveWorker.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt`
- Modify: `module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt`

All 3 workers share the same migration pattern:
1. Remove `@Scheduled` method and `pgmqClient` dependency
2. Inject typed topic
3. Add `@PostConstruct init()` that calls `topic.subscribe { envelope, handle -> handleXxx(envelope) }`
4. Move business logic to private method that returns `ConsumeResult`
5. Always return `Ack` (retries are job-level, not queue-level)

- [ ] **Step 1: Refactor OcidResolveWorker**

Replace entire file. Key changes:
- Remove: `pgmqClient`, `PgmqMessage`, `OcidResolveMessage`, `QueueNames`, `@Scheduled`, `processMessages()`, `processSingle()`
- Add: `OcidResolveTopic`, `@PostConstruct`, `subscribe()` callback, `handleResolve()` private method

```kotlin
package maple.expectation.infrastructure.worker

import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OcidResolveWorker(
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiClient: NexonApiClient,
    private val jobService: CalculationJobService,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        ocidResolveTopic.subscribe { envelope, _ -> handleResolve(envelope) }
    }

    private fun handleResolve(envelope: IntegrationEvent<*>): ConsumeResult {
        val context = TaskContext.of("OcidResolveWorker", "Resolve", envelope.payload["userIgn"].toString())
        return executor.executeOrDefault({
            val payload = envelope.payload as Map<*, *>
            val jobId = UUID.fromString(payload["jobId"].toString())
            val userIgn = payload["userIgn"].toString()

            log.info("[jobId={}] Resolving OCID for userIgn={}", jobId, userIgn)

            val ocidResponse = nexonApiClient.getOcidByCharacterName(userIgn).join()
            val ocid = ocidResponse.ocid

            if (ocid.isBlank()) {
                jobService.handleOcidFailure(jobId, "EMPTY_OCID", "Nexon API returned empty OCID")
                return@executeOrDefault ConsumeResult.Ack
            }

            val resolved = jobService.resolveOcidAndEnqueueApiData(jobId, ocid)
            if (!resolved) {
                jobService.handleOcidFailure(jobId, "TRANSITION_FAILED", "Status transition failed after OCID resolve")
            }
            log.info("[jobId={}] OCID resolved: {}", jobId, ocid)
            ConsumeResult.Ack
        }, ConsumeResult.Ack, context)
    }
}
```

- [ ] **Step 2: Refactor NexonApiWorker**

Apply same pattern. Read the full current file at `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt` first to understand all logic, then:

1. Remove: `pgmqClient`, `PgmqMessage`, `NexonApiRequestMessage`, `QueueNames`, `@Scheduled`, `processMessages()`
2. Add: `NexonApiRequestTopic`, `@PostConstruct`, subscribe callback
3. Extract processing to `handleApiRequest(envelope: IntegrationEvent<*>): ConsumeResult`
4. The current `processSingle()` logic moves into `handleApiRequest()`
5. Replace `pgmqClient.archive()` calls — PgmqTopicGroup handles archiving based on return value
6. Return `ConsumeResult.Ack` always (business failures handled by job-level retry)

```kotlin
package maple.expectation.infrastructure.worker

import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class NexonApiWorker(
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val snapshotStore: SnapshotObjectStore,
    private val jobService: CalculationJobService,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val equipmentFetchProvider: EquipmentFetchProvider
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        nexonApiRequestTopic.subscribe { envelope, _ -> handleApiRequest(envelope) }
    }

    private fun handleApiRequest(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("NexonApiWorker", "Process", userIgn)

        return executor.executeOrDefault({
            // TODO: Migrate processSingle logic here
            // - Extract jobId, ocid, presetNo from payload
            // - Call equipmentFetchProvider
            // - Call jobService.saveSnapshotAndMarkReady() or jobService.handleApiFailure()
            ConsumeResult.Ack
        }, ConsumeResult.Ack, context)
    }
}
```

**IMPORTANT:** The `handleApiRequest()` body must be filled in by reading the current `NexonApiWorker.processSingle()` logic and adapting it to:
- Read fields from `payload` Map instead of typed `message.payload`
- Remove `pgmqClient.archive()` calls (PgmqTopicGroup handles archiving)
- Return `ConsumeResult.Ack` always

- [ ] **Step 3: Refactor ApiResponseWorker**

Apply same pattern. Read the full current file at `module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt` first, then:

1. Remove: `pgmqClient`, `PgmqMessage`, `NexonApiResponseMessage`, `QueueNames`, `@Scheduled`, `processMessages()`
2. Add: `NexonApiResponseTopic`, `@PostConstruct`, subscribe callback
3. Extract to `handleApiResponse(envelope: IntegrationEvent<*>): ConsumeResult`
4. The current `processSingle()` logic moves into `handleApiResponse()`
5. Replace `pgmqClient.archive()` calls — PgmqTopicGroup handles archiving
6. Return `ConsumeResult.Ack` always

```kotlin
package maple.expectation.application.worker

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ApiResponseWorker(
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val expectationPort: ExpectationV4Port,
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val snapshotStore: SnapshotObjectStore,
    private val objectMapper: ObjectMapper,
    private val cacheManager: CacheManager,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val terminalStatuses = setOf(CalculationJobStatus.COMPLETED, CalculationJobStatus.FAILED)

    @PostConstruct
    fun init() {
        nexonApiResponseTopic.subscribe { envelope, _ -> handleApiResponse(envelope) }
    }

    private fun handleApiResponse(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("ApiResponseWorker", "Process", userIgn)

        return executor.executeOrDefault({
            // TODO: Migrate processSingle logic here
            // - Extract jobId, snapshotId, objectKey, characterId from payload
            // - Read current processSingle() and adapt
            // - Remove pgmqClient.archive() calls
            ConsumeResult.Ack
        }, ConsumeResult.Ack, context)
    }
}
```

**IMPORTANT:** The `handleApiResponse()` body must be filled in by reading the current `ApiResponseWorker.processSingle()` logic and adapting it.

- [ ] **Step 4: Compile verification**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OcidResolveWorker.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt
git add module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt
git commit -m "refactor(mq): migrate 3 workers to MQTopicGroup.subscribe() callback pattern"
```

---

## Task 9: Final Verification

- [ ] **Step 1: Full compile**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Start server and verify**

Run: `source .env && ./gradlew :module-app:bootRun`

Verify in logs:
- Topic beans created: `OcidResolveTopic`, `NexonApiRequestTopic`, `NexonApiResponseTopic`
- `PgmqEventAppender` bean created
- Workers registered subscribe callbacks
- No duplicate `@Scheduled` methods (old worker polling removed)

- [ ] **Step 4: End-to-end test**

Call expectation API and verify full pipeline:
```
REQUESTED → OCID_RESOLVING → API_REQUESTED → SNAPSHOT_READY → CALCULATING → COMPLETED
```

Check logs for:
- `[PgmqTopic]` entries showing poll/process/publish
- `[OcidResolveWorker]`, `[NexonApiWorker]`, `[ApiResponseWorker]` handling messages
- No `[PgmqClient]` direct calls from CalculationJobService or the 3 workers

- [ ] **Step 5: Commit final state**

```bash
git commit --allow-empty -m "chore(mq): MQ Abstraction Layer Phase 1 complete — 3 queues, 3 workers migrated"
```

---

## Future Work (NOT in this plan)

- **Phase 2a:** Migrate `AbstractExpectationCalcWorker` — add parallel processing to `PgmqTopicGroup` (Semaphore + ExecutorService), migrate two-phase pipeline
- **Phase 2b:** Migrate `NexonApiPgmqProcessor` → `NexonRetryTopic`
- **Phase 2c:** Migrate `NexonFanOutWorker` → `NexonFanOutTopic` (replace `FanOutQueueProducer` with topic that implements `FanOutQueuePort`)
- **Phase 2d:** Migrate `DonationWorker` + `DonationPortAdapter` → `DonationTopic`
- **Phase 2e:** Migrate `CalculationWorker` + `NexonApiCollectorScheduler` → `CalculationTopic`
- **Phase 3:** Outbox table + `OutboxEventAppender`
- **Phase 4:** Kafka relay PoC
- **Phase 5:** Per-topic Kafka migration
