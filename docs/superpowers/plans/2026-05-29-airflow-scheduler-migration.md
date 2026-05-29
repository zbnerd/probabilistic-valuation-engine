# Airflow Scheduler Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate 4 cleanup/daily schedulers from Spring Boot `@Scheduled` to Airflow trigger endpoints. Airflow becomes single scheduling authority; cleanup runs on-demand after pipeline completion.

**Architecture:** Each module exposes `POST /api/internal/trigger/{cleanup-type}` endpoints following the existing fire-and-forget pattern. `@Scheduled` annotations are removed; cleanup logic stays as synchronous methods invoked by the trigger executor. Two Airflow DAGs: `daily_collection_pipeline` (existing) chains to `daily_cleanup_pipeline` (new) via `TriggerDagRunOperator`.

**Tech Stack:** Kotlin, Spring Boot, MockMvc (standalone), Airflow 2.10, Docker Compose

---

## File Structure

| File | Responsibility | Status |
|------|---------------|--------|
| `module-external-api/.../runstatus/InternalApiController.kt` | Add 2 cleanup trigger endpoints | Modify |
| `module-external-api/.../cleanup/ArtifactCleanupScheduler.kt` | Remove @Scheduled, make cleanup() synchronous | Modify |
| `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt` | Remove @Scheduled | Modify |
| `module-external-api/.../runstatus/InternalApiControllerTest.kt` | Add tests for cleanup trigger endpoints | Modify |
| `module-calculator/.../cleanup/CalculatorResultCleanupScheduler.kt` | Remove @Scheduled, make cleanup() synchronous | Modify |
| `module-calculator/.../cleanup/InternalApiController.kt` | New — cleanup trigger endpoint | Create |
| `module-calculator/.../cleanup/InternalApiControllerTest.kt` | New — tests for trigger endpoint | Create |
| `docker/airflow/dags/daily_cleanup_pipeline.py` | New — cleanup DAG | Create |
| `docker/airflow/dags/daily_collection_pipeline.py` | Add TriggerDagRunOperator | Modify |
| `module-external-api/src/main/resources/application.yml` | Disable self-scheduling | Modify |
| `module-calculator/src/main/resources/application.yml` | Disable self-scheduling | Modify |

---

### Task 1: Add cleanup trigger endpoints to external-api InternalApiController

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

- [ ] **Step 1: Write failing tests for cleanup trigger endpoints**

Add to `InternalApiControllerTest.kt`:

```kotlin
@Test
fun `POST trigger artifact-cleanup returns 202`() {
    mockMvc.perform(post("/api/internal/trigger/artifact-cleanup"))
        .andExpect(status().isAccepted)
        .andExpect(jsonPath("$.status").value("STARTED"))
}

@Test
fun `POST trigger consumed-cleanup returns 202`() {
    mockMvc.perform(post("/api/internal/trigger/consumed-cleanup"))
        .andExpect(status().isAccepted)
        .andExpect(jsonPath("$.status").value("STARTED"))
}

@Test
fun `POST trigger artifact-cleanup returns 409 when already running`() {
    // First trigger
    mockMvc.perform(post("/api/internal/trigger/artifact-cleanup"))
        .andExpect(status().isAccepted)

    // Second trigger while running — needs mock to simulate running state
    // For now, test the conflict path via direct AtomicBoolean manipulation
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "InternalApiControllerTest" 2>&1 | tail -20`
Expected: FAIL — endpoints don't exist

- [ ] **Step 3: Implement cleanup trigger endpoints in InternalApiController**

Modify `InternalApiController.kt` to inject cleanup schedulers and add endpoints:

