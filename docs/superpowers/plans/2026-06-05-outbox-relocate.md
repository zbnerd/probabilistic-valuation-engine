# Outbox Query Relocate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `findCompletedJobsMissingOutboxEvents` from `CalculationJobPort` to `OutboxEventPort` so each port owns the table it queries. Pure relocation, no behavior change.

**Architecture:** Method signature stays identical. SQL moves verbatim. Adapter gains a `NamedParameterJdbcTemplate` constructor param. Scanner swaps the port it calls. Tests on the existing `OutboxEventPortAdapterTest` add a `@Mock jdbc` plus 3 new cases.

**Tech Stack:** Kotlin 1.9+, Spring Boot, JPA + `JpaRepository`, `NamedParameterJdbcTemplate`, Mockito + AssertJ.

**Reference spec:** `docs/superpowers/specs/2026-06-05-outbox-relocate-design.md`

---

## File Structure

| File | Responsibility | Change |
| ---- | -------------- | ------ |
| `module-core/.../core/port/out/CalculationJobPort.kt` | Domain port for job lifecycle | Remove 1 method |
| `module-core/.../core/port/out/OutboxEventPort.kt` | Domain port for outbox events | Add 1 method |
| `module-infra/.../adapter/outgoing/CalculationJobPortAdapter.kt` | JPA adapter for `CalculationJobPort` | Remove 1 override; keep `jdbc` for `createOrFindActiveJob` |
| `module-infra/.../adapter/outgoing/OutboxEventPortAdapter.kt` | JPA + JDBC adapter for `OutboxEventPort` | Add `jdbc` ctor param + 1 override |
| `module-infra/.../infrastructure/job/OutboxCompensatingScanner.kt` | `@Scheduled` scanner that creates missing outbox events | Swap port call; drop `jobPort` ctor param |
| `module-infra/src/test/.../adapter/outgoing/OutboxEventPortAdapterTest.kt` | Unit tests for the adapter | Add `@Mock jdbc` + 3 new tests |

---

## Task 1: Remove method from `CalculationJobPort`

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt:27`

- [ ] **Step 1: Remove the method declaration**

Open `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`. Delete line 27 in its entirety:

```kotlin
    fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>
```

The interface should now have 17 methods (lines 9–27 originally, becomes 9–26 after deletion). Save the file.

- [ ] **Step 2: Compile to surface adapter override mismatch**

Run from worktree root:

```bash
./gradlew :module-core:compileKotlin :module-infra:compileKotlin :module-infra:compileJava --continue
```

Expected: COMPILE FAILURE on `CalculationJobPortAdapter.kt:118` — adapter still overrides the removed method. The port side is verified by the failure.

- [ ] **Step 3: Commit the port change**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt
git commit -m "refactor(core): drop findCompletedJobsMissingOutboxEvents from CalculationJobPort"
```

---

## Task 2: Add method to `OutboxEventPort`

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt`

- [ ] **Step 1: Add the method declaration**

Open `module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt`. Add a new method to the interface (after `incrementPublishAttempts`, line 19). The file becomes:

```kotlin
package maple.expectation.core.port.out

import java.util.UUID

data class OutboxEvent(
    val eventId: UUID,
    val eventType: String,
    val jobId: UUID,
    val payload: String?,
    val published: Boolean,
    val publishAttempts: Int,
)

interface OutboxEventPort {
    fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean
    fun findUnpublished(limit: Int): List<OutboxEvent>
    fun markPublished(eventId: UUID)
    fun markAllPublished(eventIds: List<UUID>)
    fun incrementPublishAttempts(eventId: UUID)
    fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>
}
```

- [ ] **Step 2: Compile to surface adapter implementation mismatch**

```bash
./gradlew :module-core:compileKotlin :module-infra:compileKotlin :module-infra:compileJava --continue
```

Expected: COMPILE FAILURE on `OutboxEventPortAdapter.kt` — adapter does not yet implement the new method. The port side is verified by the failure.

- [ ] **Step 3: Commit the port addition**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt
git commit -m "refactor(core): add findCompletedJobsMissingOutboxEvents to OutboxEventPort"
```

---

## Task 3: Remove method from `CalculationJobPortAdapter`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt:118-130`

- [ ] **Step 1: Remove the override and its blank line**

Open `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`. Delete lines 117 (blank) and 118–130 (the override). The end of the class should now read:

