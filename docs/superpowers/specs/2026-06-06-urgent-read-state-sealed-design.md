# Design: UrgentReadState sealed class (issue #959)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Issue: #959

---

## 1. Background / Problem

### Background

`UrgentReadState` is a bare enum in `module-rest-controller/.../read/UrgentReadStatus.kt`. State-determination logic lives in `ReadModelCacheService.status()` (line 129-144). State-consumption logic is duplicated in `ExpectationV6Controller.getStatus()` (line 50-64). Both independently branch on enum values, with subtle differences (consumption checks `PENDING || UNKNOWN`, determination produces all 4).

### Problem

The enum is a data carrier. Decisions like "should we try the DB?" and "what's the retry-after value?" are computed in two places via scattered `if` checks. Adding a new state requires touching both files in sync.

### Goal

Move state-dependent behavior onto the state types via Kotlin sealed class. Replace `state == PENDING || state == UNKNOWN` with `state.shouldTryDb()`. Replace retry-after computation with `state.retryAfterSeconds(configDefault)`. Preserve JSON output format.

---

## 2. Decision

Convert `UrgentReadState` from enum to sealed class in same file/package. Four subtypes: `Ready`, `NotFound`, `Pending`, `Unknown`. Each carries its own behavior methods and (for `Pending`) its own data fields.

```text
module-rest-controller/.../read/UrgentReadStatus.kt
├── UrgentReadState  (sealed class)
│   ├── Ready         (object, singleton)
│   ├── NotFound      (object, singleton)
│   ├── Pending       (data class, carries queuePositionApprox + estimatedWaitSeconds)
│   └── Unknown       (object, singleton)
└── UrgentReadStatusResponse  (unchanged DTO)
```

JSON serialization uses `@JsonValue` on the `name` property and `@JsonCreator` on `fromName()`. Output string values stay identical: `"READY"`, `"NOT_FOUND"`, `"PENDING"`, `"UNKNOWN"`.

---

## 3. Trade-offs

### Sensitivity

- **JSON contract:** `UrgentReadStatusResponse.state` serialization is part of the public API. Any change to subtype `NAME` constants breaks clients.
- **Controller consumption:** `ExpectationV6Controller.getStatus()` is the only consumer of the `shouldTryDb` decision. If the rule changes (e.g., "don't try DB on Pending either"), only the subtype changes.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Sealed class in same file as response DTO | Co-located with related types | One file grows |
| `Pending` is data class with fields | Carries queue/wait data without separate property | Equality is by value |
| `Ready/NotFound/Unknown` are objects | No allocation, identity equality | Cannot carry fields (correct: no data) |
| `@JsonValue` / `@JsonCreator` for Jackson | Idiomatic Kotlin + Jackson pattern | Couples to Jackson annotation |

### Risk

- Jackson polymorphic deserialization of sealed class: must work with `name` as the discriminator. Mitigation: `@JsonValue` on `name` and `@JsonCreator` on `fromName`; tests assert round-trip.
- `Pending` data class equality: `Pending(1, 30) != Pending(1, 30)` is `false` (equal). Any test that asserted reference identity breaks. Mitigation: no such usage in current code.

### Non-Risk

- DB schema: untouched (no DB).
- Wire format (JSON): preserved by `@JsonValue`/`@JsonCreator`.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|------:|-------|
| `UrgentReadState` types | 1 enum → 1 sealed + 4 subtypes | Net file: 1 |
| Raw enum comparisons in controller | 2 (`== PENDING || == UNKNOWN`) → 0 | `shouldTryDb()` |
| Branching in `ReadModelCacheService.status()` | 4-way when + 2 ifs | Direct subtype construction |
| New test file | 1 | `UrgentReadStateTest.kt` |

### Observed Result

Post-implementation:
- `UrgentReadState` is sealed class with 4 subtypes
- `ReadModelCacheService.status()` returns subtype directly
- `ExpectationV6Controller.getStatus()` uses `state.shouldTryDb()`
- JSON output unchanged
- `./gradlew :module-rest-controller:test` passes

---

## 5. Summary

> Convert `UrgentReadState` enum to sealed class with behavior methods; preserve JSON contract; collapse scattered branching into polymorphic dispatch.

---

## 6. Implementation Outline (reference for writing-plans)

1. Convert `UrgentReadState` in `UrgentReadStatus.kt` to sealed class with 4 subtypes + `name` property + `@JsonValue` annotation
2. Add `fromName(s)` companion factory with `@JsonCreator` annotation; throws `IllegalArgumentException` on unknown
3. Add behavior methods: `retryAfterSeconds(configDefault)`, `shouldTryDb()`, `queuePositionApprox`, `estimatedWaitSeconds`
4. Update `ReadModelCacheService.status()`: construct sealed subtype directly, compute fields on subtype
5. Update `ExpectationV6Controller.getStatus()`: replace `state == PENDING || state == UNKNOWN` with `state.shouldTryDb()`
6. Create `UrgentReadStateTest.kt`: round-trip, behavior, error handling
7. Run `./gradlew :module-rest-controller:test` and `./gradlew compileKotlin compileJava --continue`