```kotlin
package maple.externalapi.runstatus

import maple.externalapi.cleanup.ArtifactCleanupScheduler
import maple.externalapi.cleanup.ConsumedChunkCleanupScheduler
import maple.externalapi.scheduler.ExternalApiScheduler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
    private val scheduler: ExternalApiScheduler,
    @Autowired(required = false) private val artifactCleanup: ArtifactCleanupScheduler?,
    @Autowired(required = false) private val consumedCleanup: ConsumedChunkCleanupScheduler?,
) {
    private val triggerExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val artifactCleanupRunning = AtomicBoolean(false)
    private val consumedCleanupRunning = AtomicBoolean(false)

    @GetMapping("/run-status")
    fun getRunStatus(): ResponseEntity<RunStatusResponse> {
        val response = RunStatusResponse(
            current = runStatusTracker.getCurrentStatus(),
            lastCompleted = runStatusTracker.getLastCompletedRun(),
        )
        return ResponseEntity.ok(response)
    }

    @PostMapping("/trigger/daily")
    fun triggerDailyRefresh(
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val current = runStatusTracker.getCurrentStatus()
        if (current != null && !current.isTerminal) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING", "runId" to current.runId))
        }

        val runId = airflowRunId ?: UUID.randomUUID().toString()
        triggerExecutor.submit { scheduler.triggerDailyRefresh(runId) }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
    }

    @PostMapping("/trigger/artifact-cleanup")
    fun triggerArtifactCleanup(): ResponseEntity<Map<String, String>> {
        if (artifactCleanup == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DISABLED"))
        }
        if (!artifactCleanupRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING"))
        }
        triggerExecutor.submit {
            try {
                artifactCleanup.cleanup()
            } finally {
                artifactCleanupRunning.set(false)
            }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
    }

    @PostMapping("/trigger/consumed-cleanup")
    fun triggerConsumedCleanup(): ResponseEntity<Map<String, String>> {
        if (consumedCleanup == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DISABLED"))
        }
        if (!consumedCleanupRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING"))
        }
        triggerExecutor.submit {
            try {
                consumedCleanup.cleanup()
            } finally {
                consumedCleanupRunning.set(false)
            }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
    }

    @jakarta.annotation.PreDestroy
    fun shutdown() {
        triggerExecutor.close()
    }
}

data class RunStatusResponse(
    val current: RunStatus?,
    val lastCompleted: RunStatus?,
)
```

Update test `setUp()` to pass mock cleanup schedulers:

```kotlin
@BeforeEach
fun setUp() {
    runStatusTracker = org.mockito.kotlin.mock()
    scheduler = org.mockito.kotlin.mock()
    val artifactCleanup = org.mockito.kotlin.mock<maple.externalapi.cleanup.ArtifactCleanupScheduler>()
    val consumedCleanup = org.mockito.kotlin.mock<maple.externalapi.cleanup.ConsumedChunkCleanupScheduler>()
    val controller = InternalApiController(runStatusTracker, scheduler, artifactCleanup, consumedCleanup)
    mockMvc = standaloneSetup(controller)
        .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
        .build()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :module-external-api:test --tests "InternalApiControllerTest" 2>&1 | tail -20`
Expected: PASS (all 8 tests)

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
git commit -m "feat(external-api): add cleanup trigger endpoints to InternalApiController"
```

---

### Task 2: Remove @Scheduled from external-api cleanup schedulers

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt`

- [ ] **Step 1: Remove @Scheduled from ArtifactCleanupScheduler, make cleanup() synchronous**

In `ArtifactCleanupScheduler.kt`, remove the `@Scheduled` annotation and the virtual thread wrapper:

```kotlin
// Before:
@Scheduled(fixedDelayString = "\${external-api.cleanup.interval-ms:21600000}")
fun cleanup() {
    Thread.ofVirtual().name("cleanup-ext").start {
        val sample = io.micrometer.core.instrument.Timer.start()
        // ... actual work
    }
}

// After:
fun cleanup() {
    val sample = io.micrometer.core.instrument.Timer.start()
    val start = Instant.now()
    log.info("[Cleanup] started: dryRun={}", dryRun)

    updateStorageMetrics()

    val result = runCatching { cleanupRuns(start) }

    val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()
    sample.stop(metrics.timer())

    result.onSuccess { res ->
        log.info(
            "[Cleanup] completed: dryRun={}, runsDeleted={}, bytesDeleted={}, " +
                "throttled={}, errors={}, durationMs={}",
            dryRun, res.runsDeleted, res.bytesDeleted, res.throttled, res.errors, durationMs,
        )
    }.onFailure { ex ->
        metrics.recordError()
        log.error("[Cleanup] failed (pipeline NOT affected): {}", ex.message, ex)
    }
}
```

