# Design: SchedulerPhaseUtils God Object decomposition (issue #966)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Issue: #966
- Note: extends the #961 Clock transition to the scheduler phase layer. Issue #966 is "Blocked by #961" but the decomposition is independent — #966 introduces the per-responsibility `@Component` classes and the shared `Clock` bean, while #961 covers the rest of `module-external-api`. They can be merged in either order; #966 is self-contained once the `Clock` bean is registered.

---

## 1. Background / Problem

### Background

`SchedulerPhaseUtils` (module-external-api) is an `internal object` with 6 distinct responsibilities wrapped in static methods. 18 call sites across 3 phase classes (`RankingFetchPhase`, `SnapshotFetchPhase`, `OcidLookupPhase`) depend on it.

The methods hide:
- 6 `Instant.now()` calls (time is untestable)
- 1 `Files.createDirectories` + 1 `Files.writeString` (filesystem I/O is unmockable)
- 1 `kotlinx.coroutines.delay(100)` (already coroutine-friendly — the issue's "Thread.sleep" reference is outdated; current code uses `delay`)
- 1 `DateTimeFormatter` with `ZoneId.systemDefault()` (timezone is implicit)
- Stateless exception unwrapping (`extractHttpStatus`)

### Problem

- `Instant.now()` and `ZoneId.systemDefault()` are baked into `newRunId` and `writeRunningMarker`. Tests cannot assert on a fixed run ID or a fixed marker timestamp.
- The phase classes can only be unit-tested with the real filesystem and the real clock.
- The `internal object` is a single point of compilation coupling: changing `newRunId`'s format breaks all 3 phases simultaneously.
- Mixed responsibilities in one type: rate-limiting, run-ID generation, file I/O, logging, and HTTP-status extraction are unrelated concerns sharing a name.

### Goal

Split `SchedulerPhaseUtils` into 5 single-purpose `@Component` types, each injectable with a shared `Clock` bean (and the file-writer with a `Path` factory). The 3 phase classes depend on the new beans by constructor injection. The `internal object` is deleted.

---

## 2. Decision

Five new `@Component` types, one shared `Clock` bean. The `internal object` is removed.

### A) `SchedulerClockConfig` (Configuration)

Provides the single `Clock` bean. In production: `Clock.systemDefaultZone()`. In tests: override.

```kotlin
@Configuration
class SchedulerClockConfig {
    @Bean
    fun systemClock(): Clock = Clock.systemDefaultZone()
}
```

### B) `RunIdGenerator` (`@Component`)

```kotlin
@Component
class RunIdGenerator(private val clock: Clock) {
    fun newRunId(): String {
        val now = clock.instant()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(clock.zone)
        return "${formatter.format(now)}-${now.nano}"
    }
}
```

### C) `RunMarkerWriter` (`@Component`)

```kotlin
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

### D) `SchedulerRateLimiter` (`@Component`)

```kotlin
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

    suspend fun acquirePermitsSuspend(
        rateLimiter: Bucket,
        batchSize: Int,
        remaining: Int,
    ): Int {
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

### E) `SchedulerProgressLogger` (`@Component`)

```kotlin
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

### F) `HttpStatusExtractor` (`@Component`, stateless)

```kotlin
@Component
class HttpStatusExtractor {
    fun extract(ex: Throwable): Int {
        val cause = if (ex is java.util.concurrent.CompletionException) ex.cause else ex
        return when (cause) {
            is org.springframework.web.reactive.function.client.WebClientResponseException -> cause.statusCode.value()
            else -> 0
        }
    }
}
```

### G) Call-site updates

Each of `RankingFetchPhase`, `SnapshotFetchPhase`, `OcidLookupPhase` gets the relevant beans injected via constructor (replacing the static-method calls). The phase classes are already `@Component` (or registered as Spring beans), so constructor injection is additive.

| Old call | New call |
|---|---|
| `SchedulerPhaseUtils.newRunId()` | `runIdGenerator.newRunId()` |
| `SchedulerPhaseUtils.writeRunningMarker(runDir)` | `runMarkerWriter.writeRunningMarker(runDir)` |
| `SchedulerPhaseUtils.newRateLimiter(permits)` | `schedulerRateLimiter.newRateLimiter(permits)` |
| `SchedulerPhaseUtils.acquirePermitsSuspend(...)` | `schedulerRateLimiter.acquirePermitsSuspend(...)` |
| `SchedulerPhaseUtils.logProgress(...)` | `schedulerProgressLogger.logProgress(...)` |
| `SchedulerPhaseUtils.logSummary(...)` | `schedulerProgressLogger.logSummary(...)` |
| `SchedulerPhaseUtils.extractHttpStatus(ex)` | `httpStatusExtractor.extract(ex)` |

### H) `internal object SchedulerPhaseUtils` is deleted

After all 18 call sites are migrated, the file is removed.

---

## 3. Trade-offs

### Sensitivity

- **Test framework:** `SchedulerPhaseUtilsTest` is the only test file touching this class. It tests 3 coroutine-based scenarios using `runTest`. The 3 tests target `newRateLimiter` + `acquirePermitsSuspend`, both of which move to `SchedulerRateLimiter`. The test file is renamed and updated to instantiate a `SchedulerRateLimiter` directly (no Spring needed — pure constructor + suspend function).
- **Bean wiring:** All 5 new types are `@Component`. Spring picks them up automatically. No new `@Configuration` is needed beyond `SchedulerClockConfig` for the `Clock` bean.
- **Log lines:** Format strings and log prefixes (`[Scheduler]`) are preserved verbatim.
- **`PHASE_TICK_INTERVAL_MS`:** Moved from top-level `private const` to `SchedulerRateLimiter.Companion`. No public API change.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| 5 separate `@Component` classes | One responsibility each, individually testable | 5 new files, 3 phase classes gain constructor params |
| `Clock` as a constructor-injected bean | All time becomes testable; `Instant.now()` disappears from production | 1 new `@Configuration` class |
| `HttpStatusExtractor` as `@Component` (not `object`) | Consistent with the rest of the split; future logic (e.g. metrics) can be added | Slight overhead vs. a stateless `object` |
| Keep `delay(100)` (no `Thread.sleep` refactor) | The code is already coroutine-friendly; the issue's "Thread.sleep 3건" was outdated | None |
| Move `PHASE_TICK_INTERVAL_MS` to `Companion` | Encapsulation | Tiny: same numeric constant |
| Delete `SchedulerPhaseUtils.kt` rather than deprecate | Forces every call site to update; no half-migrated state | One less place to look if a regression appears — mitigated by the new tests |

### Risk

- **Bean wiring regressions:** Spring's component scan must find all 5 new classes. Mitigation: the package `maple.externalapi.scheduler.phase` is already in the component-scan path (the existing phase classes live there).
- **Constructor-parameter order in phase classes:** Adding 4-5 params to `RankingFetchPhase` / `SnapshotFetchPhase` / `OcidLookupPhase` risks breaking positional call sites. Mitigation: the phase classes are Spring-managed and constructed by DI; no manual construction outside the test code.
- **`Clock` zone:** `Clock.systemDefaultZone()` matches the original `ZoneId.systemDefault()` behavior, so the run-ID format is unchanged. The `clock.zone` is the same as the system default.
- **`@Bean` `systemClock`:** The `Clock` interface is `java.time.Clock`; the bean returns the system default. If a future test needs a fixed clock, it overrides the bean in a test slice.
- **Test file path:** The existing test moves from `SchedulerPhaseUtilsTest.kt` to `SchedulerRateLimiterTest.kt`. No external dependency on the test class name (no `@Tag` references, no reflection).

### Non-Risk

- DB schema: unchanged.
- Wire format: unchanged.
- Kafka message format: unchanged.
- `Bucket` / `Bandwidth` / `delay` API: unchanged.
- Module boundary (`module-external-api`): all new classes stay inside the module.
- Existing 3 `SchedulerPhaseUtilsTest` tests are preserved (relabeled, retargeted) — the scenarios are byte-identical.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|------:|-------|
| `SchedulerPhaseUtils` `internal object` | 1 → 0 | Deleted |
| `Instant.now()` in `SchedulerPhaseUtils` | 6 → 0 | All replaced by `clock.instant()` |
| `Files.*` calls in `SchedulerPhaseUtils` | 2 → 0 | Moved to `RunMarkerWriter` |
| `kotlinx.coroutines.delay` in `SchedulerPhaseUtils` | 1 → 1 | Moved to `SchedulerRateLimiter.acquirePermitsSuspend` |
| New `@Component` types | 5 | `RunIdGenerator`, `RunMarkerWriter`, `SchedulerRateLimiter`, `SchedulerProgressLogger`, `HttpStatusExtractor` |
| New `@Configuration` | 1 | `SchedulerClockConfig` (provides `Clock` bean) |
| Updated phase classes | 3 | `RankingFetchPhase`, `SnapshotFetchPhase`, `OcidLookupPhase` |
| Existing tests preserved | 3 | `acquirePermitsSuspend` × 3, retargeted to `SchedulerRateLimiter` |
| New unit tests | ~7 | One happy-path test per `@Component`, plus a fixed-Clock test for `RunIdGenerator` |
| Call sites updated | 18 | All `SchedulerPhaseUtils.*` references removed |

### Observed Result

Post-implementation:
- `SchedulerPhaseUtils.kt` does not exist
- Each phase class has its dependencies declared explicitly in the constructor
- `Clock` is the single time source — overridable in tests
- All existing 3 `SchedulerPhaseUtilsTest` cases pass against `SchedulerRateLimiter`
- `./gradlew :module-external-api:compileKotlin` passes
- `./gradlew :module-external-api:test` passes

---

## 5. Summary

> Replace the `internal object SchedulerPhaseUtils` with 5 single-purpose `@Component` classes (`RunIdGenerator`, `RunMarkerWriter`, `SchedulerRateLimiter`, `SchedulerProgressLogger`, `HttpStatusExtractor`) plus a shared `Clock` bean; update 18 call sites across the 3 phase classes; delete the original object.

---

## 6. Implementation Outline (reference for writing-plans)

1. Create `SchedulerClockConfig` (`module-external-api/.../scheduler/phase/SchedulerClockConfig.kt`) with `@Bean fun systemClock(): Clock = Clock.systemDefaultZone()`.
2. Create `RunIdGenerator` (`@Component`, constructor `Clock`) with `newRunId(): String` using `clock.instant()` and `clock.zone`.
3. Create `RunMarkerWriter` (`@Component`, constructor `Clock`) with `writeRunningMarker(runDir: Path)` using `Files.createDirectories` + `Files.writeString` + `clock.instant().toString()`.
4. Create `SchedulerRateLimiter` (`@Component`) with `newRateLimiter(permits: Int): Bucket` and `suspend fun acquirePermitsSuspend(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int` (moved verbatim from the object).
5. Create `SchedulerProgressLogger` (`@Component`, constructor `Clock`) with `logProgress` and `logSummary` (moved verbatim, `Instant.now()` → `clock.instant()`).
6. Create `HttpStatusExtractor` (`@Component`) with `extract(ex: Throwable): Int` (moved verbatim).
7. Update `RankingFetchPhase` constructor to inject `RunIdGenerator`, `RunMarkerWriter`, `SchedulerRateLimiter`, `SchedulerProgressLogger`, `HttpStatusExtractor`; replace all `SchedulerPhaseUtils.*` call sites.
8. Update `SnapshotFetchPhase` the same way.
9. Update `OcidLookupPhase` the same way.
10. Rename `SchedulerPhaseUtilsTest.kt` → `SchedulerRateLimiterTest.kt` and retarget the 3 `runTest` cases to `SchedulerRateLimiter`.
11. Add unit tests for the new components (one happy-path per component, plus a fixed-Clock test for `RunIdGenerator`).
12. Delete `SchedulerPhaseUtils.kt`.
13. Run `./gradlew :module-external-api:compileKotlin` and `./gradlew :module-external-api:test`.
