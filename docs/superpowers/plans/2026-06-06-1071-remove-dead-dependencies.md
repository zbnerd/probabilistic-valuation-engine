# #1071 — Remove Dead Dependencies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove 5 injected constructor fields that are never read by any method in their containing class. Pure mechanical refactor — no behavior change.

**Architecture:** Each task is a single-file edit. Remove the field, remove its constructor parameter, update any `@Autowired` / Spring injection if the field is the only consumer of a bean (likely not — these are dead fields, so the bean has other consumers). One commit per file.

**Tech Stack:** Kotlin, Spring Boot, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-infra/.../worker/AbstractExpectationCalcWorker.kt` | Drop `characterOcidPort` + `computeBuffer` fields |
| `module-calculator/.../processor/CalculationCache.kt` | Drop `log` field |
| `module-infra/.../ai/AiSreService.kt` | Drop `aiEnabled` field (unblocks #1086) |
| `module-rest-controller/.../auth/JwtAuthInterceptor.kt` | Drop `objectMapper` field |
| `module-rest-controller/.../metrics/V6ReadMetrics.kt` | Convert `requestBuffer` + `inflightRegistry` to constructor-local vals (no field) |

---

## Task 1: `AbstractExpectationCalcWorker` — drop 2 dead fields

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`

- [ ] **Step 1: Find the file and read**

```bash
find /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2/module-infra/src -name "AbstractExpectationCalcWorker.kt"
```

Verify the file lists `characterOcidPort` and `computeBuffer` as constructor params and that no method body references either. (If any method does reference them, skip the removal — issue premise is wrong.)

- [ ] **Step 2: Remove from constructor**

Delete the two constructor parameters and their `private val` declarations. Update the bean wiring if needed:
```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-infra:compileKotlin --console=plain
```

If compile fails, find the bean factory method that constructs this class and remove the corresponding args.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt
git commit -m "refactor(infra): drop dead characterOcidPort + computeBuffer from AbstractExpectationCalcWorker (#1071)"
```

---

## Task 2: `CalculationCache` — drop dead `log` field

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt`

- [ ] **Step 1: Read and verify**

```bash
grep -n "log" /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2/module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt
```

Confirm `log` is declared as `private val log: ...` (likely in companion object or class) but never used in any method body. Remove the field and the `LoggerFactory.getLogger(...)` call.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-calculator:compileKotlin --console=plain
git add module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt
git commit -m "refactor(calculator): drop dead log field from CalculationCache (#1071)"
```

---

## Task 3: `AiSreService` — drop dead `aiEnabled` field (unblocks #1086)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/AiSreService.kt`

- [ ] **Step 1: Read and verify**

```bash
find /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2/module-infra/src -name "AiSreService.kt"
grep -n "aiEnabled" <path>
```

Confirm `aiEnabled` is declared and never referenced in any method body. Remove the field + constructor param. **If a `@ConditionalOnProperty(name = ["ai.enabled"])` annotation is on the class itself, do NOT remove that — it gates the bean.** The `aiEnabled` field is an in-class copy, which is the dead dependency.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-infra:compileKotlin --console=plain
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/AiSreService.kt
git commit -m "refactor(infra): drop dead aiEnabled field from AiSreService (#1071)"
```

---

## Task 4: `JwtAuthInterceptor` — drop dead `objectMapper` field

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtAuthInterceptor.kt`

- [ ] **Step 1: Read and verify**

```bash
grep -n "objectMapper" /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2/module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtAuthInterceptor.kt
```

If `objectMapper` is in the constructor and no method body uses it, remove the param + the import. **NOTE:** this is the same file we just touched for #1093 (BEARER_PREFIX const, BEARER_PREFIX_LENGTH). The fix is purely additive (removing one more field) — no conflict.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-rest-controller:compileKotlin --console=plain
git add module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtAuthInterceptor.kt
git commit -m "refactor(rest-controller): drop dead objectMapper field from JwtAuthInterceptor (#1071)"
```

---

## Task 5: `V6ReadMetrics` — convert 2 fields to constructor-local

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt`

- [ ] **Step 1: Read and verify**

```bash
find /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2/module-rest-controller/src -name "V6ReadMetrics.kt"
```

If `requestBuffer` and `inflightRegistry` are stored as `private val` fields but only used in the `init` block for gauge registration (no method reads them), convert them to constructor parameters (no `val/var`):

```kotlin
class V6ReadMetrics(
    registry: MeterRegistry,
    requestBuffer: RequestBuffer,
    inflightRegistry: InflightRequestRegistry,
) {
    init {
        Gauge.builder("v6.read.requestBuffer.size") { requestBuffer.size() }.register(registry)
        Gauge.builder("v6.read.inflight.size") { inflightRegistry.size() }.register(registry)
    }
    // ... other methods that may still use registry
}
```

The fields become constructor-local — visible to `init` and the lambda captures, not stored as properties.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-rest-controller:compileKotlin --console=plain
git add module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt
git commit -m "refactor(rest-controller): V6ReadMetrics requestBuffer/inflightRegistry as init-only params (#1071)"
```

---

## Task 6: Final verification

- [ ] **Step 1: Full compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew compileKotlin compileJava --continue
./gradlew :module-infra:test :module-calculator:test :module-rest-controller:test --console=plain
```

Expected: all pass.

- [ ] **Step 2: Comment on + close #1071**

```bash
gh issue close 1071 --comment "Closed by refactor commit. Dead dependencies removed from 5 classes across module-infra, module-calculator, module-rest-controller. No behavior change. Unblocks #1086."
```

---

## Self-Review

- **Spec coverage:** All 5 classes from issue body covered by Tasks 1-5. Build/test sweep in Task 6.
- **Placeholder scan:** No TBD/TODO. "If a method does reference it, skip" is conditional guidance, not a placeholder.
- **Type consistency:** Constructor params removed/converted match what the spec requires.
