# Issue #1085 Implementation Plan: Extract OCID + API Orchestrators from CalculationJobService

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `OcidResolutionOrchestrator` and `ApiDataFetchOrchestrator` from `CalculationJobService`; update all callers; preserve behavior; reduce facade to ~55 lines.

**Architecture:** Two new `@Service` classes in `module-infra/.../infrastructure/job/`, each owning one MQ topic family. `CalculationJobService` keeps only job creation, snapshot persistence, and dispatch delegation. Caller constructor injection updated to use the orchestrators directly.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.x, Mockito-Kotlin (unit tests), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-07-1085-calculation-job-orchestrator-extraction-design.md`

**Issue:** #1085 (step 2/2 of #1073 — `CalculationDispatchService` already merged via PR #1182)

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestrator.kt` | OCID transition + retry pipeline |
| Create | `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestrator.kt` | API request/response pipeline + snapshot ready |
| Create | `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestratorTest.kt` | Unit tests for OCID orchestrator |
| Create | `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestratorTest.kt` | Unit tests for API orchestrator |
| Modify | `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt` | Remove extracted methods; keep facade |
| Modify | `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OcidResolveWorker.kt` | Inject `OcidResolutionOrchestrator` + `ApiDataFetchOrchestrator` |
| Modify | `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt` | Inject `ApiDataFetchOrchestrator` |
| Modify | `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt` | Inject `OcidResolutionOrchestrator` for `resolveOcidInPlace` |
| Modify | `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt` | Update constructor to match new facade shape |

No public API change. No new MQ topics. No new event factories. No `@Transactional` changes.

---

## Task 1: Create OcidResolutionOrchestrator

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestrator.kt`

- [ ] **Step 1: Write the file**

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.OcidResolveEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OcidResolutionOrchestrator(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun requestOcidResolve(jobId: UUID, userIgn: String, presetNo: Int) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.OCID_RESOLVING,
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to OCID_RESOLVING", jobId)
            return
        }

        eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), userIgn, presetNo))
        log.info("[jobId={}] Transitioned to OCID_RESOLVING, resolve enqueued", jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun handleOcidFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] OCID resolve failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetryForOcid(jobId, errorCode)
            if (retried) {
                eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(job.jobId.toString(), job.userIgn, job.presetNo))
                log.info("[jobId={}] OCID resolve retry (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun resolveOcidInPlace(jobId: UUID, ocid: String): Boolean = jobPort.resolveOcidAndTransition(jobId, ocid)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :module-infra:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestrator.kt
git commit -m "refactor(1085): add OcidResolutionOrchestrator"
```

---

## Task 2: Create ApiDataFetchOrchestrator

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestrator.kt`

- [ ] **Step 1: Write the file**

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiRequestEventFactory
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ApiDataFetchOrchestrator(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun resolveOcidAndEnqueueApiData(jobId: UUID, ocid: String): Boolean {
        val transitioned = jobPort.resolveOcidAndTransition(jobId, ocid)
        if (!transitioned) {
            log.warn("[jobId={}] Cannot resolve OCID + transition to API_REQUESTED", jobId)
            return false
        }

        val job = jobPort.findJobById(jobId) ?: return false

        eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), ocid, job.userIgn, job.presetNo))
        log.info("[jobId={}] OCID resolved, API request enqueued", jobId)
        return true
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveSnapshotAndMarkReady(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        objectKey: String,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        return markSnapshotReadyInternal(jobId, snapshotEntity.snapshotId, objectKey)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean =
        markSnapshotReadyInternal(jobId, snapshotId, objectKey)

    private fun markSnapshotReadyInternal(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean {
        val ready = jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
        if (ready) {
            val job = jobPort.findJobById(jobId)
            if (job != null) {
                eventAppender.append(nexonApiResponseTopic, NexonApiResponseEventFactory.create(jobId.toString(), snapshotId.toString(), objectKey, job.ocid ?: return false, job.userIgn, job.presetNo))
                log.info("[jobId={}] Snapshot ready, response enqueued", jobId)
            }
        }
        return ready
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun handleApiFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] Failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetry(jobId, errorCode)
            if (retried) {
                eventAppender.append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(job.jobId.toString(), job.ocid ?: return, job.userIgn, job.presetNo, eventType = "RETRY_FETCH"))
                log.info("[jobId={}] Retrying (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :module-infra:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestrator.kt
git commit -m "refactor(1085): add ApiDataFetchOrchestrator"
```

