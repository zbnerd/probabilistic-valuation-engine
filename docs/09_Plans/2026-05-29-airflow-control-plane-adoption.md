# Airflow Control Plane Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt Apache Airflow as control plane for batch scheduling and observability, while preserving Kafka-based data plane for real-time event processing. Enable single-node to multi-node expansion via Coolify + Docker Compose.

**Architecture:** Airflow = Control Plane (trigger, poll, notify). Kafka = Data Plane (chunk processing, event routing). Coolify = Deployment Layer. MinIO = Shared Object Storage (Phase 3+). Layered Docker Compose files map to Coolify service groups for flexible node assignment.

**Tech Stack:** Apache Airflow 2.10 (Python 3.11), Docker Compose, PostgreSQL (Airflow metadata + application DB), MinIO (Phase 3), Coolify (Phase 4), Kotlin/Spring Boot (existing services)

---

## Scope Check

This plan covers 5 phases. Each phase produces working, independently verifiable output:
- **Phase 1-2:** Fully detailed implementation tasks with code (immediate priority)
- **Phase 3-5:** Architectural guidance with key decisions and file mappings (separate plans when implementation begins)

**Supersedes:** ADR-718 (Airflow evaluation — rejected). New context: multi-node expansion, operational pain points, control/data plane separation understanding.

---

## Grilling Decisions (12 critical decisions resolved)

| # | Topic | Decision |
|---|-------|----------|
| Q1 | Trigger pattern | Fire-and-forget: trigger returns `STARTED` immediately, pipeline runs on virtual thread, Airflow polls via `HttpSensor` |
| Q2 | Item equipment loop | No Airflow involvement — service lifecycle manages continuous loop |
| Q3 | @Scheduled migration | Move `@ConditionalOnProperty` from class to method level. Bean always exists. Only `@Scheduled(cron)` is conditional |
| Q4 | Run ID correlation | Airflow passes `X-Airflow-Run-Id` header. Service uses it as run ID. Sensor matches `runId` via XCom |
| Q5 | Internal API security | Network isolation (Phase 1-2). Simple API key (Phase 4) |
| Q6 | Downstream verification | Airflow tracks external-api only. Calculator/synchronizer failures handled by existing Kafka retry + DLQ |
| Q7 | run-on-startup | Set `false` when Airflow controls scheduling. Prevents duplicate triggers on restart + multi-node |
| Q8 | Stale match prevention | Trigger API resets state. Sensor verifies `current.runId` matches XCom value. No false success |
| Q9 | ConsumedChunkCleanup | Kafka-driven, stays in data plane. No Airflow involvement |
| Q10 | Docker↔host networking | `host.docker.internal` via `extra_hosts` in docker-compose. Transitional until services are containerized |
| Q11 | Calculator/monitoring gaps | Calculator cleanup + MonitoringReportJob stay as `@Scheduled`. No REST controllers in compute modules |
| Q12 | Phase boundary | Phase 2 = 2 DAGs only (daily_pipeline + artifact_cleanup). Keep scope minimal |

---

## File Structure

### Phase 1 (Observation Foundation)

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `docs/01_ADR/ADR-722_airflow-control-plane-adoption.md` | Supersede ADR-718 |
| Create | `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt` | Track daily pipeline state |
| Create | `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt` | Status data class |
| Create | `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt` | Phase enum |
| Create | `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` | REST endpoint for run status |
| Create | `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` | Unit tests |
| Create | `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt` | Controller tests |
| Modify | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | Integrate RunStatusTracker |
| Modify | `module-external-api/src/main/resources/application.yml` | Add internal API config |

### Phase 2 (Airflow Introduction)

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `docker-compose.airflow.yml` | Airflow services + host.docker.internal networking |
| Create | `airflow/dags/daily_collection_pipeline.py` | DAG: daily data collection (fire-and-forget + run_id correlation) |
| Create | `airflow/dags/artifact_cleanup.py` | DAG: external-api artifact cleanup trigger (6h) |
| Create | `airflow/requirements.txt` | Python dependencies |
| Create | `airflow/Dockerfile` | Custom Airflow image with dependencies |

### Phase 3 (MinIO + Scheduler Migration) — Architectural Guidance

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `docker-compose.minio.yml` | MinIO service |
| Create | `module-calculator/src/main/kotlin/maple/calculator/storage/MinIOObjectStorageAdapter.kt` | MinIO adapter |
| Create | `airflow/dags/equipment_refresh.py` | DAG: Spring Batch equipment refresh |
| Create | `airflow/dags/calculator_cleanup.py` | DAG: calculator result cleanup trigger |
| Modify | `module-calculator/build.gradle` | Add MinIO dependency |
| Modify | `module-calculator/src/main/resources/application.yml` | Add MinIO config |

### Phase 4 (Multi-Node + Coolify) — Architectural Guidance

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `docs/21_Operations/coolify-setup-guide.md` | Coolify deployment guide |
| Create | `docs/21_Operations/multi-node-topology.md` | Node topology reference |
| Modify | `docker-compose.yml` | Split into node-role-specific configs |
| Modify | `docker-compose.airflow.yml` | Coolify-compatible config |

