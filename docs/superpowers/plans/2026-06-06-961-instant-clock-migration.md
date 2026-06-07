# #961 — Instant.now() / System.nanoTime() → Clock Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace 48 `Instant.now()` / `System.nanoTime()` / `LocalDate.now()` call sites across 12 files in module-external-api with `Clock`-injected variants. `SchedulerPhaseUtils` excluded (handled by #966).

**Architecture:** Pure mechanical refactor. Each affected class gains a `Clock` constructor param (default `Clock.systemUTC()` where Spring config doesn't override). `Instant.now()` → `Instant.now(clock)`. `System.nanoTime()` deadline loops: keep `nanoTime` for performance (it's monotonic, intended for measurement) or switch to `Clock`-based `Instant.now(clock).plus(duration)` — pick per call site.

**Tech Stack:** Kotlin, Spring Boot, `java.time.Clock`, Gradle multi-module.

---

## File Structure

| File | Sites | Change |
|---|---|---|
| `snapshot/ChunkedSnapshotSink.kt` | 10 (8 Instant + 2 nanoTime) | Add `Clock` ctor param |
| `snapshot/GzipJsonlChunkWriter.kt` | 3 | Add `Clock` ctor param |
| `runstatus/RunStatusTracker.kt` | 4 | Add `Clock` ctor param |
| `runstatus/RunStatus.kt` | 1 | Remove data class default; pass `clock` from callers |
| `scheduler/phase/OcidLookupPhase.kt` | 3 | Add `Clock` ctor param |
| `scheduler/phase/SnapshotFetchPhase.kt` | 10 | Add `Clock` ctor param |
| `scheduler/phase/RankingFetchPhase.kt` | 4 | Add `Clock` ctor param |
| `infra/nexon/NexonExternalApiClientAdapter.kt` | 4 | Add `Clock` ctor param |
| `cleanup/ArtifactCleanupScheduler.kt` | 3 | Add `Clock` ctor param |
| `cleanup/ConsumedChunkCleanupScheduler.kt` | 2 (nanoTime) | Add `Clock` ctor param |
| `urgent/UrgentCharacterRequestConsumer.kt` | 3 | Add `Clock` ctor param |
| Plus: any bean factories that construct these classes need the `Clock` arg (Spring auto-wires) |

---

## Task 1: Audit exact call sites

- [ ] **Step 1: Count sites per file**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
for f in snapshot/ChunkedSnapshotSink.kt snapshot/GzipJsonlChunkWriter.kt runstatus/RunStatusTracker.kt runstatus/RunStatus.kt scheduler/phase/OcidLookupPhase.kt scheduler/phase/SnapshotFetchPhase.kt scheduler/phase/RankingFetchPhase.kt infra/nexon/NexonExternalApiClientAdapter.kt cleanup/ArtifactCleanupScheduler.kt cleanup/ConsumedChunkCleanupScheduler.kt urgent/UrgentCharacterRequestConsumer.kt; do
  full="module-external-api/src/main/kotlin/maple/externalapi/$f"
  c=$(grep -c "Instant\.now()\|System\.nanoTime()\|LocalDate\.now()" "$full" 2>/dev/null || echo 0)
  echo "$f: $c"
done
```

Cross-check against the issue's table. Note any discrepancies.

- [ ] **Step 2: Check existing test coverage**

```bash
grep -rln "Clock\.fixed\|FakeClock\|TestClock" module-external-api/src/test 2>/dev/null
```

If no test clock helper exists, note it — Task 12 may need to add a tiny `TestClock` util for testability.

---

## Task 2: Per-file migration (12 tasks, one commit per file)

For each file, the change pattern is:

1. Add `import java.time.Clock`
2. Add `private val clock: Clock = Clock.systemUTC()` to the constructor (or as a `@Value`/`@Autowired` field if a `Clock` bean is configured)
3. Replace each `Instant.now()` with `Instant.now(clock)`
4. Replace each `LocalDate.now()` with `LocalDate.now(clock)` (since `LocalDate.now(Clock)` exists in Java 9+; check the project's Java target — if 8, use `Instant.now(clock).atZone(clock.zone).toLocalDate()`)
5. For `System.nanoTime()` deadline loops: convert to `Instant.now(clock).plusNanos(duration.toNanos())` for cross-clock readability, OR keep `System.nanoTime()` and just add a `// measured via monotonic clock for perf` comment. The spec accepts either.
6. For `RunStatus.updatedAt = Instant.now()` data class default: change to nullable `Instant? = null` and let the caller (`RunStatusTracker`) pass `Instant.now(clock)` explicitly.
7. Update bean factories (if any) to pass `Clock.systemUTC()` or inject a `Clock` bean.

- [ ] **Steps per file (12 files):**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
# After all 12 files done:
./gradlew :module-external-api:test --console=plain
```

- [ ] **Step: Commit per file (12 commits):**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
git commit -m "refactor(ext-api): inject Clock into ChunkedSnapshotSink (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt
git commit -m "refactor(ext-api): inject Clock into GzipJsonlChunkWriter (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt
git commit -m "refactor(ext-api): inject Clock into RunStatusTracker (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt
git commit -m "refactor(ext-api): drop Instant.now() default from RunStatus (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git commit -m "refactor(ext-api): inject Clock into OcidLookupPhase (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt
git commit -m "refactor(ext-api): inject Clock into SnapshotFetchPhase (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt
git commit -m "refactor(ext-api): inject Clock into RankingFetchPhase (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt
git commit -m "refactor(ext-api): inject Clock into NexonExternalApiClientAdapter (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt
git commit -m "refactor(ext-api): inject Clock into ArtifactCleanupScheduler (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt
git commit -m "refactor(ext-api): inject Clock into ConsumedChunkCleanupScheduler (#961)"
git add module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt
git commit -m "refactor(ext-api): inject Clock into UrgentCharacterRequestConsumer (#961)"
```

---

## Task 3: Final verification

- [ ] **Step 1: Zero remaining `Instant.now()` / `LocalDate.now()` in target files**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
grep -rn "Instant\.now()\|LocalDate\.now()" \
  module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt \
  module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt \
  module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
  module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt \
  module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt \
  module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt \
  module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt \
  module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt \
  module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt
```

Expected: no output.

(For `RunStatus.kt`, the default was removed; for `ConsumedChunkCleanupScheduler.kt`, the `System.nanoTime()` may legitimately remain — verify per spec.)

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:test --console=plain
```

Expected: all pass.

- [ ] **Step 3: Close #961**

```bash
gh issue close 961 --comment "Closed by refactor. Clock injected into 12 classes; Instant.now() and LocalDate.now() replaced with Clock-injected variants. System.nanoTime() preserved where used for monotonic measurement. RunStatus.updatedAt default removed; callers pass Instant.now(clock) explicitly. No behavior change."
```

---

## Self-Review

- **Spec coverage:** All 12 target files covered by Task 2 sub-steps. Acceptance criteria all met.
- **Placeholder scan:** "Pick per call site" for nanoTime is intentional judgment — implementer decides.
- **Type consistency:** `Instant.now(clock)` returns `Instant` — no signature drift.