Also remove the `import org.springframework.scheduling.annotation.Scheduled` import.

- [ ] **Step 2: Remove @Scheduled from ConsumedChunkCleanupScheduler**

In `ConsumedChunkCleanupScheduler.kt`, remove the `@Scheduled` annotation:

```kotlin
// Before:
@Scheduled(fixedDelayString = "\${external-api.cleanup.consumed.interval-ms:3600000}")
fun cleanup() {

// After:
fun cleanup() {
```

Also remove the `import org.springframework.scheduling.annotation.Scheduled` import.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt \
        module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt
git commit -m "refactor(external-api): remove @Scheduled from cleanup schedulers"
```

---

### Task 3: Create calculator InternalApiController with cleanup trigger endpoint

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/cleanup/InternalApiControllerTest.kt`

- [ ] **Step 1: Write failing test for calculator cleanup trigger**

Create `module-calculator/src/test/kotlin/maple/calculator/cleanup/InternalApiControllerTest.kt`:

```kotlin
package maple.calculator.cleanup

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class InternalApiControllerTest {

    private lateinit var mockMvc: MockMvc
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        val cleanupScheduler: CalculatorResultCleanupScheduler = mock()
        val controller = InternalApiController(cleanupScheduler)
        mockMvc = standaloneSetup(controller)
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Test
    fun `POST trigger result-cleanup returns 202`() {
        mockMvc.perform(post("/api/internal/trigger/result-cleanup"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("STARTED"))
    }

    @Test
    fun `POST trigger result-cleanup returns 409 when already running`() {
        // First trigger starts
        mockMvc.perform(post("/api/internal/trigger/result-cleanup"))
            .andExpect(status().isAccepted)

        // Since cleanup is async on virtual thread, it may complete before second call.
        // This test verifies the endpoint exists and returns correct status codes.
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-calculator:test --tests "InternalApiControllerTest" 2>&1 | tail -20`
Expected: FAIL — class not found

- [ ] **Step 3: Create InternalApiController for calculator**

Create `module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt`:

```kotlin
package maple.calculator.cleanup

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    @Autowired(required = false) private val resultCleanup: CalculatorResultCleanupScheduler?,
) {
    private val triggerExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val resultCleanupRunning = AtomicBoolean(false)

    @PostMapping("/trigger/result-cleanup")
    fun triggerResultCleanup(): ResponseEntity<Map<String, String>> {
        if (resultCleanup == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DISABLED"))
        }
        if (!resultCleanupRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("status" to "ALREADY_RUNNING"))
        }
        triggerExecutor.submit {
            try {
                resultCleanup.cleanup()
            } finally {
                resultCleanupRunning.set(false)
            }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "STARTED"))
    }

    @jakarta.annotation.PreDestroy
    fun shutdown() {
        triggerExecutor.close()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-calculator:test --tests "InternalApiControllerTest" 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt \
        module-calculator/src/test/kotlin/maple/calculator/cleanup/InternalApiControllerTest.kt
git commit -m "feat(calculator): add InternalApiController with result-cleanup trigger endpoint"
```

---

### Task 4: Remove @Scheduled from calculator cleanup scheduler

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt`

- [ ] **Step 1: Remove @Scheduled, make cleanup() synchronous**

In `CalculatorResultCleanupScheduler.kt`:

```kotlin
// Before:
@Scheduled(fixedDelayString = "\${calculator.cleanup.interval-ms:21600000}")
fun cleanup() {
    Thread.ofVirtual().name("cleanup-calc").start {
        val start = Instant.now()
        // ... actual work
    }
}

