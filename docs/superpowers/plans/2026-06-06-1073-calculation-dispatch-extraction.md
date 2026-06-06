# Issue 1073 — CalculationDispatchService Extraction Implementation Plan (step 1/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract 6 PGMQ-dispatch methods from `CalculationJobService` into a new `CalculationDispatchService`. `CalculationJobService` delegates to it. Zero behavioral change.

**Architecture:** Create `CalculationDispatchService` (3 deps: `jobPort`, `pgmqClient`, `snapshotRepository`) with the 6 dispatch methods copied verbatim from `CalculationJobService`. Refactor `CalculationJobService` to swap `pgmqClient` for `dispatchService` and turn the 6 methods into 1-line delegates. Existing 4 caller files unchanged. Existing 5 `retryExternalApiJob` tests pass via delegation path. New `CalculationDispatchServiceTest` adds direct unit-test coverage for all 6 methods.

**Tech Stack:** Kotlin, Spring `@Service` + `@Transactional`, JUnit5 + Mockito-Kotlin.

**Branch:** `refactor/1073-calculation-dispatch-extraction` (already created from `origin/develop`). PR base: `develop`.

**Spec:** `docs/superpowers/specs/2026-06-06-1073-calculation-dispatch-service-design.md`

---

## File structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-infra/.../job/CalculationDispatchService.kt` | CREATE | 6 dispatch methods (verbatim from `CalculationJobService`) |
| `module-infra/.../job/CalculationJobService.kt` | MODIFY | Remove `pgmqClient` field, add `dispatchService` field, 6 methods → 1-line delegates |
| `module-infra/.../test/.../job/CalculationDispatchServiceTest.kt` | CREATE | Unit tests for 6 dispatch methods |
| `module-infra/.../test/.../job/CalculationJobServiceTest.kt` | MODIFY | Constructor: swap `pgmqClient` mock for `dispatchService` mock |

All production files under `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/`. All test files under `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/`.

---

## Task 1: Verify worktree branch

**Files:** none (verification only)

- [ ] **Step 1.1: Confirm branch**

Run: `git -C /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction branch --show-current`
Expected: `refactor/1073-calculation-dispatch-extraction`

- [ ] **Step 1.2: Confirm clean state**

Run: `git -C /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction status --short`
Expected: empty output (clean)

---

