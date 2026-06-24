# Plan: Serving Layer vs Analytics Layer Separation

- Date: 2026-06-23
- Spec: `docs/superpowers/specs/2026-06-23-serving-analytics-separation.md`
- Parent issue: #1344
- Module-rule baseline: `.claude/rules/module-boundaries.md`

---

## Phase 1 — Consumer matrix (investigative)

### Task 1.1: Document consumer matrix

- **Scope**: write `/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/analytics-consumer-matrix.md` with the 5-row consumer table from spec §4.1
- **Inputs**: spec §4.1; ADR-735 §1 volume metrics
- **ADR section to update**: append reference line in `ADR-735 §5 References`
- **Verification**:
  - File exists and contains all 5 consumers (REST, dashboard, analyst, ML, training)
  - Latency/freshness/consistency/cache/engine columns filled for every row

### Task 1.2: Document data flow decision

- **Scope**: write `/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/analytics-data-flow.md` capturing the Kafka-observer-first decision and the rejected alternatives (CDC, direct Iceberg write, batch ETL)
- **Inputs**: spec §4.3; ADR-013 (Kafka pipeline); ADR-735 §2 non-risk on Calculator
- **ADR section to update**: append reference line in `ADR-735 §5 References`
- **Verification**:
  - File lists chosen approach + 3 rejected alternatives with reason
  - Diagram matches spec §4.3 ASCII

## Phase 2 — Port contracts (module-core, framework-free)

### Task 2.1: Define `AnalyticsQueryPort`

- **Scope**: create `module-core/.../core/port/out/analytics/AnalyticsQueryPort.kt` with the three typed query methods (`topNByClass`, `levelRangeRollup`, `expectationDrift`)
- **Inputs**: spec §4.4 signatures
- **ADR section to update**: append "AnalyticsQueryPort" to `ADR-041 §3.5` outbound ports list
- **Verification**:
  ```bash
  grep -rn "AnalyticsQueryPort" module-core/src/main/java | wc -l    # ≥ 1
  grep -rE "@(Component|Service|Repository|Configuration)" module-core/src/main/java/.../analytics/ | wc -l   # 0
  ./gradlew :module-core:compileKotlin
  ```

### Task 2.2: Define `IcebergSnapshotPort`

- **Scope**: create `module-core/.../core/port/out/analytics/IcebergSnapshotPort.kt` with `listSnapshots`, `timeTravel`
- **Inputs**: spec §4.4 signatures
- **ADR section to update**: append "IcebergSnapshotPort" to `ADR-041 §3.5` outbound ports list
- **Verification**: same checks as Task 2.1, plus `grep` for any `org.apache.iceberg` import in module-core must return 0.

### Task 2.3: Extend ArchUnit rule for analytics purity

- **Scope**: extend `module-app/src/test/java/maple/expectation/architecture/ArchitectureTest.java` rule `app_should_not_depend_on_infra_implementation` to also forbid `module-app` and `module-core` from referencing engine packages (`com.clickhouse`, `org.apache.iceberg`, `io.trino`, `org.apache.spark`)
- **Inputs**: existing ArchUnit test; engine package names
- **ADR section to update**: extend `ADR-041 §4.3` test snippet
- **Verification**:
  ```bash
  ./gradlew :module-app:test --tests "maple.expectation.architecture.ArchitectureTest"
  ```

## Phase 3 — Adapter placement (module-infra, conditional)

### Task 3.1: Conditional adapter stubs

- **Scope**: create `module-infra/.../adapter/outgoing/analytics/ClickHouseAnalyticsQueryAdapter.kt` and `IcebergSnapshotAdapter.kt` and `TrinoQueryAdapter.kt` — each annotated `@ConditionalOnProperty(name = "analytics.engine", havingValue = "<engine>")` and `implements <Port>`. Stub methods throw `UnsupportedOperationException` (this task is investigation-only — no engine wiring).
- **Inputs**: spec §4.2 adapter placement; ADR-044 (LogicExecutor for try-catch avoidance)
- **ADR section to update**: append adapter list to `ADR-041 §3.5` secondary-adapter diagram
- **Verification**:
  ```bash
  grep -rn "@ConditionalOnProperty" module-infra/src/main/java/.../analytics/ | wc -l   # 3
  ./gradlew :module-infra:compileKotlin
  ./gradlew :module-app:bootRun --args='--spring.profiles.active=test' &
  sleep 30
  curl -sf http://localhost:8080/actuator/beans | grep -i analytics   # empty (engine=none default)
  ```

