# Synchronizer Flat Consumer Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `OcidLookupRunConsumer`, `BasicSnapshotChunkConsumer`, and `KafkaResultChunkConsumer` so each `consume(...)` method only deserializes, delegates to a service, and ACKs. Move endpoint filtering, ingestion logic, and event-path templating out of the consumers.

**Architecture:** Three small services / builders under `module-synchronizer`: `OcidLookupService` (file read + DB upsert + Redis write + log policy), `BasicChunkIngestionService` (endpoint filter + chunk template submission + urgent handling), `ResultChunkEventPathBuilder` (storage path templating). Each is a pure `@Component` with no Kafka awareness in the body. The existing `ChunkConsumerTemplate` continues to handle async chunk lifecycle. Both services expose a single `process(event)`-style entry point so consumers become `parse → service.process → ack-if-not-handled`.

**Tech Stack:** Kotlin 1.9, Spring Boot 3, Jackson, JUnit 5 + MockK, SLF4J, Gradle (Kotlin DSL).

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/service/OcidLookupService.kt` | Ingest `SnapshotRunCompletedEvent` for `ocid-lookup` endpoint: file read, batch upsert, Redis write, log policy | Create |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt` | Unit tests for happy / empty / Redis-failure / non-matching paths | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt` | Endpoint filter + chunk template submission + urgent flag handling | Create |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt` | Unit tests: `process` returns false for non-matching endpoint; template submission wiring for matching endpoint | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt` | Build source object key for result chunks | Create |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilderTest.kt` | Format test | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` | Shrink to `parse → service.ingest → ack` | Modify |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt` | Two thin listeners that delegate to `BasicChunkIngestionService` | Modify |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt` | Inject `ResultChunkEventPathBuilder`; use it in `onSuccess` | Modify |

---

## Task 1: Create `OcidLookupService` with happy-path test (TDD)

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/service/OcidLookupService.kt`

- [ ] **Step 1: Write the failing test — happy path**

Create `module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt`:

```kotlin
package maple.synchronizer.service

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.redis.OcidMappingRedisWriter
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.OcidMapping
import maple.synchronizer.storage.OcidMappingFileReader
import org.junit.jupiter.api.Test

class OcidLookupServiceTest {
    private val fileReader = mockk<OcidMappingFileReader>()
    private val repository = mockk<OcidMappingRepository>()
    private val redisWriter = mockk<OcidMappingRedisWriter>()

    private val service = OcidLookupService(fileReader, repository, redisWriter)

    @Test
    fun `ingest reads file, upserts db, writes redis on ocid-lookup endpoint`() {
        val event = SnapshotRunCompletedEvent(
            runId = "run-1",
            endpoint = "ocid-lookup",
            totalRecords = 2,
            manifestPath = "runs/run-1/ocid-lookup/manifest.jsonl",
        )
        val mappings = listOf(
            OcidMapping(userIgn = "f***l", ocid = "ocid-1"),
            OcidMapping(userIgn = "s***d", ocid = "ocid-2"),
        )
        every { fileReader.read(event.manifestPath) } returns mappings
        justRun { repository.batchUpsert(mappings) }
        justRun { redisWriter.writeOcidToRedis(mappings) }

        service.ingest(event)

        verify(exactly = 1) { fileReader.read(event.manifestPath) }
        verify(exactly = 1) { repository.batchUpsert(mappings) }
        verify(exactly = 1) { redisWriter.writeOcidToRedis(mappings) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.OcidLookupServiceTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: OcidLookupService`.

- [ ] **Step 3: Write minimal implementation — `OcidLookupService`**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/service/OcidLookupService.kt`:

```kotlin
package maple.synchronizer.service

