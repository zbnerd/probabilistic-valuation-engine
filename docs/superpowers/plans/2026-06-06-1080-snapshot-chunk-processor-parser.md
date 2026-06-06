# #1080 — SnapshotChunkProcessor JSON Parsing Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove `ObjectMapper` direct usage from `SnapshotChunkProcessor` and `KafkaSnapshotChunkReadyConsumer` by extracting a `SnapshotLineParser` (parse raw line → typed result or null) and a sample-log serializer, plus splitting the Kafka consumer into a deserializer + dispatcher service.

**Architecture:** Parser/service split — parser knows JSON shape, service knows business decisions (status check, dispatch, sample logging). Kafka consumer becomes a thin envelope extractor; ACK/retry decisions move into `SnapshotDispatchService`.

**Tech Stack:** Kotlin, Jackson `ObjectMapper`, Kotlin Coroutines, Spring Kafka, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotLineParser.kt` | NEW — `ObjectMapper` user, returns typed result |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SampleLogSerializer.kt` | NEW — formatting for sample log only |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` | MODIFIED — drops `ObjectMapper` field, uses parser/serializer |
| `module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt` | NEW — owns ACK/retry decisions for snapshot chunks |
| `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt` | MODIFIED — deserializes envelope, delegates to dispatcher |

---

## Task 1: Extract `SnapshotLineParser`

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotLineParser.kt`

- [ ] **Step 1: Create the parser**

```kotlin
package maple.calculator.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Result of parsing a single snapshot JSONL line. `null` payload signals
 * the line is intentionally skipped (e.g. non-SUCCESS status) — distinct
 * from a parse error, which the parser surfaces as a [RuntimeException].
 */
data class SnapshotRecord(
    val ocid: String,
    val body: JsonNode,
)

@Component
class SnapshotLineParser(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Parse one JSONL line. Returns `null` if the line's `status` field is
     * not "SUCCESS" or the body is missing — these are valid skips, not errors.
     */
    fun parse(line: String): SnapshotRecord? {
        val node = objectMapper.readTree(line)
        if (node.path("status").asText() != "SUCCESS") return null
        val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: return null
        return SnapshotRecord(ocid = node.path("key").asText(""), body = body)
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-calculator:compileKotlin --console=plain
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotLineParser.kt
git commit -m "refactor(calculator): extract SnapshotLineParser (#1080)"
```

---

## Task 2: Extract `SampleLogSerializer`

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/processor/SampleLogSerializer.kt`

- [ ] **Step 1: Create the serializer**

```kotlin
package maple.calculator.processor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import maple.calculator.model.CalculationResult
import org.springframework.stereotype.Component

/** Wraps `ObjectMapper.writeValueAsString` so the processor stays free of JSON concerns. */
@Component
class SampleLogSerializer(
    private val objectMapper: ObjectMapper,
) {
    /** Format a calculation result for the sample-debug log. Returns the input's
     *  `toString()` representation if serialization fails so logging never throws. */
    fun serialize(result: CalculationResult): String = try {
        objectMapper.writeValueAsString(result)
    } catch (ex: JsonProcessingException) {
        "<<unserializable: ${ex.originalMessage}>> ${result.ocid}:${result.presetNo}"
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-calculator:compileKotlin --console=plain
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/processor/SampleLogSerializer.kt
git commit -m "refactor(calculator): extract SampleLogSerializer (#1080)"
```

---

## Task 3: Refactor `SnapshotChunkProcessor`

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt`

- [ ] **Step 1: Drop `ObjectMapper` field, add parser + serializer**

Replace the constructor's `private val objectMapper: ObjectMapper` parameter with two new params:
```kotlin
private val lineParser: SnapshotLineParser,
private val sampleLogSerializer: SampleLogSerializer,
```

- [ ] **Step 2: Replace `parseLines` body to delegate to the parser**

Old:
```kotlin
val node = objectMapper.readTree(line)
if (node.path("status").asText() != "SUCCESS") continue
val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: continue
val ocid = node.path("key").asText("")
successCount.incrementAndGet()
```

New:
```kotlin
val record = lineParser.parse(line) ?: continue
successCount.incrementAndGet()
val ocid = record.ocid
val body = record.body
```

- [ ] **Step 3: Replace `logSample` body**

Old:
```kotlin
log.debug("[SAMPLE] {}", objectMapper.writeValueAsString(result))
```

New:
```kotlin
log.debug("[SAMPLE] {}", sampleLogSerializer.serialize(result))
```

- [ ] **Step 4: Compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-calculator:compileKotlin --console=plain
./gradlew :module-calculator:test --console=plain
```

Expected: compile success, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt
git commit -m "refactor(calculator): route SnapshotChunkProcessor through parser/serializer (#1080)"
```

---

## Task 4: Extract `SnapshotDispatchService`

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt`

- [ ] **Step 1: Create the dispatcher**

```kotlin
package maple.calculator.consumer

import maple.calculator.metrics.CalculatorMetricsListener
import maple.calculator.processor.SnapshotChunkProcessor
import maple.calculator.event.ChunkProcessingEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service

/**
 * Owns ACK/retry decisions for incoming snapshot chunks. The Kafka consumer
 * delegates here after deserialization so transport and policy are separate.
 */
@Service
class SnapshotDispatchService(
    private val chunkProcessor: SnapshotChunkProcessor,
    private val metrics: CalculatorMetricsListener,
) {
    private val log = LoggerFactory.getLogger(SnapshotDispatchService::class.java)

    fun dispatch(event: SnapshotChunkReadyEvent, ack: Acknowledgment?) {
        try {
            chunkProcessor.process(event, /* resultObjectKey */ "calculator/runs/${event.runId}/${event.endpoint}/chunks/result-${event.chunkId}.jsonl.gz")
            ack?.acknowledge()
        } catch (ex: Exception) {
            log.error("[Dispatch] chunk dispatch failed: runId={} chunkId={}: {}", event.runId, event.chunkId, ex.message, ex)
            metrics.onEvent(ChunkProcessingEvent.Failed(event.runId, event.chunkId))
            throw ex
        }
    }
}
```

Note: `SnapshotChunkReadyEvent` already lives in `maple.expectation.common.event` per the current import path — verify and match.

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-calculator:compileKotlin --console=plain
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt
git commit -m "refactor(calculator): add SnapshotDispatchService for ACK policy (#1080)"
```

---

## Task 5: Refactor `KafkaSnapshotChunkReadyConsumer`

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`

- [ ] **Step 1: Replace inline process + ACK with dispatch call**

The consumer's `consume`/`consumeUrgent` methods should:
1. Extract the envelope (event object) from the Kafka record
2. Call `dispatchService.dispatch(event, ack)`
3. ACK only on successful return (handled inside `SnapshotDispatchService`)

Remove inline `chunkProcessor.process(...)` calls, inline ACK code, and any inline error-log/metric-on-failure.

- [ ] **Step 2: Compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-calculator:compileKotlin --console=plain
./gradlew :module-calculator:test --console=plain
```

Expected: compile success, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt
git commit -m "refactor(calculator): consumer delegates to SnapshotDispatchService (#1080)"
```

---

## Task 6: Final verification

- [ ] **Step 1: Confirm no `ObjectMapper` in processor/consumer paths**

```bash
grep -rn "ObjectMapper" module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt
```

Expected: no output.

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-calculator:test --console=plain
```

Expected: all pass.

---

## Self-Review

- **Spec coverage:** Three spec components (parser, log serializer, dispatcher) covered. Consumer delegates per spec.
- **Placeholder scan:** No TBD/TODO.
- **Type consistency:** `SnapshotRecord` field names match processor call sites.
