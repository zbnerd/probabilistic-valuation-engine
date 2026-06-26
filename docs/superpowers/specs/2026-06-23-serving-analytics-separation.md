# Spec: Serving Layer vs Analytics Layer Separation

- Date: 2026-06-23
- Parent Issue: #1344
- Parent ADR: ADR-735 (Future Analytics Platform Evaluation)
- Module-boundary rule: `.claude/rules/module-boundaries.md`
- Architecture rule: ADR-041 (Multi-Module Hexagonal Architecture)

---

## 1. Goal

Define a clean architectural boundary between the **serving layer** (PostgreSQL read model + REST API, p95 < 100ms) and a **future analytics layer** (Phase 1 ClickHouse, Phase 2 Iceberg+Trino, Phase 3 Iceberg+Spark per ADR-735). The investigation must yield:

1. A consumer-by-consumer contract (latency, freshness, consistency, cache, engine)
2. A data flow design that does **not** touch the Calculator or serving path hot paths
3. A Hexagonal port/adapter map that keeps `module-core` Spring-free and forces all analytics-engine coupling into `module-infra`
4. A failure-isolation story: analytics layer down ⇒ serving path SLA unchanged

## 2. Non-Goals

- No implementation of ClickHouse, Iceberg, Trino, or Spark
- No change to serving p95 < 100ms contract
- No new Spring beans on the default `bootRun` profile
- No `module-app`/`module-infra` restructuring (handled in ADR-050 roadmap)
- No Calculator hot-path changes (ADR-735 §2 non-risk)
- No new REST endpoint on `/api/v5` (analytics endpoints, if exposed, are a separate future ADR)

## 3. Background

ADR-735 §1 documents the data volume: 595K user snapshots/day, 40M items/day, ~340GB/day internal storage, ~10TB/month, ~120TB/year. The serving path is p95 < 100ms on PG read model + L1 Caffeine + L2 PG UNLOGGED. ADR-735 §2 commits to a **PG default** with trigger conditions (T1–T8) for ClickHouse, Iceberg+Trino, and Iceberg+Spark escalation.

This spec answers the architectural design question that ADR-735 §2 leaves open: **how** do we draw the boundary contract — what port interfaces exist in `module-core`, where do adapters live, and what does the failure-isolation model look like — before any of the trigger conditions T1–T8 fire.

ADR-041 §3.5 establishes the port/adapter convention: inbound port `XxxPort` in `module-core/.../core/port/in/`, outbound port in `module-core/.../core/port/out/`, adapter in `module-infra/.../adapter/outgoing/`. The `module-boundaries.md` rules forbid `module-web` → `module-infra` direct imports and forbid Spring annotations in `module-core`.

## 4. Design

### 4.1 Consumer matrix

| Consumer | Read pattern | Latency budget | Freshness | Consistency | Volume | Engine | Cache |
|----------|--------------|----------------|-----------|-------------|--------|--------|-------|
| REST API (`/api/v5/characters/...`) | Single-row point | **p95 < 100ms** | seconds | Strong (PG RC) | 1 row | PG read model | L1 Caffeine + L2 PG UNLOGGED |
| Internal dashboard | Aggregations, queue depth | p95 < 1s | seconds | Read-committed | window | PG | L1 only |
| Analyst ad-hoc (class/level rollup) | Wide aggregates, Top-N | p95 < 10s | minutes–hours | Eventual | full history | PG materialized views → CH | none / CH internal |
| ML feature pipeline | Bulk scans, PIT joins | minutes (batch) | hours | Snapshot isolation | 1B+ rows | Iceberg+Trino | none |
| Recommendation training | Iterative (ALS) | hours (offline) | daily | Snapshot | corpus | Iceberg+Spark | none |

### 4.2 Module port map (Hexagonal, per ADR-041)

**`module-core/.../core/port/out/`** — new outbound ports (Spring-free, framework-agnostic):

```
analytics/
  AnalyticsQueryPort         // typed query methods (Phase 1)
  IcebergSnapshotPort        // list/read/timeTravel (Phase 2+)
```

**`module-infra/.../adapter/outgoing/analytics/`** — adapters (Spring-allowed, engine-coupled):

```
ClickHouseAnalyticsQueryAdapter  implements AnalyticsQueryPort       // @ConditionalOnProperty analytics.engine=clickhouse
IcebergSnapshotAdapter           implements IcebergSnapshotPort        // @ConditionalOnProperty analytics.engine=iceberg (Phase 2+)
TrinoQueryAdapter                implements AnalyticsQueryPort         // @ConditionalOnProperty analytics.engine=trino   (Phase 2+)
```

**`module-app`** — wires the adapter bean only when config property enables it. Default `analytics.engine=none` ⇒ no analytics bean registered ⇒ serving path unchanged.

### 4.3 Data flow (Phase 1, additive)

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SERVING PATH (unchanged)                            │
│                                                                             │
│  external-api ─► calculator ─► JSONL.gz → MinIO                              │
│                              └─► PG read model (serving, p95<100ms)         │
│                              └─► Kafka topic: expectation_calc_high         │
│                                                                             │
│  synchronizer ─► character_valuation_views (L2) + L1 Caffeine (REST 8080)  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          │  (additive observer — does not block serving)
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ANALYTICS PATH (new, opt-in)                        │
│                                                                             │
│  Phase 1: ClickHouse Kafka engine subscribes to q_analytics_calc_events     │
│           Materialized views in CH for Top-N / class rollups                │
│                                                                             │
│  Phase 2: Iceberg+Trino reads from MinIO via S3FileIO (read-only)          │
│           Polaris catalog on dedicated cluster                              │
│                                                                             │
│  Phase 3: Iceberg+Spark for MLlib (ALS, feature pipelines)                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

