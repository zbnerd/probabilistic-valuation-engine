# Infra Reliability Fixes (#870 + #869 + #868) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 reliability bugs — missing VT executor shutdown hooks (#870), runBlocking in Kafka consumer (#869), and Airflow 409 CONFLICT handling (#868).

**Architecture:** Two PRs split by language. PR-1 covers Kotlin fixes (8 executors missing @PreDestroy + runBlocking removal). PR-2 covers Python Airflow DAG fixes (409 handling in poll_run_completion + response_check lambdas).

**Tech Stack:** Kotlin 2.0, Java 21 Virtual Threads, Spring Kafka, kotlinx.coroutines, Python 3 Airflow DAGs

---

## File Structure

### PR-1: Kotlin Infra Fixes (#870 + #869)

| Action | File | Change |
|--------|------|--------|
| Modify | `module-infra/.../config/ExecutorConfig.kt` | Add `@PreDestroy` for `asyncExecutor`, `aiTaskExecutor` |
| Modify | `module-infra/.../config/EventConsumerConfig.kt` | Add `@PreDestroy` for high/low VT executors |
| Modify | `module-infra/.../event/EventDispatcher.kt` | Add `@PreDestroy` for `virtualThreadExecutor` |
| Modify | `module-infra/.../event/HighPriorityEventConsumer.kt` | Add `@PreDestroy` for `executor` |
| Modify | `module-infra/.../event/LowPriorityEventConsumer.kt` | Add `@PreDestroy` for `executor` |
| Modify | `module-external-api/.../auth/AuthCharacterFetchConsumer.kt` | Add `@PreDestroy` for `vtExecutor` |
| Modify | `module-calculator/.../consumer/KafkaSnapshotChunkReadyConsumer.kt` | Replace `runBlocking` with `CoroutineScope.launch` |

### PR-2: Python Airflow Fixes (#868)

| Action | File | Change |
|--------|------|--------|
| Modify | `docker/airflow/dags/daily_collection_pipeline.py` | Add 409 handling in `poll_run_completion` + `response_check` lambdas |
| Modify | `docker/airflow/dags/daily_cleanup_pipeline.py` | Add 409 handling in `response_check` lambdas |

---

## PR-1: Kotlin Infra Fixes

### Task 1: Add @PreDestroy to ExecutorConfig

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorConfig.kt:151-188`

- [ ] **Step 1: Promote VT executors to fields and add @PreDestroy**

`asyncExecutor` (line 188) and `aiTaskExecutor` (line 157) create VT executors as local variables. Promote to class fields for shutdown access.

```kotlin
// Add import
import jakarta.annotation.PreDestroy

// Add fields after line 61 (inside class body)
private val asyncVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
private val aiVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

// Change asyncExecutor bean (line 187-188) to return field:
@Bean(name = ["asyncExecutor"])
fun asyncExecutor(): ExecutorService = asyncVtExecutor

// Change aiTaskExecutor bean (line 151-182):
// - Remove line 157: val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
// - Change line 129: virtualThreadExecutor.execute(runnable) → aiVtExecutor.execute(runnable)

// Add @PreDestroy at end of class (before closing brace)
@PreDestroy
fun shutdownVirtualThreadExecutors() {
    listOf(asyncVtExecutor, aiVtExecutor).forEach { es ->
        es.shutdown()
        if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("[ExecutorConfig] VT executor did not terminate in 5s, forcing shutdown")
            es.shutdownNow()
        }
    }
    log.info("[ExecutorConfig] Virtual thread executors shut down")
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 2: Add @PreDestroy to EventConsumerConfig

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/EventConsumerConfig.kt:107-187`

- [ ] **Step 1: Promote VT executors to fields and add @PreDestroy**

The `highPriorityEventExecutor` (line 114) and `lowPriorityEventExecutor` (line 161) create VT executors as local variables inside bean methods. Promote to fields.

```kotlin
// Add import
import jakarta.annotation.PreDestroy
import java.util.concurrent.ExecutorService

// Add fields inside class (after line 51)
private val highPriorityVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
private val lowPriorityVtExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

// In highPriorityEventExecutor (line 114): replace
//   val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
// with using highPriorityVtExecutor field
// Line 129: change virtualThreadExecutor.execute(runnable) to highPriorityVtExecutor.execute(runnable)