### Task 3.2: Default config isolation

- **Scope**: ensure `application.yml` (all profiles) has `analytics.engine: none` documented as a comment block; verify no `@Bean` factory in `module-app/.../config/` is annotated unconditionally with analytics types
- **Inputs**: existing YAML structure; spec §4.2 default
- **ADR section to update**: none (config only)
- **Verification**:
  ```bash
  grep -rn "analytics.engine" --include="application*.yml" .
  ./gradlew test  # existing test suite still passes
  ```

## Phase 4 — Failure isolation & docs

### Task 4.1: Failure isolation matrix

- **Scope**: extend `/home/maple/probabilistic-valuation-engine/docs/03_Technical_Guides/analytics-failure-isolation.md` with the 6-row table from spec §4.5
- **Inputs**: spec §4.5; ADR-052 (Resilience4j circuit breaker)
- **ADR section to update**: append reference in `ADR-735 §5 References`
- **Verification**:
  - File exists; all 6 failure modes listed; "Serving" column = "Unaffected" or "Existing"

### Task 4.2: Update ADR-735 references

- **Scope**: append new doc references (`analytics-consumer-matrix.md`, `analytics-data-flow.md`, `analytics-failure-isolation.md`) to `ADR-735 §5 References`
- **Inputs**: Task 1.1, 1.2, 4.1 outputs
- **ADR section to update**: `ADR-735 §5 References` only
- **Verification**:
  ```bash
  grep -E "analytics-(consumer-matrix|data-flow|failure-isolation)" docs/01_ADR/ADR-735-*.md
  ```

### Task 4.3: Cross-link from spec & plan

- **Scope**: add "Companion issues" line to spec §7 referencing the issues created from this plan (to-issues stage output)
- **Inputs**: GitHub issue numbers from to-issues stage
- **ADR section to update**: none
- **Verification**: spec §7 lists all 8 created issues

---

## Verification matrix (final)

| Check | Command | Expected |
|-------|---------|----------|
| module-core Spring-free | `grep -rE "@(Component|Service|Repository|Configuration)" module-core/src/main/java/ | wc -l` | 0 |
| module-core engine-free | `grep -rE "com\.clickhouse\|org\.apache\.iceberg\|io\.trino\|org\.apache\.spark" module-core/src/main/java/ | wc -l` | 0 |
| Port interfaces present | `grep -rn "AnalyticsQueryPort\|IcebergSnapshotPort" module-core/src/main/java` | ≥ 2 files |
| Adapter stubs conditional | `grep -rn "@ConditionalOnProperty.*analytics.engine" module-infra/src/main/java/.../analytics/` | 3 |
| Default profile silent | `curl -sf http://localhost:8080/actuator/beans 2>/dev/null | grep -i analytics` (with `analytics.engine=none`) | empty |
| ArchUnit passes | `./gradlew :module-app:test --tests "maple.expectation.architecture.ArchitectureTest"` | BUILD SUCCESSFUL |
| Full test suite | `./gradlew test` | BUILD SUCCESSFUL |
| Calculator untouched | `git diff --stat HEAD -- module-calculator module-synchronizer module-external-api` | empty |

---

## Out of scope (confirm)

- No ClickHouse / Iceberg / Trino / Spark client wiring
- No new REST endpoint on `/api/v5`
- No module restructuring
- No calculator hot-path changes
- No Kafka topic creation (left to Phase 1 implementation ADR, fires after T1/T2/T3)

---

## Status

Proposed. Architectural-design tasks only. Each task is bounded; no engine implementation.

---

## Grill-me (5 hard questions, patched in place)

### Q1: Why does the port interface live in `module-core` and not in a new `module-analytics-core`?

If `module-core` is the framework-free domain layer for the entire application, adding ports that **only fire under `@ConditionalOnProperty`** arguably pollutes the always-loaded domain with optional capability. A new `module-analytics-core` subpackage (or even module) would signal "this is conditionally loaded" at the build-graph level, not just the bean-graph level.

