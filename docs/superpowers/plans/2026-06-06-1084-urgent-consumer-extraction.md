# #1084 — UrgentCharacterRequestConsumer Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove `ObjectMapper`, `Files.createDirectories`, and `kafkaTemplate.send` direct calls from `UrgentCharacterRequestConsumer` by extracting 3 dedicated classes.

**Architecture:** Consumer becomes orchestration only — 3 helpers handle the JSON, file, and Kafka concerns. The artifact writer delegates to the existing `ArtifactStorePort` rather than touching `Files` directly.

**Tech Stack:** Kotlin, Jackson, Spring Kafka, NIO Files, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-external-api/src/main/kotlin/maple/externalapi/parser/UrgentOcidResponseParser.kt` | NEW — HTTP response → OCID |
| `module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt` | NEW — wraps `ArtifactStorePort` for urgent chunks |
| `module-external-api/src/main/kotlin/maple/externalapi/event/UrgentEventPublisher.kt` | NEW — domain event → JSON → Kafka |
| `module-external-api/src/main/kotlin/maple/externalapi/consumer/UrgentCharacterRequestConsumer.kt` | MODIFIED — orchestration only |

---

## Task 1: Create `UrgentOcidResponseParser`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/parser/UrgentOcidResponseParser.kt`

- [ ] **Step 1: Create the parser**

```kotlin
package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class UrgentOcidResponseParser(
    private val objectMapper: ObjectMapper,
) {
    /** Extract OCID from a Nexon urgent-request HTTP response. Returns null if absent. */
    fun extractOcid(responseBody: String): String? {
        val root = objectMapper.readTree(responseBody)
        val ocid = root.path("ocid").asText()
        return ocid.takeIf { it.isNotBlank() }
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/parser/UrgentOcidResponseParser.kt
git commit -m "refactor(ext-api): add UrgentOcidResponseParser (#1084)"
```

---

## Task 2: Create `UrgentChunkArtifactWriter`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt`

- [ ] **Step 1: Create the writer**

```kotlin
package maple.externalapi.artifact

import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.springframework.stereotype.Component

/**
 * Writes urgent chunk artifacts via [ExternalApiArtifactStorePort] so the
 * consumer never touches `Files` or `GZIP` directly. The store handles
 * GZIP compression and directory creation internally.
 */
@Component
class UrgentChunkArtifactWriter(
    private val artifactStore: ExternalApiArtifactStorePort,
) {
    fun writeChunk(objectKey: String, rows: List<String>) {
        val payload = rows.joinToString(separator = "\n", postfix = "\n")
        artifactStore.writeString(objectKey, payload)
    }
}
```

Note: `ExternalApiArtifactStorePort` must expose a `writeString(key, payload)` method. If only streaming APIs exist, add a `writeString` default method to the port or use the existing `openOutputStream(key).use { it.write(...) }` pattern.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt
git commit -m "refactor(ext-api): add UrgentChunkArtifactWriter (#1084)"
```

---

## Task 3: Create `UrgentEventPublisher`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/event/UrgentEventPublisher.kt`

- [ ] **Step 1: Create the publisher**

```kotlin
package maple.externalapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.UrgentNotFoundEvent
import maple.expectation.common.event.UrgentChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * Serializes urgent domain events to JSON and publishes to Kafka. The
 * consumer never calls `kafkaTemplate.send` or `objectMapper.writeValueAsString`
 * directly.
 */
@Component
class UrgentEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(UrgentEventPublisher::class.java)

    fun publishChunkReady(event: UrgentChunkReadyEvent): CompletableFuture<*> = try {
        kafkaTemplate.send(TOPIC_CHUNK_READY, objectMapper.writeValueAsString(event))
    } catch (ex: Exception) {
        log.error("[UrgentEventPublisher] chunk-ready send failed: {}", ex.message, ex)
        CompletableFuture.completedFuture(null)
    }

    fun publishNotFound(event: UrgentNotFoundEvent): CompletableFuture<*> = try {
        kafkaTemplate.send(TOPIC_NOT_FOUND, objectMapper.writeValueAsString(event))
    } catch (ex: Exception) {
        log.error("[UrgentEventPublisher] not-found send failed: {}", ex.message, ex)
        CompletableFuture.completedFuture(null)
    }

    companion object {
        const val TOPIC_CHUNK_READY: String = "external-api.urgent.chunk-ready"
        const val TOPIC_NOT_FOUND: String = "external-api.urgent.not-found"
    }
}
```

Note: verify event class names + topic names from the actual consumer code before committing.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/event/UrgentEventPublisher.kt
git commit -m "refactor(ext-api): add UrgentEventPublisher (#1084)"
```

---

## Task 4: Refactor `UrgentCharacterRequestConsumer`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/consumer/UrgentCharacterRequestConsumer.kt`

- [ ] **Step 1: Inject 3 helpers, drop 3 fields**

Replace `objectMapper`, `kafkaTemplate`, and any `Files`/`GzipJsonlChunkWriter` direct deps with `urgentOcidResponseParser`, `urgentChunkArtifactWriter`, `urgentEventPublisher`.

- [ ] **Step 2: Replace call sites**

In `processUrgentCharacterAsync`:
- `objectMapper.readTree(responseBody).path("ocid")...` → `urgentOcidResponseParser.extractOcid(responseBody)`

In `publishUrgentChunkAsync`:
- `Files.createDirectories(...)` + `GzipJsonlChunkWriter(...)` → `urgentChunkArtifactWriter.writeChunk(objectKey, rows)`
- Domain event creation + `objectMapper.writeValueAsString` + `kafkaTemplate.send` → `urgentEventPublisher.publishChunkReady(event)`

In `publishNotFoundAsync`:
- Same: → `urgentEventPublisher.publishNotFound(event)`

- [ ] **Step 3: Compile + test + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
./gradlew :module-external-api:test --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/consumer/UrgentCharacterRequestConsumer.kt
git commit -m "refactor(ext-api): consumer delegates to parser/writer/publisher (#1084)"
```

---

## Task 5: Final verification

- [ ] **Step 1: No direct deps in consumer**

```bash
grep -nE "ObjectMapper|kafkaTemplate\.send|Files\.create|GZIPInputStream|GZIPOutputStream" \
  module-external-api/src/main/kotlin/maple/externalapi/consumer/UrgentCharacterRequestConsumer.kt
```

Expected: no output.

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:test --console=plain
```

Expected: all pass.

---

## Self-Review

- **Spec coverage:** 3 spec components (parser, writer, publisher) covered. Consumer refactored.
- **Placeholder scan:** Event class + topic names flagged for verification.
- **Type consistency:** All return types match what the consumer needs from the helpers.
