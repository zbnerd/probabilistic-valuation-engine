# V6 Read Orchestration Extraction (#1082)

- Date: 2026-06-06
- Owner: TBD
- Branch: `refactor/issue-1082-batch-read-orchestration` (worktree)
- Target module: `module-rest-controller`

---

## 1. Background / Problem

### Background

`BatchReadScheduler.resolveBatch` (78 LOC) and `ExpectationReadFacade.enqueue` (36 LOC) mix infrastructure orchestration with HTTP response generation. Issue #1082 ranks this as the #1 severity hotspot for `module-rest-controller`.

`BatchResolver` was already extracted in `refactor-batch-2` worktree to remove the 6-mixin into a service-style class — but the new class still produces `ResponseEntity` inline. The issue explicitly requires HTTP response generation to live in the controller layer.

### Problem

Two violations of the Hexagonal Architecture boundary:

1. `BatchResolver.resolveBatch` / `BatchReadScheduler.resolveBatch` directly call `ResponseEntity.ok(...)`, `ResponseEntity.status(404)...build()`, and `deferred.setResult(...)` — HTTP concerns leak into the orchestration layer.
2. `ExpectationReadFacade.enqueue` returns `Unit` and produces `ResponseEntity` for the synchronous 503 buffer-full path and the deferred timeout path — the controller cannot decide response shape.

### Goal

- `BatchReadScheduler` and `BatchResolver` produce typed outcomes only — no `ResponseEntity` references.
- `ExpectationReadFacade.enqueue` returns a typed result — controller maps to HTTP.
- Single canonical place that converts a typed outcome to `ResponseEntity` (`ReadResponseMapper`).
- `InflightRequestRegistry` owns deferred application via `applyOutcome(key, outcome)`.

---

## 2. Decision

> Introduce a new sealed `ReadOutcome` and a `ReadResponseMapper`. All HTTP-response construction lives in `ReadResponseMapper`. `BatchReadScheduler` and `ExpectationReadFacade` consume outcomes, never build `ResponseEntity`. The controller maps synchronous facade results; the registry applies async outcomes through the mapper.

```text
read/
├── ReadOutcome.kt              (sealed: Ready | NotFound | DeferredForTimeout)
├── ReadResponseMapper.kt       (object: outcome/EnqueueResult -> ResponseEntity)
├── BatchResolver.kt            (orchestration; returns Map<key, ReadOutcome>; no ResponseEntity)
├── BatchReadScheduler.kt       (drain + lifecycle only; delegates to BatchResolver)
├── ExpectationReadFacade.kt    (returns EnqueueResult; no ResponseEntity)
├── InflightRequestRegistry.kt  (adds applyOutcome(key, outcome))

controller/
├── ExpectationV6Controller.kt  (maps EnqueueResult -> 202/503; wires timeout via mapper)
```

---

## 3. Trade-offs

### Sensitivity

- Hot path: every V6 read request goes through `enqueue`; every batch goes through `BatchResolver.resolveBatch`. Mapper is on the request hot path.
- All paths to `deferred.setResult` (timeout, 503, 202, 404, 200) must route through one mapper — divergence risks inconsistent headers (`Retry-After`, `X-Error-Reason`, `Location`).

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Sealed outcome + mapper | Single source of truth for HTTP shape; testable outcomes; no ResponseEntity in service layer | Extra type + mapper file (~50 LOC) |
| Keep `getAndRemove(deferreds)` + add `applyOutcome(key, outcome)` (registry) | Deferreds stay in registry; no leak of `DeferredResult` to resolver | One extra method on registry |

### Risk

- **Stale `BatchResolver` from `refactor-batch-2` worktree**: prior branch built it differently. We will either delete that branch or rebase. Target branch is fresh `refactor/issue-1082-batch-read-orchestration` from `develop`.
- **Map-into-controller coupling**: controller now imports `EnqueueResult` from the read package. Acceptable — both live in `module-rest-controller` (no module-boundary violation).
- **Async timeout still constructs ResponseEntity inside a callback**: handled by mapper injected into the deferred callback at facade construction time (single mapper, reused for synchronous and timeout paths).

### Non-Risk

- `ReadModelCacheService` and `ReadModelQueryService` already expose typed results; no change.
- `LocalRequestBuffer` / `RequestBuffer` interface unchanged.
- Wire-level HTTP shape (`200` body, `404 X-Error-Reason`, `202 Location`, `503 Retry-After`) preserved bit-for-bit to keep client compatibility.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| `ResponseEntity` references in `BatchReadScheduler.kt` | 0 (was 4) | grep self-check |
| `ResponseEntity` references in `BatchResolver.kt` | 0 (was 4) | grep self-check |
| `ResponseEntity` references in `ExpectationReadFacade.kt` | 0 (was 3) | grep self-check |
| HTTP-shape construction sites | 1 (mapper) | single source of truth |
| `./gradlew :module-rest-controller:test` | pass | unit tests cover all outcomes |
| `./gradlew :module-rest-controller:compileKotlin compileJava --continue` | pass | |

### Observed Result

* TBD after implementation.

---

## 5. Summary

> One sealed outcome + one mapper removes `ResponseEntity` from the read orchestration layer; controller owns HTTP shape, registry owns deferred application.
