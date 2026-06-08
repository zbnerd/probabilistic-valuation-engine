# #1086 — RuleBasedAnalyzer Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract 4 pure rule-based analysis methods from `AiSreService` (module-infra) into a standalone `RuleBasedAnalyzer` class. `AiSreService` keeps only LLM orchestration. No behavior change.

**Architecture:** Stateless `object` (Kotlin) or `@Component` (Spring) — the 4 methods have zero field dependencies, so a `private const val`-style stateless singleton is the cleanest shape. Pick the shape that matches the surrounding `module-infra` convention (check sibling utility classes).

**Tech Stack:** Kotlin, Spring Boot, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/RuleBasedAnalyzer.kt` | NEW — 4 methods + return-type DTOs |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/AiSreService.kt` | MODIFIED — `fallbackAnalysis` delegates; class shrinks |

---

## Task 1: Verify issue preconditions

- [ ] **Step 1: Confirm #1071 is closed on develop**

```bash
gh issue view 1071 --json state -q '.state'
```

Expected: `CLOSED`. If still OPEN, stop and run the #1071 plan first.

- [ ] **Step 2: Read `AiSreService` to find the 4 methods + their return types + their callers**

```bash
find /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2/module-infra/src -name "AiSreService.kt"
```

Identify:
- `analyzeByKeyword(input: <type>): <return>`
- `determineSeverity(input: <type>): <return>`
- `inferAffectedComponents(input: <type>): <return>`
- `suggestActions(input: <type>): <return>`

The `fallbackAnalysis` method likely calls all 4. Confirm: each method is `private` in `AiSreService` and takes only primitives/strings from its parameter list (no `this.x` access).

---

## Task 2: Create `RuleBasedAnalyzer`

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/RuleBasedAnalyzer.kt`

- [ ] **Step 1: Create the class**

Use the same package as `AiSreService`. The 4 methods move verbatim — copy the body of each from `AiSreService`, change visibility from `private` to (depending on surrounding convention) `internal` or `public`. Adjust the qualifier:
- If the methods are simple keyword/severity helpers → `object RuleBasedAnalyzer` (Kotlin singleton, no Spring)
- If Spring DI is preferred → `@Component class RuleBasedAnalyzer` with a no-arg constructor

```kotlin
package maple.expectation.infrastructure.ai

/**
 * Stateless rule-based SRE analyzer. The 4 helpers here are pure functions —
 * no field dependencies, no Spring beans — so they live in a singleton object.
 *
 * Separated from [AiSreService] which owns the LLM-based path. The LLM path
 * delegates here when the model is unavailable or returns low confidence.
 */
internal object RuleBasedAnalyzer {
    fun analyzeByKeyword(input: /* match original signature */): /* match */ = /* body */
    fun determineSeverity(input: /* match */): /* match */ = /* body */
    fun inferAffectedComponents(input: /* match */): /* match */ = /* body */
    fun suggestActions(input: /* match */): /* match */ = /* body */
}
```

Replace `/* match */` placeholders with the actual types and bodies from the original `AiSreService` methods.

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-infra:compileKotlin --console=plain
```

Expected: success (object defined but unused — that's fine for this step).

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/RuleBasedAnalyzer.kt
git commit -m "refactor(infra): add RuleBasedAnalyzer with 4 rule-based helpers (#1086)"
```

---

## Task 3: Refactor `AiSreService` to delegate

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/AiSreService.kt`

- [ ] **Step 1: Remove the 4 private methods from `AiSreService`**

Delete the bodies of `analyzeByKeyword`, `determineSeverity`, `inferAffectedComponents`, `suggestActions`. The class now references them via `RuleBasedAnalyzer.<method>(...)`.

- [ ] **Step 2: Update `fallbackAnalysis` to call the new singleton**

In the `fallbackAnalysis` method body, replace:
```kotlin
val keywords = analyzeByKeyword(input)
val severity = determineSeverity(input)
val components = inferAffectedComponents(input)
val actions = suggestActions(input)
```

with:
```kotlin
val keywords = RuleBasedAnalyzer.analyzeByKeyword(input)
val severity = RuleBasedAnalyzer.determineSeverity(input)
val components = RuleBasedAnalyzer.inferAffectedComponents(input)
val actions = RuleBasedAnalyzer.suggestActions(input)
```

(Or import statically if the project uses that style.)

- [ ] **Step 3: Compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-infra:compileKotlin --console=plain
./gradlew :module-infra:test --console=plain
```

Expected: all pass (no behavior change).

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/AiSreService.kt
git commit -m "refactor(infra): AiSreService.fallbackAnalysis delegates to RuleBasedAnalyzer (#1086)"
```

---

## Task 4: Final verification

- [ ] **Step 1: AiSreService line count drops significantly**

```bash
wc -l module-infra/src/main/kotlin/maple/expectation/infrastructure/ai/AiSreService.kt
```

Expected: ~150-200 lines (down from 288).

- [ ] **Step 2: RuleBasedAnalyzer exists and is the only place these methods are defined**

```bash
grep -rn "fun analyzeByKeyword\|fun determineSeverity\|fun inferAffectedComponents\|fun suggestActions" module-infra/src/main
```

Expected: only matches in `RuleBasedAnalyzer.kt`.

- [ ] **Step 3: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-2
./gradlew :module-infra:test --console=plain
```

Expected: all pass.

- [ ] **Step 4: Comment on + close #1086**

```bash
gh issue close 1086 --comment "Closed by extraction. RuleBasedAnalyzer owns the 4 pure rule-based helpers; AiSreService keeps only LLM orchestration. No behavior change."
```

---

## Self-Review

- **Spec coverage:** New class with 4 methods ✅. AiSreService delegates ✅. Line-count drop implicit but not enforced. No-behavior-change ✅.
- **Placeholder scan:** `/* match */` placeholders are intentional — implementer must fill from source. Acceptable per plan rules.
- **Type consistency:** All signatures must match originals verbatim.
