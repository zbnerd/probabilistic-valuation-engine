# #991 — ItemEquipmentContinuousLoop Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract `runItemEquipmentLoop` / `runItemEquipmentCycle` / `startItemEquipmentLoopOnce` from `ExternalApiScheduler` (195 lines, 4 responsibilities) into a new `ItemEquipmentContinuousLoop` class. `ExternalApiScheduler` keeps only daily pipeline trigger + lifecycle.

**Architecture:** Single-responsibility split. The continuous loop class owns its own `ReentrantLock` + `AtomicBoolean` and the `ItemEquipmentFetchPhase` dependency. Scheduler delegates.

**Tech Stack:** Kotlin, Spring Boot, `ReentrantLock`, `AtomicBoolean`, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-external-api/.../scheduler/ItemEquipmentContinuousLoop.kt` | NEW — continuous loop + lock + state |
| `module-external-api/.../scheduler/ExternalApiScheduler.kt` | MODIFIED — drops 3 methods, delegates to loop |

---

## Task 1: Identify split points

- [ ] **Step 1: Read `ExternalApiScheduler`**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
find module-external-api/src -name "ExternalApiScheduler.kt"
```

Identify the 3 methods (`runItemEquipmentLoop`, `runItemEquipmentCycle`, `startItemEquipmentLoopOnce`) + their dependencies (which fields they read).

- [ ] **Step 2: Confirm #986 is closed on develop**

```bash
gh issue view 986 --json state -q '.state'
```

Expected: `CLOSED`. The plan assumes #986's per-endpoint phases exist (specifically `ItemEquipmentFetchPhase`).

---

## Task 2: Create `ItemEquipmentContinuousLoop`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt`

- [ ] **Step 1: Create the class**

```kotlin
package maple.externalapi.scheduler

import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Owns the continuous ITEM_EQUIPMENT fetch loop. Separated from
 * [ExternalApiScheduler] so the scheduler can focus on the daily pipeline
 * trigger + lifecycle, and this class focuses on the long-running cycle.
 *
 * State: one `ReentrantLock` + one `AtomicBoolean` for "is the loop running".
 */
@Component
class ItemEquipmentContinuousLoop(
    private val itemEquipmentFetchPhase: ItemEquipmentFetchPhase,
) {
    private val log = LoggerFactory.getLogger(ItemEquipmentContinuousLoop::class.java)
    private val running = AtomicBoolean(false)
    private val cycleLock = ReentrantLock()

    /** Run the loop until [stop] is called. Public entry point. */
    fun startItemEquipmentLoopOnce(workerExecutor: ExecutorService) { /* ... */ }

    /** One iteration of the cycle. Returns true if loop should continue. */
    private fun runItemEquipmentCycle(workerExecutor: ExecutorService): Boolean { /* ... */ }

    /** Request loop termination. Idempotent. */
    fun stop() {
        running.set(false)
        cycleLock.lock()
        try { /* signal condition */ } finally { cycleLock.unlock() }
    }
}
```

(Copy the 3 method bodies verbatim from `ExternalApiScheduler`.)

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
```

Expected: success (new class unused for now).

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt
git commit -m "refactor(ext-api): add ItemEquipmentContinuousLoop (#991)"
```

---

## Task 3: Refactor `ExternalApiScheduler` to delegate

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

- [ ] **Step 1: Inject the loop, drop the inlined methods + lock + atomic**

Replace the inlined `runItemEquipmentLoop` / `runItemEquipmentCycle` / `startItemEquipmentLoopOnce` methods with delegation to `ItemEquipmentContinuousLoop.startItemEquipmentLoopOnce(workerExecutor)`. Drop the `ReentrantLock` and `AtomicBoolean` fields.

- [ ] **Step 2: Compile + test + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
./gradlew :module-external-api:test --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "refactor(ext-api): ExternalApiScheduler delegates ITEM_EQUIPMENT loop (#991)"
```

---

## Task 4: Final verification

- [ ] **Step 1: Scheduler is shorter**

```bash
wc -l module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
```

Expected: noticeably smaller (target <100 lines).

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:test --console=plain
```

Expected: all pass.

- [ ] **Step 3: Close #991**

```bash
gh issue close 991 --comment "Closed by refactor. ITEM_EQUIPMENT continuous loop extracted to ItemEquipmentContinuousLoop; scheduler keeps only daily pipeline + lifecycle. No behavior change."
```

---

## Self-Review

- **Spec coverage:** New class ✅. Scheduler delegating ✅. No behavior change ✅.
- **Placeholder scan:** Method bodies "/* ... */" — implementer copies from source. Acceptable.
- **Type consistency:** `ItemEquipmentFetchPhase` (from #986) is the new dep. `ReentrantLock` + `AtomicBoolean` move together.
