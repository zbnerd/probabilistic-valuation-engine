# Design: Consolidate ChunkExecutionStatus into sealed class (issue #960)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Issue: #960

---

## 1. Background / Problem

### Background

`ChunkConsumerTemplate` (module-synchronizer) branches on `ChunkExecutionStatus` enum in 4+ locations. The branching logic — `isTerminalSkip`, `shouldAckSkip`, `shouldPreserveKafkaRedelivery`, reclaimed-expired detection — lives as private extension functions on the enum, scattered across the consumer file. The enum itself is a flat data carrier with no behavior.

### Problem

State-dependent logic is scattered. Adding a new state or transition rule requires touching multiple call sites. The enum equality checks (`== ChunkExecutionStatus.PROCESSING`) leak enum identity into business decisions instead of letting the state decide.

### Goal

Consolidate state-dependent behavior into the state types themselves via Kotlin sealed class. Replace raw enum comparisons with polymorphic dispatch. Preserve DB schema, wire format, and metrics tag strings.

---

## 2. Decision

Replace `maple.expectation.common.event.ChunkExecutionStatus` (enum) with `maple.synchronizer.state.ChunkExecutionStatus` (sealed class). Move behavior into subtypes. DB serialization uses `name` strings (unchanged from before).

```text
module-common/.../event/ChunkExecutionStatus.kt          → DELETE
module-synchronizer/.../state/ChunkExecutionStatus.kt    → CREATE (sealed + 4 subtypes)
module-synchronizer/.../repository/ChunkExecutionRepository.kt  → use fromName + sealed ctor
module-synchronizer/.../consumer/ChunkConsumerTemplate.kt        → drop private extensions, use polymorphic methods
module-synchronizer/.../metrics/SynchronizerMetrics.kt           → accept sealed ChunkExecutionStatus
```

PENDING is **not** a sealed subtype. It remains a `const val PENDING_NAME = "PENDING"` on the companion object, used only by `insertPendingIfAbsent`. PENDING never appears in `ChunkExecutionState` reads (filtered earlier in the pipeline).

---

## 3. Trade-offs

### Sensitivity

- **DB schema**: enum `name` strings must round-trip identically. Any change to subtype `NAME` constants breaks serialization.
- **Metrics tags**: `recordChunkExecutionFailed(... status, reason)` records `status.name` as a metric tag. Cardinality is unchanged (still PENDING, PROCESSING, SUCCEEDED, FAILED_TERMINAL, FAILED_RETRYABLE).
- **Test count**: 2 test files reference the old enum directly; both need updates.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Sealed class in module-synchronizer (not module-common) | Single-responsibility, no framework-free cross-module coupling | File move; old enum deleted |
| Drop PENDING from sealed subtypes | Avoid YAGNI subtype with no behavior | Insert path uses constant instead of type |
| `data class` for retryable/terminal | Carries `Instant?` fields without separate property | Equality is by value (test helpers need updates) |
| Singleton `object` for Processing/Succeeded | No allocation, identity equality | Cannot carry fields (correct: those states need no Instant data) |

### Risk

- `FailedRetryable` and `FailedTerminal` are data classes — equals/hashCode are by value. Any code that relied on enum identity (`status === FAILED_RETRYABLE`) will silently break. Mitigation: exhaustive search before commit; no such usage found in current codebase.
- Metrics interface signature change (`ChunkExecutionStatus` is sealed now, not enum). Java callers passing the old enum must update. Mitigation: only one caller (`SynchronizerMetrics` itself) plus tests.

### Non-Risk

- DB schema: untouched. `name` strings preserved.
- Kafka message format: untouched.
- Wire types (`ChunkExecutionIdentity`, `ChunkExecutionClaim`, `ChunkExecutionState`): field types change (`status: ChunkExecutionStatus` now polymorphic) but no new fields.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|------:|-------|
| `ChunkExecutionStatus` files | 1 enum → 1 sealed class file | Net file count change: 0 |
| Raw enum comparisons in ChunkConsumerTemplate | 5 → 0 | Polymorphic dispatch replaces `==` |
| Private extension functions removed | 3 | `isTerminalSkip`, `shouldAckSkip`, `shouldPreserveKafkaRedelivery` |
| New test file | 1 | `ChunkExecutionStatusTest.kt` |
| Updated test files | 2 | `ChunkConsumerTemplateTest`, `ChunkExecutionRepositoryTest` |

### Observed Result

Post-implementation:
- All `if (state.status == ChunkExecutionStatus.X)` checks in `ChunkConsumerTemplate` removed
- `state.status.shouldAckSkip(now)` / `.shouldPreserveKafkaRedelivery(now)` polymorphic calls
- `state.leaseUntil` drives reclaimed-expired detection (replaces `state.status == PROCESSING` check)
- `./gradlew :module-synchronizer:test` passes
- `./gradlew compileKotlin compileJava --continue` passes

---

## 5. Summary

> Move `ChunkExecutionStatus` from enum to sealed class in module-synchronizer; behavior lives on subtypes; DB and metrics unchanged.

---

## 6. Implementation Outline (reference for writing-plans)

1. Create `module-synchronizer/.../state/ChunkExecutionStatus.kt` with sealed class + 4 subtypes + companion
2. Delete `module-common/.../event/ChunkExecutionStatus.kt`
3. Update `ChunkExecutionRepository`: replace `valueOf` with `fromName`; pass `Instant?` into `FailedRetryable(nextRetryAt)`; write path uses `PENDING_NAME` / `Succeeded.NAME` / `FailedRetryable.NAME` / `FailedTerminal.NAME`
4. Update `SynchronizerMetrics`: change method signatures to accept sealed `ChunkExecutionStatus`; record `status.name` as tag
5. Update `ChunkConsumerTemplate`: remove `isTerminalSkip/shouldAckSkip/shouldPreserveKafkaRedelivery` extensions; use polymorphic methods; drop `state.status == PROCESSING` check (drive off `leaseUntil`); use sealed type checks in `markSucceededAndAck`/`markFailureAndAck` paths
6. Update test helpers and create `ChunkExecutionStatusTest.kt`
7. Run `./gradlew :module-synchronizer:test` and `./gradlew compileKotlin compileJava --continue`