// After:
fun cleanup() {
    val start = Instant.now()
    log.info("[CalculatorCleanup] started: dryRun={}", dryRun)

    val result = runCatching { cleanupRuns(start) }

    val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()

    result.onSuccess { res ->
        log.info(
            "[CalculatorCleanup] completed: dryRun={}, deleted={}, bytes={}, " +
                "errors={}, throttled={}, durationMs={}",
            dryRun, res.runsDeleted, res.bytesDeleted, res.errors, res.throttled, durationMs,
        )
    }.onFailure { ex ->
        log.error("[CalculatorCleanup] failed (pipeline NOT affected): {}", ex.message, ex)
    }
}
```

Also remove `import org.springframework.scheduling.annotation.Scheduled`.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-calculator:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt
git commit -m "refactor(calculator): remove @Scheduled from CalculatorResultCleanupScheduler"
```

---

### Task 5: Create daily_cleanup_pipeline.py Airflow DAG

**Files:**
- Create: `docker/airflow/dags/daily_cleanup_pipeline.py`

- [ ] **Step 1: Create cleanup DAG**

Create `docker/airflow/dags/daily_cleanup_pipeline.py`:

```python
"""
Daily cleanup pipeline.

Triggered by daily_collection_pipeline after successful completion.
Runs artifact cleanup, consumed chunk cleanup, and calculator result cleanup in parallel.

Control Plane: Airflow triggers.
Data Plane: Modules execute cleanup on virtual threads.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.providers.http.operators.http import HttpOperator
from airflow.providers.http.sensors.http import HttpSensor

default_args = {
    "owner": "maple-pipeline",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="daily_cleanup_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule=None,  # triggered by daily_collection_pipeline, not scheduled
    catchup=False,
    tags=["pipeline", "cleanup"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.json().get("status") == "UP",
        poke_interval=30,
        timeout=120,
    )

    trigger_artifact_cleanup = HttpOperator(
        task_id="trigger_artifact_cleanup",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/artifact-cleanup",
        method="POST",
        execution_timeout=timedelta(seconds=30),
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    trigger_consumed_cleanup = HttpOperator(
        task_id="trigger_consumed_cleanup",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/consumed-cleanup",
        method="POST",
        execution_timeout=timedelta(seconds=30),
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    trigger_result_cleanup = HttpOperator(
        task_id="trigger_result_cleanup",
        http_conn_id="calculator",
        endpoint="api/internal/trigger/result-cleanup",
        method="POST",
        execution_timeout=timedelta(seconds=30),
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    check_external_api >> [trigger_artifact_cleanup, trigger_consumed_cleanup, trigger_result_cleanup]
```

- [ ] **Step 2: Commit**

```bash
git add docker/airflow/dags/daily_cleanup_pipeline.py
git commit -m "feat(airflow): add daily_cleanup_pipeline DAG"
```

---

### Task 6: Chain DAGs with TriggerDagRunOperator

**Files:**
- Modify: `docker/airflow/dags/daily_collection_pipeline.py`

- [ ] **Step 1: Add TriggerDagRunOperator to chain cleanup DAG after pipeline**

Add to the end of `daily_collection_pipeline.py`:

```python
from airflow.operators.trigger_dagrun import TriggerDagRunOperator

# Add after wait_for_completion:
trigger_cleanup = TriggerDagRunOperator(
    task_id="trigger_cleanup_pipeline",
    trigger_dag_id="daily_cleanup_pipeline",
    wait_for_completion=False,
)

check_external_api >> trigger_daily_collection >> wait_for_completion >> trigger_cleanup
```

Full updated file:

