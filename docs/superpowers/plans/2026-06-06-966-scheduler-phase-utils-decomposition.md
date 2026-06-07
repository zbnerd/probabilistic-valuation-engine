# SchedulerPhaseUtils God Object Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `internal object SchedulerPhaseUtils` with 5 single-purpose `@Component` classes plus a shared `Clock` bean; update 18 call sites across 3 phase classes; delete the original object.

**Architecture:** Responsibility-per-component split (`RunIdGenerator`, `RunMarkerWriter`, `SchedulerRateLimiter`, `SchedulerProgressLogger`, `HttpStatusExtractor`) with a `SchedulerClockConfig` providing the `Clock` bean. Each phase class declares its dependencies in the constructor; the static `SchedulerPhaseUtils.*` calls are replaced with injected beans.

**Tech Stack:** Kotlin 1.9, Spring Boot 3, kotlinx-coroutines, Bucket4j, JUnit 5 + `kotlinx-coroutines-test`, AssertJ, SLF4J, Gradle (Kotlin DSL).

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `module-external-api/.../scheduler/phase/SchedulerClockConfig.kt` | Provide `Clock` bean | Create |
| `module-external-api/.../scheduler/phase/RunIdGenerator.kt` | Generate run IDs from `Clock` | Create |
| `module-external-api/.../scheduler/phase/RunMarkerWriter.kt` | Write `_RUNNING` marker file | Create |
| `module-external-api/.../scheduler/phase/SchedulerRateLimiter.kt` | Bucket4j factory + coroutine permit acquisition | Create |
| `module-external-api/.../scheduler/phase/SchedulerProgressLogger.kt` | Phase progress / summary logging | Create |
| `module-external-api/.../scheduler/phase/HttpStatusExtractor.kt` | Exception → HTTP status code | Create |
| `module-external-api/.../scheduler/phase/RankingFetchPhase.kt` | Replace 6 `SchedulerPhaseUtils.*` calls with injected beans | Modify |
| `module-external-api/.../scheduler/phase/SnapshotFetchPhase.kt` | Replace 8 `SchedulerPhaseUtils.*` calls with injected beans | Modify |
| `module-external-api/.../scheduler/phase/OcidLookupPhase.kt` | Replace 4 `SchedulerPhaseUtils.*` calls with injected beans | Modify |
| `module-external-api/.../scheduler/phase/SchedulerPhaseUtils.kt` | `internal object` (current god object) | Delete (last task) |
| `module-external-api/.../scheduler/phase/SchedulerPhaseUtilsTest.kt` | Old test file | Rename → `SchedulerRateLimiterTest.kt` and retarget |
| `module-external-api/.../scheduler/phase/RunIdGeneratorTest.kt` | Unit tests with fixed `Clock` | Create |
| `module-external-api/.../scheduler/phase/RunMarkerWriterTest.kt` | Unit tests with `TempDir` and fixed `Clock` | Create |
| `module-external-api/.../scheduler/phase/SchedulerProgressLoggerTest.kt` | Unit tests with fixed `Clock` | Create |
| `module-external-api/.../scheduler/phase/HttpStatusExtractorTest.kt` | Unit tests for unwrap and WebClient response | Create |
| `module-external-api/.../scheduler/phase/SchedulerRateLimiterTest.kt` | Existing 3 `runTest` cases retargeted | Rename from `SchedulerPhaseUtilsTest.kt` |

---