**Resolution (best practice, accepted)**: keep ports in `module-core/.../core/port/out/analytics/`. Reasons:
- ADR-041 §3.5 establishes a single hexagonal core; ADR-050's roadmap tracks any future module extraction.
- Interface definitions carry zero runtime cost — `@ConditionalOnProperty` lives on adapters, not ports.
- Splitting core fragments the ArchUnit rule set and breaks the "single domain" mental model that ADR-041 explicitly chooses.
- If Phase 3 ever extracts analytics into a separate service, port relocation is mechanical (move file + module dependency).

### Q2: The Kafka observer requires a *new* Kafka topic — that's not "Calculator untouched" if someone has to create it.

The spec says analytics ingest is "additive," but producing a derived topic (`q_analytics_calc_events`) from `expectation_calc_high` is a new pipeline component owned by... whom? Not Calculator (untouched). Not synchronizer (different domain). That makes it an orphan producer with no team owner.

**Resolution (best practice, accepted)**: ownership belongs to a new Airflow DAG, not a long-running producer. The DAG reads existing `q_expectation_calc_high` on a schedule, re-emits the same payload (or a derived schema) into a topic consumed by ClickHouse `Kafka` engine table. This keeps:
- Calculator unchanged (still writes `expectation_calc_high`)
- Synchronizer unchanged (still consumes `expectation_calc_high` for read-model projection)
- Analytics ingest scheduled, observable, and replay-able via Airflow backfill (matches ADR-046 outbox pattern philosophy)

Update: add explicit `owner: airflow-dag-analytics-ingest` note to spec §4.3. Plan Task 1.2 should mention this DAG location.

### Q3: `AnalyticsQueryPort` returns `ValuationSnapshot`, `RollupResult`, `DriftSeries` — these domain types must already exist in `module-core`. Are they defined today?

If those types don't exist in `module-core`, Task 2.1 will fail at compile time (unresolved reference). Even if stub types are added, they leak analytics-shape concepts into the always-loaded domain.

**Resolution (best practice, accepted)**: define the three return types as records in `module-core/.../core/domain/analytics/` as part of Task 2.1. They are pure data carriers (`ValuationSnapshot(characterId, world, valuation, timestamp)`, `RollupResult(bucket, count, mean, stddev)`, `DriftSeries(timestamp, value)`). No engine dependency. They serve as the **contract language** for analytics queries. If an adapter needs engine-specific projections, it converts at the adapter boundary. This is consistent with ADR-041 §3.4 (port returns domain types, not infrastructure types).

### Q4: `@ConditionalOnProperty` adapters throw `UnsupportedOperationException` at runtime — that means they register as real beans and could be autowired accidentally. Why not omit them entirely until Phase 1 fires?

If a developer injects `AnalyticsQueryPort` in some unrelated code path today, the `@ConditionalOnProperty` bean activates and `UnsupportedOperationException` blows up at runtime — exactly the failure mode the ArchUnit test was supposed to prevent.

**Resolution (best practice, accepted)**: dual-gate the stub adapters. Add `@ConditionalOnProperty(name = "analytics.engine", havingValue = "<engine>")` AND `@Conditional(AnalyticsEnabledCondition.class)` where the condition checks `analytics.enabled=true`. Default config sets `analytics.enabled=false`. Any accidental injection fails at startup with a clear `NoSuchBeanDefinitionException`, not at runtime with a stub exception. Update Task 3.1 verification command to check both annotations.

### Q5: Failure isolation §4.5 lists "MinIO write failure → Calculator fails pipeline (existing)" — but if analytics layer is Kafka-observer, it does NOT write MinIO. The row is wrong.

The row implies analytics writes MinIO. It doesn't. Remove or annotate the row to clarify "analytics does not write MinIO in Phase 1."

**Resolution (best practice, accepted)**: change the failure-isolation row to:
- MinIO write failure (existing) → Calculator fails pipeline → analytics layer does not write MinIO in Phase 1; no analytics impact
- This makes the dependency direction explicit (serving owns MinIO writes; analytics is downstream).

Update Task 4.1 input to reflect this correction.

---

**All 5 questions resolved.** Spec §4.3, plan Tasks 1.2, 2.1, 3.1, and 4.1 updated accordingly. No open architectural branches.