```kotlin
    override fun completeFromCalculating(jobId: UUID): Boolean = jobRepository.completeFromCalculating(jobId) > 0

    private fun CalculationJobEntity.toDomain() = CalculationJob(
        jobId = jobId,
        ocid = ocid,
        userIgn = userIgn,
        presetNo = presetNo,
        requestKey = requestKey,
        status = CalculationJobStatus.valueOf(status),
        snapshotId = snapshotId,
        retryCount = retryCount,
        maxRetries = maxRetries,
        nextRetryAt = nextRetryAt,
        lockedBy = lockedBy,
        lockedUntil = lockedUntil,
        lastErrorCode = lastErrorCode,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}
```

Do not remove the `jdbc` constructor parameter or the `import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate` import — `createOrFindActiveJob` (line 26) still uses `jdbc`.

- [ ] **Step 2: Compile — only `OutboxEventPortAdapter` should now fail**

```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue
```

Expected: COMPILE FAILURE on `OutboxEventPortAdapter.kt` only (missing `findCompletedJobsMissingOutboxEvents` implementation). No other compile errors.

- [ ] **Step 3: Commit the adapter removal**

```bash
git add module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt
git commit -m "refactor(infra): remove findCompletedJobsMissingOutboxEvents from CalculationJobPortAdapter"
```

---

## Task 4: Implement the method in `OutboxEventPortAdapter`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt`

- [ ] **Step 1: Add `jdbc` constructor parameter and new override**

Open `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt`. Replace the entire file with the following:

```kotlin
package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.core.port.out.OutboxEvent
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxEventPortAdapter(
    private val repo: OutboxEventRepository,
    private val jdbc: NamedParameterJdbcTemplate,
) : OutboxEventPort {

    @Transactional(value = "transactionManager", readOnly = false)
    override fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean = repo.insertIfAbsent(UUID.randomUUID(), eventType, jobId, payload) > 0

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findUnpublished(limit: Int): List<OutboxEvent> = repo.findUnpublished(limit, PageRequest.of(0, limit)).map {
        OutboxEvent(it.eventId, it.eventType, it.jobId, it.payload, it.published, it.publishAttempts)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun markPublished(eventId: UUID) {
        repo.markPublished(eventId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun markAllPublished(eventIds: List<UUID>) {
        if (eventIds.isEmpty()) return
        repo.markAllPublished(eventIds)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun incrementPublishAttempts(eventId: UUID) {
        repo.incrementPublishAttempts(eventId)
    }

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID> {
        val sql = """
            SELECT j.job_id FROM calculation_jobs j
            WHERE j.status = 'COMPLETED'
              AND j.completed_at < now() - INTERVAL '1 minute'
              AND NOT EXISTS (
                SELECT 1 FROM outbox_events o
                WHERE o.job_id = j.job_id AND o.event_type = 'CALCULATION_COMPLETED'
              )
            LIMIT :limit
        """.trimIndent()
        return jdbc.queryForList(sql, mapOf("limit" to limit), UUID::class.java)
    }
}
```

The SQL block is verbatim from the old `CalculationJobPortAdapter` location. Annotation matches the existing read-only method `findUnpublished`.

- [ ] **Step 2: Compile to surface scanner call-site mismatch**

```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue
```

Expected: COMPILE FAILURE on `OutboxCompensatingScanner.kt` — `CalculationJobPort` no longer has the method, but the scanner still calls it. All other modules compile clean.

- [ ] **Step 3: Commit the adapter implementation**

```bash
git add module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt
git commit -m "refactor(infra): implement findCompletedJobsMissingOutboxEvents in OutboxEventPortAdapter"
```

---

## Task 5: Update the caller in `OutboxCompensatingScanner`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt`

- [ ] **Step 1: Drop `jobPort` ctor param and update the call**

Open `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt`. Replace the entire file with:

```kotlin
package maple.expectation.infrastructure.job

import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.outbox.compensating-scanner.enabled"], havingValue = "true", matchIfMissing = false)
class OutboxCompensatingScanner(
    private val outboxPort: OutboxEventPort,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    fun scan() {
        val context = TaskContext.of("OutboxCompensatingScanner", "Scan", "system")
        executor.executeVoid({
            val orphaned = outboxPort.findCompletedJobsMissingOutboxEvents(50)
            if (orphaned.isEmpty()) return@executeVoid

            log.warn("Found {} orphaned completed jobs without outbox events", orphaned.size)
            for (jobId in orphaned) {
                val payload = """{"jobId":"$jobId","orphanRecovery":true}"""
                outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, payload)
                log.info("[jobId={}] Compensating: created outbox event", jobId)
            }
        }, context)
    }
}
```

