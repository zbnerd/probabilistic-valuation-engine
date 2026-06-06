# ChunkConsumerTemplate State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `FailureDecision` to sealed class; wire `ArtifactNotFoundException` in synchronizer readers; rewrite `markFailureAndAck` + `shouldAckSkip` as exhaustive `when` over sealed types.

**Architecture:** Sealed `FailureDecision` with `Retryable(attemptCount, nextRetryAt)` and `Terminal(attemptCount, terminalReason)`. `classifyFailure` returns sealed subtype via `ex is ArtifactNotFoundException` check (replaces substring matching). `markFailureAndAck` is a flat `when(decision)`. `shouldAckSkip` is a flat `when(status)` over the post-#960 sealed `ChunkExecutionStatus`.

**Tech Stack:** Kotlin sealed class, Spring Kafka, JUnit 5 + Mockito

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-synchronizer/.../storage/ResultFileReader.kt` | Modify | Throw `ArtifactNotFoundException` instead of `IllegalStateException` |
| `module-synchronizer/.../storage/OcidMappingFileReader.kt` | Modify | Same |
| `module-synchronizer/.../storage/BasicChunkFileReader.kt` | Modify | Same (2 sites) |
| `module-synchronizer/.../consumer/ChunkConsumerTemplate.kt` | Modify | Sealed `FailureDecision`; `classifyFailure` returns sealed subtype; `markFailureAndAck` + `shouldAckSkip` use exhaustive `when` |
| `module-synchronizer/src/test/.../consumer/ChunkConsumerTemplateTest.kt` | Modify | Add tests for sealed decision and exhaustive when |
| `module-synchronizer/src/test/.../processor/DefaultChunkProcessorTest.kt` | Modify | Update exception type expectation |

---

## Task 1: Wire ArtifactNotFoundException in file readers

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/ResultFileReader.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt`

- [ ] **Step 1: Update `ResultFileReader.kt`**

Add at the top of the file (after existing imports):
```kotlin
import maple.expectation.common.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
```

Find:
```kotlin
if (!Files.exists(path)) {
    throw IllegalStateException("Result file not found: $path")
}
```
Replace with:
```kotlin
if (!Files.exists(path)) {
    throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ResultFileReader", path.toString())
}
```

- [ ] **Step 2: Update `OcidMappingFileReader.kt`**

Add the same imports. Find:
```kotlin
throw IllegalStateException("Ocid mapping file not found: $path")
```
Replace with:
```kotlin
throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "OcidMappingFileReader", path.toString())
```

- [ ] **Step 3: Update `BasicChunkFileReader.kt` (2 sites)**

Add the same imports. Find both occurrences of:
```kotlin
throw IllegalStateException("Chunk file not found: $path")
```
Replace each with:
```kotlin
throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "BasicChunkFileReader", path.toString())
```

