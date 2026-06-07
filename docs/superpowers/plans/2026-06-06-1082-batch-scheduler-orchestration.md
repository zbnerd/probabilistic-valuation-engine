# #1082 — BatchReadScheduler Orchestration Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract multi-infra orchestration from `BatchReadScheduler.resolveBatch` + `ExpectationReadFacade.enqueue` so they no longer call Redis/DB/Kafka directly and no longer generate `ResponseEntity` instances. Service layer returns typed results; controllers build HTTP responses.

**Architecture:** Hexagonal-shaped split. `BatchResolver` already exists (from #1074); this plan adds typed-result return types and a controller-side response builder. `ExpectationReadFacade.enqueue` becomes a typed-return function (no `ResponseEntity`).

**Tech Stack:** Kotlin, Spring Boot, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-rest-controller/.../read/BatchReadScheduler.kt` | Already slims to lifecycle + scheduling. `resolveBatch` returns typed result. |
| `module-rest-controller/.../read/BatchResolver.kt` | Returns `BatchResolveResult` (sealed class or DTO) instead of `ResponseEntity` |
| `module-rest-controller/.../read/ExpectationReadResponseMapper.kt` (NEW) | Static mapper: `BatchResolveResult` → `ResponseEntity` |
| `module-rest-controller/.../expectation/ExpectationReadFacade.kt` | `enqueue` returns `EnqueueResult` (typed) instead of `ResponseEntity` |
| `module-rest-controller/.../expectation/EnqueueResponseMapper.kt` (NEW) | Static mapper: `EnqueueResult` → `ResponseEntity` |
| Controller layer | Calls mapper after facade/resolver returns |

---

## Task 1: Read current state

- [ ] **Step 1: Read `BatchResolver` + `ExpectationReadFacade`**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
find module-rest-controller/src -name "BatchResolver.kt" -o -name "ExpectationReadFacade.kt" -o -name "ExpectationV6Controller.kt"
```

Identify the public method return types and the `ResponseEntity` constructions inside them.

- [ ] **Step 2: Find controllers that consume these methods**

```bash
grep -rn "BatchResolver\|ExpectationReadFacade" module-rest-controller/src/main
```

---

## Task 2: Define typed result DTOs

**Files:**
- Create: `module-rest-controller/.../read/BatchResolveResult.kt`
- Create: `module-rest-controller/.../expectation/EnqueueResult.kt`

- [ ] **Step 1: Create the result types**

```kotlin
package maple.restcontroller.read

/**
 * Typed result from BatchResolver. Replaces direct ResponseEntity return
 * so the HTTP-shape decision lives in the controller layer.
 */
sealed interface BatchResolveResult {
    data class Success(val items: List<Any>) : BatchResolveResult
    data class PartialSuccess(val items: List<Any>, val urgentTriggered: Boolean) : BatchResolveResult
    data class CacheMiss(val needsDbFallback: Boolean) : BatchResolveResult
    data class UrgentTriggered(val reason: String) : BatchResolveResult
}
```

```kotlin
package maple.restcontroller.expectation

sealed interface EnqueueResult {
    data class Accepted(val jobId: String) : EnqueueResult
    data class AlreadyRunning(val existingJobId: String) : EnqueueResult
    data class ServiceUnavailable(val reason: String) : EnqueueResult
}
```

(Adjust variants to match the actual branches in the source.)

- [ ] **Step 2: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolveResult.kt \
        module-rest-controller/src/main/kotlin/maple/restcontroller/expectation/EnqueueResult.kt
git commit -m "refactor(rest-controller): add typed BatchResolveResult + EnqueueResult (#1082)"
```

---

## Task 3: Refactor `BatchResolver` to return typed result

**Files:**
- Modify: `module-rest-controller/.../read/BatchResolver.kt`

- [ ] **Step 1: Change return type**

Replace `fun resolveBatch(): ResponseEntity<*>` (or whatever it currently returns) with `fun resolveBatch(): BatchResolveResult`. Inside the method, replace `ResponseEntity.ok(...)`, `ResponseEntity.status(404).body(...)`, etc. with the appropriate `BatchResolveResult` variant.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-rest-controller:compileKotlin --console=plain
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt
git commit -m "refactor(rest-controller): BatchResolver returns BatchResolveResult (#1082)"
```

---

## Task 4: Refactor `ExpectationReadFacade.enqueue` to return typed result

**Files:**
- Modify: `module-rest-controller/.../expectation/ExpectationReadFacade.kt`

- [ ] **Step 1: Change return type**

Same pattern: replace `ResponseEntity<*>` return with `EnqueueResult`.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-rest-controller:compileKotlin --console=plain
git add module-rest-controller/src/main/kotlin/maple/restcontroller/expectation/ExpectationReadFacade.kt
git commit -m "refactor(rest-controller): ExpectationReadFacade.enqueue returns EnqueueResult (#1082)"
```

---

## Task 5: Create response mappers + update controllers

**Files:**
- Create: `module-rest-controller/.../read/ExpectationReadResponseMapper.kt`
- Create: `module-rest-controller/.../expectation/EnqueueResponseMapper.kt`
- Modify: any controller that called the old `ResponseEntity`-returning methods

- [ ] **Step 1: Create mappers**

```kotlin
package maple.restcontroller.read

import org.springframework.http.ResponseEntity

object ExpectationReadResponseMapper {
    fun toResponse(result: BatchResolveResult): ResponseEntity<*> = when (result) {
        is BatchResolveResult.Success -> ResponseEntity.ok(result.items)
        is BatchResolveResult.PartialSuccess -> ResponseEntity.ok(result.items)
        is BatchResolveResult.CacheMiss -> ResponseEntity.status(404).body(emptyList<Any>())
        is BatchResolveResult.UrgentTriggered -> ResponseEntity.accepted().body(emptyList<Any>())
    }
}
```

(Similar for `EnqueueResponseMapper`.)

- [ ] **Step 2: Update controllers**

Find every controller call site that received `ResponseEntity` from `BatchResolver.resolveBatch()` or `ExpectationReadFacade.enqueue(...)`. Replace with: receive the typed result, pass to mapper, return the `ResponseEntity`.

- [ ] **Step 3: Compile + test + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-rest-controller:compileKotlin --console=plain
./gradlew :module-rest-controller:test --console=plain
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadResponseMapper.kt \
        module-rest-controller/src/main/kotlin/maple/restcontroller/expectation/EnqueueResponseMapper.kt \
        module-rest-controller/src/main/kotlin/maple/restcontroller/
git commit -m "refactor(rest-controller): controllers build HTTP responses via mapper (#1082)"
```

---

## Task 6: Final verification

- [ ] **Step 1: No `ResponseEntity` in service/facade layer**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
grep -n "ResponseEntity" \
  module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt \
  module-rest-controller/src/main/kotlin/maple/restcontroller/expectation/ExpectationReadFacade.kt
```

Expected: no output (or only references in KDoc comments).

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-3
./gradlew :module-rest-controller:test --console=plain
```

Expected: all pass.

- [ ] **Step 3: Close #1082**

```bash
gh issue close 1082 --comment "Closed by refactor. resolveBatch and enqueue return typed results; HTTP responses built by controllers via static mappers. No behavior change."
```

---

## Self-Review

- **Spec coverage:** resolveBatch delegated to BatchResolver ✅. HTTP responses moved to controller ✅. enqueue returns typed ✅.
- **Placeholder scan:** Result variants "adjust to match source" — implementer verifies. Acceptable.
- **Type consistency:** Mapper pattern mirrors DTO conventions in the codebase.
