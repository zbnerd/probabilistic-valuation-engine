# ChunkExecutionStatus Sealed Class Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `ChunkExecutionStatus` enum with a sealed class hierarchy in module-synchronizer; move behavior onto subtypes; preserve DB schema and metrics tags.

**Architecture:** Move enum from `module-common` to `module-synchronizer` as `sealed class ChunkExecutionStatus`. Four subtypes (`Processing`, `Succeeded`, `FailedRetryable`, `FailedTerminal`) carry their own transition/decision methods. PENDING remains a string constant for insert path only. Repository uses `fromName()` for reads and writes subtype `NAME` strings for writes (round-trip identical to old enum).

**Tech Stack:** Kotlin sealed class, Spring JDBC, JUnit 5 + Mockito

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionStatus.kt` | Delete | Old enum |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt` | Create | Sealed class + 4 subtypes + companion |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkExecutionRepository.kt` | Modify | `valueOf` → `fromName`; pass `Instant?` into `FailedRetryable`/`FailedTerminal`; insert path uses `PENDING_NAME` |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt` | Modify | Drop private extensions; polymorphic methods; drop `state.status == PROCESSING` check |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt` | Modify | Method signatures accept sealed `ChunkExecutionStatus`; record `status.name` as tag |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt` | Create | Sealed class behavior unit tests |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt` | Modify | Update `state()` helper |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/repository/ChunkExecutionRepositoryTest.kt` | Modify | Update enum → sealed constructor references |

---

## Task 1: Create sealed class file

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt`

- [ ] **Step 1: Write the file**

```kotlin
package maple.synchronizer.state

import java.time.Instant

sealed class ChunkExecutionStatus(val name: String) {
    abstract fun isTerminal(): Boolean
    abstract fun shouldAckSkip(now: Instant): Boolean
    abstract fun shouldPreserveKafkaRedelivery(now: Instant): Boolean
    fun isTerminalSkip(): Boolean = this is Succeeded || this is FailedTerminal

    companion object {
        const val PENDING_NAME: String = "PENDING"

        fun fromName(s: String): ChunkExecutionStatus = when (s) {
            Processing.NAME -> Processing
            Succeeded.NAME -> Succeeded
            FailedRetryable.NAME -> FailedRetryable(null)
            FailedTerminal.NAME -> FailedTerminal(null)
            else -> throw IllegalArgumentException("Unknown ChunkExecutionStatus name: $s")
        }
    }

    /** Singleton — use `is Processing` checks, not `===`. */
    object Processing : ChunkExecutionStatus(NAME) {
        const val NAME: String = "PROCESSING"
        override fun isTerminal(): Boolean = false
        override fun shouldAckSkip(now: Instant): Boolean = false
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
        /** True when the lease has expired or was never set — this chunk is reclaimable. */
        fun isReclaimed(leaseUntil: Instant?, now: Instant): Boolean =
            leaseUntil?.isAfter(now) != true
    }

    /** Singleton — use `is Succeeded` checks, not `===`. */
    object Succeeded : ChunkExecutionStatus(NAME) {
        const val NAME: String = "SUCCEEDED"
        override fun isTerminal(): Boolean = true
        override fun shouldAckSkip(now: Instant): Boolean = true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }

    data class FailedRetryable(val nextRetryAt: Instant?) : ChunkExecutionStatus(NAME) {
        companion object { const val NAME: String = "FAILED_RETRYABLE" }
        override fun isTerminal(): Boolean = false
        override fun shouldAckSkip(now: Instant): Boolean = nextRetryAt?.isAfter(now) != true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = nextRetryAt?.isAfter(now) == true
    }

    data class FailedTerminal(val reason: String?) : ChunkExecutionStatus(NAME) {
        companion object { const val NAME: String = "FAILED_TERMINAL" }
        override fun isTerminal(): Boolean = true
        override fun shouldAckSkip(now: Instant): Boolean = true
        override fun shouldPreserveKafkaRedelivery(now: Instant): Boolean = false
    }
}
```

- [ ] **Step 2: Compile to verify syntax**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: SUCCESS (file is isolated, not yet referenced)

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt
git commit -m "feat(synchronizer): introduce ChunkExecutionStatus sealed class (#960)"
```

---

