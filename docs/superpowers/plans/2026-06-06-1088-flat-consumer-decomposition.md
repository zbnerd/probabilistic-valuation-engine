# Synchronizer Flat Consumer Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `OcidLookupRunConsumer`, `BasicSnapshotChunkConsumer`, and `KafkaResultChunkConsumer` so each `consume(...)` method only deserializes, delegates to a service, and ACKs. Move endpoint filtering, ingestion logic, and event-path templating out of the consumers.

**Architecture:** Three small services / builders under `module-synchronizer`: `OcidLookupService` (file read + DB upsert + Redis write + log policy), `BasicChunkIngestionService` (endpoint predicate), `ResultChunkEventPathBuilder` (storage path templating). Each is a pure `@Component` with no Kafka awareness. The existing `ChunkConsumerTemplate` continues to handle async chunk lifecycle for the two chunk consumers.

**Tech Stack:** Kotlin 1.9, Spring Boot 3, Jackson, JUnit 5 + MockK, SLF4J, Gradle (Kotlin DSL).

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/service/OcidLookupService.kt` | Ingest `SnapshotRunCompletedEvent` for `ocid-lookup` endpoint: file read, batch upsert, Redis write, log policy | Create |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt` | Unit tests for happy / empty / Redis-failure paths | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt` | Single predicate `shouldHandle(event)` for `character-basic` endpoint | Create |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt` | Unit tests for the predicate | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt` | Build source object key for result chunks | Create |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilderTest.kt` | Format test | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` | Shrink to `parse → service.ingest → ack` | Modify |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt` | Inject `BasicChunkIngestionService`; replace inline endpoint checks | Modify |
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
Expected: COMPILATION FAILURE — `Unresolved reference: OcidLookupService` (and the `OcidMappingRedisWriter` / `OcidMappingFileReader` types must exist as referenced).

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

Add import at top of file:

```kotlin
import io.mockk.verify
```

(`verify` may already be imported from Task 1.)

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

Note the dropped `log`, `fileReader`, `repository`, `ocidMappingRedisWriter` fields and their imports.

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

Create `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt`:

```kotlin
package maple.synchronizer.service

import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class BasicChunkIngestionServiceTest {
    private val service = BasicChunkIngestionService()

    @Test
    fun `shouldHandle returns true for character-basic endpoint`() {
        val event = event(endpoint = "character-basic")
        assertTrue(service.shouldHandle(event))
    }

    @Test
    fun `shouldHandle returns false for other endpoints`() {
        val event = event(endpoint = "ocid-lookup")
        assertFalse(service.shouldHandle(event))
    }

    @Test
    fun `shouldHandle returns false for blank endpoint`() {
        val event = event(endpoint = "")
        assertFalse(service.shouldHandle(event))
    }

    private fun event(endpoint: String) = SnapshotChunkReadyEvent(
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

Confirm `SnapshotChunkReadyEvent`'s constructor parameter names from the existing consumer (lines 7, 48, 56 of `BasicSnapshotChunkConsumer.kt`). The names used here are placeholders; the actual test file must use the real constructor signature discovered from the source.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.BasicChunkIngestionServiceTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: BasicChunkIngestionService` (and possibly the `SnapshotChunkReadyEvent` constructor if names differ).

- [ ] **Step 3: Write minimal implementation**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt`:

```kotlin
package maple.synchronizer.service

import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.springframework.stereotype.Component

@Component
class BasicChunkIngestionService {
    fun shouldHandle(event: SnapshotChunkReadyEvent): Boolean = event.endpoint == "character-basic"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.service.BasicChunkIngestionServiceTest" -i`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt \
        module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt
git commit -m "feat(synchronizer): add BasicChunkIngestionService.shouldHandle predicate"
```

---

## Task 5: Wire `BasicChunkIngestionService` into `BasicSnapshotChunkConsumer`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`

- [ ] **Step 1: Add constructor parameter and replace inline endpoint check**

In `BasicSnapshotChunkConsumer.kt`:

1. Add import: `import maple.synchronizer.service.BasicChunkIngestionService`
2. Add to constructor parameters (position it before `chunkConsumerTemplate` for readability):
   ```kotlin
   private val ingestionService: BasicChunkIngestionService,
   ```
3. In `consume(...)` (around line 50) replace:
   ```kotlin
   if (event.endpoint != "character-basic") {
       acknowledgment.acknowledge()
       return
   }
   ```
   with:
   ```kotlin
   if (!ingestionService.shouldHandle(event)) {
       acknowledgment.acknowledge()
       return
   }
   ```
4. In `consumeUrgentBasic(...)` (around line 76) apply the same replacement.

- [ ] **Step 2: Compile + run synchronizer tests**

Run: `./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue && ./gradlew :module-synchronizer:test -i`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt
git commit -m "refactor(synchronizer): BasicSnapshotChunkConsumer uses BasicChunkIngestionService"
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
Expected: BUILD SUCCESSFUL — all tests pass, including the 8 new tests added in Tasks 1, 2, 4, 6.

- [ ] **Step 3: Sanity-check consumer file sizes**

Run:
```bash
wc -l module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt \
       module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt \
       module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt
```
Expected: `OcidLookupRunConsumer.kt` is roughly 30 lines (down from 65), the other two unchanged in line count but with one indirection added.

- [ ] **Step 4: Verify no leftover hardcoded path template**

Run: `grep -n "runs/\\\${" module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/*.kt || echo "no matches"`
Expected: `no matches` — the only `runs/$runId/...` template is in `ResultChunkEventPathBuilder.kt`.

- [ ] **Step 5: Commit any verification artefacts (none expected)**

If everything is clean, no further commit is needed. If verification surfaces a stray reference, fix it and commit as a follow-up.

---

## Self-Review

**Spec coverage:**
- §2.A Extract `OcidLookupService` → Task 1 (create) + Task 2 (tests) + Task 3 (consumer refactor). Covered.
- §2.B `BasicChunkIngestionService` → Task 4 (create) + Task 5 (wire into consumer). Covered.
- §2.C `ResultChunkEventPathBuilder` → Task 6 (create) + Task 7 (wire into consumer). Covered.
- §2 acceptance criteria (`./gradlew :module-synchronizer:test`, compileKotlin/compileJava) → Task 8. Covered.

**Placeholder scan:** No "TBD" or "implement later" markers. All step 3 / 1 code blocks are complete. Task 4 Step 1 calls out the constructor parameter-name placeholder explicitly because the test must mirror the real `SnapshotChunkReadyEvent` signature — this is intentional, not a real placeholder.

**Type consistency:** `OcidLookupService.ingest(event: SnapshotRunCompletedEvent)` used in both Task 1 test and Task 3 consumer. `BasicChunkIngestionService.shouldHandle(event: SnapshotChunkReadyEvent): Boolean` used in Task 4 test and Task 5 consumer. `ResultChunkEventPathBuilder.sourceObjectKey(runId, sourceEndpoint, chunkId): String` used in Task 6 test and Task 7 consumer. Consistent.