Key constraint: **Calculator and synchronizer are not modified**. Analytics ingest is an observer on existing Kafka topic `expectation_calc_high` (or a new derived `q_analytics_calc_events` topic produced by an Airflow re-emit DAG). This honors ADR-735 §2 "Calculator is not affected."

### 4.4 Port contracts (signatures, no implementation)

```kotlin
// module-core/.../core/port/out/analytics/AnalyticsQueryPort.kt
interface AnalyticsQueryPort {
    fun topNByClass(world: World, characterClass: String, n: Int): List<ValuationSnapshot>
    fun levelRangeRollup(world: World, characterClass: String, range: IntRange): RollupResult
    fun expectationDrift(world: World, characterClass: String, window: Duration): DriftSeries
}

// module-core/.../core/port/out/analytics/IcebergSnapshotPort.kt
interface IcebergSnapshotPort {
    fun listSnapshots(table: String): List<SnapshotId>
    fun timeTravel(table: String, timestamp: Instant): ScanResult
}
```

Signatures are framework-free. Engine-specific SQL, JDBC, REST calls, and Iceberg library types live in adapters.

### 4.5 Failure isolation

| Failure | Serving | Analytics | Mitigation |
|---------|---------|-----------|------------|
| ClickHouse down | Unaffected | 503 + fallback to PG matview | Resilience4j circuit breaker (ADR-052); 10s timeout |
| Kafka analytics topic lag | Unaffected | Stale aggregates | Lag SLO + alert; serving does not depend on lag |
| Airflow DAG failed | Unaffected | Stale CH until next run | Alert; serving reads from PG |
| Iceberg catalog unreachable | Unaffected | Analytics 503 | Compaction pipeline alarm |
| MinIO write failure | Calculator fails (existing) | n/a | Existing calculator error handling |
| PG replica lag | Possible stale serving read (existing) | n/a | L1/L2 hits mask |

Serving path never reads from analytics layer. Analytics layer may fall back to PG materialized views, never to the live serving read path.

### 4.6 Boundary contract principles

1. Analytics layer is **read-only** with respect to serving — never writes back to PG read model
2. Calculator (port 8082) is **untouched** — analytics ingest is an observer, not a writer
3. REST API on 8080 reads **only** PG read model; analytics endpoints, if added, use distinct SLA and a separate controller (future ADR)
4. No analytics adapter is registered without an explicit `analytics.engine` config property
5. `module-core` adds **only port interfaces**; no engine client classes
6. `module-web` and `module-app` only depend on `module-core` port interfaces, never on `module-infra` analytics adapters (per `.claude/rules/module-boundaries.md`)

## 5. Acceptance Criteria

- [ ] Consumer matrix documented with latency, freshness, consistency, cache, engine per consumer
- [ ] `AnalyticsQueryPort` interface defined in `module-core/.../core/port/out/analytics/`
- [ ] `IcebergSnapshotPort` interface defined in `module-core/.../core/port/out/analytics/`
- [ ] Adapters scoped to `module-infra/.../adapter/outgoing/analytics/` and gated by `@ConditionalOnProperty`
- [ ] Data flow diagram: serving writes → analytics ingest (Kafka observer + Airflow reconciliation)
- [ ] Failure isolation table: every analytics component failure enumerated, serving SLA impact = none
- [ ] ArchUnit test extended: `module-core` has zero Spring annotations (existing) AND zero references to engine packages (ClickHouse, Iceberg, Trino, Spark)
- [ ] Default `application.yml` has `analytics.engine=none`; no analytics beans registered
- [ ] Calculator and synchronizer source files: zero diff

## 6. Trade-offs

| Choice | Get | Give up |
|--------|-----|---------|
| Kafka observer (vs CDC vs direct write) | Additive, no app changes, reuses ADR-013 | Schema drift risk; needs topic ownership rules |
| Port interfaces in `module-core` (vs new `module-analytics`) | Single core, no module restructuring (matches ADR-050 roadmap) | `module-core` is no longer "zero-conditional" — analytics ports exist even when disabled |
| `@ConditionalOnProperty` adapters (vs always-on with NULL impl) | Zero serving-path impact when disabled; simple to reason about | Two code paths to test; config sprawl |
| CH first, Iceberg later (per ADR-735 ladder) | Validated trigger conditions; no premature lakehouse | Two migration cycles |
| No analytics REST endpoint in Phase 0 | Avoids scope creep; preserves serving SLA | Analyst must use CH client directly until Phase 1 endpoint ADR |

**Risk:** If T1 (analytical p95 > 10s) fires before `module-core` ports are merged, we may need a hotfix that bypasses the port. Mitigation: ports are pure interfaces — even a hotfix adapter can implement them without port rework.

**Non-Risk:** Calculator throughput regression — calculator hot path unchanged; Kafka observer is downstream.

## 7. References

- Parent: [ADR-735](docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md)
- Architecture: [ADR-041](docs/01_ADR/ADR-041-multi-module-hexagonal-architecture-dip.md)
- Module rules: `.claude/rules/module-boundaries.md`
- Pipeline: [ADR-013](docs/01_ADR/ADR-013-high-throughput-event-pipeline.md) (Kafka choreography)
- Resilience: [ADR-052](docs/01_ADR/ADR-052-resilience4j-circuit-breaker.md) (failure isolation primitive)
- Issue: #1344
- Companion: #1343 (Class Hierarchy Data Modeling), #1345 (Historical Analytics Requirements)

---

## Status

Proposed (investigation output). No implementation. Architectural-design tasks only.