---

## Task 3: Shrink CalculationJobService to facade

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`

- [ ] **Step 1: Replace file contents**

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobClaim
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val dispatchService: CalculationDispatchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun createJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJob {
        val job = jobPort.createJob(ocid, userIgn, presetNo)
        log.info("[jobId={}] Job created in REQUESTED state", job.jobId)
        return job
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun createOrFindActiveJob(ocid: String?, userIgn: String, presetNo: Int): CalculationJobClaim {
        val claim = jobPort.createOrFindActiveJob(ocid, userIgn, presetNo)
        if (claim.created) {
            log.info("[jobId={}] Job claimed in REQUESTED state", claim.job.jobId)
        } else {
            log.debug("[jobId={}] Existing active job reused", claim.job.jobId)
        }
        return claim
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveInputSnapshotAndMarkReady(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        return jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
    }

    fun retryOcidResolvingJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean =
        dispatchService.retryOcidResolvingJob(jobId, userIgn, presetNo)

    fun retryApiRequestedJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean =
        dispatchService.retryApiRequestedJob(jobId, userIgn, presetNo)

    fun dispatchToExternalApi(jobId: UUID, userIgn: String, presetNo: Int) {
        dispatchService.dispatchToExternalApi(jobId, userIgn, presetNo)
    }

    fun dispatchCalculationCompleted(payload: CalculationCompletedPayload) {
        dispatchService.dispatchCalculationCompleted(payload)
    }

    fun saveInputSnapshotAndDispatchCalculation(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
        payload: CalculationRequestedPayload,
    ): Boolean = dispatchService.saveInputSnapshotAndDispatchCalculation(snapshotEntity, jobId, snapshotId, payload)

    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean =
        dispatchService.retryExternalApiJob(jobId, errorCode)
}
```

- [ ] **Step 2: Verify it compiles (expected to fail at callers)**

Run: `./gradlew :module-infra:compileKotlin --no-daemon`
Expected: FAIL — `OcidResolveWorker`, `NexonApiWorker`, `ExternalApiWorker` still reference the old method signatures.

- [ ] **Step 3: Do not commit yet — proceed to Task 4 to update callers**

---

## Task 4: Update OcidResolveWorker

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OcidResolveWorker.kt`

- [ ] **Step 1: Update imports and constructor**

The current file imports `maple.expectation.infrastructure.job.CalculationJobService` and uses `jobService: CalculationJobService`. Replace the import with both orchestrators and update the constructor + method call sites.

Replace the import line:
```kotlin
import maple.expectation.infrastructure.job.CalculationJobService
```
with:
```kotlin
import maple.expectation.infrastructure.job.ApiDataFetchOrchestrator
import maple.expectation.infrastructure.job.OcidResolutionOrchestrator
```

Update the constructor (around line 33):
```kotlin
    private val ocidOrchestrator: OcidResolutionOrchestrator,
    private val apiOrchestrator: ApiDataFetchOrchestrator,