// In lowPriorityEventExecutor (line 161): replace
//   val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
// with using lowPriorityVtExecutor field
// Line 176: change virtualThreadExecutor.execute(runnable) to lowPriorityVtExecutor.execute(runnable)

// Add @PreDestroy at end of class
@PreDestroy
fun shutdownEventExecutors() {
    listOf(highPriorityVtExecutor, lowPriorityVtExecutor).forEach { es ->
        es.shutdown()
        if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("[EventConsumerConfig] VT executor did not terminate in 5s, forcing shutdown")
            es.shutdownNow()
        }
    }
    log.info("[EventConsumerConfig] Event consumer executors shut down")
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 3: Add @PreDestroy to EventDispatcher

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/EventDispatcher.kt:24`

- [ ] **Step 1: Add @PreDestroy using safe cast**

Current line 24:
```kotlin
private val virtualThreadExecutor: Executor = if (enableAsync) Executors.newVirtualThreadPerTaskExecutor() else Executor { it.run() }
```

Keep field as-is. Use safe cast `as? ExecutorService` in @PreDestroy — no extra field needed.

```kotlin
// Add import
import jakarta.annotation.PreDestroy

// Keep line 24 unchanged. Add @PreDestroy at end of class:
@PreDestroy
fun shutdown() {
    (virtualThreadExecutor as? ExecutorService)?.let { es ->
        es.shutdown()
        if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("[EventDispatcher] VT executor did not terminate in 5s")
            es.shutdownNow()
        }
        log.info("[EventDispatcher] Virtual thread executor shut down")
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 4: Add @PreDestroy to HighPriorityEventConsumer + LowPriorityEventConsumer

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/HighPriorityEventConsumer.kt:23`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/LowPriorityEventConsumer.kt:23`

- [ ] **Step 1: Add @PreDestroy to HighPriorityEventConsumer**

Current line 23:
```kotlin
private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor()
```

Change to:
```kotlin
// Add imports
import jakarta.annotation.PreDestroy
import java.util.concurrent.ExecutorService

// Change line 23 to:
private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

// Add at end of class (before closing brace)
@PreDestroy
fun shutdown() {
    executor.shutdown()
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warn("[HighPriorityEventConsumer] VT executor did not terminate in 5s")
        executor.shutdownNow()
    }
    logger.info("[HighPriorityEventConsumer] Executor shut down")
}
```

- [ ] **Step 2: Add @PreDestroy to LowPriorityEventConsumer**

Same pattern. Current line 23:
```kotlin
private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor()
```

Change to:
```kotlin
// Add imports
import jakarta.annotation.PreDestroy
import java.util.concurrent.ExecutorService

// Change line 23 to:
private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

// Add at end of class
@PreDestroy
fun shutdown() {
    executor.shutdown()
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warn("[LowPriorityEventConsumer] VT executor did not terminate in 5s")
        executor.shutdownNow()
    }
    logger.info("[LowPriorityEventConsumer] Executor shut down")
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 5: Add @PreDestroy to AuthCharacterFetchConsumer

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt:24`

- [ ] **Step 1: Add @PreDestroy**

Current line 24:
```kotlin
private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()
```

Add import and shutdown:

```kotlin
// Add import
import jakarta.annotation.PreDestroy

// Add after line 24 (after vtExecutor field)
@PreDestroy
fun shutdown() {
    vtExecutor.shutdown()
    if (!vtExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
        log.warn("[AuthFetch] VT executor did not terminate in 5s")
        vtExecutor.shutdownNow()
    }
    log.info("[AuthFetch] VT executor shut down")
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 6: Remove runBlocking from KafkaSnapshotChunkReadyConsumer (#869)

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt`

- [ ] **Step 1: Replace runBlocking with CoroutineScope.launch**

Current file uses `runBlocking { coordinator.handle(event) }` at lines 29 and 43. Replace with async dispatch using a managed CoroutineScope. ACK happens after processing completes in the coroutine callback.

```kotlin
package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import jakarta.annotation.PreDestroy

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val coordinator: CalculatorChunkProcessingCoordinator,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        scope.launch {
            try {
                coordinator.handle(event)
                // ACK only on success — on failure, Kafka redelivers via DefaultErrorHandler → retry/DLQ
                runCatching { acknowledgment.acknowledge() }
                    .onFailure { log.warn("[Consumer] ACK failed: runId={} chunkId={}", event.runId, event.chunkId) }
            } catch (e: Exception) {
                log.error(
                    "[Consumer] chunk processing failed: runId={} chunkId={}",
                    event.runId, event.chunkId, e,
                )
                // Intentionally NOT ACKing — Kafka will redeliver. Coordinator is idempotent.
            }
        }
    }

    @KafkaListener(
        topics = ["\${calculator.kafka.urgent-snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.urgent-consumer-group-id}",
    )
    fun consumeUrgent(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received URGENT chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        scope.launch {
            try {
                coordinator.handle(event)
                runCatching { acknowledgment.acknowledge() }
                    .onFailure { log.warn("[Consumer] URGENT ACK failed: runId={} chunkId={}", event.runId, event.chunkId) }
            } catch (e: Exception) {
                log.error(
                    "[Consumer] URGENT chunk processing failed: runId={} chunkId={}",
                    event.runId, event.chunkId, e,
                )
                // Intentionally NOT ACKing — Kafka will redeliver. Coordinator is idempotent.
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        // Note: does NOT drain in-flight coroutines. Trade-off accepted because:
        // 1. coordinator.handle() is idempotent (checks existing results)
        // 2. Un-ACKed messages → Kafka redelivery on next startup
        log.info("[Consumer] Coroutine scope cancelled")
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-calculator:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 7: Full compile + test

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|BUILD|ERROR" | tail -10`
Expected: BUILD SUCCESSFUL, zero errors

- [ ] **Step 2: Run tests**

Run: `./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit PR-1**

```bash
git checkout -b fix/infra-vt-executor-shutdown-runblocking
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorConfig.kt \
       module-infra/src/main/kotlin/maple/expectation/infrastructure/config/EventConsumerConfig.kt \
       module-infra/src/main/kotlin/maple/expectation/infrastructure/event/EventDispatcher.kt \
       module-infra/src/main/kotlin/maple/expectation/infrastructure/event/HighPriorityEventConsumer.kt \
       module-infra/src/main/kotlin/maple/expectation/infrastructure/event/LowPriorityEventConsumer.kt \
       module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt \
       module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt
git commit -m "fix(infra): add @PreDestroy for VT executors + remove runBlocking

- Add @PreDestroy to 8 virtual thread executors lacking shutdown hooks (#870)
  - ExecutorConfig: asyncExecutor, aiTaskExecutor
  - EventConsumerConfig: highPriorityEventExecutor, lowPriorityEventExecutor
  - EventDispatcher, HighPriorityEventConsumer, LowPriorityEventConsumer
  - AuthCharacterFetchConsumer
- Replace runBlocking with CoroutineScope.launch in KafkaSnapshotChunkReadyConsumer (#869)
- Semaphore acquire/release paths verified safe (no leaks found)

Refs: #870, #869"
```

- [ ] **Step 4: Create PR**

```bash
git push origin fix/infra-vt-executor-shutdown-runblocking
gh pr create --base develop --title "fix(infra): VT executor shutdown hooks + runBlocking removal" --body "Fixes #870, #869

## Changes
- **#870**: Add @PreDestroy to 8 virtual thread executors missing shutdown hooks
- **#869**: Semaphore re-verified safe. Removed runBlocking from KafkaSnapshotChunkReadyConsumer, replaced with CoroutineScope.launch + async ACK

## Files Changed (7)
- module-infra: ExecutorConfig, EventConsumerConfig, EventDispatcher, HighPriorityEventConsumer, LowPriorityEventConsumer
- module-external-api: AuthCharacterFetchConsumer
- module-calculator: KafkaSnapshotChunkReadyConsumer

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## PR-2: Python Airflow Fixes

### Task 8: Add 409 CONFLICT handling to daily_collection_pipeline

**Files:**
- Modify: `docker/airflow/dags/daily_collection_pipeline.py`

- [ ] **Step 1: Add helper function and fix poll_run_completion**

Add a helper function `is_accepted_response` for reuse. Fix `poll_run_completion` to handle 409.

```python
def is_accepted_response(response):
    """Check if HTTP response indicates success or already-accepted state.
    Handles 409 CONFLICT (already running/started) as acceptance.
    """
    if response.status_code == 409:
        return True
    try:
        return response.json().get("status") in ("UP", "STARTED")
    except (ValueError, AttributeError):
        return False
```

Modify `poll_run_completion` — after `resp.raise_for_status()` on line 30, add 409 handling:

```python
def poll_run_completion(**context):
    """Poll run-status, return True when triggered run reaches terminal state."""
    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    if isinstance(trigger_response, str):
        trigger_response = json.loads(trigger_response)
    run_id = trigger_response["runId"]

    resp = requests.get("http://host.docker.internal:8081/api/internal/run-status", timeout=10)

    # 409 CONFLICT means a run is already active — treat as in-progress, not error
    if resp.status_code == 409:
        raise RuntimeError(f"Run {run_id} still in progress (409 CONFLICT - another run active)")

    resp.raise_for_status()
    data = resp.json()

    current = data.get("current")
    if not current or current.get("runId") != run_id:
        raise RuntimeError(f"Run {run_id} not yet started or runId mismatch")

    if not current.get("terminal", False):
        phase = current.get("phase", "UNKNOWN")
        raise RuntimeError(f"Run {run_id} still in progress: {phase}")

    if current.get("phase") == "FAILED":
        error = current.get("errorMessage", "unknown")
        raise RuntimeError(f"Run {run_id} failed: {error}")

    return True
```

- [ ] **Step 2: Update response_check lambdas in daily_collection_pipeline**

Replace inline lambdas with `is_accepted_response`:

```python
# Line 99: change
response_check=lambda r: r.json().get("status") == "UP",
# to:
response_check=is_accepted_response,

# Line 109: change
response_check=lambda r: r.json().get("status") == "STARTED",
# to:
response_check=is_accepted_response,
```

- [ ] **Step 3: Verify Python syntax**

Run: `python3 -c "import ast; ast.parse(open('docker/airflow/dags/daily_collection_pipeline.py').read()); print('OK')"`
Expected: OK

---

### Task 9: Add 409 CONFLICT handling to daily_cleanup_pipeline

**Files:**
- Modify: `docker/airflow/dags/daily_cleanup_pipeline.py`

- [ ] **Step 1: Add helper import and update response_check lambdas**

The helper `is_accepted_response` is defined in `daily_collection_pipeline.py` but DAG files run independently. Define the same helper in this file (or import from shared module — but these DAGs don't share modules, so inline it).

```python
# Add after imports (after line 6)

def is_accepted_response(response):
    """Check if HTTP response indicates success or already-accepted state.
    Handles 409 CONFLICT (already running/started) as acceptance.
    """
    if response.status_code == 409:
        return True
    try:
        return response.json().get("status") in ("UP", "STARTED")
    except (ValueError, AttributeError):
        return False
```

Update all 4 response_check lambdas:

```python
# Line 37: change
response_check=lambda r: r.json().get("status") == "UP",
# to:
response_check=is_accepted_response,

# Line 48: change
response_check=lambda r: r.json().get("status") == "STARTED",
# to:
response_check=is_accepted_response,

# Line 57: change
response_check=lambda r: r.json().get("status") == "STARTED",
# to:
response_check=is_accepted_response,

# Line 66: change
response_check=lambda r: r.json().get("status") == "STARTED",
# to:
response_check=is_accepted_response,
```

- [ ] **Step 2: Verify Python syntax**

Run: `python3 -c "import ast; ast.parse(open('docker/airflow/dags/daily_cleanup_pipeline.py').read()); print('OK')"`
Expected: OK

- [ ] **Step 3: Commit PR-2**

```bash
git checkout -b fix/airflow-409-conflict-handling
git add docker/airflow/dags/daily_collection_pipeline.py \
       docker/airflow/dags/daily_cleanup_pipeline.py
git commit -m "fix(airflow): handle 409 CONFLICT in DAG response checks

- Add is_accepted_response() helper treating 409 as acceptance
- Update poll_run_completion to not fail on 409 (treat as in-progress)
- Replace 6 inline response_check lambdas with helper function

Fixes #868"
```

- [ ] **Step 4: Create PR**

```bash
git push origin fix/airflow-409-conflict-handling
gh pr create --base develop --title "fix(airflow): handle 409 CONFLICT in DAG response checks" --body "Fixes #868

## Changes
- Add \`is_accepted_response()\` helper to both DAGs — treats 409 CONFLICT as success
- \`poll_run_completion\`: 409 → raise RuntimeError (Airflow retries), not crash
- 6 \`response_check\` lambdas → use shared helper

## Files Changed (2)
- docker/airflow/dags/daily_collection_pipeline.py
- docker/airflow/dags/daily_cleanup_pipeline.py

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
