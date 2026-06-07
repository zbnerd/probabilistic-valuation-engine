# ChunkConsumerTemplate Failure Classification Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the negative-boolean `shouldAckSkip` naming and add KDoc explaining the Kafka ack policy per branch in the synchronizer chunk consumer template and its companion status sealed class.

**Architecture:** Pure rename + KDoc pass. The runtime semantics are already correct (PR #1174 sealed `FailureDecision`, ArtifactNotFoundException typed check from #983). The remaining defect is reader-facing: the boolean name inverts the caller's mental model, and the sealed class methods have no documentation. The fix is mechanical: rename to a positive form, add KDoc that makes the per-branch policy explicit, update the test to match.

**Tech Stack:** Kotlin, JUnit 5, AssertJ.

---

## File Structure

| File | Change |
|------|--------|
| `module-synchronizer/.../state/ChunkExecutionStatus.kt` | Rename abstract method + 5 overrides; add KDoc on abstract method |
| `module-synchronizer/.../consumer/ChunkConsumerTemplate.kt` | Rename private extension, update 1 call site, replace inline comment with KDoc |
| `module-synchronizer/.../state/ChunkExecutionStatusTest.kt` | Rename 8 call sites; no behavior change |

The `classifyFailure` branching gap is already resolved — current code selects `maxAttempts` before the `>=` check (lines 250-254), so no fallthrough exists. The acceptance criterion is satisfied by the existing implementation; this plan does not modify `classifyFailure`.

The extension method in `ChunkConsumerTemplate.kt:287` (private `ChunkExecutionState.shouldAckSkip`) currently delegates to `status.shouldAckSkip(now)`. After rename, the extension name and the sealed-class method name both become `shouldAcknowledge`. This is safe because they are on different receivers — Kotlin resolves by receiver type. The extension's KDoc spells out the state-transition diagram; the abstract method's KDoc explains the ack contract.

---

## Task 1: Rename abstract `shouldAckSkip` → `shouldAcknowledge` on ChunkExecutionStatus

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt:7`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt:31,38,48,54,60` (5 overrides)

- [ ] **Step 1: Update the abstract method declaration with KDoc**

In `ChunkExecutionStatus.kt:7`, replace the abstract method declaration and add KDoc:

```kotlin
    /**
     * Whether the Kafka consumer should acknowledge the message (true) or leave it
     * unacked so Kafka redelivers it later (false).
     *
     * The contract: `true` = "this chunk is finished from our perspective, ack and move on".
     * `false` = "another worker is processing this, or Kafka should retry it later — preserve
     * the message for redelivery".
     *
     * Per-subtype policy is documented on each override.
     */
    abstract fun shouldAcknowledge(now: Instant): Boolean
```

- [ ] **Step 2: Update Pending override (line 31)**

Replace:
```kotlin
        override fun shouldAckSkip(now: Instant): Boolean = false
```
With:
```kotlin
        // Acknowledge when state is PENDING? No — PENDING means the row was just inserted and
        // a worker is about to claim it. Leave unacked so the in-flight worker proceeds.
        override fun shouldAcknowledge(now: Instant): Boolean = false
```

- [ ] **Step 3: Update Processing override (line 38)**

Replace:
```kotlin
        override fun shouldAckSkip(now: Instant): Boolean = false
```
With:
```kotlin
        // Acknowledge while PROCESSING? No — a worker holds the lease and is still running.
        // Leaving unacked lets Kafka redeliver if the worker dies; `leaseUntil` reclaim logic
        // handles the timeout case.
        override fun shouldAcknowledge(now: Instant): Boolean = false
```

- [ ] **Step 4: Update Succeeded override (line 48)**

Replace:
```kotlin
        override fun shouldAckSkip(now: Instant): Boolean = true
```
With:
```kotlin
        // SUCCEEDED: work is done, the chunk will not be reprocessed. Acknowledge.
        override fun shouldAcknowledge(now: Instant): Boolean = true
```

- [ ] **Step 5: Update FailedRetryable override (line 54)**

Replace:
```kotlin
        override fun shouldAckSkip(now: Instant): Boolean = nextRetryAt?.isAfter(now) != true
```
With:
```kotlin
        // FAILED_RETRYABLE with a future retry: another worker will pick it up — leave unacked
        // so Kafka redelivers when the backoff expires.
        // FAILED_RETRYABLE with past or null retry: the retry window has passed and the row
        // is stale; no worker will pick it up. Acknowledge to drain the queue.
        override fun shouldAcknowledge(now: Instant): Boolean = nextRetryAt?.isAfter(now) != true
```

- [ ] **Step 6: Update FailedTerminal override (line 60)**

Replace:
```kotlin
        override fun shouldAckSkip(now: Instant): Boolean = true
```
With:
```kotlin
        // FAILED_TERMINAL: chunk exhausted retries or hit a non-retryable error. Work is
        // permanently done from this consumer's perspective. Acknowledge.
        override fun shouldAcknowledge(now: Instant): Boolean = true
```

- [ ] **Step 7: Compile to confirm no remaining references in main code**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin compileJava --continue
```
Expected: success. The only remaining `shouldAckSkip` reference in the production tree must be inside `ChunkConsumerTemplate.kt:287` (the extension method, which is renamed in Task 2).

- [ ] **Step 8: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt
git commit -m "refactor(synchronizer): rename ChunkExecutionStatus.shouldAckSkip to shouldAcknowledge with KDoc"
```

---

## Task 2: Rename private extension in ChunkConsumerTemplate + update caller

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt:287-300` (extension)
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt:44` (caller)

- [ ] **Step 1: Update the caller at line 44**

Replace:
```kotlin
        if (state.shouldAckSkip()) {
```
With:
```kotlin
        if (state.shouldAcknowledge()) {
```

- [ ] **Step 2: Replace the extension method (lines 287-300) with positive-name + KDoc**

Replace the entire block:
```kotlin
    private fun ChunkExecutionState.shouldAckSkip(): Boolean {
        val now = Instant.now()
        // Note: PROCESSING + active lease returns TRUE (skip — another worker holds it).
        // FAILED_RETRYABLE + future retry returns FALSE (don't skip — Kafka should redeliver later).
        // This inversion is intentional: skip means "ack and move on", so we ack when the work
        // is already done (terminal / leased) and leave unacked when Kafka should retry.
        return when (val s = status) {
            is ChunkExecutionStatus.Succeeded,
            is ChunkExecutionStatus.FailedTerminal -> true
            is ChunkExecutionStatus.FailedRetryable -> s.nextRetryAt?.isAfter(now) != true
            ChunkExecutionStatus.Processing -> leaseUntil?.isAfter(now) == true
            ChunkExecutionStatus.Pending -> false
        }
    }
```

With:
```kotlin
    /**
     * Whether the Kafka message should be acknowledged for this chunk state.
     *
     * State transition policy:
     * - SUCCEEDED → ack. The work is complete and the chunk will not be reprocessed.
     * - FAILED_TERMINAL → ack. Retries exhausted or non-retryable error; nothing more to do here.
     * - FAILED_RETRYABLE with `nextRetryAt` in the future → do NOT ack. Another worker
     *   will pick the chunk up when the backoff expires; preserve the message for redelivery.
     * - FAILED_RETRYABLE with past or null `nextRetryAt` → ack. The retry window has
     *   elapsed and the row is stale.
     * - PROCESSING with an active lease (`leaseUntil` in the future) → ack. Another worker
     *   is still processing this chunk; let it finish.
     * - PROCESSING with expired or null lease → do NOT ack. The lease has timed out and
     *   the chunk is reclaimable; preserve the message so the reclaim path runs.
     * - PENDING → do NOT ack. The row was just inserted; a worker is about to claim it.
     */
    private fun ChunkExecutionState.shouldAcknowledge(): Boolean {
        val now = Instant.now()
        return when (val s = status) {
            is ChunkExecutionStatus.Succeeded,
            is ChunkExecutionStatus.FailedTerminal -> true
            is ChunkExecutionStatus.FailedRetryable -> s.nextRetryAt?.isAfter(now) != true
            ChunkExecutionStatus.Processing -> leaseUntil?.isAfter(now) == true
            ChunkExecutionStatus.Pending -> false
        }
    }
```

- [ ] **Step 3: Compile main code**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin compileJava --continue
```
Expected: success. The name `shouldAckSkip` should no longer exist anywhere in `src/main`.

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): rename shouldAckSkip to shouldAcknowledge with state-policy KDoc"
```

---

## Task 3: Update ChunkExecutionStatusTest to match new name

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt`

There are 8 call sites of `shouldAckSkip(now)` in this file. All must become `shouldAcknowledge(now)`. No behavior change.

- [ ] **Step 1: Replace all 8 call sites in one pass**

Run:
```bash
sed -i 's/shouldAckSkip(now)/shouldAcknowledge(now)/g' module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt
```

- [ ] **Step 2: Verify the substitution**

Run:
```bash
grep -n "shouldAckSkip\|shouldAcknowledge" module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt
```
Expected: 8 lines containing `shouldAcknowledge(now)`, 0 lines containing `shouldAckSkip`.

- [ ] **Step 3: Run the test class**

Run:
```bash
./gradlew :module-synchronizer:test --tests 'maple.synchronizer.state.ChunkExecutionStatusTest'
```
Expected: all tests pass. (No test in this file covers the renamed method's behavior change — that's covered by `ChunkConsumerTemplateTest`.)

- [ ] **Step 4: Run the full synchronizer test class**

Run:
```bash
./gradlew :module-synchronizer:test
```
Expected: all tests pass. The template tests do not call `shouldAckSkip` directly, but they exercise the `state.shouldAcknowledge()` path through `template.submit(...)`.

- [ ] **Step 5: Compile sanity check**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava :module-synchronizer:compileTestKotlin :module-synchronizer:compileTestJava --continue
```
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt
git commit -m "test(synchronizer): update ChunkExecutionStatusTest for shouldAcknowledge rename"
```

---

## Task 4: Verify no stragglers and close #1098

- [ ] **Step 1: Whole-codebase grep for the old name**

Run:
```bash
grep -rn "shouldAckSkip" /home/maple/probabilistic-valuation-engine --include='*.kt' --include='*.java' 2>/dev/null | grep -v '/.worktrees/' | grep -v '/build/' | grep -v '/.claude/worktrees/'
```
Expected: no output.

- [ ] **Step 2: Compile + test final pass**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin compileJava --continue && ./gradlew :module-synchronizer:test
```
Expected: success.

- [ ] **Step 3: Comment on #1098 and close**

Run:
```bash
gh issue comment 1098 --body "Resolved by renaming \`shouldAckSkip\` to \`shouldAcknowledge\` across ChunkExecutionStatus (abstract + 5 overrides) and the private extension in ChunkConsumerTemplate, with KDoc spelling out the per-branch Kafka ack policy. \`classifyFailure\` already uses \`ArtifactNotFoundException\` (typed) from #983 and selects the correct \`maxAttempts\` before the \`>=\` check (no fallthrough gap remains). Closing."
gh issue close 1098 --comment "Closed by refactor commits in this branch."
```

- [ ] **Step 4: Commit (no source changes)**

```bash
git add -A && git diff --cached --quiet || git commit -m "chore: close #1098 after shouldAcknowledge rename"
```
(If the previous task commits already cover everything, this step is a no-op.)

---

## Self-Review

**Spec coverage:**
- ✅ "Exception classification uses typed exceptions instead of string matching" — already done in #983 (`ex is ArtifactNotFoundException` at ChunkConsumerTemplate.kt:249).
- ✅ "`shouldAckSkip` renamed with positive semantics and documented" — Task 1 (sealed class) + Task 2 (template extension) + Task 3 (test).
- ✅ "Branching gap in `classifyFailure` documented or restructured" — already resolved; `maxAttempts` is selected before the comparison (lines 250-254), so the original fallthrough does not exist. Plan does not modify `classifyFailure`.
- ✅ "Compile passes" — verified in Task 1 Step 7, Task 2 Step 3, Task 3 Step 5.
- ✅ "Existing tests still pass" — Task 3 Step 4.

**Placeholder scan:** No "TBD"/"TODO" steps. Each code block is the actual diff.

**Type consistency:** `shouldAcknowledge` introduced on the sealed class (Task 1) is the same method the extension calls (Task 2). Test updates match the rename (Task 3). No `clearLayers`/`clearFullLayers`-style drift.