Removed: `import maple.expectation.core.port.out.CalculationJobPort`, the `jobPort: CalculationJobPort` constructor parameter, and changed the call target on line 25 from `jobPort.findCompletedJobsMissingOutboxEvents(50)` to `outboxPort.findCompletedJobsMissingOutboxEvents(50)`.

- [ ] **Step 2: Compile — full project should now compile clean**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. No compile failures anywhere.

- [ ] **Step 3: Commit the scanner update**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt
git commit -m "refactor(infra): call outboxPort for missing outbox event lookup in scanner"
```

---

## Task 6: Update `OutboxEventPortAdapterTest` with `@Mock jdbc` and 3 new tests

**Files:**
- Modify: `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt`

- [ ] **Step 1: Write the failing test file**

Open `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt`. Replace the entire file with:

```kotlin
package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@ExtendWith(MockitoExtension::class)
class OutboxEventPortAdapterTest {

    @Mock lateinit var repo: OutboxEventRepository

    @Mock lateinit var jdbc: NamedParameterJdbcTemplate

    @InjectMocks lateinit var adapter: OutboxEventPortAdapter

    @Test
    fun `insertIfAbsent delegates to ON CONFLICT DO NOTHING`() {
        val jobId = UUID.randomUUID()
        whenever(repo.insertIfAbsent(any(), eq("CALCULATION_COMPLETED"), eq(jobId), any())).thenReturn(1)

        val result = adapter.insertIfAbsent("CALCULATION_COMPLETED", jobId, "{}")

        assertThat(result).isTrue()
    }

    @Test
    fun `insertIfAbsent returns false when conflict`() {
        val jobId = UUID.randomUUID()
        whenever(repo.insertIfAbsent(any(), eq("CALCULATION_COMPLETED"), eq(jobId), any())).thenReturn(0)

        val result = adapter.insertIfAbsent("CALCULATION_COMPLETED", jobId, "{}")

        assertThat(result).isFalse()
    }

    @Test
    fun `findCompletedJobsMissingOutboxEvents returns ids from jdbc`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        whenever(jdbc.queryForList(any<String>(), any<Map<String, Any>>(), eq(UUID::class.java))).thenReturn(ids)

        val result = adapter.findCompletedJobsMissingOutboxEvents(50)

        assertThat(result).hasSize(2).containsExactlyElementsOf(ids)
    }

    @Test
    fun `findCompletedJobsMissingOutboxEvents returns empty list when jdbc yields nothing`() {
        whenever(jdbc.queryForList(any<String>(), any<Map<String, Any>>(), eq(UUID::class.java))).thenReturn(emptyList())

        val result = adapter.findCompletedJobsMissingOutboxEvents(50)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findCompletedJobsMissingOutboxEvents passes limit to jdbc query`() {
        whenever(jdbc.queryForList(any<String>(), any<Map<String, Any>>(), eq(UUID::class.java))).thenReturn(emptyList())

        adapter.findCompletedJobsMissingOutboxEvents(25)

        verify(jdbc).queryForList(any<String>(), eq(mapOf("limit" to 25)), eq(UUID::class.java))
    }
}
```

- [ ] **Step 2: Run the new tests to confirm they pass**

```bash
./gradlew :module-infra:test --tests "maple.expectation.adapter.outgoing.OutboxEventPortAdapterTest"
```

Expected: 5 tests, all PASS (2 existing + 3 new). The new tests use Mockito stubs so they pass against the implementation that was committed in Task 4. If the implementation regressed, the 3 new tests would fail.

- [ ] **Step 3: Run the full unit test suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. No test regressions. Default `./gradlew test` excludes `integration`, `pgmq`, `sentinel`, `quarantine`, `flaky` tags per `.claude/rules/testing-conventions.md`.

- [ ] **Step 4: Commit the test update**

```bash
git add module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt
git commit -m "test(infra): cover findCompletedJobsMissingOutboxEvents in OutboxEventPortAdapterTest"
```

---

## Task 7: Final verification

**Files:** none modified

- [ ] **Step 1: Full compile gate**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full unit test gate**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 3: Verify issue acceptance criteria**

Run from worktree root:

```bash
grep -rn "findCompletedJobsMissingOutboxEvents" --include="*.kt" .
```

Expected: 3 matches total:
- `module-core/.../core/port/out/OutboxEventPort.kt` (declaration)
- `module-infra/.../adapter/outgoing/OutboxEventPortAdapter.kt` (override)
- `module-infra/.../infrastructure/job/OutboxCompensatingScanner.kt` (call site)

No matches in `CalculationJobPort.kt` or `CalculationJobPortAdapter.kt`. Acceptance criteria from issue #1076 are satisfied.

- [ ] **Step 4: No commit needed** — this task is verification only.