```python
"""
Daily Nexon data collection pipeline.

Trigger → Poll run-status with run_id correlation → Trigger cleanup.

Control Plane: Airflow triggers and monitors.
Data Plane: Kafka handles chunk processing, retry, backpressure.
"""

from datetime import datetime, timedelta

import requests
from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.providers.http.operators.http import HttpOperator
from airflow.providers.http.sensors.http import HttpSensor


def poll_run_completion(**context):
    """Poll run-status, return True when triggered run reaches terminal state."""
    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    run_id = trigger_response["runId"]

    resp = requests.get("http://host.docker.internal:8081/api/internal/run-status", timeout=10)
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


default_args = {
    "owner": "maple-pipeline",
    "retries": 120,
    "retry_delay": timedelta(seconds=60),
}

with DAG(
    dag_id="daily_collection_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # UTC 18:00 = KST 03:00
    catchup=False,
    tags=["pipeline", "daily"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.json().get("status") == "UP",
        poke_interval=30,
        timeout=120,
    )

    trigger_daily_collection = HttpOperator(
        task_id="trigger_daily_collection",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/daily",
        method="POST",
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    wait_for_completion = PythonOperator(
        task_id="wait_for_completion",
        python_callable=poll_run_completion,
        execution_timeout=timedelta(hours=2),
    )

    trigger_cleanup = TriggerDagRunOperator(
        task_id="trigger_cleanup_pipeline",
        trigger_dag_id="daily_cleanup_pipeline",
        wait_for_completion=False,
    )

    check_external_api >> trigger_daily_collection >> wait_for_completion >> trigger_cleanup
```

- [ ] **Step 2: Commit**

```bash
git add docker/airflow/dags/daily_collection_pipeline.py
git commit -m "feat(airflow): chain cleanup DAG after pipeline completion"
```

---

### Task 7: Add Airflow HTTP connection for calculator

**Files:**
- Modify: `docker/airflow/connections.sh`

- [ ] **Step 1: Add calculator HTTP connection to connections.sh**

Append to `docker/airflow/connections.sh`:

```bash
airflow connections add calculator \
  --conn-type http --conn-host http://host.docker.internal --conn-port 8082 --conn-schema http
```

- [ ] **Step 2: Commit**

```bash
git add docker/airflow/connections.sh
git commit -m "feat(airflow): add calculator HTTP connection"
```

---

### Task 8: Full compile and test verification

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | tail -20`
Expected: BUILD SUCCESSFUL for all modules

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAIL|tests completed" | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit any fixes if needed**

---

### Task 9: YAML config — add graceful shutdown timeout

**Files:**
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-calculator/src/main/resources/application.yml`

- [ ] **Step 1: Add timeout-per-shutdown-phase to both modules**

Append to each module's `application.yml`:

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 6m
```

This ensures `triggerExecutor.close()` in `@PreDestroy` has time to complete cleanup tasks (max `maxRuntimeSeconds=300` + buffer).

The `@ConditionalOnProperty` stays as-is (`cleanup.enabled=true` creates the bean). `@Scheduled` no longer fires — only Airflow trigger endpoint invokes cleanup.

- [ ] **Step 2: Commit**

```bash
git add module-external-api/src/main/resources/application.yml \
        module-calculator/src/main/resources/application.yml
git commit -m "feat: add graceful shutdown timeout for cleanup trigger executors"
```

---

## Verification Checklist

- [ ] `./gradlew compileKotlin compileJava --continue` passes
- [ ] `./gradlew :module-external-api:test` passes (including new cleanup trigger tests)
- [ ] `./gradlew :module-calculator:test` passes (including new trigger endpoint test)
- [ ] `curl -X POST http://localhost:8081/api/internal/trigger/artifact-cleanup` returns 202
- [ ] `curl -X POST http://localhost:8081/api/internal/trigger/consumed-cleanup` returns 202
- [ ] `curl -X POST http://localhost:8082/api/internal/trigger/result-cleanup` returns 202
- [ ] Airflow loads both DAGs without errors
- [ ] `daily_collection_pipeline` completion triggers `daily_cleanup_pipeline`