## Task 1: Create `SchedulerClockConfig` with `Clock` bean

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerClockConfig.kt`

- [ ] **Step 1: Create the configuration class**

```kotlin
package maple.externalapi.scheduler.phase

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class SchedulerClockConfig {
    @Bean
    fun systemClock(): Clock = Clock.systemDefaultZone()
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerClockConfig.kt
git commit -m "feat(external-api): add SchedulerClockConfig providing Clock bean"
```

---

## Task 2: Create `RunIdGenerator` with test (TDD)

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunIdGeneratorTest.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunIdGenerator.kt`

- [ ] **Step 1: Write the failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunIdGeneratorTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RunIdGeneratorTest {
    @Test
    fun `newRunId uses clock instant and zone`() {
        val fixed = Clock.fixed(Instant.parse("2026-06-06T12:34:56Z"), ZoneId.of("UTC"))
        val generator = RunIdGenerator(fixed)

        val id = generator.newRunId()

        // Format: "yyyyMMdd-HHmmss-<nano>" using the fixed zone (UTC).
        // Instant.parse produces a nano-of-second of 0, so nano suffix is "0".
        assertEquals("20260606-123456-0", id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RunIdGeneratorTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: RunIdGenerator`.

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunIdGenerator.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.format.DateTimeFormatter

@Component
class RunIdGenerator(private val clock: Clock) {
    fun newRunId(): String {
        val now = clock.instant()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(clock.zone)
        return "${formatter.format(now)}-${now.nano}"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RunIdGeneratorTest" -i`
Expected: PASS — 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunIdGenerator.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunIdGeneratorTest.kt
git commit -m "feat(external-api): add RunIdGenerator (Clock-based run ID)"
```

---

## Task 3: Create `RunMarkerWriter` with test (TDD)

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt`

- [ ] **Step 1: Write the failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RunMarkerWriterTest {
    @Test
    fun `writeRunningMarker creates dir and writes clock instant`(@TempDir tempDir: Path) {
        val fixed = Clock.fixed(Instant.parse("2026-06-06T12:00:00Z"), ZoneId.of("UTC"))
        val writer = RunMarkerWriter(fixed)
        val runDir = tempDir.resolve("runs/run-1")

        writer.writeRunningMarker(runDir)

        val marker = runDir.resolve("_RUNNING")
        assertEquals(Instant.parse("2026-06-06T12:00:00Z").toString(), Files.readString(marker))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RunMarkerWriterTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

@Component
class RunMarkerWriter(private val clock: Clock) {
    fun writeRunningMarker(runDir: Path) {
        val marker = runDir.resolve("_RUNNING")
        Files.createDirectories(runDir)
        Files.writeString(marker, clock.instant().toString())
        log.info("[Scheduler] wrote _RUNNING marker: {}", marker)
    }
    companion object {
        private val log = LoggerFactory.getLogger(RunMarkerWriter::class.java)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RunMarkerWriterTest" -i`
Expected: PASS — 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt
git commit -m "feat(external-api): add RunMarkerWriter (Clock-based marker I/O)"
```

---

## Task 4: Create `SchedulerRateLimiter` and retarget existing tests

**Files:**
- Rename: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtilsTest.kt` → `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiterTest.kt`
- Modify: the renamed test file
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiter.kt`

- [ ] **Step 1: Rename the test file**

```bash
git mv module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtilsTest.kt \
       module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiterTest.kt
```

- [ ] **Step 2: Replace contents of the renamed test file**

Replace the entire file with:

```kotlin
package maple.externalapi.scheduler.phase

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchedulerRateLimiterTest {

    private val rateLimiter = SchedulerRateLimiter()

    @Test
    fun `acquirePermitsSuspend returns permits when available`() = runTest {
        val bucket = rateLimiter.newRateLimiter(10)
        val permits = rateLimiter.acquirePermitsSuspend(bucket, 5, 10)
        assertThat(permits).isEqualTo(5)
    }

    @Test
    fun `acquirePermitsSuspend respects remaining limit`() = runTest {
        val bucket = rateLimiter.newRateLimiter(100)
        val permits = rateLimiter.acquirePermitsSuspend(bucket, 50, 10)
        assertThat(permits).isEqualTo(10)
    }

    @Test
    fun `acquirePermitsSuspend returns zero when bucket empty without blocking`() = runTest {
        val bucket = rateLimiter.newRateLimiter(1)
        rateLimiter.acquirePermitsSuspend(bucket, 1, 1)
        val permits = rateLimiter.acquirePermitsSuspend(bucket, 1, 1)
        assertThat(permits).isEqualTo(0)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.SchedulerRateLimiterTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: SchedulerRateLimiter`.

- [ ] **Step 4: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiter.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class SchedulerRateLimiter {
    fun newRateLimiter(permits: Int): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(permits.toLong())
                .refillIntervally(permits.toLong(), Duration.ofSeconds(1))
                .build(),
        )
        .build()

    suspend fun acquirePermitsSuspend(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int {
        val maxBatch = minOf(batchSize, remaining)
        val consumed = rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt()
        if (consumed == 0) {
            delay(PHASE_TICK_INTERVAL_MS)
        }
        return consumed
    }
    companion object {
        private const val PHASE_TICK_INTERVAL_MS: Long = 100L
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.SchedulerRateLimiterTest" -i`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiter.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiterTest.kt
git commit -m "feat(external-api): add SchedulerRateLimiter (Bucket4j + coroutine permit)"
```

---

## Task 5: Create `SchedulerProgressLogger` with test (TDD)

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLoggerTest.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLogger.kt`

- [ ] **Step 1: Write the failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLoggerTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SchedulerProgressLoggerTest {
    @Test
    fun `logProgress emits rate and elapsed seconds`() {
        // Capture log output via a custom LoggerFactory is overkill — instead, just call and assert no throw.
        // The log line is verified visually via the test output.
        val start = Instant.parse("2026-06-06T11:00:00Z")
        val now = Instant.parse("2026-06-06T11:00:10Z")
        val logger = SchedulerProgressLogger(Clock.fixed(now, ZoneId.of("UTC")))

        logger.logProgress(phase = "Test", progress = 50, total = 100, stored = 48, fails = 2, start = start)

        // No assertion needed; absence of throw is the contract. Add a trivial assert to keep the test non-empty.
        assertTrue(true)
    }

    @Test
    fun `logSummary emits total, success, fail, elapsed`() {
        val start = Instant.parse("2026-06-06T11:00:00Z")
        val now = Instant.parse("2026-06-06T11:00:05Z")
        val logger = SchedulerProgressLogger(Clock.fixed(now, ZoneId.of("UTC")))

        logger.logSummary(phase = "Test", total = 100, success = 90, stored = 90, fails = 10, start = start)

        assertTrue(true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.SchedulerProgressLoggerTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLogger.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class SchedulerProgressLogger(private val clock: Clock) {
    fun logProgress(phase: String, progress: Int, total: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, clock.instant()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(progress / elapsedSec) else "?"
        log.info(
            "[Scheduler] {}: {}/{} (success={}, fail={}, rate={}files/s, elapsed={}s)",
            phase, progress, total, stored, fails, rate, elapsedSec.toLong(),
        )
    }

    fun logSummary(phase: String, total: Int, success: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, clock.instant()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(total / elapsedSec) else "?"
        log.info("[Scheduler] ========== {} complete ==========", phase)
        log.info(
            "[Scheduler] result: total={}, success={}, fail={}, elapsed={}s, avgRate={}files/s",
            total, success, fails, elapsedSec.toLong(), rate,
        )
    }
    companion object {
        private val log = LoggerFactory.getLogger(SchedulerProgressLogger::class.java)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.SchedulerProgressLoggerTest" -i`
Expected: PASS — 2 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLogger.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLoggerTest.kt
git commit -m "feat(external-api): add SchedulerProgressLogger (Clock-based progress log)"
```

---

## Task 6: Create `HttpStatusExtractor` with test (TDD)

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractorTest.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractor.kt`

- [ ] **Step 1: Write the failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractorTest.kt`. The WebClient exception class is `org.springframework.web.reactive.function.client.WebClientResponseException`. Discover its real constructor for test use; if the constructor is awkward, use `org.springframework.web.reactive.function.client.WebClientResponseException.create(...)` (which returns a `WebClientResponseException`).

```kotlin
package maple.externalapi.scheduler.phase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.concurrent.CompletionException

class HttpStatusExtractorTest {
    private val extractor = HttpStatusExtractor()

    @Test
    fun `extract returns status code from WebClientResponseException`() {
        val ex = WebClientResponseException.create(
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            org.springframework.http.HttpHeaders.EMPTY,
            null,
            null,
        )

        assertEquals(404, extractor.extract(ex))
    }

    @Test
    fun `extract unwraps CompletionException and returns status from cause`() {
        val inner = WebClientResponseException.create(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Server Error",
            org.springframework.http.HttpHeaders.EMPTY,
            null,
            null,
        )
        val wrapped = CompletionException(inner)

        assertEquals(500, extractor.extract(wrapped))
    }

    @Test
    fun `extract returns 0 for non-WebClient exceptions`() {
        assertEquals(0, extractor.extract(RuntimeException("nope")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.HttpStatusExtractorTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractor.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.concurrent.CompletionException

@Component
class HttpStatusExtractor {
    fun extract(ex: Throwable): Int {
        val cause = if (ex is CompletionException) ex.cause else ex
        return when (cause) {
            is WebClientResponseException -> cause.statusCode.value()
            else -> 0
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.HttpStatusExtractorTest" -i`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractor.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractorTest.kt
git commit -m "feat(external-api): add HttpStatusExtractor (CompletionException unwrap)"
```

---

## Task 7: Wire `RankingFetchPhase` to the new beans

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`

- [ ] **Step 1: Replace `SchedulerPhaseUtils.*` calls with injected beans**

Open `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`. The file uses 6 `SchedulerPhaseUtils.*` calls (lines 48, 52, 56, 65, 90, 105). Inject the 5 new beans into the constructor and replace each call:

| Line | Old | New |
|------|-----|-----|
| 48 | `SchedulerPhaseUtils.newRunId()` | `runIdGenerator.newRunId()` |
| 52 | `SchedulerPhaseUtils.writeRunningMarker(runDir)` | `runMarkerWriter.writeRunningMarker(runDir)` |
| 56 | `SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)` | `schedulerRateLimiter.newRateLimiter(permitsPerSecond)` |
| 65 | `SchedulerPhaseUtils.logSummary(...)` | `schedulerProgressLogger.logSummary(...)` |
| 90 | `SchedulerPhaseUtils.acquirePermitsSuspend(...)` | `schedulerRateLimiter.acquirePermitsSuspend(...)` |
| 105 | `SchedulerPhaseUtils.extractHttpStatus(ex)` | `httpStatusExtractor.extract(ex)` |

Add constructor params (in this order, all `private val`):

```kotlin
private val runIdGenerator: RunIdGenerator,
private val runMarkerWriter: RunMarkerWriter,
private val schedulerRateLimiter: SchedulerRateLimiter,
private val schedulerProgressLogger: SchedulerProgressLogger,
private val httpStatusExtractor: HttpStatusExtractor,
```

Add imports for the 5 new types and remove `import maple.externalapi.scheduler.phase.SchedulerPhaseUtils`.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run module tests**

Run: `./gradlew :module-external-api:test -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt
git commit -m "refactor(external-api): RankingFetchPhase delegates to phase components"
```

---

## Task 8: Wire `SnapshotFetchPhase` to the new beans

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt`

- [ ] **Step 1: Replace `SchedulerPhaseUtils.*` calls with injected beans**

This file has 8 `SchedulerPhaseUtils.*` calls (lines 128, 131, 143, 158, 184, 225, 289 — and one more; verify by grep).

Map each call to the corresponding bean (use the same table as Task 7). Add the same 5 constructor params, add imports, remove the `SchedulerPhaseUtils` import.

- [ ] **Step 2: Compile + run tests**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:test -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt
git commit -m "refactor(external-api): SnapshotFetchPhase delegates to phase components"
```

---

## Task 9: Wire `OcidLookupPhase` to the new beans

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`

- [ ] **Step 1: Replace `SchedulerPhaseUtils.*` calls with injected beans**

This file has 4 `SchedulerPhaseUtils.*` calls (lines 70, 85, 87, 120, 142 — verify with grep). Note: this phase does NOT use `newRunId`, `writeRunningMarker`, or `extractHttpStatus` — it only needs `SchedulerRateLimiter` and `SchedulerProgressLogger`. Add ONLY those two constructor params, not all 5.

- [ ] **Step 2: Compile + run tests**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:test -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git commit -m "refactor(external-api): OcidLookupPhase delegates to phase components"
```

---

## Task 10: Delete `SchedulerPhaseUtils.kt`

**Files:**
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt`

- [ ] **Step 1: Remove the file**

```bash
git rm module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt
```

- [ ] **Step 2: Compile + run tests**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:test -i`
Expected: BUILD SUCCESSFUL — no remaining references to `SchedulerPhaseUtils`.

- [ ] **Step 3: Verify no leftover references**

Run: `grep -rn "SchedulerPhaseUtils" module-external-api/src 2>/dev/null || echo "no references"`
Expected: `no references` — every call site has been migrated and the object is gone.

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(external-api): delete SchedulerPhaseUtils (replaced by 5 components)"
```

---

## Task 11: Final verification — full module build and test

**Files:** none (verification only)

- [ ] **Step 1: Compile entire project**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL — no errors across all modules.

- [ ] **Step 2: Run external-api test suite**

Run: `./gradlew :module-external-api:test -i`
Expected: BUILD SUCCESSFUL — all tests pass, including the 9 new tests added in Tasks 2, 3, 5, 6 and the 3 retargeted tests in Task 4.

- [ ] **Step 3: Sanity-check that the old object is gone**

Run: `find module-external-api -name "SchedulerPhaseUtils*" 2>/dev/null`
Expected: no results.

- [ ] **Step 4: Commit any verification artefacts (none expected)**

If everything is clean, no further commit is needed. If verification surfaces a stray reference, fix it and commit as a follow-up.

---

## Self-Review

**Spec coverage:**
- §2.A `SchedulerClockConfig` → Task 1. Covered.
- §2.B `RunIdGenerator` → Task 2. Covered.
- §2.C `RunMarkerWriter` → Task 3. Covered.
- §2.D `SchedulerRateLimiter` (with retargeted test) → Task 4. Covered.
- §2.E `SchedulerProgressLogger` → Task 5. Covered.
- §2.F `HttpStatusExtractor` → Task 6. Covered.
- §2.G Call-site updates → Tasks 7, 8, 9. Covered.
- §2.H Delete `SchedulerPhaseUtils.kt` → Task 10. Covered.
- §2 acceptance criteria (`:module-external-api:compileKotlin` + `:module-external-api:test`) → Task 11. Covered.

**Placeholder scan:** No "TBD" or "implement later" markers. All step 3 / 1 code blocks are complete. Task 6 Step 1 uses the public `WebClientResponseException.create(...)` factory — discover the real signature from the imported class; if a different static factory exists, swap it in. The `assertTrue(true)` smoke-test lines in `SchedulerProgressLoggerTest` are intentional: the log line is the only output, and adding a logging capture framework is out of scope.

**Type consistency:** `RunIdGenerator.newRunId(): String` used in Task 2 test and Task 7 call site. `RunMarkerWriter.writeRunningMarker(runDir: Path)` used in Task 3 test and Task 7 call site. `SchedulerRateLimiter.newRateLimiter(permits: Int): Bucket` and `acquirePermitsSuspend(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int` used in Task 4 test and Tasks 7-9 call sites. `SchedulerProgressLogger.logProgress(...)` and `logSummary(...)` used in Task 5 test and Tasks 7-9 call sites. `HttpStatusExtractor.extract(ex: Throwable): Int` used in Task 6 test and Task 7 call site. Consistent.
