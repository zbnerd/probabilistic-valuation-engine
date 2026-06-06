# Design: ChunkConsumerTemplate state machine sealed class (issue #983)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Issue: #983
- Note: blocked by #979 (Status/Endpoint enum). Implementing #983 standalone; #979 unresolved. If #979 introduces a different chunk-status enum, the `ChunkExecutionStatus` references in this spec will need adjustment.

---

## 1. Background / Problem

### Background

`ChunkConsumerTemplate` (module-synchronizer) has 3 methods with complex conditional logic:
- `markFailureAndAck` (lines 189-238) — `failure.terminalReason == null` repeated 3 times, 4 exit paths
- `classifyFailure` (lines 241-253) — artifact-missing × attempt-count 2×2 matrix via if/else + `"file not found"` substring matching
- `shouldAckSkip` (lines 271-283) — 4 branches with fallthrough, `PROCESSING + lease active` and `FAILED_RETRYABLE + future retry` produce inverted "skip" decisions

`FailureDecision` is a private data class with a single nullable field. `ArtifactNotFoundException` already exists in `module-common` but is not used by the synchronizer's file readers — they throw `IllegalStateException("... file not found: ...")` instead, and `classifyFailure` matches on substring.

### Problem

The private `FailureDecision` is a poor-man's sealed class (one nullable field for "which kind"). The state machine is implicit. Substring exception matching is fragile.

### Goal

Replace `FailureDecision` with a proper sealed class. Replace substring matching with exception type matching. Replace nested conditionals with exhaustive `when` over the sealed types. Wire the existing `ArtifactNotFoundException` in synchronizer's file readers.

---

## 2. Decision

Three sub-refactors in one PR:

### A) Wire `ArtifactNotFoundException` in 3 synchronizer readers

Replace `throw IllegalStateException("X file not found: $path")` with `throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ReaderName", path)` in:
- `ResultFileReader.kt:24`
- `OcidMappingFileReader.kt:33`
- `BasicChunkFileReader.kt:49, 74` (2 sites)

### B) Convert `FailureDecision` to sealed class

```kotlin
sealed class FailureDecision {
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

### C) Exhaustive `when` in `markFailureAndAck` and `shouldAckSkip`

See spec Section 6 for the full code. Both methods become `when (...)` over the sealed types.

---

## 3. Trade-offs

### Sensitivity

- **Test file coverage:** `DefaultChunkProcessorTest` and `ChunkConsumerTemplateTest` assert on `IllegalStateException` types. After wiring `ArtifactNotFoundException`, those assertions may need updates.
- **Reader blast radius:** All 4 file-not-found throw sites in synchronizer change exception type. Downstream `catch (IllegalStateException)` blocks (if any) become less specific.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Wire existing `ArtifactNotFoundException` | Type-safe classification | 4 throw sites change; test assertions update |
| Sealed `FailureDecision` with `abstract val reason` | DRY reason logic; type-safe | One extra property to maintain |
| Exhaustive `when` in `shouldAckSkip` | Compiler-enforced coverage of all 4 states | Code grows; less obvious at a glance |
| Combine A+B+C in one PR | Atomic state-machine refactor | Larger diff; harder to revert |

### Risk

- **Behavioral drift:** The sealed `FailureDecision` must produce identical DB writes and metric calls as the old data-class version. Mitigation: tests assert on the same metric calls + DB writes; `recordChunkExecutionFailed` is called with the same `reason` strings.
- **`markFailedRetryable` signature:** it accepts `Instant?` for `nextRetryAt`. The new `Retryable` always has a non-null `nextRetryAt`. Mitigation: pass it through; no signature change.
- **#979 dependency:** The blocked issue may introduce a new chunk-status enum. Mitigation: implement #983 against current `ChunkExecutionStatus` (post-#960 sealed class). If #979 changes it, follow-up PR adapts.

### Non-Risk

- DB schema: unchanged.
- Wire format: unchanged.
- Kafka message format: unchanged.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|------:|-------|
| `FailureDecision` types | 1 data class → 1 sealed + 2 subtypes | Net: same file location |
| Substring exception matches in synchronizer | 1 → 0 | `classifyFailure` uses `is ArtifactNotFoundException` |
| File readers throwing `ArtifactNotFoundException` | 0 → 3 readers, 4 sites | |
| `markFailureAndAck` exit paths | 4 implicit → 2 explicit (Retryable / Terminal) | |
| `shouldAckSkip` branches | 4 with fallthrough → exhaustive `when` on 4 states | |
| New test methods | ~6 | Sealed decision + exhaustive when + readers |

### Observed Result

Post-implementation:
- `classifyFailure` returns `FailureDecision.Retryable` or `FailureDecision.Terminal`
- `markFailureAndAck` is a flat `when (decision)` body
- `shouldAckSkip` is a flat `when (status)` body with inline comments
- 3 file readers throw `ArtifactNotFoundException` for missing files
- `./gradlew :module-synchronizer:test` passes
- `./gradlew compileKotlin compileJava --continue` passes

---

## 5. Summary

> Convert `FailureDecision` to sealed class; wire `ArtifactNotFoundException` in synchronizer readers; rewrite `markFailureAndAck` + `shouldAckSkip` as exhaustive `when` over sealed types.

---

## 6. Implementation Outline (reference for writing-plans)

1. Update `ResultFileReader`, `OcidMappingFileReader`, `BasicChunkFileReader` to throw `ArtifactNotFoundException` (4 sites total)
2. Replace `FailureDecision` data class with sealed class (2 subtypes: `Retryable`, `Terminal`) at the bottom of `ChunkConsumerTemplate.kt`
3. Rewrite `classifyFailure` to return sealed subtype, using `ex is ArtifactNotFoundException` instead of substring match
4. Rewrite `markFailureAndAck` body as `when (decision)` exhaustive
5. Rewrite `shouldAckSkip` body as `when (status)` exhaustive (assumes post-#960 sealed `ChunkExecutionStatus`)
6. Add unit tests for `classifyFailure` (artifact + non-artifact × under-max + at-max) and `shouldAckSkip` (all 4 sealed states)
7. Update `DefaultChunkProcessorTest:67-74` to expect `ArtifactNotFoundException`
8. Update `ChunkConsumerTemplateTest:168` (uses `IllegalStateException` — keep as the non-artifact case, add a parallel test for `ArtifactNotFoundException`)
9. Run `./gradlew :module-synchronizer:test` and `./gradlew compileKotlin compileJava --continue`