```

Update method call sites:
- Line 74: `jobService.handleOcidFailure(...)` → `ocidOrchestrator.handleOcidFailure(...)`
- Line 78: `jobService.resolveOcidAndEnqueueApiData(...)` → `apiOrchestrator.resolveOcidAndEnqueueApiData(...)`
- Line 80: `jobService.handleOcidFailure(...)` → `ocidOrchestrator.handleOcidFailure(...)`

- [ ] **Step 2: Verify compile**

Run: `./gradlew :module-infra:compileKotlin --no-daemon`
Expected: still fails (other workers not updated).

- [ ] **Step 3: Do not commit yet — proceed to Task 5**

---

## Task 5: Update NexonApiWorker

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt`

- [ ] **Step 1: Update imports, constructor, and call sites**

Replace the import:
```kotlin
import maple.expectation.infrastructure.job.CalculationJobService
```
with:
```kotlin
import maple.expectation.infrastructure.job.ApiDataFetchOrchestrator
```

Update the constructor (around line 29):
```kotlin
    private val apiOrchestrator: ApiDataFetchOrchestrator,
```

Update call sites:
- Line 51: `jobService.handleApiFailure(...)` → `apiOrchestrator.handleApiFailure(...)`
- Line 110: `jobService.saveSnapshotAndMarkReady(...)` → `apiOrchestrator.saveSnapshotAndMarkReady(...)`

- [ ] **Step 2: Verify compile (still failing)**

Run: `./gradlew :module-infra:compileKotlin --no-daemon`
Expected: still fails (ExternalApiWorker not updated).

- [ ] **Step 3: Do not commit yet — proceed to Task 6**

---

## Task 6: Update ExternalApiWorker

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt`

- [ ] **Step 1: Update imports and constructor**

Replace the import:
```kotlin
import maple.expectation.infrastructure.job.CalculationJobService
```
with:
```kotlin
import maple.expectation.infrastructure.job.OcidResolutionOrchestrator
```

Update the constructor (around line 73) — add a new dependency alongside the existing `jobService`:
```kotlin
    private val jobService: CalculationJobService,
    private val ocidOrchestrator: OcidResolutionOrchestrator,