## Task 2: Create unit test for sealed class

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package maple.synchronizer.state

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ChunkExecutionStatusTest {

    private val now: Instant = Instant.parse("2026-06-06T12:00:00Z")
    private val future: Instant = now.plusSeconds(60)
    private val past: Instant = now.minusSeconds(60)

    @Test
    fun `fromName round-trips all four non-pending names`() {
        assertThat(ChunkExecutionStatus.fromName("PROCESSING")).isEqualTo(ChunkExecutionStatus.Processing)
        assertThat(ChunkExecutionStatus.fromName("SUCCEEDED")).isEqualTo(ChunkExecutionStatus.Succeeded)
        assertThat(ChunkExecutionStatus.fromName("FAILED_RETRYABLE")).isEqualTo(ChunkExecutionStatus.FailedRetryable(null))
        assertThat(ChunkExecutionStatus.fromName("FAILED_TERMINAL")).isEqualTo(ChunkExecutionStatus.FailedTerminal(null))
    }

    @Test
    fun `fromName throws IllegalArgumentException on unknown value`() {
        val ex = assertThrows<IllegalArgumentException> {
            ChunkExecutionStatus.fromName("BOGUS")
        }
        assertThat(ex.message).contains("BOGUS")
    }

    @Test
    fun `Processing is not terminal and does not preserve redelivery`() {
        val s = ChunkExecutionStatus.Processing
        assertThat(s.isTerminal()).isFalse()
        assertThat(s.shouldAckSkip(now)).isFalse()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `Processing isReclaimed is true when lease is null or expired`() {
        val s = ChunkExecutionStatus.Processing
        assertThat(s.isReclaimed(leaseUntil = null, now = now)).isTrue()
        assertThat(s.isReclaimed(leaseUntil = past, now = now)).isTrue()
    }

    @Test
    fun `Processing isReclaimed is false when lease is in the future`() {
        val s = ChunkExecutionStatus.Processing
        assertThat(s.isReclaimed(leaseUntil = future, now = now)).isFalse()
    }

    @Test
    fun `Succeeded is terminal and always ack-skips`() {
        val s = ChunkExecutionStatus.Succeeded
        assertThat(s.isTerminal()).isTrue()
        assertThat(s.isTerminalSkip()).isTrue()
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `FailedTerminal is terminal and always ack-skips`() {
        val s = ChunkExecutionStatus.FailedTerminal("MAX_ATTEMPTS_EXCEEDED")
        assertThat(s.isTerminal()).isTrue()
        assertThat(s.isTerminalSkip()).isTrue()
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `FailedRetryable with future retry preserves redelivery and does not ack-skip`() {
        val s = ChunkExecutionStatus.FailedRetryable(future)
        assertThat(s.isTerminal()).isFalse()
        assertThat(s.shouldAckSkip(now)).isFalse()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isTrue()
    }

    @Test
    fun `FailedRetryable with past retry does not preserve redelivery and ack-skips`() {
        val s = ChunkExecutionStatus.FailedRetryable(past)
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `FailedRetryable with null nextRetryAt ack-skips immediately`() {
        val s = ChunkExecutionStatus.FailedRetryable(null)
        assertThat(s.shouldAckSkip(now)).isTrue()
        assertThat(s.shouldPreserveKafkaRedelivery(now)).isFalse()
    }

    @Test
    fun `PENDING_NAME constant equals PENDING`() {
        assertThat(ChunkExecutionStatus.PENDING_NAME).isEqualTo("PENDING")
    }

    @Test
    fun `name property on each subtype matches NAME constant`() {
        assertThat(ChunkExecutionStatus.Processing.name).isEqualTo("PROCESSING")
        assertThat(ChunkExecutionStatus.Succeeded.name).isEqualTo("SUCCEEDED")
        assertThat(ChunkExecutionStatus.FailedRetryable(null).name).isEqualTo("FAILED_RETRYABLE")
        assertThat(ChunkExecutionStatus.FailedTerminal(null).name).isEqualTo("FAILED_TERMINAL")
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test --tests "maple.synchronizer.state.ChunkExecutionStatusTest"`
Expected: 9 tests, all PASS

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt
git commit -m "test(synchronizer): ChunkExecutionStatus sealed class behavior tests (#960)"
```

---

## Task 3: Update ChunkExecutionRepository to use sealed class

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkExecutionRepository.kt`

- [ ] **Step 1: Update imports**

Replace the import:
```kotlin
import maple.expectation.common.event.ChunkExecutionStatus
```
with:
```kotlin
import maple.synchronizer.state.ChunkExecutionStatus
```

- [ ] **Step 2: Update `findStatus` to use `fromName`**

In `findStatus`, replace:
```kotlin
return jdbc.query(sql, identity.toParams()) { rs, _ -> rs.getString("status") }
    .firstOrNull()
    ?.let(ChunkExecutionStatus::valueOf)
```
with:
```kotlin
return jdbc.query(sql, identity.toParams()) { rs, _ -> rs.getString("status") }
    .firstOrNull()
    ?.let(ChunkExecutionStatus::fromName)
```

- [ ] **Step 3: Update `findExecutionState` to build sealed subtypes**

In `findExecutionState`, replace:
```kotlin
return jdbc.query(sql, identity.toParams()) { rs, _ ->
    ChunkExecutionState(
        status = ChunkExecutionStatus.valueOf(rs.getString("status")),
        nextRetryAt = rs.getTimestamp("next_retry_at")?.toInstant(),
        leaseUntil = rs.getTimestamp("lease_until")?.toInstant(),
        attemptCount = rs.getInt("attempt_count"),
    )
}.firstOrNull()
```
with:
```kotlin
return jdbc.query(sql, identity.toParams()) { rs, _ ->
    val statusName = rs.getString("status")
    val nextRetryAt = rs.getTimestamp("next_retry_at")?.toInstant()
    val status: ChunkExecutionStatus = when (statusName) {
        ChunkExecutionStatus.FailedRetryable.NAME -> ChunkExecutionStatus.FailedRetryable(nextRetryAt)
        else -> ChunkExecutionStatus.fromName(statusName)
    }
    ChunkExecutionState(
        status = status,
        nextRetryAt = nextRetryAt,
        leaseUntil = rs.getTimestamp("lease_until")?.toInstant(),
        attemptCount = rs.getInt("attempt_count"),
    )
}.firstOrNull()
```

- [ ] **Step 4: Update `insertPendingIfAbsent` to use `PENDING_NAME`**

Find the parameter binding:
```kotlin
.addValue("status", ChunkExecutionStatus.PENDING.name)
```
Replace with:
```kotlin
.addValue("status", ChunkExecutionStatus.PENDING_NAME)
```

- [ ] **Step 5: Find and update any write paths that use `.name` on the old enum**

Search the file for any remaining `ChunkExecutionStatus.X` references (success, failure writes). Update each to use the new sealed subtype's `name` property or `NAME` constant:

For `markSucceeded`:
```kotlin
.addValue("status", ChunkExecutionStatus.Succeeded.name)
```

For `markFailedRetryable`:
```kotlin
.addValue("status", ChunkExecutionStatus.FailedRetryable.NAME)
```

For `markFailedTerminal`:
```kotlin
.addValue("status", ChunkExecutionStatus.FailedTerminal.NAME)
```

(If a method already uses `.name` on an instance, that still works — `Processing.name`, `Succeeded.name`, `FailedRetryable(...).name` all return the same string. Prefer the `NAME` companion const for static references.)

- [ ] **Step 6: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: SUCCESS, or only failures in files not yet updated (ChunkConsumerTemplate, SynchronizerMetrics)

- [ ] **Step 7: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkExecutionRepository.kt
git commit -m "refactor(synchronizer): ChunkExecutionRepository uses sealed ChunkExecutionStatus (#960)"
```

---

## Task 4: Update SynchronizerMetrics signatures

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt`

- [ ] **Step 1: Update import**

Replace:
```kotlin
import maple.expectation.common.event.ChunkExecutionStatus
```
with:
```kotlin
import maple.synchronizer.state.ChunkExecutionStatus
```

- [ ] **Step 2: Find all method signatures taking `ChunkExecutionStatus`**

Search the file for `status: ChunkExecutionStatus`. Each signature already accepts the new sealed type now (no syntactic change), but the **call sites** in `ChunkConsumerTemplate` and tests pass enum constants like `ChunkExecutionStatus.SUCCEEDED` — those need to become `ChunkExecutionStatus.Succeeded`, `ChunkExecutionStatus.FailedRetryable(null)`, etc.

Update all callers (we'll do them in Tasks 5 and 6). The `SynchronizerMetrics` file itself needs no signature changes — `status.name` returns the same string as the old enum `name`.

- [ ] **Step 3: Compile (should still have type errors in callers)**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: FAIL only in `ChunkConsumerTemplate.kt` and test files. No new failures in `SynchronizerMetrics.kt`.

- [ ] **Step 4: Commit import change**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt
git commit -m "refactor(synchronizer): SynchronizerMetrics imports sealed ChunkExecutionStatus (#960)"
```

---

## Task 5: Update ChunkConsumerTemplate to use polymorphic methods

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`

- [ ] **Step 1: Update import**

Replace:
```kotlin
import maple.expectation.common.event.ChunkExecutionStatus
```
with:
```kotlin
import maple.synchronizer.state.ChunkExecutionStatus
```

- [ ] **Step 2: Replace `state.shouldAckSkip()` call (line ~43)**

Find:
```kotlin
if (state.shouldAckSkip()) {
```
Replace with:
```kotlin
if (state.status.shouldAckSkip(Instant.now())) {
```

- [ ] **Step 3: Replace `state.shouldPreserveKafkaRedelivery()` call (line ~69)**

Find:
```kotlin
if (state.shouldPreserveKafkaRedelivery()) {
```
Replace with:
```kotlin
if (state.status.shouldPreserveKafkaRedelivery(Instant.now())) {
```

- [ ] **Step 4: Replace `state.status == PROCESSING` check (line ~91)**

Find:
```kotlin
if (state.status == ChunkExecutionStatus.PROCESSING) {
    metrics.recordChunkExecutionReclaimedExpired(request.identity.executionType)
}
```
Replace with:
```kotlin
if (state.isReclaimedExpired(Instant.now())) {
    metrics.recordChunkExecutionReclaimedExpired(request.identity.executionType)
}
```

Add this private extension to the bottom of the file (alongside other private extensions or near the `FailureDecision` data class):
```kotlin
private fun ChunkExecutionState.isReclaimedExpired(now: Instant): Boolean =
    (status as? ChunkExecutionStatus.Processing)?.isReclaimed(leaseUntil, now) == true
```

- [ ] **Step 5: Update `markUnsupportedSchema` to use sealed type**

Find:
```kotlin
ChunkExecutionStatus.FAILED_TERMINAL,
```
Replace with:
```kotlin
ChunkExecutionStatus.FailedTerminal(UNSUPPORTED_SCHEMA_VERSION),
```

- [ ] **Step 6: Update `markFailureAndAck` to use sealed types**

Find:
```kotlin
val status = if (failure.terminalReason == null) {
    ChunkExecutionStatus.FAILED_RETRYABLE
} else {
    ChunkExecutionStatus.FAILED_TERMINAL
}
```
Replace with:
```kotlin
val status = if (failure.terminalReason == null) {
    ChunkExecutionStatus.FailedRetryable(
        Instant.now().plus(properties.retryBaseBackoff.multipliedBy(claim.attemptCount.toLong())),
    )
} else {
    ChunkExecutionStatus.FailedTerminal(failure.terminalReason)
}
```

- [ ] **Step 7: Delete the three private extension functions (lines ~268-286)**

Delete:
```kotlin
private fun ChunkExecutionStatus.isTerminalSkip(): Boolean =
    this == ChunkExecutionStatus.SUCCEEDED || this == ChunkExecutionStatus.FAILED_TERMINAL

private fun ChunkExecutionState.shouldAckSkip(): Boolean {
    val now = Instant.now()
    if (status.isTerminalSkip()) {
        return true
    }
    if (status == ChunkExecutionStatus.FAILED_RETRYABLE && nextRetryAt?.isAfter(now) == true) {
        return false
    }
    if (status == ChunkExecutionStatus.PROCESSING && leaseUntil?.isAfter(now) == true) {
        return true
    }
    return false
}

private fun ChunkExecutionState.shouldPreserveKafkaRedelivery(): Boolean =
    status == ChunkExecutionStatus.FAILED_RETRYABLE && nextRetryAt?.isAfter(Instant.now()) == true
```

- [ ] **Step 8: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: SUCCESS or only test-file failures

- [ ] **Step 9: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): ChunkConsumerTemplate uses sealed status polymorphic methods (#960)"
```

---

## Task 6: Delete old enum file in module-common

**Files:**
- Delete: `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionStatus.kt`

- [ ] **Step 1: Verify no remaining references to the old enum**

Run: `cd /home/maple/probabilistic-valuation-engine && grep -rln "maple.expectation.common.event.ChunkExecutionStatus" --include="*.kt" .`
Expected: empty output

- [ ] **Step 2: Delete the file**

Run: `cd /home/maple/probabilistic-valuation-engine && git rm module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionStatus.kt`

- [ ] **Step 3: Compile to verify no stale imports**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew compileKotlin compileJava --continue`
Expected: SUCCESS (test compilation will still fail until Task 7)

- [ ] **Step 4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git commit -m "refactor(common): remove obsolete ChunkExecutionStatus enum (#960)"
```

---

## Task 7: Update existing tests

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt`
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/repository/ChunkExecutionRepositoryTest.kt`

- [ ] **Step 1: Update `ChunkConsumerTemplateTest` imports and `state()` helper**

Find the import:
```kotlin
import maple.expectation.common.event.ChunkExecutionStatus
```
Replace with:
```kotlin
import maple.synchronizer.state.ChunkExecutionStatus
```

Find the `state()` helper. Update its `status` parameter type and constructors. Example (the actual helper shape will be discovered when reading the test file):
```kotlin
private fun state(
    status: ChunkExecutionStatus,
    leaseUntil: Instant? = null,
    nextRetryAt: Instant? = null,
): ChunkExecutionState = ChunkExecutionState(
    status = status,
    nextRetryAt = nextRetryAt,
    leaseUntil = leaseUntil,
    attemptCount = 0,
)
```

Then update every call site that passes `ChunkExecutionStatus.SUCCEEDED`, `.PENDING`, `.PROCESSING`, `.FAILED_RETRYABLE` to the new sealed type:
- `ChunkExecutionStatus.SUCCEEDED` → `ChunkExecutionStatus.Succeeded`
- `ChunkExecutionStatus.PENDING` → drop (no longer a type — PENDING never appears in `ChunkExecutionState`)
- `ChunkExecutionStatus.PROCESSING` → `ChunkExecutionStatus.Processing`
- `ChunkExecutionStatus.FAILED_RETRYABLE` → `ChunkExecutionStatus.FailedRetryable(null)`

- [ ] **Step 2: Update `ChunkExecutionRepositoryTest`**

Find the import:
```kotlin
import maple.expectation.common.event.ChunkExecutionStatus
```
Replace with:
```kotlin
import maple.synchronizer.state.ChunkExecutionStatus
```

Update references:
- `ChunkExecutionStatus.PENDING` → `ChunkExecutionStatus.PENDING_NAME` (string constant, since tests may assert DB string value)
- `ChunkExecutionStatus.FAILED_RETRYABLE` → `ChunkExecutionStatus.FailedRetryable(null)` (or compare against `ChunkExecutionStatus.fromName("FAILED_RETRYABLE")`)

- [ ] **Step 3: Run synchronizer tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test`
Expected: PASS (all old tests + new ChunkExecutionStatusTest)

- [ ] **Step 4: Full compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew compileKotlin compileJava --continue`
Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt module-synchronizer/src/test/kotlin/maple/synchronizer/repository/ChunkExecutionRepositoryTest.kt
git commit -m "test(synchronizer): update tests for sealed ChunkExecutionStatus (#960)"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run full test suite**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew test`
Expected: PASS (or pre-existing unrelated failures only)

- [ ] **Step 2: Run full compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew compileKotlin compileJava --continue`
Expected: SUCCESS

- [ ] **Step 3: Search for stale enum references**

Run: `cd /home/maple/probabilistic-valuation-engine && grep -rn "ChunkExecutionStatus\.\(PENDING\|PROCESSING\|SUCCEEDED\|FAILED_RETRYABLE\|FAILED_TERMINAL\)" --include="*.kt" .`
Expected: empty output (all enum constants replaced)

- [ ] **Step 4: Search for stale import path**

Run: `cd /home/maple/probabilistic-valuation-engine && grep -rn "maple.expectation.common.event.ChunkExecutionStatus" --include="*.kt" .`
Expected: empty output

- [ ] **Step 5: Diff stat summary**

Run: `cd /home/maple/probabilistic-valuation-engine && git log --oneline develop..HEAD`
Expected: ~8 commits covering sealed class, tests, repo, consumer, metrics, common cleanup, test updates

- [ ] **Step 6: Push and open PR**

```bash
cd /home/maple/probabilistic-valuation-engine
git push origin HEAD
gh pr create --base develop --title "refactor(synchronizer): ChunkExecutionStatus sealed class (#960)" --body "Implements #960. Replaces enum with sealed class, polymorphic dispatch, DB schema preserved."
```