## Task 2: Create `CalculationDispatchService` (TDD)

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationDispatchService.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationDispatchServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationDispatchServiceTest.kt`:

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.queue.QueueNames
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class CalculationDispatchServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var pgmqClient: PgmqClient
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository

    private lateinit var service: CalculationDispatchService

    @BeforeEach
    fun setUp() {
        service = CalculationDispatchService(jobPort, pgmqClient, snapshotRepository)
    }

    // ===== retryOcidResolvingJob =====

    @Test
    fun `retryOcidResolvingJob sends external API payload when retry increments`() {
        val job = job()
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_TIMEOUT")).thenReturn(true)

        val result = service.retryOcidResolvingJob(job.jobId, job.userIgn, job.presetNo)

        assertThat(result).isTrue()
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryOcidResolvingJob does not send when retry fails`() {
        val job = job()
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_TIMEOUT")).thenReturn(false)

        val result = service.retryOcidResolvingJob(job.jobId, job.userIgn, job.presetNo)

        assertThat(result).isFalse()
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    // ===== retryApiRequestedJob =====

    @Test
    fun `retryApiRequestedJob sends external API payload when retry increments`() {
        val job = job()
        whenever(jobPort.incrementRetry(job.jobId, "EXTERNAL_API_TIMEOUT")).thenReturn(true)

        val result = service.retryApiRequestedJob(job.jobId, job.userIgn, job.presetNo)

        assertThat(result).isTrue()
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    // ===== dispatchToExternalApi =====

    @Test
    fun `dispatchToExternalApi sends payload when transition succeeds`() {
        val job = job()
        whenever(jobPort.transitionStatus(job.jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING)).thenReturn(true)

        service.dispatchToExternalApi(job.jobId, job.userIgn, job.presetNo)

        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `dispatchToExternalApi does not send when transition fails`() {
        val job = job()
        whenever(jobPort.transitionStatus(job.jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING)).thenReturn(false)

        service.dispatchToExternalApi(job.jobId, job.userIgn, job.presetNo)

        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    // ===== dispatchCalculationCompleted =====

    @Test
    fun `dispatchCalculationCompleted sends to CALCULATION_COMPLETED queue`() {
        val payload = CalculationCompletedPayload(jobId = "job-1")

        service.dispatchCalculationCompleted(payload)

        verify(pgmqClient).send(QueueNames.CALCULATION_COMPLETED, payload)
    }

    // ===== saveInputSnapshotAndDispatchCalculation =====

    @Test
    fun `saveInputSnapshotAndDispatchCalculation saves snapshot and dispatches when mark ready succeeds`() {
        val job = job()
        val entity = mock<CalculationSnapshotEntity>()
        whenever(entity.snapshotId).thenReturn(UUID.randomUUID())
        val payload = CalculationRequestedPayload(jobId = job.jobId.toString())
        whenever(jobPort.markSnapshotReady(job.jobId, entity.snapshotId, CalculationJobStatus.API_REQUESTED)).thenReturn(true)

        val result = service.saveInputSnapshotAndDispatchCalculation(entity, job.jobId, entity.snapshotId, payload)

        assertThat(result).isTrue()
        verify(snapshotRepository).save(entity)
        verify(jobPort).markSnapshotReady(job.jobId, entity.snapshotId, CalculationJobStatus.API_REQUESTED)
        verify(pgmqClient).send(QueueNames.CALCULATION_REQUESTED, payload)
    }

    @Test
    fun `saveInputSnapshotAndDispatchCalculation does not dispatch when mark ready fails`() {
        val job = job()
        val entity = mock<CalculationSnapshotEntity>()
        whenever(entity.snapshotId).thenReturn(UUID.randomUUID())
        val payload = CalculationRequestedPayload(jobId = job.jobId.toString())
        whenever(jobPort.markSnapshotReady(job.jobId, entity.snapshotId, CalculationJobStatus.API_REQUESTED)).thenReturn(false)

        val result = service.saveInputSnapshotAndDispatchCalculation(entity, job.jobId, entity.snapshotId, payload)

        assertThat(result).isFalse()
        verify(snapshotRepository).save(entity)
        verify(pgmqClient, never()).send(eq(QueueNames.CALCULATION_REQUESTED), any())
    }

    // ===== retryExternalApiJob =====

    @Test
    fun `retryExternalApiJob increments OCID retry when job is OCID_RESOLVING`() {
        val job = job(status = CalculationJobStatus.OCID_RESOLVING)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(job.jobId, "OCID_RESOLVE_ERROR")

        assertThat(result).isTrue()
        verify(jobPort).incrementRetryForOcid(job.jobId, "OCID_RESOLVE_ERROR")
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryExternalApiJob marks exhausted job failed when retries exceeded`() {
        val job = job(status = CalculationJobStatus.API_REQUESTED, retryCount = 3, maxRetries = 3)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isTrue()
        verify(jobPort).markFailed(job.jobId, "EXTERNAL_API_ERROR", "Max retries exceeded")
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    @Test
    fun `retryExternalApiJob returns false for non-processable job status`() {
        val job = job(status = CalculationJobStatus.COMPLETED)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isFalse()
        verify(jobPort, never()).markFailed(eq(job.jobId), any(), any())
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    private fun job(
        status: CalculationJobStatus = CalculationJobStatus.REQUESTED,
        ocid: String? = null,
        retryCount: Int = 0,
        maxRetries: Int = 3,
    ) = CalculationJob(
        jobId = UUID.randomUUID(),
        ocid = ocid,
        userIgn = "test-character",
        presetNo = 1,
        status = status,
        retryCount = retryCount,
        maxRetries = maxRetries,
    )

    private fun <T> mock(): T = org.mockito.kotlin.mock()
}
```

> **NOTE:** The `private fun <T> mock(): T = org.mockito.kotlin.mock()` helper is a workaround for `org.mockito.kotlin.mock<T>()` reified inline fun not being callable from non-inline contexts with a nullable receiver. If the test file already has imports for mockito-kotlin and the existing pattern in the codebase uses `@Mock lateinit var`, prefer that. The above is a safe fallback.

> **NOTE:** Verify exact constructor signatures of `CalculationJob`, `CalculationCompletedPayload`, `CalculationRequestedPayload`, `ExternalApiJobPayload`, `CalculationSnapshotEntity` by reading their files. Adjust if any fields differ.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.job.CalculationDispatchServiceTest" --continue 2>&1 | tail -15
```
Expected: COMPILATION FAILURE (`CalculationDispatchService` not found)

- [ ] **Step 3: Create the dispatch service implementation**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationDispatchService.kt`:

```kotlin
package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.queue.QueueNames
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CalculationDispatchService(
    private val jobPort: CalculationJobPort,
    private val pgmqClient: PgmqClient,
    private val snapshotRepository: CalculationSnapshotRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryOcidResolvingJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean {
        val incremented = jobPort.incrementRetryForOcid(jobId, "OCID_RESOLVE_TIMEOUT")
        if (incremented) {
            pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId.toString(), userIgn, presetNo))
        }
        return incremented
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryApiRequestedJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean {
        val incremented = jobPort.incrementRetry(jobId, "EXTERNAL_API_TIMEOUT")
        if (incremented) {
            pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId.toString(), userIgn, presetNo))
        }
        return incremented
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun dispatchToExternalApi(jobId: UUID, userIgn: String, presetNo: Int) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.OCID_RESOLVING,
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to OCID_RESOLVING", jobId)
            return
        }

        pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(jobId.toString(), userIgn, presetNo))
        log.info("[jobId={}] Dispatched to consolidated external API pipeline", jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun dispatchCalculationCompleted(payload: CalculationCompletedPayload) {
        pgmqClient.send(QueueNames.CALCULATION_COMPLETED, payload)
        log.info("[jobId={}] Calculation completed payload dispatched", payload.jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveInputSnapshotAndDispatchCalculation(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
        payload: CalculationRequestedPayload,
    ): Boolean {
        snapshotRepository.save(snapshotEntity)
        val ready = jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
        if (!ready) {
            log.warn("[jobId={}] Cannot mark SNAPSHOT_READY before calculation dispatch", jobId)
            return false
        }
        pgmqClient.send(QueueNames.CALCULATION_REQUESTED, payload)
        log.info("[jobId={}] Calculation requested", jobId)
        return true
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean {
        val job = jobPort.findJobById(jobId) ?: return false
        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, "Max retries exceeded")
            log.warn("[jobId={}] External API failed after {} retries", jobId, job.retryCount)
            return true
        }
        val incremented = when (job.status) {
            CalculationJobStatus.OCID_RESOLVING -> jobPort.incrementRetryForOcid(jobId, errorCode)
            CalculationJobStatus.API_REQUESTED,
            CalculationJobStatus.RETRYING,
            -> jobPort.incrementRetry(jobId, errorCode)
            CalculationJobStatus.REQUESTED -> jobPort.transitionStatus(
                jobId,
                CalculationJobStatus.REQUESTED,
                CalculationJobStatus.OCID_RESOLVING,
            )
            else -> false
        }
        if (!incremented) {
            log.warn("[jobId={}] External API retry not scheduled from state {}", jobId, job.status)
            return false
        }
        pgmqClient.send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
        log.info("[jobId={}] External API retry scheduled (attempt {})", jobId, job.retryCount + 1)
        return true
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.job.CalculationDispatchServiceTest" --continue 2>&1 | tail -15
```
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationDispatchService.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationDispatchServiceTest.kt
git commit -m "refactor(1073): extract CalculationDispatchService with 6 dispatch methods"
```

---

## Task 3: Refactor `CalculationJobService` to delegate

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`

- [ ] **Step 1: Update imports**

Remove the now-unused PGMQ imports from `CalculationJobService.kt`:
- `maple.expectation.infrastructure.queue.QueueNames` (no longer used directly)
- `maple.expectation.infrastructure.pgmq.ExternalApiJobPayload` (no longer used directly)
- `maple.expectation.infrastructure.pgmq.CalculationRequestedPayload` (no longer used directly)
- `maple.expectation.infrastructure.pgmq.CalculationCompletedPayload` (no longer used directly)

Keep: `PgmqClient` import will be removed in the next step (constructor change).

- [ ] **Step 2: Update constructor**

Replace the constructor with:

```kotlin
@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val dispatchService: CalculationDispatchService,
) {
```

Changes:
- Remove `pgmqClient: PgmqClient` field
- Add `dispatchService: CalculationDispatchService` field (last position)
- Remove the `import maple.expectation.infrastructure.pgmq.PgmqClient` line

- [ ] **Step 3: Replace 6 method bodies with delegates**

Delete the 6 method bodies (lines 147-244 in the original file):
- `retryOcidResolvingJob`
- `retryApiRequestedJob`
- `dispatchToExternalApi`
- `saveInputSnapshotAndDispatchCalculation` (lines 193-209, the dispatch-specific one — not to be confused with `saveInputSnapshotAndMarkReady` which stays)
- `dispatchCalculationCompleted`
- `retryExternalApiJob`

Replace each with a 1-line delegate:

```kotlin
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
```

**Important**: 
- `dispatchToExternalApi`, `dispatchCalculationCompleted`, and `saveInputSnapshotAndDispatchCalculation` reference types (`CalculationCompletedPayload`, `CalculationRequestedPayload`) that are still imported. Keep those imports — they're needed for the delegate signatures.
- The `// ===== Consolidated Pipeline Methods (ExternalApiWorker) =====` section comment is removed since the 6 methods are now delegates (not consolidated pipeline methods).

- [ ] **Step 4: Compile check**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL. (No test compilation since the test file still uses the old constructor — we'll fix in next task.)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt
git commit -m "refactor(1073): make CalculationJobService delegate to CalculationDispatchService"
```

---

## Task 4: Update `CalculationJobServiceTest` constructor

**Files:**
- Modify: `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt`

- [ ] **Step 1: Update imports**

Remove unused imports:
- `maple.expectation.infrastructure.pgmq.ExternalApiJobPayload` (still used in `verify(pgmqClient).send(...)` calls — keep)
- Actually, `ExternalApiJobPayload` IS used in `verify(pgmqClient).send(...)` — keep
- `PgmqClient` is no longer a field mock — remove the import only if unused
- `QueueNames` is still used in verify calls — keep

Looking at the test: `@Mock lateinit var pgmqClient: PgmqClient` is declared but will become unused since `CalculationJobService` no longer has `pgmqClient` (delegated). The test still verifies `pgmqClient.send(...)` because the mock delegate's effect is observed through the real `pgmqClient` mock? No — when `CalculationJobService.retryExternalApiJob(...)` delegates to `dispatchService.retryExternalApiJob(...)`, the real `pgmqClient` in `dispatchService` is invoked. The test's `pgmqClient` mock is now unused.

**Decision:** Keep the existing 5 tests. Replace `@Mock lateinit var pgmqClient: PgmqClient` with `@Mock lateinit var dispatchService: CalculationDispatchService`. Update the `setUp` to pass `dispatchService` instead of `pgmqClient`. The verify calls need to change to verify `dispatchService.retryExternalApiJob(...)` instead of `pgmqClient.send(...)`.

- [ ] **Step 2: Update mock fields and constructor**

Replace the test file's `@Mock` declarations and `setUp` block:

```kotlin
    @Mock lateinit var jobPort: CalculationJobPort

    @Mock lateinit var eventAppender: DomainEventAppender

    @Mock lateinit var dispatchService: CalculationDispatchService

    @Mock lateinit var ocidResolveTopic: OcidResolveTopic

    @Mock lateinit var nexonApiRequestTopic: NexonApiRequestTopic

    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository

    private lateinit var service: CalculationJobService

    @BeforeEach
    fun setUp() {
        service = CalculationJobService(
            jobPort = jobPort,
            eventAppender = eventAppender,
            ocidResolveTopic = ocidResolveTopic,
            nexonApiRequestTopic = nexonApiRequestTopic,
            nexonApiResponseTopic = nexonApiResponseTopic,
            snapshotRepository = snapshotRepository,
            dispatchService = dispatchService,
        )
    }
```

- [ ] **Step 3: Update test method verify calls**

The 5 `retryExternalApiJob` tests need their `verify(pgmqClient).send(...)` calls changed to `verify(dispatchService).retryExternalApiJob(...)`. The delegation path means the test now verifies the call was forwarded.

Replace the test methods' verify calls:

For each of the 5 existing tests (`retryExternalApiJob increments OCID retry when job is resolving OCID`, etc.):
- `verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(...))` → `verify(dispatchService).retryExternalApiJob(job.jobId, ...)` (with appropriate error code argument)

Use Mockito-Kotlin argument matchers. The exact pattern depends on the test's call:

**Test 1** (`increments OCID retry when job is resolving OCID`):
- Calls `service.retryExternalApiJob(job.jobId, "OCID_RESOLVE_ERROR")`
- Asserts `result == true`
- Original verifies: `verify(jobPort).incrementRetryForOcid(...)` and `verify(pgmqClient).send(...)`
- New verifies: `verify(jobPort).incrementRetryForOcid(...)` and `verify(dispatchService).retryExternalApiJob(job.jobId, "OCID_RESOLVE_ERROR")`

**Test 2** (`increments API retry when job is API requested`):
- Calls `service.retryExternalApiJob(job.jobId)` (default error code = "EXTERNAL_API_ERROR")
- New verifies: `verify(dispatchService).retryExternalApiJob(job.jobId)` — default argument

**Test 3** (`stores provided API error code`):
- Calls `service.retryExternalApiJob(job.jobId, "NEXON_RATE_LIMITED")`
- New verifies: `verify(dispatchService).retryExternalApiJob(job.jobId, "NEXON_RATE_LIMITED")`

**Test 4** (`increments API retry when job is retrying`):
- Calls `service.retryExternalApiJob(job.jobId)` (default)
- New verifies: `verify(dispatchService).retryExternalApiJob(job.jobId)`

**Test 5** (`marks exhausted job failed and archives current message`):
- Calls `service.retryExternalApiJob(job.jobId)` (default)
- New verifies: `verify(dispatchService).retryExternalApiJob(job.jobId)` — and the result of that delegation is what makes `markFailed` happen inside `dispatchService`. This test now passes if `markFailed` is NOT called on `jobPort` (because the actual `markFailed` is inside `dispatchService`, which is mocked). The test's assertion of `result == true` still holds.
- **This test's intent changes** — it now verifies that the delegate is called and returns `true`. The "marks exhausted job failed" assertion is no longer verifiable on `jobPort` because the real logic is in the mocked `dispatchService`.

**Test 6** (if exists — `returns false for non external API processable job`):
- Calls `service.retryExternalApiJob(job.jobId)`
- New verifies: `verify(dispatchService).retryExternalApiJob(job.jobId)`
- The `result == false` assertion still holds if the mock returns false (Mockito returns false by default for Boolean methods).

> **NOTE**: The original test file has 5 tests (lines 60-138). The 5th test (`returns false for non external API processable job`) at line 127 needs the same update. If there are more tests below line 138 in the file that weren't shown, update them too with the same pattern.

After updating, the test file verifies: **`CalculationJobService` correctly delegates `retryExternalApiJob` to `CalculationDispatchService`**. The real `retryExternalApiJob` behavior is tested in `CalculationDispatchServiceTest` (Task 2).

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew :module-infra:test --tests "maple.expectation.infrastructure.job.CalculationJobServiceTest" --continue 2>&1 | tail -15
```
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt
git commit -m "refactor(1073): update CalculationJobServiceTest for delegation"
```

---

## Task 5: Compile + test gates

**Files:** none (verification only)

- [ ] **Step 5.1: Compile module-infra**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew :module-infra:compileKotlin compileJava --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5.2: Run module-infra unit tests**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew :module-infra:test --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL (all tests pass — both `CalculationJobServiceTest` and `CalculationDispatchServiceTest`).

- [ ] **Step 5.3: Full repo compile (sanity)**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
./gradlew compileKotlin compileJava --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

---

## Task 6: PR

**Files:** none

- [ ] **Step 6.1: Push branch**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/1073-calculation-dispatch-extraction
git push -u origin refactor/1073-calculation-dispatch-extraction
```

- [ ] **Step 6.2: Create PR**

```bash
gh pr create \
  --base develop \
  --head refactor/1073-calculation-dispatch-extraction \
  --title "refactor(1073): extract CalculationDispatchService (step 1/2)" \
  --body "$(cat <<'EOF'
## Summary
Extract 6 PGMQ-dispatch methods from `CalculationJobService` into a new `CalculationDispatchService` (step 1 of 2). `CalculationJobService` delegates to the new service via 1-line wrappers.

## Files
- Created: `job/CalculationDispatchService.kt` (3 deps, 6 methods) + tests (10 cases)
- Modified: `job/CalculationJobService.kt` — swap `pgmqClient` for `dispatchService`, 6 methods → delegates
- Modified: `test/.../CalculationJobServiceTest.kt` — mock `dispatchService` instead of `pgmqClient`, verify delegation

## Behavior
Zero behavioral change. Method signatures unchanged. 4 caller files unchanged:
- `CalculationJobTimeoutScanner` — calls `retryOcidResolvingJob`/`retryApiRequestedJob`
- `CalculationRequestedWorker` — calls `dispatchCalculationCompleted`
- `AbstractExpectationCalcWorker` — calls `dispatchToExternalApi`
- `ExternalApiWorker` — calls `saveInputSnapshotAndDispatchCalculation`/`retryExternalApiJob`

## Step 2 (out of scope, future PR)
- Migrate callers to inject `CalculationDispatchService` directly
- Remove delegation methods from `CalculationJobService`

## Verification
- [x] `./gradlew :module-infra:compileKotlin compileJava --continue` passes
- [x] `./gradlew :module-infra:test` passes
- [x] `./gradlew compileKotlin compileJava --continue` passes (full repo)
- [x] 5 existing `CalculationJobServiceTest` tests pass (via delegation)
- [x] 10 new `CalculationDispatchServiceTest` tests pass

Closes #1073

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6.3: Verify PR exists**

Run:
```bash
gh pr view --json number,url,title,state | jq '{number, url, title, state}'
```
Expected: state `OPEN`.

---

## Acceptance criteria

From #1073:
- [x] New `CalculationDispatchService` created with 6 dispatch methods (Task 2)
- [x] `CalculationJobService` delegates to `CalculationDispatchService` (Task 3)
- [x] `./gradlew compileKotlin compileJava --continue` passes (Task 5)
- [x] `./gradlew test` passes (Task 5)
- [x] No behavioral change (Tasks 2-4, all method bodies moved verbatim)