### Phase 5 (Advanced Features) — Future

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `airflow/dags/run_artifact.py` | DAG: manifest.json generation in MinIO |
| Modify | Airflow DAGs | SLA monitoring, backfill support |

---

## Phase 1: Observation Foundation

### Task 1: Supersede ADR-718

**Files:**
- Create: `docs/01_ADR/ADR-722_airflow-control-plane-adoption.md`

- [ ] **Step 1: Write ADR document**

```markdown
# ADR-722: Airflow Control Plane Adoption — ADR-718 Supersede

- Status: Proposed
- Date: 2026-05-29
- Owner: zbnerd

---

## 1. Background / Problem

### Background

ADR-718에서 Airflow 도입을 기각함. 당시 판단: "파이프라인은 실시간 이벤트 스트리밍, batch ETL 오케스트레이터와 패러다임 다름".

이후 운영 환경 변화:
- 3AM cron 기반 배치 파이프라인의 상태 추적 부재 체감
- 장애 시 수동 복구 반복
- 20+개 스케줄러가 4개 모듈에 분산, 중앙 관리 불가
- external-api / calculator 수평 확장 계획으로 distributed scheduler duplication risk 현실화

### Problem

운영 가시성과 스케줄러 중앙 관리가 1인 운영의 병목.

### Goal

Airflow를 Control Plane으로 도입. Data Plane(Kafka)은 변경 없음.

---

## 2. Decision

> Airflow를 Control Plane으로 도입. Kafka 이벤트 드리븐 Data Plane은 그대로 유지. Airflow는 트리거, 상태 폴링, 알림, 이력 관리만 수행.

```text
Airflow (Control Plane): 트리거, 상태 폴링, SLA, 알림, 런 이력
Kafka (Data Plane): 청크 이벤트 라우팅, 실시간 처리, 재시도, 백프레셔
Services (Execution): API 호출, 계산, DB upsert, 파일 IO
```

ADR-718과의 차이: "Airflow vs Kafka" 이분법 → "Control Plane + Data Plane" 분리.

---

## 3. Trade-offs

### Sensitivity

* DAG 복잡도 (endpoint × calculator × materialization 조합)
* 멀티노드 전환 시 스케줄러 중복 실행
* Airflow metadata DB 부하 (현재 규모에선 낮음)
* MinIO 전환 타이밍 (Phase 3)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Airflow Control Plane | 런 추적, 중앙 스케줄링, DAG UI, 수동 트리거 | Airflow 운영 오버헤드 (Python, metadata DB) |
| Kafka Data Plane 유지 | 실시간 처리, 청크 단위 재시도, fan-out | Airflow가 data plane 관여 불가 |
| Coolify + Docker Compose | 단순 멀티노드, K8s 복잡도 회피 | K8s 네이티브 기능 (auto-scaling, self-healing) |
| MinIO object storage | 노드 독립성, 클라우드 마이그레이션 경로 | Local filesystem 성능 (latency) |

### Risk

* Airflow 운영 부담 (1인 팀) — Phase 1→2에서 최소 DAG로 시작
* MinIO 전환 중 일시적 파일 접근 불가 — blue-green 전환 필요
* Coolify 미성숙 — Docker Compose fallback 유지

### Non-Risk

* Kafka consumer 안정성 — Airflow 관여 없음
* ChunkConsumerTemplate retry — 기존 상태 머신 유지
* Urgent 파이프라인 — Airflow 관여 없음

---

## 4. Result / Evidence

### Metrics

| Metric | Before | Target |
| ------ | ------ | ------ |
| Run visibility | Log grep | Airflow UI + Grafana |
| Scheduler count | 8+ @Scheduled (분산) | 3-5 Airflow DAGs (중앙) |
| Failure detection | Manual | Airflow SLA + Discord alert |
| Multi-node scheduler safety | N/A | Airflow singleton scheduler |
| Backfill capability | Script | Airflow manual trigger |

### Observed Result

* (Phase 1-2 완료 후 업데이트)

---

## 5. Summary

> ADR-718의 "Airflow vs Kafka" 이분법을 "Control Plane + Data Plane"으로 재평가. Airflow는 관측과 스케줄링만, Kafka는 데이터 처리를 담당.
```

- [ ] **Step 2: Commit ADR**

```bash
git add docs/01_ADR/ADR-722_airflow-control-plane-adoption.md
git commit -m "docs(adr): add ADR-722 Airflow control plane adoption, supersedes ADR-718"
```

---

### Task 2: Pipeline Phase Enum and Run Status Model

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt`

- [ ] **Step 1: Create PipelinePhase enum**

```kotlin
// module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt
package maple.externalapi.runstatus

enum class PipelinePhase {
    IDLE,
    RANKING_FETCH,
    OCID_LOOKUP,
    OCID_CACHE_REFRESH,
    CHARACTER_BASIC,
    ITEM_EQUIPMENT,
    COMPLETED,
    FAILED,
}
```

- [ ] **Step 2: Create RunStatus data class**

```kotlin
// module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt
package maple.externalapi.runstatus

