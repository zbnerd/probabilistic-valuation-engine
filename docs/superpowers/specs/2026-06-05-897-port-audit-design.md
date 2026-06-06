# Issue 897 — Outbound Port Audit Design

- Issue: #897
- Date: 2026-06-05
- Owner: TBD
- Branch: `worktree-refactor+906-port-abstraction` (worktree)

> **Note (2026-06-06):** 6 dead Like ports (LikeAtomicFetchStrategy, CompensationCommand, LikeRelationBufferStrategy, LikeRelationSyncPort, LikeSyncPort, LikeEventPublisher) were deleted in PR TBD. See [2026-06-06-like-port-merge-design.md](2026-06-06-like-port-merge-design.md) for the actual deletion rationale.

---

## 1. Background / Problem

### Background

`module-core` defines 49 outbound port interfaces across `core/port/out/` (43), `core/calculator/port/` (5), and `core/flame/port/` (1). Issue #897 reports that this count is too high: new contributors cannot tell which ports are real boundaries vs. implementation mirrors, and at least one port (`BufferStatusQuery`) is a no-op stub with no adapter.

### Problem

Without an explicit seam classification:

- Real seams (multiple adapters) and hypothetical seams (one adapter) look identical in the codebase
- Like-related ports (6) and Monitoring-related ports (6) appear to overlap; a future contributor would not know whether to extend an existing port or create a new one
- Dead ports inflate the type graph and add maintenance overhead

### Goal

Produce a single classification table covering all 49 ports, recommend removal/merge actions, and deliver the result as an ADR + this spec. **No code change in this issue** — the removal/merge PRs are follow-up work.

---

## 2. Decision

> We will audit all 49 outbound ports, classify each by adapter count, and deliver classification + recommendations as a spec + ADR. Recommendations are advisory; the actual removal/merge code changes are tracked as follow-up issues.

```text
Deliverables
├── docs/superpowers/specs/2026-06-05-897-port-audit-design.md   (this file, with full table)
└── docs/01_ADR/ADR-391-outbound-port-seam-classification.md     (decision + trade-offs)
```

---

## 3. Classification Methodology

For each of the 49 outbound port files, enumerate every adapter implementation in `module-infra`, `module-rest-controller`, `module-app` (legacy), and any test fake. Count:

- **prod adapters** (real infra wiring, e.g. `*PortAdapter` classes bound via Spring)
- **test fakes** (in-memory no-op stubs used only in unit/integration tests)

### Categories

| Category | Adapter count | Replacement likelihood | Recommendation |
|----------|--------------:|------------------------|----------------|
| **Real seam** | ≥ 2 (prod+test) or ≥ 2 prod | High | Keep |
| **Active seam** | 1 prod, no test fake | Medium | Keep (used; future swap plausible) |
| **Hypothetical seam** | 1 prod, no test fake, low swap value | Low | **Remove** |
| **Dead seam** | 0 prod adapters (no-op stub only) | None | **Remove** |

### Replacement-likely heuristic

A port is *likely to be replaced* if any of the following hold:

- It wraps an external system (Nexon API, Discord/Slack, AI provider, MQ vendor)
- It is consumed by ≥ 2 distinct call sites in `module-core`
- It is documented in `docs/03_Technical_Guides/` as a known boundary

If none of these hold, the port is a hypothetical seam.

---

## 4. Classification Table (preliminary — to be filled by adapter enumeration step)

> **This table is the work product of Task 3 in the implementation plan.** The audit is performed by enumerating `:module-infra:assemble`, `:module-rest-controller:assemble`, and grepping test sources. The numbers below are the *audit* output, not assumed values.

| # | Port file | Adapters (prod) | Test fakes | Call sites | Category | Recommendation |
|---|-----------|----------------:|-----------:|-----------:|----------|----------------|
| 1 | `AiAnalysisPort.kt` | TBD | TBD | TBD | TBD | TBD |
| 2 | `AlertNotificationPort.kt` | TBD | TBD | TBD | TBD | TBD |
| ... | ... | ... | ... | ... | ... | ... |
| 49 | `StarforceLookupPort.kt` | TBD | TBD | TBD | TBD | TBD |

(Full table filled in plan Task 3.)

### Already known

- `BufferStatusQuery` — issue body flags as no-op stub. Confirmed to be a Dead seam candidate.
- `LikeSyncPort`, `LikeRelationSyncPort`, `LikeEventPublisher`, `LikeBufferStrategy`, `LikeRelationBufferStrategy`, `LikeAtomicFetchStrategy` (6) — merge candidate group.
- `AlertPort`, `AlertPublisher`, `AlertNotificationPort`, `AnomalyDetectionPort`, `AiAnalysisPort`, `MetricsQueryPort`, `SystemMetricsPort` (7, not 6 as issue body suggests — `AlertPort` and `AlertPublisher` are separate) — Monitoring/alerts merge candidate group.

---

## 5. Like Group — Merge Proposal (signature sketch)

Current 6 ports cover three concerns: data fetch, buffer, sync. Proposed merge into 2 ports:

```kotlin
// Merged: like data + buffer (3 → 1)
interface LikeReadPort {
    fun fetchAtomic(userId: String): Optional<LikeData>
    fun bufferPush(event: LikeEvent): CompletableFuture<Void>
    fun bufferSize(): Int  // replaces BufferStatusQuery-like behavior
}

// Merged: sync + event publish (2 → 1)
interface LikeSyncPort {
    fun syncRelation(fromUser: String, toUser: String): Result<Unit>
    fun publish(event: LikeEvent): Result<Unit>
}
```

**Net reduction: 6 → 2.** Affects adapters in `module-infra/.../like/` and any consumer in `module-core`. Tracked as follow-up issue (not in #897).

---

## 6. Monitoring Group — Merge Proposal (signature sketch)

Current 7 ports. Proposed merge into 2:

```kotlin
// Merged: alert + anomaly (4 → 1)
interface AlertingPort {
    fun publish(alert: AlertEvent): Result<Unit>
    fun detect(measurement: MetricSnapshot): List<Anomaly>
    fun notify(channel: NotificationChannel, payload: String): Result<Unit>
}

// Merged: metrics query (3 → 1)
interface MetricsReadPort {
    fun <T> query(metric: MetricName, range: TimeRange): List<T>
    fun currentSystem(): SystemSnapshot
    fun outboxStats(queue: String): OutboxStats
}
```

**Net reduction: 7 → 2.** Tracked as follow-up issue.

---

## 7. Out of Scope

- Inbound ports (16) — not in #897 scope
- Calculator/flame ports — classified but no merge proposed (each is a thin wrapper around a single algorithm; no overlap detected)
- Actual port removal/merge code changes — follow-up issues
- Any adapter rewrite

---

## 8. Verification

- The classification table is complete (49 rows, none TBD)
- The ADR contains a 5-line summary of net port count after recommended removals/merges
- The spec + ADR pass `grep -n "TBD\|TODO"` with zero hits in the final state
- Both files are committed on the working branch

---

## 9. Summary

> Audit all 49 outbound ports in `module-core`, classify each by adapter count, deliver recommendations as a spec + ADR with a Like-group and Monitoring-group merge sketch — no code change in #897 itself.
