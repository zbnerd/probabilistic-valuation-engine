# #986 — SnapshotFetchPhase Endpoint-Specific Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Split `SnapshotFetchPhase` (292 lines, 9 deps) into `CharacterBasicFetchPhase` + `ItemEquipmentFetchPhase` + `BatchFetchSupport` utility. Remove the original `SnapshotFetchPhase` class entirely. Update `ExternalApiScheduler` references.

**Architecture:** Each endpoint gets its own phase class (specific orchestration); common batch logic (rate limiting, CF chaining, recursive processBatch) lives in a shared `BatchFetchSupport`. The old parameterized God method disappears.

**Tech Stack:** Kotlin, Spring Boot, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-external-api/.../scheduler/phase/BatchFetchSupport.kt` | NEW — shared rate-limit + batch processing utility |
| `module-external-api/.../scheduler/phase/CharacterBasicFetchPhase.kt` | NEW — CHARACTER_BASIC-specific orchestration |
| `module-external-api/.../scheduler/phase/ItemEquipmentFetchPhase.kt` | NEW — ITEM_EQUIPMENT-specific orchestration |
| `module-external-api/.../scheduler/phase/SnapshotFetchPhase.kt` | DELETED |
| `module-external-api/.../scheduler/ExternalApiScheduler.kt` | MODIFIED — reference new phase classes |
| `module-external-api/.../scheduler/phase/SnapshotFetchPhaseTest.kt` | DELETED (replaced by per-endpoint tests if any) |

---

## Task 1: Read `SnapshotFetchPhase` and identify split points

- [ ] **Step 1: Read the source**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
find module-external-api/src -name "SnapshotFetchPhase.kt"
```

Identify in the body:
- Common helpers (rate limiter, CF chain, recursive processBatch, error handling) → move to `BatchFetchSupport`
- CHARACTER_BASIC-specific branch (skip-if-existing, has X-Header metadata) → `CharacterBasicFetchPhase`
- ITEM_EQUIPMENT-specific branch (different request key, no skip guard) → `ItemEquipmentFetchPhase`

- [ ] **Step 2: Find all callers**

```bash
grep -rn "SnapshotFetchPhase" module-external-api/src
```

List every file that references the class. Most are `ExternalApiScheduler`; check for any others.

---

## Task 2: Create `BatchFetchSupport`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt`

- [ ] **Step 1: Identify common helpers**

From `SnapshotFetchPhase`, extract the methods that don't depend on endpoint-specific config (e.g., `acquirePermits`, `processBatchRecursively`, `wrapWithErrorHandling`, `createRateLimiter`). These become methods of `BatchFetchSupport`.

- [ ] **Step 2: Create the class**

```kotlin
package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * Shared rate-limiting + batch-processing utilities for endpoint-specific
 * fetch phases. Encapsulates the bucket4j integration, the recursive
 * processBatch loop, and the common error-handling wrappers.
 */
@Component
class BatchFetchSupport {
    fun newRateLimiter(permitsPerSecond: Int): Bucket { /* ... */ }
    suspend fun acquirePermitsSuspend(bucket: Bucket, permits: Int, maxWaitMs: Long): Int { /* ... */ }
    fun <T> runOnExecutor(executor: ExecutorService, block: suspend () -> T): CompletableFuture<T> { /* ... */ }
    // ... other common helpers
}
```

- [ ] **Step 3: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
```

Expected: success (BatchFetchSupport unused for now — that's fine for this step).

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt
git commit -m "refactor(ext-api): add BatchFetchSupport for shared fetch utilities (#986)"
```

---

## Task 3: Create `CharacterBasicFetchPhase`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`

- [ ] **Step 1: Create the class**

Move the CHARACTER_BASIC branch of `SnapshotFetchPhase.execute()` (and its helpers) into a new class. Inject `BatchFetchSupport` instead of duplicating the helpers.

```kotlin
package maple.externalapi.scheduler.phase

import ...

@Component
@ConditionalOnProperty(name = ["external-api.character-basic.enabled"], havingValue = "true", matchIfMissing = false)
class CharacterBasicFetchPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectStorage: ObjectStorage,
    private val ocidLookupPhase: OcidLookupPhase,  // or similar
    private val batchSupport: BatchFetchSupport,
    @Value("\${external-api.character-basic.permits-per-second:50}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
) {
    fun execute(workerExecutor: ExecutorService): CompletableFuture<Path> {
        // ... CHARACTER_BASIC-specific orchestration
        // Use batchSupport.newRateLimiter, batchSupport.acquirePermitsSuspend, etc.
    }
}
```

(Constructor params depend on what the original CHARACTER_BASIC branch used — verify against the source.)

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
```

Expected: success (new class exists, not yet wired in scheduler).

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt
git commit -m "refactor(ext-api): add CharacterBasicFetchPhase (#986)"
```

---

## Task 4: Create `ItemEquipmentFetchPhase`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`

- [ ] **Step 1: Create the class**

Same pattern as Task 3 but for the ITEM_EQUIPMENT branch. Inject `BatchFetchSupport`.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt
git commit -m "refactor(ext-api): add ItemEquipmentFetchPhase (#986)"
```

---

## Task 5: Update `ExternalApiScheduler`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

- [ ] **Step 1: Replace `SnapshotFetchPhase` field(s) with the 2 new phases**

Find the constructor params / fields that hold `SnapshotFetchPhase`. Replace with `characterBasicFetchPhase: CharacterBasicFetchPhase` + `itemEquipmentFetchPhase: ItemEquipmentFetchPhase`. Update the daily pipeline code that calls them — instead of `snapshotFetchPhase.execute(...)` (which parameterized endpoint), call the appropriate new phase.

- [ ] **Step 2: Delete `SnapshotFetchPhase.kt` + its test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
rm module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt
rm module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhaseTest.kt 2>/dev/null || true
```

- [ ] **Step 3: Compile + test + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:compileKotlin --console=plain
./gradlew :module-external-api:test --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git rm module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt 2>/dev/null
git commit -m "refactor(ext-api): wire ExternalApiScheduler to per-endpoint phases, drop SnapshotFetchPhase (#986)"
```

---

## Task 6: Final verification

- [ ] **Step 1: Confirm `SnapshotFetchPhase` no longer exists**

```bash
find module-external-api/src -name "SnapshotFetchPhase.kt"
```

Expected: no output.

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-external-api:test --console=plain
```

Expected: all pass.

- [ ] **Step 3: Comment on + close #986**

```bash
gh issue close 986 --comment "Closed by refactor. SnapshotFetchPhase split into CharacterBasicFetchPhase + ItemEquipmentFetchPhase + BatchFetchSupport. ExternalApiScheduler wires to per-endpoint phases. No behavior change. Unblocks #991."
```

---

## Self-Review

- **Spec coverage:** 2 new phase classes + 1 utility + scheduler update + original class removed. All 5 acceptance criteria covered.
- **Placeholder scan:** No TBD/TODO. Constructor params may need adjustment based on actual source — implementer verifies.
- **Type consistency:** Both phases depend on `BatchFetchSupport` for shared helpers.
