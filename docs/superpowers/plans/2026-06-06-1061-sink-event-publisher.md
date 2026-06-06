# #1061 — SinkEventPublisher Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the 3 event-publish methods (`publishChunkReady`, `publishRunCompleted`, `publishRunFailed`) from `ChunkedSnapshotSink` to a new `SinkEventPublisher` class, plus a `publishSafely(event, name)` helper to remove the duplicated try-catch + exceptionally pattern.

**Architecture:** Single-responsibility split. Sink keeps write orchestration (queue lifecycle, chunk rotation, file I/O). Publisher owns event emission + error isolation.

**Tech Stack:** Kotlin, Spring Kafka, KafkaTemplate, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SinkEventPublisher.kt` | NEW — 3 publish methods + `publishSafely` helper |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` | MODIFIED — drops publisher deps + 3 methods |

---

## Task 1: Create `SinkEventPublisher`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SinkEventPublisher.kt`

- [ ] **Step 1: Create the publisher**

```kotlin
package maple.externalapi.snapshot

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.RunCompletedEvent
import maple.expectation.common.event.RunFailedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * Owns event emission for [ChunkedSnapshotSink]. The sink delegates here so
 * write orchestration and event policy evolve independently.
 */
@Component
class SinkEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val log = LoggerFactory.getLogger(SinkEventPublisher::class.java)

    fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<*> =
        publishSafely(event, "SnapshotChunkReady")

    fun publishRunCompleted(event: RunCompletedEvent): CompletableFuture<*> =
        publishSafely(event, "RunCompleted")

    fun publishRunFailed(event: RunFailedEvent): CompletableFuture<*> =
        publishSafely(event, "RunFailed")

    /** Common try-catch + exceptionally pattern. Send failures are logged but do not
     *  propagate — the sink must finish draining its queue even if the broker is down. */
    private fun publishSafely(event: Any, name: String): CompletableFuture<*> = try {
        kafkaTemplate.send(TOPIC, event).exceptionally { ex ->
            log.error("[SinkEventPublisher] {} send failed: {}", name, ex.message, ex)
            null
        }
    } catch (ex: Exception) {
        log.error("[SinkEventPublisher] {} send threw synchronously: {}", name, ex.message, ex)
        CompletableFuture.completedFuture(null)
    }

    companion object {
        const val TOPIC: String = "external-api.snapshot.chunk-ready"
    }
}
```

Note: verify the event class names match the actual `maple.expectation.common.event` package — adjust if the repo uses different class names (`SnapshotChunkReadyEvent` is the only one explicitly mentioned in the issue; the other two may be inferred from the consumer side).

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SinkEventPublisher.kt
git commit -m "refactor(ext-api): add SinkEventPublisher with publishSafely helper (#1061)"
```

---

## Task 2: Refactor `ChunkedSnapshotSink` to delegate

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`

- [ ] **Step 1: Inject `SinkEventPublisher`, drop `KafkaTemplate`**

Constructor: replace `kafkaTemplate: KafkaTemplate<String, Any>` with `eventPublisher: SinkEventPublisher`. Drop unused import of `KafkaTemplate`.

- [ ] **Step 2: Replace 3 publish methods with delegation**

```kotlin
fun publishChunkReady(event: SnapshotChunkReadyEvent) = eventPublisher.publishChunkReady(event)
fun publishRunCompleted(event: RunCompletedEvent) = eventPublisher.publishRunCompleted(event)
fun publishRunFailed(event: RunFailedEvent) = eventPublisher.publishRunFailed(event)
```

Or, if these are called from inside the sink as `this.publishChunkReady(...)`, just route through the publisher — no need for forwarding methods if the callers can be updated to call `eventPublisher.publishChunkReady(event)` directly.

- [ ] **Step 3: Compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
./gradlew :module-external-api:test --console=plain
```

Expected: compile success, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
git commit -m "refactor(ext-api): ChunkedSnapshotSink delegates to SinkEventPublisher (#1061)"
```

---

## Task 3: Final verification

- [ ] **Step 1: Sink no longer imports `KafkaTemplate`**

```bash
grep -n "KafkaTemplate" module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
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

- **Spec coverage:** Spec's 3 publish methods + `publishSafely` helper ✅. Three components ✅.
- **Placeholder scan:** No TBD/TODO. Event class names flagged as "verify" — implementer must match.
- **Type consistency:** `publishSafely` returns `CompletableFuture<*>` to match existing call sites' expected types.