import java.time.Instant

data class RunStatus(
    val runId: String,
    val phase: PipelinePhase,
    val startedAt: Instant,
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val chunksProcessed: Int = 0,
    val recordsProcessed: Long = 0,
    val errorMessage: String? = null,
) {
    val isTerminal: Boolean get() = phase == PipelinePhase.COMPLETED || phase == PipelinePhase.FAILED
}
```

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/
git commit -m "feat(external-api): add PipelinePhase enum and RunStatus model"
```

---

### Task 3: RunStatusTracker Component

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
package maple.externalapi.runstatus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RunStatusTrackerTest {

    private val tracker = RunStatusTracker()

    @Test
    fun `initial status is IDLE`() {
        val status = tracker.getCurrentStatus()
        assertThat(status).isNull()
    }

    @Test
    fun `startRun creates RUNNING status`() {
        val runId = UUID.randomUUID().toString()
        tracker.startRun(runId)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.runId).isEqualTo(runId)
        assertThat(status.phase).isEqualTo(PipelinePhase.RANKING_FETCH)
        assertThat(status.isTerminal).isFalse()
    }

    @Test
    fun `transitionPhase updates phase`() {
        val runId = UUID.randomUUID().toString()
        tracker.startRun(runId)
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
    }

    @Test
    fun `completeRun sets COMPLETED`() {
        val runId = UUID.randomUUID().toString()
        tracker.startRun(runId)
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)
        tracker.transitionPhase(PipelinePhase.CHARACTER_BASIC)
        tracker.completeRun(100, 600000L)

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.COMPLETED)
        assertThat(status.isTerminal).isTrue()
        assertThat(status.chunksProcessed).isEqualTo(100)
        assertThat(status.recordsProcessed).isEqualTo(600000L)
        assertThat(status.completedAt).isNotNull()
    }

    @Test
    fun `failRun sets FAILED with message`() {
        val runId = UUID.randomUUID().toString()
        tracker.startRun(runId)
        tracker.failRun("Nexon API timeout")

        val status = tracker.getCurrentStatus()!!
        assertThat(status.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(status.errorMessage).isEqualTo("Nexon API timeout")
    }

    @Test
    fun `getLastCompletedRun returns most recent completed`() {
        tracker.startRun("run-1")
        tracker.completeRun(10, 1000L)

        Thread.sleep(10)

        tracker.startRun("run-2")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP)

        val last = tracker.getLastCompletedRun()!!
        assertThat(last.runId).isEqualTo("run-1")
        assertThat(last.phase).isEqualTo(PipelinePhase.COMPLETED)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest" 2>&1 | tail -5
```

Expected: FAIL — `RunStatusTracker` class not found.

- [ ] **Step 3: Implement RunStatusTracker**

```kotlin
// module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt
package maple.externalapi.runstatus

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Component
class RunStatusTracker {

    private val log = LoggerFactory.getLogger(javaClass)
    private val currentRun = AtomicReference<RunStatus>(null)
    private val lastCompletedRun = AtomicReference<RunStatus>(null)

    fun startRun(runId: String) {
        val status = RunStatus(
            runId = runId,
            phase = PipelinePhase.RANKING_FETCH,
            startedAt = Instant.now(),
        )
        currentRun.set(status)
        lastCompletedRun.set(null)
        log.info("[RunStatus] started run={}", runId)
    }

    fun transitionPhase(phase: PipelinePhase) {
        currentRun.updateAndGet { current ->
            current?.copy(phase = phase, updatedAt = Instant.now())
        }
        log.info("[RunStatus] phase={}", phase)
    }

    fun completeRun(chunksProcessed: Int, recordsProcessed: Long) {
        val now = Instant.now()
        currentRun.updateAndGet { current ->
            current?.copy(
                phase = PipelinePhase.COMPLETED,
                updatedAt = now,
                completedAt = now,
                chunksProcessed = chunksProcessed,
                recordsProcessed = recordsProcessed,
            )
        }
        lastCompletedRun.set(currentRun.get())
        log.info("[RunStatus] completed chunks={} records={}", chunksProcessed, recordsProcessed)
    }

    fun failRun(errorMessage: String) {
        val now = Instant.now()
        currentRun.updateAndGet { current ->
            current?.copy(
                phase = PipelinePhase.FAILED,
                updatedAt = now,
                completedAt = now,
                errorMessage = errorMessage,
            )
        }
        lastCompletedRun.set(currentRun.get())
        log.error("[RunStatus] failed: {}", errorMessage)
    }

    fun getCurrentStatus(): RunStatus? = currentRun.get()

    fun getLastCompletedRun(): RunStatus? = lastCompletedRun.get()
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest" 2>&1 | tail -5
```

Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
git commit -m "feat(external-api): add RunStatusTracker for pipeline state tracking"
```

---

### Task 4: Internal API Controller for Run Status

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
package maple.externalapi.runstatus

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.bean.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant

@WebMvcTest(InternalApiController::class)
class InternalApiControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var runStatusTracker: RunStatusTracker

    @Test
    fun `GET run-status returns 200 with null current when no run`() {
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(null)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").isEmpty)
            .andExpect(jsonPath("$.lastCompleted").isEmpty)
    }

    @Test
    fun `GET run-status returns current run status`() {
        val status = RunStatus(
            runId = "run-123",
            phase = PipelinePhase.OCID_LOOKUP,
            startedAt = Instant.now(),
        )
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(status)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(null)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.runId").value("run-123"))
            .andExpect(jsonPath("$.current.phase").value("OCID_LOOKUP"))
            .andExpect(jsonPath("$.current.terminal").value(false))
    }

    @Test
    fun `GET run-status latest returns completed run`() {
        val completed = RunStatus(
            runId = "run-122",
            phase = PipelinePhase.COMPLETED,
            startedAt = Instant.now().minusSeconds(3600),
            completedAt = Instant.now(),
            chunksProcessed = 800,
            recordsProcessed = 600000,
        )
        whenever(runStatusTracker.getCurrentStatus()).thenReturn(null)
        whenever(runStatusTracker.getLastCompletedRun()).thenReturn(completed)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastCompleted.runId").value("run-122"))
            .andExpect(jsonPath("$.lastCompleted.phase").value("COMPLETED"))
            .andExpect(jsonPath("$.lastCompleted.chunksProcessed").value(800))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest" 2>&1 | tail -5
```

Expected: FAIL — `InternalApiController` not found.

- [ ] **Step 3: Implement InternalApiController**

```kotlin
// module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt
package maple.externalapi.runstatus

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
) {
    @GetMapping("/run-status")
    fun getRunStatus(): ResponseEntity<RunStatusResponse> {
        val response = RunStatusResponse(
            current = runStatusTracker.getCurrentStatus(),
            lastCompleted = runStatusTracker.getLastCompletedRun(),
        )
        return ResponseEntity.ok(response)
    }
}

data class RunStatusResponse(
    val current: RunStatus?,
    val lastCompleted: RunStatus?,
)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest" 2>&1 | tail -5
```

Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
git commit -m "feat(external-api): add /api/internal/run-status endpoint"
```

---

### Task 5: Integrate RunStatusTracker into ExternalApiScheduler

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

- [ ] **Step 1: Move @ConditionalOnProperty from class to method level**

Remove `@ConditionalOnProperty` from the class. Add a guard to `scheduledDailyRefresh()` and `runOnStartup` handler. The bean itself must always exist so `triggerDailyRefresh()` remains callable by the controller.

```kotlin
// BEFORE (class level):
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(...) { ... }

// AFTER (bean always exists, scheduling is conditional):
@Component
class ExternalApiScheduler(
    // ... existing params ...
    private val runStatusTracker: RunStatusTracker,
    @Value("\${external-api.schedule.enabled:true}") private val scheduleEnabled: Boolean,
) {
    @Scheduled(cron = "\${external-api.schedule.daily-cron:0 0 3 * * *}")
    fun scheduledDailyRefresh() {
        if (!scheduleEnabled) return
        val runId = "scheduled-${System.currentTimeMillis()}"
        runStatusTracker.startRun(runId)
        runCatching { triggerDailyRefresh() }
            .onSuccess { runStatusTracker.completeRun(0, 0) }
            .onFailure { runStatusTracker.failRun(it.message ?: "Unknown error") }
    }

    // triggerDailyRefresh() stays unchanged — no tracker calls here.
    // The caller (scheduler or controller) is responsible for tracker updates.
}
```

- [ ] **Step 2: Compile and verify**

```bash
./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all external-api tests**

```bash
./gradlew :module-external-api:test 2>&1 | tail -5
```

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "refactor(external-api): move @ConditionalOnProperty to method level, integrate RunStatusTracker"
```

---

### Task 6: Compile Verification + Runtime Smoke Test

**Files:** None new (verification only)

- [ ] **Step 1: Full compile**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | tail -5
```

Expected: BUILD SUCCESSFUL, no ERROR.

- [ ] **Step 2: Full test suite**

```bash
./gradlew test 2>&1 | grep -E "BUILD|FAIL|tests completed" | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Runtime smoke test — start external-api and hit run-status**

```bash
# Terminal 1: Start external-api
set -a && source .env && set +a && export SPRING_PROFILES_ACTIVE=local
./gradlew :module-external-api:bootRun &

# Wait for health
until curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; do sleep 2; done

# Terminal 2: Hit run-status endpoint
curl -s http://localhost:8081/api/internal/run-status | python3 -m json.tool
```

Expected: `{"current": null, "lastCompleted": null}` with HTTP 200.

- [ ] **Step 4: Kill external-api**

```bash
kill $(lsof -ti:8081) 2>/dev/null
```

---

## Phase 2: Airflow Introduction

### Task 7: Airflow Docker Compose

**Files:**
- Create: `docker-compose.airflow.yml`
- Create: `airflow/Dockerfile`
- Create: `airflow/requirements.txt`

- [ ] **Step 1: Create Airflow custom Dockerfile**

```dockerfile
# airflow/Dockerfile
FROM apache/airflow:2.10.4-python3.11

USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*
USER airflow

COPY requirements.txt /opt/airflow/requirements.txt
RUN pip install --no-cache-dir -r /opt/airflow/requirements.txt
```

- [ ] **Step 2: Create requirements.txt**

```txt
# airflow/requirements.txt
apache-airflow-providers-http==4.14.0
apache-airflow-providers-postgres==5.14.0
apache-airflow-providers-celery==3.10.0
```

- [ ] **Step 3: Build custom Airflow image**

```bash
cd /home/maple/probabilistic-valuation-engine
docker build -t maple-airflow:2.10.4 -f airflow/Dockerfile airflow/
```

- [ ] **Step 4: Create docker-compose.airflow.yml**

```yaml
# docker-compose.airflow.yml
# Airflow Control Plane — run with: docker compose -f docker-compose.yml -f docker-compose.airflow.yml up
x-airflow-common: &airflow-common
  image: maple-airflow:2.10.4
  extra_hosts:
    - "host.docker.internal:host-gateway"  # Access host JVM services from Docker
  environment:
    AIRFLOW__CORE__EXECUTOR: LocalExecutor
    AIRFLOW__CORE__DAGS_FOLDER: /opt/airflow/dags
    AIRFLOW__DATABASE__SQL_ALCHEMY_CONN: postgresql+psycopg2://airflow:airflow@airflow-db:5432/airflow
    AIRFLOW__CORE__LOAD_EXAMPLES: "false"
    AIRFLOW__CORE__LOAD_DEFAULT_CONNECTIONS: "false"
    AIRFLOW__WEBSERVER__EXPOSE_CONFIG: "true"
    AIRFLOW__SCHEDULER__DAG_DIR_LIST_INTERVAL: 30
    AIRFLOW__CORE__DEFAULT_TIMEZONE: "Asia/Seoul"
    AIRFLOW__WEBSERVER__DEFAULT_UI_TIMEZONE: "Asia/Seoul"
    AIRFLOW__API__AUTH_BACKENDS: "airflow.api.auth.backend.session"
    _AIRFLOW_DB_MIGRATE: "true"
    _AIRFLOW_WWW_USER_CREATE: "true"
    _AIRFLOW_WWW_USER_USERNAME: ${AIRFLOW_ADMIN_USER:-admin}
    _AIRFLOW_WWW_USER_PASSWORD: ${AIRFLOW_ADMIN_PASSWORD:-admin}
  volumes:
    - ./airflow/dags:/opt/airflow/dags
  networks:
    - maple-network
  depends_on:
    airflow-db:
      condition: service_healthy

services:
  airflow-db:
    image: postgres:17
    environment:
      POSTGRES_USER: airflow
      POSTGRES_PASSWORD: ${AIRFLOW_DB_PASSWORD:-airflow}
      POSTGRES_DB: airflow
    volumes:
      - airflow-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U airflow"]
      interval: 5s
      retries: 5
    networks:
      - maple-network
    restart: unless-stopped

  airflow-webserver:
    <<: *airflow-common
    command: webserver
    ports:
      - "${AIRFLOW_WEB_PORT:-8085}:8080"
    healthcheck:
      test: ["CMD", "curl", "--fail", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 5
    restart: unless-stopped

  airflow-scheduler:
    <<: *airflow-common
    command: scheduler
    healthcheck:
      test: ["CMD-SHELL", "airflow jobs check --job-type SchedulerJob --local"]
      interval: 30s
      timeout: 10s
      retries: 5
    restart: unless-stopped

volumes:
  airflow-db-data:

networks:
  maple-network:
    external: true
```

- [ ] **Step 5: Verify Airflow starts**

```bash
# Start infra + Airflow (app services remain as JARs)
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d

# Wait for health
until curl -sf http://localhost:8085/health > /dev/null 2>&1; do sleep 5; done
echo "Airflow webserver ready on :8085"

# Verify UI accessible
curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/
```

Expected: HTTP 200.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.airflow.yml airflow/Dockerfile airflow/requirements.txt
git commit -m "feat(infra): add Airflow Docker Compose with PostgreSQL metadata DB"
```

---

### Task 8: Airflow DAG — Daily Collection Pipeline

**Files:**
- Create: `airflow/dags/daily_collection_pipeline.py`

- [ ] **Step 1: Create DAG file**

```python
# airflow/dags/daily_collection_pipeline.py
"""
Daily Collection Pipeline
Fire-and-forget trigger + run_id correlation via XCom.
KST 03:00 = UTC 18:00 previous day.
"""
import json
from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.http import SimpleHttpOperator
from airflow.sensors.http import HttpSensor

default_args = {
    "owner": "maple-pipeline",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "retry_exponential_backoff": True,
    "max_retry_delay": timedelta(minutes=30),
}

def check_run_completion(response, task_instance):
    """Verify the run we triggered (not a stale one) is complete."""
    data = response.json()
    current = data.get("current")
    if not current:
        return False
    expected_run_id = task_instance.xcom_pull(task_ids="trigger_daily_collection", key="runId")
    return (
        current.get("runId") == expected_run_id
        and current.get("terminal") is True
        and current.get("phase") == "COMPLETED"
    )

with DAG(
    dag_id="daily_collection_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # UTC 18:00 = KST 03:00
    catchup=False,
    tags=["pipeline", "daily"],
    description="Trigger daily Nexon data collection and wait for completion",
) as dag:

    # Step 1: Check external-api is healthy
    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.json().get("status") == "UP",
        poke_interval=30,
        timeout=120,
    )

    # Step 2: Trigger daily collection (fire-and-forget)
    # Returns immediately with runId. Pipeline runs on virtual thread.
    trigger_collection = SimpleHttpOperator(
        task_id="trigger_daily_collection",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/daily",
        method="POST",
        headers={"X-Airflow-Run-Id": "{{ run_id }}"},
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    # Step 3: Wait for THIS run to complete (run_id correlation prevents stale match)
    wait_for_completion = HttpSensor(
        task_id="wait_for_completion",
        http_conn_id="external_api",
        endpoint="api/internal/run-status",
        response_check=lambda r: check_run_completion(r, **{}),
        poke_interval=300,  # 5 minutes
        timeout=7200,       # 2 hours max
    )

    check_external_api >> trigger_collection >> wait_for_completion
```

- [ ] **Step 2: Verify DAG parses**

```bash
docker exec $(docker ps -qf "name=airflow-scheduler") \
    airflow dags list 2>&1 | grep daily_collection
```

Expected: `daily_collection_pipeline` listed with no parse errors.

- [ ] **Step 3: Commit**

```bash
git add airflow/dags/daily_collection_pipeline.py
git commit -m "feat(airflow): add daily_collection_pipeline DAG"
```

---

### Task 9: Airflow DAG — Artifact Cleanup

**Files:**
- Create: `airflow/dags/artifact_cleanup.py`

- [ ] **Step 1: Create artifact cleanup DAG**

Only external-api artifact cleanup. Calculator cleanup stays as `@Scheduled` (no REST controller). Consumed chunk cleanup stays Kafka-driven.

```python
# airflow/dags/artifact_cleanup.py
"""
Artifact Cleanup
Triggers old artifact file cleanup on external-api.
Every 6 hours.
"""
from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.http import SimpleHttpOperator

default_args = {
    "owner": "maple-pipeline",
    "retries": 1,
    "retry_delay": timedelta(minutes=10),
}

with DAG(
    dag_id="artifact_cleanup",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 */6 * * *",  # every 6 hours
    catchup=False,
    tags=["maintenance", "cleanup"],
    description="Trigger old artifact cleanup on external-api",
) as dag:

    cleanup_artifacts = SimpleHttpOperator(
        task_id="cleanup_artifacts",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/cleanup-artifacts",
        method="POST",
    )
```

- [ ] **Step 2: Verify all DAGs parse**

```bash
docker exec $(docker ps -qf "name=airflow-scheduler") \
    airflow dags list 2>&1 | grep -E "daily_collection|artifact_cleanup"
```

Expected: Both DAGs listed.

- [ ] **Step 3: Commit**

```bash
git add airflow/dags/artifact_cleanup.py
git commit -m "feat(airflow): add artifact_cleanup DAG"
```

---

### Task 10: Airflow HTTP Connections Configuration

**Files:** None (Airflow UI / CLI configuration)

- [ ] **Step 1: Configure Airflow HTTP connection for external-api**

```bash
# host.docker.internal resolves to host machine from Docker container
docker exec $(docker ps -qf "name=airflow-scheduler") \
    airflow connections add external_api \
    --conn-type http \
    --conn-host http://host.docker.internal \
    --conn-port 8081
```

- [ ] **Step 2: Verify connection**

```bash
docker exec $(docker ps -qf "name=airflow-scheduler") \
    airflow connections list 2>&1 | grep external_api
```

Expected: `external_api` listed.

- [ ] **Step 3: Trigger daily_collection_pipeline manually to verify**

```bash
docker exec $(docker ps -qf "name=airflow-scheduler") \
    airflow dags trigger daily_collection_pipeline

# Check status
docker exec $(docker ps -qf "name=airflow-scheduler") \
    airflow dags list-runs -d daily_collection_pipeline 2>&1 | head -5
```

Expected: Run appears with `queued` or `running` status. (Will fail at `check_external_api` if external-api is not running — expected. DAG structure and connection are verified.)

---

### Task 11: Internal Trigger Endpoints (Enabling Airflow HTTP Triggers)

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`

- [ ] **Step 1: Add fire-and-forget trigger endpoints**

Key design: `triggerDaily()` starts pipeline on virtual thread, returns immediately with `runId`. `X-Airflow-Run-Id` header is used as run ID for correlation.

```kotlin
// Add to InternalApiController.kt

@ConditionalOnProperty(name = ["external-api.internal-api.enabled"], havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
    private val externalApiScheduler: ExternalApiScheduler,
    private val artifactCleanupScheduler: ArtifactCleanupScheduler,
) {
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()

    // ... existing GET run-status ...

    @PostMapping("/trigger/daily")
    fun triggerDaily(
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val runId = airflowRunId ?: "local-${System.currentTimeMillis()}"
        vtExecutor.submit {
            runStatusTracker.startRun(runId)
            runCatching {
                externalApiScheduler.triggerDailyRefresh()
            }.onSuccess {
                runStatusTracker.completeRun(0, 0)
            }.onFailure { ex ->
                runStatusTracker.failRun(ex.message ?: "Unknown error")
            }
        }
        return ResponseEntity.ok(mapOf("status" to "STARTED", "runId" to runId))
    }

    @PostMapping("/trigger/cleanup-artifacts")
    fun triggerCleanupArtifacts(): ResponseEntity<Map<String, String>> {
        vtExecutor.submit {
            artifactCleanupScheduler.cleanup()
        }
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt
git commit -m "feat(external-api): add fire-and-forget trigger endpoints with run_id correlation"
```

---

## Phase 3: MinIO + Scheduler Migration — Architectural Guidance

Phase 3 requires a separate detailed plan when implementation begins. Key decisions and file mappings:

### 3.1 MinIO ObjectStorage Adapter

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/storage/MinIOObjectStorageAdapter.kt`
- Modify: `module-calculator/build.gradle` — add `implementation("io.minio:minio:8.5.14")`
- Modify: `module-calculator/src/main/resources/application.yml` — add `minio.endpoint`, `minio.access-key`, `minio.secret-key`, `minio.bucket`
- Create: `docker-compose.minio.yml`

**Interface mapping:**
| ObjectStorage method | MinIO operation |
|---------------------|-----------------|
| `openInputStream(key)` | `getObject(bucket, key)` |
| `openOutputStream(key)` | `putObject(bucket, key, stream)` |
| `exists(key)` | `statObject(bucket, key)` |
| `listDirectories(prefix)` | `listObjects(bucket).withPrefix(prefix).withDelimiter("/")` |
| `deleteDirectory(prefix)` | `listObjects` + `removeObjects` |
| `calculateDirectorySize(prefix)` | `listObjects` + sum `size()` |

**Switching strategy:** `@ConditionalOnProperty` selects `LocalObjectStorageAdapter` or `MinIOObjectStorageAdapter` based on config. No code changes needed to switch.

### 3.2 Scheduler Migration Order

| Scheduler | Current | Target DAG | Trigger |
|-----------|---------|-----------|---------|
| `ExternalApiScheduler.scheduledDailyRefresh()` | `@Scheduled(cron)` | `daily_collection_pipeline` | Airflow cron |
| `ArtifactCleanupScheduler.cleanup()` | `@Scheduled(fixedDelay=6h)` | `chunk_cleanup` | Airflow `0 */6 * * *` |
| `ConsumedChunkCleanupScheduler.cleanup()` | `@Scheduled(fixedDelay=1h)` | Keep as-is (Kafka-driven) | No change |
| `CalculatorResultCleanupScheduler.cleanup()` | `@Scheduled(fixedDelay=6h)` | `chunk_cleanup` | Airflow trigger |
| `MonitoringReportJob` | `@Scheduled(cron=hourly)` | `monitoring_report` | Airflow `0 * * * *` |
| `BatchScheduler.runEquipmentRefreshJob()` | `@Scheduled(cron=02:00)` | `equipment_refresh` | New DAG |

**Migration pattern:** Add `@ConditionalOnProperty` to each scheduler. When Airflow controls the trigger, set property to `false`. Old `@Scheduled` is disabled, Airflow DAG calls the internal trigger endpoint.

### 3.3 Run Artifact Concept

Each completed run writes `manifest.json` to MinIO:

```json
{
  "run_id": "run-1748524800000",
  "started_at": "2026-05-29T18:00:00Z",
  "completed_at": "2026-05-29T19:30:00Z",
  "phase_results": {
    "ranking_fetch": {"pages": 3000, "users": 594000},
    "ocid_lookup": {"resolved": 594000, "failed": 1200},
    "character_basic": {"chunks": 800, "records": 594000},
    "item_equipment": {"chunks": 800, "records": 594000}
  },
  "calculator": {"chunks_processed": 800, "items_calculated": 6500000},
  "synchronizer": {"documents_synced": 594000}
}
```

Airflow DAG reads this manifest in a final verification step.

---

## Phase 4: Multi-Node + Coolify — Architectural Guidance

### 4.1 Coolify Setup

1. Deploy Coolify on Node 1 (control node)
2. Connect Node 2+ as Coolify worker nodes
3. Define service groups in Coolify matching layered compose files:
   - **infra**: postgres, kafka, redis, minIO, elasticsearch
   - **control**: airflow-webserver, airflow-scheduler, airflow-db, prometheus, grafana
   - **ingestion**: external-api (scalable to N replicas)
   - **compute**: calculator, synchronizer (scalable to N replicas)
   - **query**: rest-controller
4. Coolify handles: deployment, health checks, restart, log aggregation, SSL termination

### 4.2 Node Topology

```
Node 1 (Control + Ingestion):
  - Coolify agent
  - PostgreSQL (application + Airflow metadata)
  - Kafka + Redis
  - MinIO
  - Airflow (webserver + scheduler)
  - External-api (primary)
  - Prometheus + Grafana

Node 2 (Compute — added when needed):
  - Coolify agent
  - External-api (replica) or Calculator (replica)
  - Synchronizer

Node N (additional workers):
  - Coolify agent
  - Calculator / Synchronizer replicas
```

### 4.3 Service Discovery

- Docker internal DNS within same compose network
- `host.docker.internal` for host → Docker communication (single-node phase)
- Coolify generates internal DNS for multi-node services
- Airflow connections updated to use Coolify service names

### 4.4 Shared Storage Strategy

| Data Type | Storage | Access Pattern |
|-----------|---------|---------------|
| Pipeline artifacts (.jsonl.gz) | MinIO | Object key in Kafka event |
| Application DB | PostgreSQL | JDBC via .env |
| Cache | Redis | Centralized on Node 1 |
| Airflow metadata | PostgreSQL (separate DB) | Airflow-managed |
| Logs | ELK (Elasticsearch) | Fluent-bit per node |

### 4.5 Docker Compose Split Strategy

```
infra/
  docker-compose.yml          # Base: postgres, kafka, redis, minio
  docker-compose.airflow.yml  # Control: airflow services
  docker-compose.monitor.yml  # Observability: prometheus, grafana, loki
  docker-compose.elk.yml      # Logging: elasticsearch, kibana, fluent-bit

coolify/
  service-groups.yml           # Coolify service group definitions
  node-1.yml                   # Node 1 service assignments
  node-2.yml                   # Node 2 service assignments
```

---

## Phase 5: Advanced Features — Future

### 5.1 SLA Monitoring

```python
# Add to daily_collection_pipeline DAG
sla_miss_callback = notify_slack_or_discord
sla = timedelta(hours=2)  # Pipeline must complete within 2 hours
```

### 5.2 Backfill Support

Airflow UI → "Trigger DAG w/ config" → pass `run_date` parameter → service handles historical data fetch.

### 5.3 Future K8s Migration Path

- `LocalExecutor` → `KubernetesExecutor`
- Docker Compose → Helm chart (official Airflow Helm)
- Coolify → Kubernetes-native deployment (ArgoCD or Flux)
- MinIO → S3 or MinIO operator

---

## Self-Review

### 1. Spec Coverage

| Requirement | Task |
|-------------|------|
| ADR supersede | Task 1 |
| Run status model | Task 2 |
| RunStatusTracker | Task 3 |
| Run status REST endpoint | Task 4 |
| Scheduler refactor (conditional scheduling) | Task 5 |
| Compile + runtime verification | Task 6 |
| Airflow Docker Compose | Task 7 |
| Daily pipeline DAG (fire-and-forget + run_id) | Task 8 |
| Artifact cleanup DAG | Task 9 |
| Airflow connections (host.docker.internal) | Task 10 |
| Internal trigger endpoints (fire-and-forget) | Task 11 |
| MinIO adapter | Phase 3 guidance |
| Coolify multi-node | Phase 4 guidance |

### 2. Placeholder Scan

- No TBD/TODO found
- Phase 3-5 use "Architectural Guidance" — these are design decisions, not implementation placeholders. Separate plans needed when implementation begins.

### 3. Type Consistency

- `RunStatus`, `PipelinePhase`, `RunStatusResponse` used consistently across Tasks 2-4, 5, 11
- `RunStatusTracker` injected in Task 5 (scheduler) and Task 11 (controller) — both paths call startRun/completeRun/failRun
- Airflow connection ID `external_api` matches between DAG files (Task 8-9) and connection setup (Task 10)
- Docker `host.docker.internal` matches between compose file (Task 7) and connection config (Task 10)
- `triggerDailyRefresh()` in Task 5 has NO tracker calls — caller (scheduler or controller) manages tracker

---

## Verification Checklist

After completing Phase 1-2:

- [ ] `./gradlew compileKotlin compileJava --continue` passes
- [ ] `./gradlew test` passes
- [ ] `curl http://localhost:8081/api/internal/run-status` returns 200
- [ ] `curl -X POST http://localhost:8081/api/internal/trigger/daily` returns `{"status":"STARTED","runId":"..."}`
- [ ] ExternalApiScheduler bean exists even when `external-api.schedule.enabled=false`
- [ ] `docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d` starts cleanly
- [ ] Airflow UI accessible at `http://localhost:8085`
- [ ] 2 DAGs visible in Airflow UI: `daily_collection_pipeline`, `artifact_cleanup`
- [ ] Airflow can resolve `host.docker.internal` (verify: `docker exec ... ping host.docker.internal`)
- [ ] Manual DAG trigger reaches `check_external_api` (fails if external-api not running — expected)
- [ ] ADR-722 committed to `docs/01_ADR/`
- [ ] Phase 3+ architectural guidance documented for future implementation