- [ ] **Step 4: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: SUCCESS (callers don't care about exception type)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/
git commit -m "refactor(synchronizer): wire ArtifactNotFoundException in file readers (#983)"
```

---

## Task 2: Convert FailureDecision to sealed class

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`

- [ ] **Step 1: Replace the private `FailureDecision` data class**

Find:
```kotlin
private data class FailureDecision(
    val terminalReason: String?,
)
```

Replace with:
```kotlin
private sealed class FailureDecision {
    abstract val attemptCount: Int
    abstract val reason: String

    data class Retryable(
        override val attemptCount: Int,
        val nextRetryAt: Instant,
    ) : FailureDecision() {
        override val reason: String = RETRYABLE_FAILURE
    }

    data class Terminal(
        override val attemptCount: Int,
        val terminalReason: String,
    ) : FailureDecision() {
        override val reason: String = terminalReason
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: FAIL only in `classifyFailure` and `markFailureAndAck` (callers expect the old shape)

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): FailureDecision sealed class with Retryable and Terminal (#983)"
```

---

## Task 3: Rewrite classifyFailure to return sealed subtype

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`

- [ ] **Step 1: Add import**

Add at the top of the file (after existing imports):
```kotlin
import maple.expectation.error.exception.ArtifactNotFoundException
```

- [ ] **Step 2: Replace `classifyFailure` body**

Find:
```kotlin
private fun classifyFailure(
    ex: Throwable,
    claim: ChunkExecutionClaim,
): FailureDecision {
    val artifactMissing = ex.message?.contains("file not found", ignoreCase = true) == true
    if (artifactMissing && claim.attemptCount >= properties.retry.artifactMissingMaxAttempts) {
        return FailureDecision(ARTIFACT_MISSING_MAX_ATTEMPTS)
    }
    if (!artifactMissing && claim.attemptCount >= properties.retry.maxAttempts) {
        return FailureDecision(MAX_ATTEMPTS_EXCEEDED)
    }
    return FailureDecision(terminalReason = null)
}
```

Replace with:
```kotlin
private fun classifyFailure(
    ex: Throwable,
    claim: ChunkExecutionClaim,
): FailureDecision {
    val artifactMissing = ex is ArtifactNotFoundException
    val maxAttempts = if (artifactMissing) {
        properties.retry.artifactMissingMaxAttempts
    } else {
        properties.retry.maxAttempts
    }
    return if (claim.attemptCount >= maxAttempts) {
        val terminalReason = if (artifactMissing) ARTIFACT_MISSING_MAX_ATTEMPTS else MAX_ATTEMPTS_EXCEEDED
        FailureDecision.Terminal(
            attemptCount = claim.attemptCount,
            terminalReason = terminalReason,
        )
    } else {
        FailureDecision.Retryable(
            attemptCount = claim.attemptCount,
            nextRetryAt = Instant.now().plus(
                properties.retryBaseBackoff.multipliedBy(claim.attemptCount.toLong()),
            ),
        )
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: FAIL only in `markFailureAndAck` (still uses old `failure.terminalReason`)

- [ ] **Step 4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): classifyFailure returns sealed FailureDecision subtype (#983)"
```

---

## Task 4: Rewrite markFailureAndAck as exhaustive when

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`

- [ ] **Step 1: Replace `markFailureAndAck` body**

Find the entire `markFailureAndAck` method (lines 189-239). Replace with:

```kotlin
private fun markFailureAndAck(
    request: ChunkConsumerRequest,
    claim: ChunkExecutionClaim,
    ex: Throwable,
) {
    val decision = classifyFailure(ex, claim)
    val error = ex.message ?: ex.javaClass.simpleName

    val marked = when (decision) {
        is FailureDecision.Retryable -> chunkExecutionRepository.markFailedRetryable(
            request.identity,
            claim.attemptCount,
            error,
            decision.nextRetryAt,
        )
        is FailureDecision.Terminal -> chunkExecutionRepository.markFailedTerminal(
            request.identity,
            claim.attemptCount,
            error,
            decision.terminalReason,
        )
    }

    if (!marked) {
        logFailedStateWrite(request, claim)
        return
    }

    val status: ChunkExecutionStatus = when (decision) {
        is FailureDecision.Retryable -> ChunkExecutionStatus.FailedRetryable(decision.nextRetryAt)
        is FailureDecision.Terminal -> ChunkExecutionStatus.FailedTerminal(decision.terminalReason)
    }
    metrics.recordChunkExecutionFailed(
        request.identity.executionType,
        status,
        decision.reason,
    )
    request.onFailure(ex)

    when (decision) {
        is FailureDecision.Retryable -> {
            request.log.warn(
                "[{}] retryable chunk failure recorded, leaving unacked for Kafka redelivery: runId={} chunkId={} attempt={}",
                request.logPrefix,
                request.runId,
                request.chunkId,
                claim.attemptCount,
            )
        }
        is FailureDecision.Terminal -> {
            request.acknowledgment.acknowledge()
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: FAIL only in `shouldAckSkip` (still uses old enum comparison)

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): markFailureAndAck uses exhaustive when on FailureDecision (#983)"
```

---

## Task 5: Rewrite shouldAckSkip as exhaustive when

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`

- [ ] **Step 1: Replace `shouldAckSkip` body**

Find:
```kotlin
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
```

Replace with:
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
    }
}
```

- [ ] **Step 2: Delete the now-unused `isTerminalSkip` extension**

Find:
```kotlin
private fun ChunkExecutionStatus.isTerminalSkip(): Boolean =
    this == ChunkExecutionStatus.SUCCEEDED || this == ChunkExecutionStatus.FAILED_TERMINAL
```

Delete this method. (After #960 it is part of the sealed class itself as `ChunkExecutionStatus.isTerminalSkip()`, so no replacement is needed in this file.)

- [ ] **Step 3: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: SUCCESS (test compilation may still fail)

- [ ] **Step 4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): shouldAckSkip uses exhaustive when on sealed status (#983)"
```

---

## Task 6: Add unit tests for sealed FailureDecision

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt`

- [ ] **Step 1: Add the following test methods to the existing test class**

Read the existing test file first to understand the `request(...)` helper, `state(...)` helper, and `properties` fixture. Then add these tests (adjust the helper calls to match the file's conventions):

```kotlin
@Test
fun `classifyFailure with ArtifactNotFoundException and attempts under max returns Retryable`() {
    val ex = ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "Test", "test/path")
    val claim = ChunkExecutionClaim(attemptCount = 1)
    // Use reflection or a test-only accessor to call private classifyFailure.
    // Or: invoke submit() with a process that throws ArtifactNotFoundException
    //     and verify the resulting DB call is markFailedRetryable (not Terminal).
    // For simplicity, exercise via submit():
    template.submit(request(
        process = { throw ex },
        attemptCount = 1,
    ))
    verify(chunkExecutionRepository).markFailedRetryable(
        any(), eq(1), any(), any()
    )
    verify(chunkExecutionRepository, never()).markFailedTerminal(any(), any(), any(), any())
}

@Test
fun `classifyFailure with ArtifactNotFoundException and attempts at max returns Terminal ARTIFACT_MISSING`() {
    val ex = ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "Test", "test/path")
    template.submit(request(
        process = { throw ex },
        attemptCount = properties.retry.artifactMissingMaxAttempts,
    ))
    verify(chunkExecutionRepository).markFailedTerminal(
        any(), eq(properties.retry.artifactMissingMaxAttempts), any(), eq("ARTIFACT_MISSING_MAX_ATTEMPTS")
    )
}

@Test
fun `classifyFailure with non-artifact exception and attempts at max returns Terminal MAX_ATTEMPTS`() {
    val ex = IllegalStateException("some other error")
    template.submit(request(
        process = { throw ex },
        attemptCount = properties.retry.maxAttempts,
    ))
    verify(chunkExecutionRepository).markFailedTerminal(
        any(), eq(properties.retry.maxAttempts), any(), eq("MAX_ATTEMPTS_EXCEEDED")
    )
}

@Test
fun `shouldAckSkip returns true for Succeeded`() {
    val state = ChunkExecutionState(
        status = ChunkExecutionStatus.Succeeded,
        nextRetryAt = null,
        leaseUntil = null,
        attemptCount = 0,
    )
    assertThat(state.shouldAckSkip()).isTrue()
}

@Test
fun `shouldAckSkip returns true for FailedTerminal`() {
    val state = ChunkExecutionState(
        status = ChunkExecutionStatus.FailedTerminal("X"),
        nextRetryAt = null,
        leaseUntil = null,
        attemptCount = 0,
    )
    assertThat(state.shouldAckSkip()).isTrue()
}

@Test
fun `shouldAckSkip returns false for FailedRetryable with future retry`() {
    val state = ChunkExecutionState(
        status = ChunkExecutionStatus.FailedRetryable(Instant.now().plusSeconds(60)),
        nextRetryAt = Instant.now().plusSeconds(60),
        leaseUntil = null,
        attemptCount = 1,
    )
    assertThat(state.shouldAckSkip()).isFalse()
}

@Test
fun `shouldAckSkip returns true for Processing with active lease`() {
    val state = ChunkExecutionState(
        status = ChunkExecutionStatus.Processing,
        nextRetryAt = null,
        leaseUntil = Instant.now().plusSeconds(60),
        attemptCount = 0,
    )
    assertThat(state.shouldAckSkip()).isTrue()
}
```

(Adapt the helper calls — `request(...)`, `state(...)`, `properties` — to match the file's existing fixtures. The new tests follow the same pattern as existing ones in `ChunkConsumerTemplateTest.kt`.)

- [ ] **Step 2: Run tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test --tests "maple.synchronizer.consumer.ChunkConsumerTemplateTest"`
Expected: All tests PASS (old + new)

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt
git commit -m "test(synchronizer): tests for sealed FailureDecision and exhaustive shouldAckSkip (#983)"
```

---

## Task 7: Update DefaultChunkProcessorTest exception expectation

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt`

- [ ] **Step 1: Update the test**

Find:
```kotlin
fun `process - file not found propagates exception`() {
    ...
    .thenThrow(IllegalStateException("Result file not found"))
    ...
    .hasMessageContaining("Result file not found")
}
```

Update the throw type to `ArtifactNotFoundException`:
```kotlin
fun `process - file not found propagates ArtifactNotFoundException`() {
    ...
    .thenThrow(ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ResultFileReader", "/tmp/missing"))
    ...
    .isInstanceOf(ArtifactNotFoundException::class.java)
    .hasMessageContaining("Result file not found")
}
```

- [ ] **Step 2: Add import**

Add at the top of the test file:
```kotlin
import maple.expectation.common.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
```

- [ ] **Step 3: Run test**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test --tests "maple.synchronizer.processor.DefaultChunkProcessorTest"`
Expected: PASS

- [ ] **Step 4: Update existing reader tests to expect ArtifactNotFoundException**

In `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/ResultFileReaderTest.kt`, find the test at line ~74:
```kotlin
.hasMessageContaining("Result file not found")
```

Add to that same test (or replace the assertion chain):
```kotlin
.isInstanceOf(ArtifactNotFoundException::class.java)
```

Add the import:
```kotlin
import maple.expectation.error.exception.ArtifactNotFoundException
```

Apply the same change in `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt` at line ~54:
- Add `ArtifactNotFoundException` import
- Add `.isInstanceOf(ArtifactNotFoundException::class.java)` to the assertion chain

For `BasicChunkFileReaderTest.kt`, check if a similar test exists. If yes, apply the same change. If no, skip.

- [ ] **Step 5: Run storage tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test --tests "maple.synchronizer.storage.*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/test/kotlin/maple/synchronizer/storage/ResultFileReaderTest.kt module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt module-synchronizer/src/test/kotlin/maple/synchronizer/storage/BasicChunkFileReaderTest.kt
git commit -m "test(synchronizer): reader tests expect ArtifactNotFoundException (#983)"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run full test suite**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew test`
Expected: PASS (or pre-existing unrelated failures only)

- [ ] **Step 2: Full compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew compileKotlin compileJava --continue`
Expected: SUCCESS

- [ ] **Step 3: Search for stale substring matching**

Run: `cd /home/maple/probabilistic-valuation-engine && grep -rn "file not found" --include="*.kt" module-synchronizer/src/main/`
Expected: empty output (substring match removed; only the new exception messages remain)

- [ ] **Step 4: Push and open PR**

```bash
cd /home/maple/probabilistic-valuation-engine
git push origin HEAD
gh pr create --base develop --title "refactor(synchronizer): ChunkConsumerTemplate state machine sealed class (#983)" --body "Implements #983. Sealed FailureDecision; ArtifactNotFoundException wiring; exhaustive when in markFailureAndAck + shouldAckSkip. Note: blocked by #979, not resolved."
```