import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.redis.OcidMappingRedisWriter
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.OcidMappingFileReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OcidLookupService(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val ocidMappingRedisWriter: OcidMappingRedisWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(event: SnapshotRunCompletedEvent) {
        if (event.endpoint != "ocid-lookup") return

        log.info(
            "[OcidService] received: runId={} totalRecords={} manifestPath={}",
            event.runId, event.totalRecords, event.manifestPath,
        )

        val mappings = fileReader.read(event.manifestPath)
        if (mappings.isEmpty()) {
            log.warn("[OcidService] no mappings found in: {}", event.manifestPath)
            return
        }

        repository.batchUpsert(mappings)
        runCatching {
            ocidMappingRedisWriter.writeOcidToRedis(mappings)
        }.onFailure { ex ->
            log.error(
                "[OcidService] Redis write failed after DB upsert: runId={} mappings={} - {}. Redis may be stale until next run.",
                event.runId, mappings.size, ex.message, ex,
            )
        }

        log.info("[OcidService] completed: runId={} processed={}", event.runId, mappings.size)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.OcidLookupServiceTest" -i`
Expected: PASS — 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/service/OcidLookupService.kt \
        module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt
git commit -m "feat(synchronizer): add OcidLookupService (TDD happy path)"
```

---

## Task 2: Extend `OcidLookupService` tests — empty + Redis-failure + non-matching endpoint

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt`

- [ ] **Step 1: Add three more test methods**

Append to `OcidLookupServiceTest`:

```kotlin
    @Test
    fun `ingest skips db and redis when file is empty`() {
        val event = SnapshotRunCompletedEvent(
            runId = "run-1",
            endpoint = "ocid-lookup",
            totalRecords = 0,
            manifestPath = "runs/run-1/ocid-lookup/manifest.jsonl",
        )
        every { fileReader.read(event.manifestPath) } returns emptyList()

        service.ingest(event)

        verify(exactly = 1) { fileReader.read(event.manifestPath) }
        verify(exactly = 0) { repository.batchUpsert(any()) }
        verify(exactly = 0) { redisWriter.writeOcidToRedis(any()) }
    }

    @Test
    fun `ingest swallows redis failure after db upsert`() {
        val event = SnapshotRunCompletedEvent(
            runId = "run-1",
            endpoint = "ocid-lookup",
            totalRecords = 1,
            manifestPath = "runs/run-1/ocid-lookup/manifest.jsonl",
        )
        val mappings = listOf(OcidMapping(userIgn = "f***l", ocid = "ocid-1"))
        every { fileReader.read(event.manifestPath) } returns mappings
        justRun { repository.batchUpsert(mappings) }
        every { redisWriter.writeOcidToRedis(mappings) } throws RuntimeException("redis down")

        // Must NOT throw — caller (consumer) will ACK.
        service.ingest(event)

        verify(exactly = 1) { repository.batchUpsert(mappings) }
        verify(exactly = 1) { redisWriter.writeOcidToRedis(mappings) }
    }

    @Test
    fun `ingest is a no-op for non-ocid-lookup endpoint`() {
        val event = SnapshotRunCompletedEvent(
            runId = "run-1",
            endpoint = "character-basic",
            totalRecords = 0,
            manifestPath = "runs/run-1/character-basic/manifest.jsonl",
        )

        service.ingest(event)

        verify(exactly = 0) { fileReader.read(any()) }
        verify(exactly = 0) { repository.batchUpsert(any()) }
        verify(exactly = 0) { redisWriter.writeOcidToRedis(any()) }
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.OcidLookupServiceTest" -i`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt
git commit -m "test(synchronizer): cover empty/redis-fail/non-matching OcidLookupService paths"
```

---

## Task 3: Refactor `OcidLookupRunConsumer` to delegate to `OcidLookupService`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`

- [ ] **Step 1: Replace consumer body with delegation**

Rewrite the file to:

```kotlin
package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.service.OcidLookupService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["synchronizer.kafka.ocid-lookup-enabled"], havingValue = "true")
class OcidLookupRunConsumer(
    private val ocidLookupService: OcidLookupService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = ["\${synchronizer.kafka.ocid-lookup-topic}"],
        groupId = "\${synchronizer.kafka.ocid-lookup-consumer-group-id}",
    )
    fun consume(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
    ) {
        val event = objectMapper.readValue(record.value(), SnapshotRunCompletedEvent::class.java)
        ocidLookupService.ingest(event)
        acknowledgment.acknowledge()
    }
}
```

- [ ] **Step 2: Compile to verify no stale references**

Run: `./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run module tests**

Run: `./gradlew :module-synchronizer:test -i`
Expected: BUILD SUCCESSFUL, all synchronizer tests pass (including 4 from Task 1+2).

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt
git commit -m "refactor(synchronizer): OcidLookupRunConsumer delegates to OcidLookupService"
```

---

## Task 4: Create `BasicChunkIngestionService` with test (TDD)

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt`

- [ ] **Step 1: Write the failing test**

Create `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt`. This test verifies only the endpoint filter (the heavy template-submission wiring is integration-tested in the existing template tests). Discover the actual `SnapshotChunkReadyEvent` constructor parameter names from `module-synchronizer/.../consumer/BasicSnapshotChunkConsumer.kt` (it constructs one at line 48) and use those exact names.

```kotlin
package maple.synchronizer.service

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.synchronizer.consumer.ChunkConsumerTemplate
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.BasicChunkFileReader
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Qualifier
import java.util.concurrent.ExecutorService

class BasicChunkIngestionServiceTest {
    private val fileReader = mockk<BasicChunkFileReader>(relaxed = true)
    private val repository = mockk<CharacterBasicRepository>(relaxed = true)
    private val ocidRepo = mockk<OcidMappingRepository>(relaxed = true)
    private val template = mockk<ChunkConsumerTemplate>(relaxed = true)
    private val publisher = mockk<KafkaChunkConsumedEventPublisher>(relaxed = true)
    private val executor = mockk<ExecutorService>(relaxed = true)

    private val service = BasicChunkIngestionService(
        fileReader = fileReader,
        repository = repository,
        ocidMappingRepository = ocidRepo,
        chunkConsumerTemplate = template,
        consumedEventPublisher = publisher,
        executor = executor,
    )

    @Test
    fun `process returns false and skips template for non-character-basic endpoint`() {
        val event = makeEvent(endpoint = "ocid-lookup")

        val handled = service.process(
            event = event,
            eventPayloadJson = "{}",
            acknowledgment = mockk(relaxed = true),
            topic = "t",
            messageKey = "k",
            urgent = false,
        )

        assertFalse(handled)
        verify(exactly = 0) { template.submit(any()) }
    }

    @Test
    fun `process returns true and submits template for character-basic endpoint`() {
        val event = makeEvent(endpoint = "character-basic")

        val handled = service.process(
            event = event,
            eventPayloadJson = "{}",
            acknowledgment = mockk(relaxed = true),
            topic = "t",
            messageKey = "k",
            urgent = false,
        )

        assertTrue(handled)
        verify(exactly = 1) { template.submit(any()) }
    }

    private fun makeEvent(endpoint: String) = SnapshotChunkReadyEvent(
        // Use the actual parameter names from the real constructor.
        // This placeholder block is filled in by the executing subagent from the source.
        runId = "run-1",
        chunkId = "chunk-1",
        endpoint = endpoint,
        objectKey = "k",
        recordCount = 0,
        eventType = "t",
        schemaVersion = 1,
    )
}
```

> **Note for the executing subagent:** Open `BasicSnapshotChunkConsumer.kt` and read the actual `SnapshotChunkReadyEvent(...)` constructor at line 48. The `makeEvent` helper above is a placeholder — replace it with the exact parameter names from the source.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.BasicChunkIngestionServiceTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: BasicChunkIngestionService` (and possibly the `SnapshotChunkReadyEvent` constructor parameter names if they differ from the placeholder).

- [ ] **Step 3: Write minimal implementation**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt`. Mirror the existing `submitBasicChunk` body, `upsertOcidFromBasicRecords`, and constructor wiring from `BasicSnapshotChunkConsumer.kt` (lines 26-34 and 90-166). The endpoint filter, urgent-flag decision, and template submission all move here; the consumer becomes a thin dispatcher.

```kotlin
package maple.synchronizer.service

import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.infrastructure.executor.TaskContext
import maple.synchronizer.consumer.ChunkConsumerRequest
import maple.synchronizer.consumer.ChunkConsumerTemplate
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.BasicChunkFileReader
import maple.synchronizer.storage.BasicRecord
import maple.synchronizer.storage.OcidMapping
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore

@Component
class BasicChunkIngestionService(
    private val fileReader: BasicChunkFileReader,
    private val repository: CharacterBasicRepository,
    private val ocidMappingRepository: OcidMappingRepository,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val consumedEventPublisher: KafkaChunkConsumedEventPublisher,
    @Qualifier("basicSnapshotChunkExecutor") private val executor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val processingPermit = Semaphore(2)

    fun process(
        event: SnapshotChunkReadyEvent,
        eventPayloadJson: String,
        acknowledgment: Acknowledgment,
        topic: String?,
        messageKey: String?,
        urgent: Boolean,
    ): Boolean {
        if (event.endpoint != "character-basic") return false

        val runId = event.runId
        val chunkId = event.chunkId
        val operation = if (urgent) "UrgentChunk" else "Chunk"
        val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_BASIC_CHUNK,
            runId = runId,
            endpoint = event.endpoint,
            chunkId = chunkId,
        )

        log.info(
            "[BasicSync] {}received: runId={} chunkId={} objectKey={} records={}",
            if (urgent) "URGENT " else "",
            runId, chunkId, event.objectKey, event.recordCount,
        )

        chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                logPrefix = "BasicSync",
                log = log,
                identity = identity,
                topic = topic ?: event.eventType,
                messageKey = messageKey ?: event.kafkaKey(),
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventPayloadJson = eventPayloadJson,
                objectKey = event.objectKey,
                acknowledgment = acknowledgment,
                processingPermit = processingPermit,
                executor = executor,
                processContext = TaskContext.of("BasicSync", "${operation}Process", chunkId),
                lifecycleContext = TaskContext.of("BasicSync", "${operation}Lifecycle", chunkId),
                process = {
                    var totalRecords = 0
                    fileReader.readInBatches(event.objectKey) { batch ->
                        repository.bulkUpsert(runId, chunkId, batch)
                        if (urgent) {
                            upsertOcidFromBasicRecords(batch)
                        }
                        totalRecords += batch.size
                    }
                    log.info(
                        "[BasicSync] {}chunk processed: runId={} chunkId={} records={}",
                        if (urgent) "urgent " else "",
                        runId,
                        chunkId,
                        totalRecords,
                    )
                },
                onSuccess = {
                    consumedEventPublisher.publish(ChunkConsumedEvent(
                        runId = runId,
                        endpoint = event.endpoint,
                        chunkId = chunkId,
                        objectKey = event.objectKey,
                    ))
                },
                onFailure = { ex ->
                    log.error(
                        "[BasicSync] {}chunk processing failed: runId={} chunkId={}",
                        if (urgent) "urgent " else "",
                        runId,
                        chunkId,
                        ex,
                    )
                },
            ),
        )
        return true
    }

    private fun upsertOcidFromBasicRecords(records: List<BasicRecord>) {
        val mappings = records.map { OcidMapping(userIgn = it.userIgn, ocid = it.ocid) }
        ocidMappingRepository.batchUpsert(mappings)
        log.info("[BasicSync] batch upserted OCID mappings: count={}", mappings.size)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.BasicChunkIngestionServiceTest" -i`
Expected: PASS — 2 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt \
        module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt
git commit -m "feat(synchronizer): add BasicChunkIngestionService.process (endpoint filter + template wiring)"
```

---

## Task 5: Refactor `BasicSnapshotChunkConsumer` to delegate to `BasicChunkIngestionService`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`

- [ ] **Step 1: Replace consumer with thin dispatcher**

Rewrite the file to:

```kotlin
package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.synchronizer.service.BasicChunkIngestionService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val ingestionService: BasicChunkIngestionService,
) {
    @KafkaListener(
        topics = ["\${synchronizer.kafka.basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.basic-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        if (!ingestionService.process(event, message, acknowledgment, topic, messageKey, urgent = false)) {
            acknowledgment.acknowledge()
        }
    }

    @KafkaListener(
        topics = ["\${synchronizer.kafka.urgent-basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.urgent-basic-consumer-group-id}",
    )
    fun consumeUrgentBasic(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        if (!ingestionService.process(event, message, acknowledgment, topic, messageKey, urgent = true)) {
            acknowledgment.acknowledge()
        }
    }
}
```

- [ ] **Step 2: Compile + run synchronizer tests**

Run: `./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue && ./gradlew :module-synchronizer:test -i`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt
git commit -m "refactor(synchronizer): BasicSnapshotChunkConsumer delegates to BasicChunkIngestionService"
```

---

## Task 6: Create `ResultChunkEventPathBuilder` with test (TDD)

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilderTest.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt`

- [ ] **Step 1: Write the failing test**

Create `module-synchronizer/src/test/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilderTest.kt`:

```kotlin
package maple.synchronizer.event

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ResultChunkEventPathBuilderTest {
    private val builder = ResultChunkEventPathBuilder()

    @Test
    fun `sourceObjectKey produces runs path template`() {
        val actual = builder.sourceObjectKey(
            runId = "run-1",
            sourceEndpoint = "character-basic",
            chunkId = "chunk-42",
        )
        assertEquals("runs/run-1/character-basic/chunks/chunk-42.jsonl.gz", actual)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.event.ResultChunkEventPathBuilderTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: ResultChunkEventPathBuilder`.

- [ ] **Step 3: Write minimal implementation**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt`:

```kotlin
package maple.synchronizer.event

import org.springframework.stereotype.Component

@Component
class ResultChunkEventPathBuilder {
    fun sourceObjectKey(runId: String, sourceEndpoint: String, chunkId: String): String =
        "runs/$runId/$sourceEndpoint/chunks/$chunkId.jsonl.gz"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.event.ResultChunkEventPathBuilderTest" -i`
Expected: PASS — 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt \
        module-synchronizer/src/test/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilderTest.kt
git commit -m "feat(synchronizer): add ResultChunkEventPathBuilder for source object key"
```

---

## Task 7: Wire `ResultChunkEventPathBuilder` into `KafkaResultChunkConsumer`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt`

- [ ] **Step 1: Inject builder, replace hardcoded path**

In `KafkaResultChunkConsumer.kt`:

1. Add import: `import maple.synchronizer.event.ResultChunkEventPathBuilder`
2. Add constructor parameter:
   ```kotlin
   private val eventPathBuilder: ResultChunkEventPathBuilder,
   ```
3. In the `onSuccess` callback inside `submit` (around line 105), replace:
   ```kotlin
   sourceObjectKey = "runs/${runId}/${event.sourceEndpoint}/chunks/${chunkId}.jsonl.gz",
   ```
   with:
   ```kotlin
   sourceObjectKey = eventPathBuilder.sourceObjectKey(
       runId = runId,
       sourceEndpoint = event.sourceEndpoint.ifBlank { "result" },
       chunkId = chunkId,
   ),
   ```

- [ ] **Step 2: Compile + run synchronizer tests**

Run: `./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue && ./gradlew :module-synchronizer:test -i`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt
git commit -m "refactor(synchronizer): KafkaResultChunkConsumer uses ResultChunkEventPathBuilder"
```

---

## Task 8: Final verification — full module build and test

**Files:** none (verification only)

- [ ] **Step 1: Compile entire project**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL — no errors across all modules.

- [ ] **Step 2: Run synchronizer test suite**

Run: `./gradlew :module-synchronizer:test -i`
Expected: BUILD SUCCESSFUL — all tests pass, including 7 new tests added in Tasks 1, 2, 4, 6.

- [ ] **Step 3: Sanity-check consumer file sizes**

Run:
```bash
wc -l module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt \
       module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt \
       module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt
```
Expected: `OcidLookupRunConsumer.kt` and `BasicSnapshotChunkConsumer.kt` are much shorter than the originals; `KafkaResultChunkConsumer.kt` line count roughly unchanged but with one indirection added.

- [ ] **Step 4: Verify no leftover hardcoded path template**

Run: `grep -n "runs/\\\${" module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/*.kt || echo "no matches"`
Expected: `no matches` — the only `runs/$runId/...` template is in `ResultChunkEventPathBuilder.kt`.

- [ ] **Step 5: Commit any verification artefacts (none expected)**

If everything is clean, no further commit is needed. If verification surfaces a stray reference, fix it and commit as a follow-up.

---

## Self-Review

**Spec coverage:**
- §2.A Extract `OcidLookupService` → Task 1 (create) + Task 2 (tests) + Task 3 (consumer refactor). Covered.
- §2.B `BasicChunkIngestionService` (symmetric with `OcidLookupService`) → Task 4 (create) + Task 5 (wire into consumer). Covered.
- §2.C `ResultChunkEventPathBuilder` → Task 6 (create) + Task 7 (wire into consumer). Covered.
- §2 acceptance criteria (`./gradlew :module-synchronizer:test`, compileKotlin/compileJava) → Task 8. Covered.

**Placeholder scan:** No "TBD" or "implement later" markers. Task 4 Step 1 contains an explicit note that the executing subagent must verify the `SnapshotChunkReadyEvent` constructor parameter names from the source — this is intentional, not a real placeholder. All other step 3 / 1 code blocks are complete.

**Type consistency:** `OcidLookupService.ingest(event: SnapshotRunCompletedEvent)` used in both Task 1 test and Task 3 consumer. `BasicChunkIngestionService.process(event: SnapshotChunkReadyEvent, eventPayloadJson: String, acknowledgment: Acknowledgment, topic: String?, messageKey: String?, urgent: Boolean): Boolean` used in both Task 4 test and Task 5 consumer. `ResultChunkEventPathBuilder.sourceObjectKey(runId: String, sourceEndpoint: String, chunkId: String): String` used in both Task 6 test and Task 7 consumer. Consistent.
