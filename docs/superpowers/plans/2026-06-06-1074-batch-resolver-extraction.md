# #1074 — BatchResolver Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract `BatchReadScheduler.resolveBatch` (65 lines, 6 deps) into a new `BatchResolver` class. `BatchReadScheduler` shrinks to lifecycle + scheduling + delegation, owning only `properties` + `resolver` + `log` + `metrics`.

**Architecture:** Single-responsibility split. `BatchResolver` owns cache lookup, DB fallback, negative cache, urgent pipeline triggering. `BatchReadScheduler` keeps `SmartLifecycle` (start/stop/isRunning/phase) and `@Scheduled` (scheduledDrain).

**Tech Stack:** Kotlin, Spring `SmartLifecycle`, Spring Scheduler, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt` | NEW — owns `resolveBatch` |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt` | MODIFIED — drops 5 deps, delegates to resolver |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/BatchResolverTest.kt` | NEW (or update existing) — covers resolver behavior |

---

## Task 1: Define the resolver class

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt`

- [ ] **Step 1: Create `BatchResolver`**

Lift the existing `resolveBatch` method body verbatim into the new class. Constructor takes the 6 dependencies (`cacheService`, `queryService`, `urgentPublisher`, `properties`, `metrics`, `log`) plus the logger. Method signature: `fun resolveBatch()` — same return type as before (likely `Unit` or `Int`).

If the method has helpers (`negativeCacheCheck`, `cacheLookup`, `dbFallback`), keep them as `private` functions on the new class.

Use the constructor-injection pattern:
```kotlin
@Component
class BatchResolver(
    private val cacheService: ReadModelCacheService,
    private val queryService: ReadModelQueryService,
    private val urgentPublisher: UrgentRequestPublisher,
    private val properties: BatchReadProperties,
    private val metrics: ReadModelMetrics,
) {
    private val log = LoggerFactory.getLogger(BatchResolver::class.java)

    fun resolveBatch() {
        // ... existing body moved from BatchReadScheduler ...
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:compileKotlin --console=plain
```

Expected: success (resolver has duplicates of the scheduler's fields — that's fine, scheduler will be cleaned up next).

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt
git commit -m "refactor(rest-controller): add BatchResolver owning resolveBatch (#1074)"
```

---

## Task 2: Refactor `BatchReadScheduler` to delegate

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt`

- [ ] **Step 1: Replace 5 dependencies with `BatchResolver`**

Old constructor: takes `cacheService`, `queryService`, `urgentPublisher`, `properties`, `metrics`, `log` (6 deps).
New constructor: takes `resolver: BatchResolver`, `properties: BatchReadProperties` (for the scheduledDrain interval), `log`. Lifecycle-related fields (e.g. `running`, `PHASE_BATCH_READ`) stay.

- [ ] **Step 2: Replace `resolveBatch()` body with delegation**

Old body (the 65 lines) → New body:
```kotlin
private fun resolveBatch() {
    resolver.resolveBatch()
}
```

Or even inline the call from `scheduledDrain` directly: `resolver.resolveBatch()`.

- [ ] **Step 3: Compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:compileKotlin --console=plain
./gradlew :module-rest-controller:test --console=plain
```

Expected: compile success, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt
git commit -m "refactor(rest-controller): BatchReadScheduler delegates to BatchResolver (#1074)"
```

---

## Task 3: Final verification

- [ ] **Step 1: Scheduler is now small**

```bash
wc -l module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt
```

Expected: <50 lines.

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:test --console=plain
```

Expected: all pass.

---

## Self-Review

- **Spec coverage:** Two spec components (resolver + slimmed scheduler) covered by Tasks 1-2. No behavioral change requirement satisfied.
- **Placeholder scan:** Method body intentionally says "existing body moved" — implementer must read the source. Acceptable since it's a literal move.
- **Type consistency:** Same dependency types, same return type.