```

- [ ] **Step 2: Update call sites for `resolveOcidInPlace`**

- Line 366: `jobService.resolveOcidInPlace(jobId, cached)` → `ocidOrchestrator.resolveOcidInPlace(jobId, cached)`
- Line 387: `jobService.resolveOcidInPlace(jobId, ocid)` → `ocidOrchestrator.resolveOcidInPlace(jobId, ocid)`

Leave the other `jobService.*` call sites unchanged (`saveInputSnapshotAndMarkReady`, `saveInputSnapshotAndDispatchCalculation`, `retryExternalApiJob` stay on the facade).

- [ ] **Step 3: Verify compile passes**

Run: `./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit the refactor**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestrator.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestrator.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OcidResolveWorker.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt
git commit -m "refactor(1085): extract OcidResolutionOrchestrator + ApiDataFetchOrchestrator from CalculationJobService"
```

---

## Task 7: Add OcidResolutionOrchestrator unit test

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestratorTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.OcidResolveEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class OcidResolutionOrchestratorTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var eventAppender: DomainEventAppender
    @Mock lateinit var ocidResolveTopic: OcidResolveTopic

    private lateinit var service: OcidResolutionOrchestrator

    @BeforeEach
    fun setUp() {
        service = OcidResolutionOrchestrator(
            jobPort = jobPort,
            eventAppender = eventAppender,
            ocidResolveTopic = ocidResolveTopic,
        )
    }

    @Test
    fun `requestOcidResolve enqueues event on successful transition`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
            .thenReturn(true)

        service.requestOcidResolve(jobId, "testIgn", 1)

        verify(eventAppender).append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), "testIgn", 1))
    }

    @Test
    fun `requestOcidResolve skips enqueue when transition fails`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
            .thenReturn(false)

        service.requestOcidResolve(jobId, "testIgn", 1)

        verify(eventAppender, never()).append(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `handleOcidFailure marks failed when max retries exceeded`() {
        val jobId = UUID.randomUUID()
        val job = CalculationJob(
            jobId = jobId, ocid = null, userIgn = "ign", presetNo = 1,
            status = CalculationJobStatus.OCID_RESOLVING, retryCount = 5, maxRetries = 5,
        )
        whenever(jobPort.findJobById(jobId)).thenReturn(job)

        service.handleOcidFailure(jobId, "CODE", "boom")

        verify(jobPort).markFailed(jobId, "CODE", "boom")
        verify(eventAppender, never()).append(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `handleOcidFailure re-enqueues OCID resolve on retry`() {
        val jobId = UUID.randomUUID()
        val job = CalculationJob(
            jobId = jobId, ocid = null, userIgn = "ign", presetNo = 1,
            status = CalculationJobStatus.OCID_RESOLVING, retryCount = 0, maxRetries = 5,
        )
        whenever(jobPort.findJobById(jobId)).thenReturn(job)
        whenever(jobPort.incrementRetryForOcid(jobId, "CODE")).thenReturn(true)

        service.handleOcidFailure(jobId, "CODE", "boom")

        verify(eventAppender).append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), "ign", 1))
    }

    @Test
    fun `resolveOcidInPlace delegates to jobPort`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.resolveOcidAndTransition(jobId, "ocid-1")).thenReturn(true)

        val result = service.resolveOcidInPlace(jobId, "ocid-1")

        assertThat(result).isTrue()
        verify(jobPort).resolveOcidAndTransition(jobId, "ocid-1")
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :module-infra:test --tests "*OcidResolutionOrchestratorTest*" --no-daemon`
Expected: 5 tests, all pass.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestratorTest.kt
git commit -m "test(1085): add OcidResolutionOrchestrator unit tests"
```

---

## Task 8: Add ApiDataFetchOrchestrator unit test

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestratorTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiRequestEventFactory
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ApiDataFetchOrchestratorTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var eventAppender: DomainEventAppender
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository
    @Mock lateinit var nexonApiRequestTopic: NexonApiRequestTopic
    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    private lateinit var service: ApiDataFetchOrchestrator

    @BeforeEach
    fun setUp() {
        service = ApiDataFetchOrchestrator(
            jobPort = jobPort,
            eventAppender = eventAppender,
            snapshotRepository = snapshotRepository,
            nexonApiRequestTopic = nexonApiRequestTopic,
            nexonApiResponseTopic = nexonApiResponseTopic,
        )
    }

    private fun job(ocid: String? = "ocid-1", retryCount: Int = 0, maxRetries: Int = 5) = CalculationJob(
        jobId = UUID.randomUUID(), ocid = ocid, userIgn = "ign", presetNo = 1,
        status = CalculationJobStatus.API_REQUESTED, retryCount = retryCount, maxRetries = maxRetries,
    )

    @Test
    fun `resolveOcidAndEnqueueApiData enqueues API request on success`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.resolveOcidAndTransition(jobId, "ocid-1")).thenReturn(true)
        whenever(jobPort.findJobById(jobId)).thenReturn(job())

        val result = service.resolveOcidAndEnqueueApiData(jobId, "ocid-1")

        assertThat(result).isTrue()
        verify(eventAppender).append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(jobId.toString(), "ocid-1", "ign", 1))
    }

    @Test
    fun `resolveOcidAndEnqueueApiData returns false when transition fails`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.resolveOcidAndTransition(jobId, "ocid-1")).thenReturn(false)

        val result = service.resolveOcidAndEnqueueApiData(jobId, "ocid-1")

        assertThat(result).isFalse()
        verify(eventAppender, never()).append(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `saveSnapshotAndMarkReady persists snapshot and enqueues response`() {
        val jobId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val entity = CalculationSnapshotEntity(
            snapshotId = snapshotId,
            jobId = jobId,
            objectKey = "obj/key",
            expiresAt = java.time.Instant.now().plusSeconds(3600),
        )
        whenever(jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)).thenReturn(true)
        whenever(jobPort.findJobById(jobId)).thenReturn(job())

        val result = service.saveSnapshotAndMarkReady(entity, jobId, "obj/key")

        assertThat(result).isTrue()
        verify(snapshotRepository).save(entity)
        verify(eventAppender).append(nexonApiResponseTopic, NexonApiResponseEventFactory.create(jobId.toString(), snapshotId.toString(), "obj/key", "ocid-1", "ign", 1))
    }

    @Test
    fun `handleApiFailure marks failed when max retries exceeded`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.findJobById(jobId)).thenReturn(job(retryCount = 5, maxRetries = 5))

        service.handleApiFailure(jobId, "CODE", "boom")

        verify(jobPort).markFailed(jobId, "CODE", "boom")
        verify(eventAppender, never()).append(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `handleApiFailure re-enqueues API request on retry`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.findJobById(jobId)).thenReturn(job(retryCount = 0, maxRetries = 5))
        whenever(jobPort.incrementRetry(jobId, "CODE")).thenReturn(true)

        service.handleApiFailure(jobId, "CODE", "boom")

        verify(eventAppender).append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(jobId.toString(), "ocid-1", "ign", 1, eventType = "RETRY_FETCH"))
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :module-infra:test --tests "*ApiDataFetchOrchestratorTest*" --no-daemon`
Expected: 5 tests, all pass.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestratorTest.kt
git commit -m "test(1085): add ApiDataFetchOrchestrator unit tests"
```

---

## Task 9: Update CalculationJobServiceTest for new constructor

**Files:**
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt`

- [ ] **Step 1: Replace file contents**

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class CalculationJobServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository
    @Mock lateinit var dispatchService: CalculationDispatchService

    private lateinit var service: CalculationJobService

    @BeforeEach
    fun setUp() {
        service = CalculationJobService(
            jobPort = jobPort,
            snapshotRepository = snapshotRepository,
            dispatchService = dispatchService,
        )
    }

    @Test
    fun `retryExternalApiJob delegates to dispatchService`() {
        val jobId = UUID.randomUUID()
        whenever(dispatchService.retryExternalApiJob(jobId, "TEST_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(jobId, "TEST_ERROR")

        assertThat(result).isTrue()
        verify(dispatchService).retryExternalApiJob(jobId, "TEST_ERROR")
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :module-infra:test --tests "*CalculationJobServiceTest*" --no-daemon`
Expected: 1 test, passes.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt
git commit -m "test(1085): update CalculationJobServiceTest for facade constructor"
```

---

## Task 10: Full verification

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`, no failing tests, no `UnexpectedRollbackException`.

- [ ] **Step 3: Verify facade size**

Run: `wc -l module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`
Expected: ≤ 80 lines.

- [ ] **Step 4: Verify the extracted files exist**

Run: `ls module-infra/src/main/kotlin/maple/expectation/infrastructure/job/`
Expected: lists `CalculationJobService.kt`, `CalculationDispatchService.kt`, `OcidResolutionOrchestrator.kt`, `ApiDataFetchOrchestrator.kt`.

- [ ] **Step 5: Final commit (only if any incidental fix was needed)**

```bash
git status
# If there are incidental changes:
# git add -A && git commit -m "chore(1085): post-verification cleanup"
```

---

## Self-Review Checklist

- [x] All 4 caller files updated (OcidResolveWorker, NexonApiWorker, ExternalApiWorker + test)
- [x] 2 new orchestrator files created
- [x] 1 facade file modified
- [x] 2 new test files created, 1 existing test updated
- [x] No `TBD` / `TODO` / "implement later"
- [x] No "add appropriate error handling" vague steps
- [x] All `@Transactional` annotations preserved at method level
- [x] All public method signatures of facade-retained methods unchanged
- [x] No new MQ topics, no new event factories
- [x] Test code shown for every test step
- [x] Exact file paths, exact commands, exact expected output throughout
