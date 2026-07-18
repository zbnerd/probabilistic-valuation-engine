# System Knowledge Base — Probabilistic Valuation Engine

> **Purpose.** Single-document onboarding for a senior engineer taking over maintainership of this
> Kotlin/Spring Boot ETL system. Synthesized 2026-07-06 from source code, ~190 ADRs, performance
> reports, chaos/incident docs, git history, and infrastructure config. Every claim carries a source
> pointer. Where two docs disagree, the disagreement is stated explicitly.
>
> **What the system does in one sentence.** It fetches MapleStory character data from the Nexon
> OpenAPI, computes the *probabilistic expected cost* of each character's equipment (cube / starforce /
> flame Monte-Carlo-style DP), materializes the results into precomputed PostgreSQL read models, and
> serves them through a read API — all as a self-orchestrated, self-healing, horizontally-scalable
> pipeline.

---

## Table of Contents

1. [System Architecture](#1-system-architecture)
2. [Data Flow (ETL)](#2-data-flow-etl)
3. [Component Dependency Map](#3-component-dependency-map)
4. [Data Model & Database Schema](#4-data-model--database-schema)
5. [Messaging & Object Storage](#5-messaging--object-storage)
6. [Orchestration (Airflow Control Plane)](#6-orchestration-airflow-control-plane)
7. [Infrastructure, Deployment, CI/CD](#7-infrastructure-deployment-cicd)
8. [Observability & Logging](#8-observability--logging)
9. [Timeline of Architectural Evolution](#9-timeline-of-architectural-evolution)
10. [Performance Optimization History](#10-performance-optimization-history)
11. [Chaos Engineering, Incidents & Security](#11-chaos-engineering-incidents--security)
12. [Testing Strategy](#12-testing-strategy)
13. [Known Limitations](#13-known-limitations)
14. [Future Improvements](#14-future-improvements)
15. [FAQ](#15-faq)
16. [Interview-Ready Explanation](#16-interview-ready-explanation)
17. [Knowledge Graph](#17-knowledge-graph)
18. [Source Index](#18-source-index)

---

## 1. System Architecture

**Stack.** Kotlin 2.1.0 + Java 21 (Virtual Threads) / Spring Boot 3.5.4 / Gradle multi-module /
PostgreSQL 17 + PGMQ / Kafka (KRaft) / MinIO (S3) / Redis / Airflow 2.10.5 / Coolify deploy.
Versions pinned in `gradle/libs.versions.toml`; root config `build.gradle`, `settings.gradle`.

**Architectural style.** Hexagonal (Ports & Adapters). The domain (`module-core`) has **zero** Spring or
infrastructure dependencies; the direction of dependency is always inward: `common → core ← infra`,
`web → core` (never `web → infra`). Enforced by:
- `module-common`'s Gradle task `verifyNoSpringDependency` (fails the build if any `spring-*` jar leaks in) — `module-common/build.gradle`.
- ArchUnit tests in `module-web/src/test/java/maple/expectation/arch/`.
- `module-web/build.gradle` deliberately omits `module-infra` (comment: *"web layer must not depend on infrastructure"*, ADR-005).

### 1.1 Module catalog (12 Gradle modules)

| Module | Layer | Spring? | Role | Entry / port |
|---|---|---|---|---|
| `module-common` | common | **No** (enforced) | Utilities, error base, response envelopes, `TaskContext`, streaming JSONL parser, `ObjectStorage` interface, event DTOs, Avro schemas | plain jar |
| `module-core` | domain | **No** | Pure domain: cube/starforce/flame calculators, probability convolution, ~20 inbound ports, ~40 outbound ports | plain jar |
| `module-infra` | infra | Yes (JPA/Kafka/AWS-SDK) | Adapters implementing core ports, `LogicExecutor`, PGMQ client, advisory locks, tiered cache, workers, MinIO storage | plain jar |
| `module-web` | web | Yes | Controllers (v1/v4/v5), DTOs, filters, CORS, validation — depends only on core ports | plain jar |
| `module-app` | app (**legacy monolith**) | Yes (bootJar) | Original composition root; still holds V4/V5 calculation workers, port→adapter wiring | `ExpectationApplication.java` |
| `module-auth` | service lib | Yes | JWT gen, login, Redis session, fingerprint, Kafka login orchestration | plain jar (consumed by app + rest-controller) |
| `module-external-api` | service | Yes (bootJar) | **COLLECT** — Nexon fetch, chunking, gzip→MinIO, Kafka publish | `ExternalApiApplication.kt` :8081 |
| `module-calculator` | service | Yes (bootJar) | **COMPUTE** — consume chunks, DP calculation, gzip→MinIO | `CalculatorApplication.kt` :8082 |
| `module-synchronizer` | service | Yes (bootJar) | **SYNC** — project results into Postgres read models + Redis | `SynchronizerApplication.kt` :8083 |
| `module-cleanup` | service | Yes (bootJar) | **CLEAN** — GC MinIO run artifacts, stale-Kafka rewind | `CleanupApplication.kt` :8084 |
| `module-rest-controller` | service | Yes (bootJar) | **SERVE** — standalone V6 read API (expectation/ranking/popular/like/auth) | `RestControllerApplication.kt` :8080 |
| `module-chaos-test` | test-only | Yes | 19 nightmare + chaos scenarios; `bootJar`/`jar` disabled | `src/chaos-test/`, `src/nightmare-test/` |

> **Legacy vs current.** `module-app` is the original monolith that once did everything. It is being
> superseded by the 5 standalone services. New ETL work goes into `external-api / calculator /
> synchronizer / cleanup`; new read endpoints into `rest-controller`. `module-app` still contains the
> V4/V5 calculation worker logic reused by the pipeline. (Source: architecture agent, ADR-042 dual-generation.)

### 1.2 Cross-cutting: `LogicExecutor`

All exception handling flows through `LogicExecutor` (`module-infra/.../infrastructure/executor/`) —
raw `try/catch` is banned by `.claude/rules/code-rules.md` and ADR-044. It composes three interfaces
(`BasicExecutor`, `SafeExecutor`, `ResilientExecutor`) and exposes 6 patterns: `execute`,
`executeOrDefault`, `executeWithTranslation`, `executeWithFallback`, `executeOrCatch`,
`executeWithFinally`. Every call takes a `TaskContext.of(component, operation[, dynamicValue])`
(`module-common/.../executor/TaskContext.kt`) — `component`+`operation` are metric tags, `dynamicValue`
is log-only (cardinality control).

---

## 2. Data Flow (ETL)

The current pipeline is **Kafka + MinIO driven** (the legacy PGMQ synchronous-queue path in
`module-app` still exists but is not the ETL backbone). Pattern = **collect → compute → serve → clean**.

```
Airflow (control plane)
   │  POST /api/internal/trigger/phase/{PHASE}  (X-Airflow-Run-Id, X-Upstream-Run-Id)
   ▼
module-external-api (8081) — COLLECT
   │  WebClient/Netty HTTP2 → Nexon OpenAPI (open.api.nexon.com/maplestory/v1/*)
   │  4 phases: RANKING_FETCH → OCID_LOOKUP → CHARACTER_BASIC → ITEM_EQUIPMENT
   │  ChunkedSnapshotSink (single writer thread) → GzipJsonlChunkWriter (temp file, Deflater.BEST_SPEED)
   │  → putFileAsync → MinIO  runs/{runId}/{endpoint}/chunks/part-NNNNNN.jsonl.gz
   │  Kafka publish per chunk: external-api.snapshot.chunk-ready
   ▼ (topic) external-api.snapshot.chunk-ready
module-calculator (8082) — COMPUTE
   │  KafkaSnapshotChunkReadyConsumer → waitForSourceChunk (MinIO exists() idempotency)
   │  SnapshotChunkProcessor: parse FlatItem → CalculationCache (off-heap DP cube/starforce)
   │  CalculationResultWriter (temp file → putFileAsync)
   │  → MinIO  calculator/runs/{runId}/{endpoint}/chunks/result-{chunkId}.jsonl.gz
   │  Kafka publish: calculator.result.chunk-ready
   ▼ (topic) calculator.result.chunk-ready
module-synchronizer (8083) — SYNC / read-model projection
   │  KafkaResultChunkConsumer + BasicSnapshotChunkConsumer + OcidLookupRunConsumer
   │  ChunkPipelineOrchestrator → EquipmentDocumentBuilder
   │  → JDBC bulk upsert (Postgres read models) + Redis ZSET ranking + Redis ocid map
   │  Kafka publish: synchronizer.chunk.consumed
   ▼ (topic) synchronizer.chunk.consumed
module-cleanup (8084) — CLEAN
   │  ConsumedChunkInbox drains chunk.consumed
   │  RunCleanupService.deleteByPrefix("runs/…"/"calculator/runs/…")
   │  StaleKafkaSkipService rewinds offsets by runId (does NOT commit — safe live)
   │  Triggered hourly by Airflow cleanup_pipeline
   ▼
module-rest-controller (8080) / query-server (Next.js) — SERVE
      reads Postgres read models + Redis L2 cache (no write path)
```

**Phase chaining nuance** (`ExternalApiScheduler.kt`): each phase gets a fresh `runId`;
`ITEM_EQUIPMENT` reads the ocid-mapping written by `OCID_LOOKUP`'s runId (not `CHARACTER_BASIC`'s).
Infinite-loop mode (`PhaseLoopController`) keeps `ITEM_EQUIPMENT` running with a new runId per iteration
until `PhaseStopSignal`. (Source: ETL-flow agent.)

**Idempotency rails.** Chunk-ready is published only *after* the MinIO upload future completes;
`waitForSourceChunk` polls `exists()` and republishes existing results on duplicate delivery;
`ChunkConsumerTemplate`/`ChunkExecutionStatus` state machine is idempotent on Kafka redelivery;
`RunStatusTracker.acquirePhaseSlot` prevents double-runs.

---

## 3. Component Dependency Map

### 3.1 Build-time module graph (verified from build.gradle files)

```
module-common (no Spring; verifyNoSpringDependency)
   ▲
module-core (pure domain; ports)
   ▲
   ├── module-infra (JPA, adapters, LogicExecutor, PGMQ, locks, cache, workers, MinIO)
   │      ▲
   ├── module-web (controllers, DTO)          ← core+common only, NOT infra
   │      ▲
   └──────┴── module-app (legacy monolith: core+infra+common+web)
module-auth → common, core            (consumed by app + rest-controller)
module-external-api / calculator / synchronizer → common, core, infra
module-cleanup → common, infra
module-rest-controller → common, core, auth   (NOT infra)
module-chaos-test → (test) core, infra, common, app
```

### 3.2 Runtime service graph

```
Airflow ──HTTP──▶ external-api ──Kafka──▶ calculator ──Kafka──▶ synchronizer ──Kafka──▶ cleanup
                       │                      │                     │
                       ▼ MinIO runs/          ▼ MinIO calculator/   ▼ Postgres read models + Redis
                                                                     ▲
                                          rest-controller / query-server (SERVE) ── reads ─┘
Shared infra: PostgreSQL17+PGMQ · Kafka(KRaft) · MinIO · Redis · Prometheus/Grafana/Loki
All services on external bridge `maple-network` (ADR-744, no host publish).
```

---

## 4. Data Model & Database Schema

**Engine.** PostgreSQL 17 + PGMQ (image `jumski/postgres-17-pgmq`). Single-DB strategy (ADR-314):
MySQL and MongoDB fully removed; Redis retained for a narrow role. Migrations: Flyway
`module-infra/src/main/resources/db/migration/V100…V128` (29 files) + boot init `docker/postgres/init.sql`.

### 4.1 Key tables

| Table | Migration | Role |
|---|---|---|
| `game_character` | pre-Flyway | Per-IGN record; `fingerprint`, `account_id` (V103), `like_count` (trigger V104) |
| `character_valuation_views` | pre-Flyway; `preset_no` V113 | **Primary V5 read model**, JSONB `presets`, `@Version` optimistic lock. Postgres replacement for Mongo `character_valuation` |
| `character_expectation_read_model` | V111 (LOGGED) | Gzip full V5 payload per user_ign; atomic `upsert_expectation_read_model()` fn, monotonic guard V112 |
| `calculation_jobs` | V114/115/116/117/120 | Async job orchestrator: `status` enum, nullable `ocid`, `request_key` dedup (V120) |
| `calculation_snapshots` | V114 | Object-storage pointers (LOCAL/MINIO), `expires_at` |
| `calculation_snapshot_inputs` | V117 | Write-path staging, `payload jsonb`, `job_id UNIQUE` |
| `calculation_results` | V117/122 | Gzip `response_body bytea` + projection cols (`total_expected_cost`, `presets jsonb`) |
| `outbox_events` | V117 | Transactional outbox (same-TX emit) |
| `cache_storage` | V110 (UNLOGGED) | Postgres L2 cache tier, `BYTEA` value, `expires_at` |
| `character_equipment_read_model` | V123/124/125 | V6 per-(ocid,preset) gzip doc, `document_hash` skip-unchanged |
| `character_basic_read_model` | V126 | V6 basic-info read path |
| `chunk_execution` | V128 | Synchronizer unified exec state, `UNIQUE(execution_type,run_id,endpoint,chunk_id)`, lease-based |
| `equipment_persistence_tracker` | V103/119 | Crash-recovery for async equipment save |
| `dlq_replay_meta` / `rate_limit` | V108 / V109 | Anti-loop DLQ counter / sliding-window rate counter |
| `character_like_*` | V100 | Like buffer (UNLOGGED) + count + relation (Redis replacement) |

**Removed** (V106): `donation_outbox`, `event_outbox`, `nexon_api_outbox` → PGMQ.

### 4.2 PGMQ queues (`pgmq.q_*` live / `pgmq.a_*` archive)

`nexon_retry_queue`, `nexon_fanout_queue`, `expectation_calc_high/low`, `ocid_resolve_queue`,
`nexon_api_request/response_queue`, `calculation_requested/completed_queue` (3-stage split V121),
`result_ready_queue`. Client: `PgmqClient.kt` — **`send()` requires an active transaction**
(`TransactionSynchronizationManager.isActualTransactionActive()`) to guarantee ADR-316 same-TX publish;
`archiveBatch()` uses a single CTE round-trip; atomic dedup via `pgmq_send_if_absent()` + expression
indexes on `(message->>'userIgn')` (V112).

### 4.3 CQRS = "Async Materialized View" (ADR-388)

The read models are populated **inline in the calculation transaction** (`syncToViewTable()` inside the
`@Transactional` worker), not via a separate sync consumer. The original event→PGMQ→consumer→view design
was abandoned (event never published, consumer never built, ~95KB payload exceeded PGMQ ~8KB limit).
Queue = lightweight job trigger; DB = source of truth; calculation rollback ⇒ view rollback.

### 4.4 What replaced what (ADR-314 single-DB)

- MySQL → PostgreSQL (ADR-341, ADR-051 deprecated)
- MongoDB read-side → PostgreSQL JSONB (ADR-340, ADR-325, ADR-036)
- Redis Streams → PGMQ (ADR-316, ADR-055 superseded)
- Redis Pub/Sub → PostgreSQL `LISTEN/NOTIFY` (ADR-323; `PostgresNotifyPublisher/Subscriber`, channel `cache_invalidation`)
- Redisson lock → `pg_try_advisory_xact_lock` (ADR-318/321; `PostgresAdvisoryLockStrategy`, `OrderedLockExecutor` deadlock prevention ADR-078)

**Redis still used** (ADR-022 *deprecated* 2026-06-09 — removal reversed): `module-rest-controller` V6
read caches, and synchronizer `OcidMappingRedisWriter` + `EquipmentRankingRedisWriter` (ZSET). Future
Redis L2 for calculator scale-out allowed without a new ADR.

---

## 5. Messaging & Object Storage

### 5.1 Kafka topics

| Topic | Producer | Consumer(s) |
|---|---|---|
| `external-api.snapshot.chunk-ready` | external-api | calculator, synchronizer (basic) |
| `external-api.snapshot.run-completed` / `.run-failed` | external-api | monitors / run-status |
| `external-api.ocid.lookup-ready` | external-api (OCID phase) | synchronizer `OcidLookupRunConsumer` |
| `external-api.urgent.snapshot.chunk-ready` | external-api urgent | calculator + synchronizer urgent listeners |
| `calculator.result.chunk-ready` | calculator | synchronizer, cleanup scan |
| `synchronizer.chunk.consumed` | synchronizer | cleanup `ConsumedChunkInbox` |
| `auth-character-fetch-request/response` | module-auth ↔ external-api | login orchestration |

Broker: `confluentinc/cp-kafka:7.7.0`, KRaft mode.

### 5.2 MinIO object storage

Interface `ObjectStorage` (`module-common`) with `MinioObjectStorage` (S3 SDK v2 + `S3TransferManager`)
and `LocalFsObjectStorage` impls. Bucket `maple-expectation`; per-module service-account keys mounted at
`/run/secrets/sa-<module>`; ILM 2-day expiry on `runs/`, `calculator/`, `ocid-mapping/` (`docker/minio/bootstrap.sh`).

**Key layout.** Source: `runs/{runId}/{endpoint}/chunks/part-NNNNNN.jsonl.gz` (+ `manifest.json`,
`_SUCCESS`, `_RUNNING`, `failed.jsonl`); ocid map: `runs/{runId}/ocid-lookup-mapping-{runId}.jsonl.gz`;
results: `calculator/runs/{runId}/{endpoint}/chunks/result-{chunkId}.jsonl.gz`.

**Temp-file upload pattern** (ADR-730): both writers stream gzip to a temp file, then `putFileAsync`
(S3TransferManager, own thread pool); temp deleted only on upload success. This replaced a
`PipedInputStream`/`PipedOutputStream` design that produced **0-row gzip files** (`IOException: Read end
dead` + `Deflater has been closed`) — the calculator empty-results bug.

---

## 6. Orchestration (Airflow Control Plane)

**Framing (ADR-720, supersedes ADR-718).** Airflow = **Control Plane** (trigger, poll, SLA, alert,
history); Kafka = **Data Plane** (unchanged). Initially rejected (ADR-718, "paradigm mismatch"), then
adopted when 20+ ad-hoc schedulers across modules became unmanageable.

**DAGs** (`docker/airflow/dags/`): 5 single-purpose DAGs (ADR-734) —
`ranking_ocid_lookup_pipeline`, `character_basic_pipeline`, `item_equipment_pipeline`,
`daily_full_pipeline` (retired ADR-740), `stop_loop_pipeline` — plus `morning_chain_pipeline`
(`0 18 * * *` UTC = 03:00 KST) and hourly `cleanup_pipeline`. Factory `phase_pipeline_factory.py`
builds per-phase DAGs with `branch_on_mode` (once / count / infinite) and Kafka-counting sensors.

**Hard-won fixes:**
- Use `PythonOperator` + `requests.post()`, not `HttpOperator` — `HttpHook.run()` raises on any 4xx before `response_check` (ADR-726).
- Loop-started sensor must check `status == "RUNNING"`, not `iterationCount >= 1` — one ITEM_EQUIPMENT iteration ≈ 62 min so completion-based check always timed out (ADR-739).
- When upstream OCID_LOOKUP is mid-refresh, defer+retry rather than treating null upstreamRunId as fatal — the loop was dying at iteration ~274 (ADR-742).

---

## 7. Infrastructure, Deployment, CI/CD

### 7.1 Docker compose stack

| File | Purpose | Status |
|---|---|---|
| `docker-compose.yml` | Core infra: postgres, kafka, redis, minio(+bootstrap), prometheus, grafana, loki, promtail, cadvisor, autoheal | ACTIVE |
| `docker-compose.services.yml` | 4 Spring Boot apps (ext-api/calc/sync/cleanup) | ACTIVE overlay |
| `docker-compose.airflow.yml` | airflow-db (pg17), webserver, scheduler (2.10.5) | ACTIVE overlay |
| `docker-compose.observability.yml` | alertmanager + node-exporter (standalone) | **LEGACY / not running** |
| `docker-compose.postgres.yml` | standalone pg + legacy redis | LEGACY |

Single external bridge `maple-network` (`172.20.0.0/16`) shared by infra + apps. Shared runtime image
`docker/Dockerfile.runtime` (`eclipse-temurin:21-jre-alpine`, non-root). Build/deploy helpers:
`docker/services/build.sh` (tags `:dev` + `:sha-<7>`), `deploy-apps.sh` (7-stage pre-flight incl. IDLE
gate on `calculation_jobs`, health-poll, then start autoheal/cadvisor *after* apps healthy).

### 7.2 Deployment strategy

- **Coolify** with 3-layer self-healing (ADR-731/732/733): L1 `restart: always`, L2 Coolify Sentinel, L3 `autoheal` sidecar (watches `/actuator/health`). Image pipeline: push→GH Actions bootJar→GHCR→Coolify auto-deploy.
- **nohup → docker** migration (ADR-737): solved network duality (`docker network connect --alias`), missing `:dev` tag (auto-resolve `:sha-*`), airflow host-network drift.
- **Internal-network-only** (ADR-744, post-incident): removed all `0.0.0.0` host publishes; only `127.0.0.1` (SSH-tunnel) + Coolify `80/443` + SSH `22` internet-facing.

### 7.3 CI/CD (GitHub Actions, ADR-054)

- `ci.yml`: `spotlessApply` → `test -PfastTest` (Testcontainers PG) → `minio-it` → `docker-smoke` (compose up + health poll) → `build-and-push` (GHCR, develop only).
- `gradle.yml`: legacy master CD → SSH deploy to EC2 (`fuser -k 8080`, scp jar, `deploy.sh`).
- `nightly.yml` (`0 15 * * *`): staged unit → integration → chaos → nightmare, then `test-reporter` summary.

---

## 8. Observability & Logging

**Stack (ADR-053):** Prometheus + Grafana + Loki + Promtail + OpenTelemetry tracing. Apps expose
`/actuator/prometheus` (Micrometer). Prometheus scrapes by service name over `maple-network` (5s
interval). Rules under `docker/prometheus/rules/`: `alert_rules.yml`, `lock-alerts.yml`,
`load-test-rules.yml`, `offheap-alerts.yml`, `cache-backend-alerts.yml`. 15 Grafana dashboards
(`docker/grafana/dashboards/`) + top-level `grafana/dashboard-pipeline*.json`.

**Key metric names** (`.claude/rules/prometheus-metrics.md`): `external_api_item_equipment_fetched_total`,
`calculator_chunks_processed_total`, `calculator_chunk_items_per_second`, plus JVM/GC/CPU/HikariCP.

**Logging (ADR-log-governance).** JSON structured (`logstash-logback-encoder`), source-level PII masking
(`StringMaskingUtils`), MDC `runId`/`chunkId` correlation, log budget ≤3 INFO/chunk. Docker `json-file`
`max-size 50m × max-file 10` = 500 MB/container (ADR-741 — raised from 30 MB after log rotate-out hid the
loop-death stacktraces).

**Known observability gaps:** `alertmanager` and `node-exporter` are referenced by `prometheus.yml` but
only defined in the legacy overlay — **not running**. Alerts evaluate but are not delivered; system-metric
alerts (`HighCpuUsage`, etc.) have no data.

---

## 9. Timeline of Architectural Evolution

ADR numbering is chronological in three bands: 001–088 (early 2026), 312–393 (Mar–Apr), 700–744 (May–Jul).

| Era | When | What | Key ADRs |
|---|---|---|---|
| Single-cache monolith | late 2025 | Redis-only cache, MySQL, blocking Tomcat, 719 RPS | — |
| Tiered cache | Dec 2025–Feb 2026 | Caffeine L1 + Redis L2 + SingleFlight | 003, 043, 058 |
| Virtual Threads | Feb 2026 | Java 21 VT + AbortPolicy (post-P1 #168 CallerRuns disaster) | 045, 048 |
| V5 CQRS design | Feb 2026 | MySQL command + MongoDB read + Outbox sync | 036, 037, 038, 079/080/081 |
| Hexagonal split | Feb 2026 | 4-module + Kotlin, DIP, domain extraction | 315, 317, 041, 352, 353, 369, 370 |
| **PostgreSQL pivot** | **Mar 2026** | Single-DB: Redis→PG, Mongo→JSONB, MySQL/Mongo removed, PGMQ adopted | 314, 319, 325, 340, 341, 316, 363 |
| V5 Async Materialized View | Apr 2026 | Inline view-write replaces sync consumer | 388, 374, 375 |
| Pipeline perf | Apr 2026 | 27× PGMQ pipeline, fan-out queue-driven, PgBouncer, Kafka→PGMQ writes | pgmq-perf-27x, 355, 387, pgmq-kafka-migration |
| Object storage | May–Jun 2026 | Unified `ObjectStorage`, MinIO readiness VS1/VS2, temp-file upload | 719, 725, 730, 743 |
| Airflow control plane | May 2026 | ADR-720 supersedes 718; phase-separated DAGs | 720, 726, 393, 734, 739, 740, 742 |
| IO/CPU split & async | Jun 2026 | VT for IO, Dispatchers.Default for CPU; pure CF chain; backpressure | 723, 724, blocking-async-contract |
| Coolify + Docker | Jun 2026 | 3-layer self-healing, nohup→docker, GHCR | 731, 732, 733, 737 |
| Security hardening | Jul 2026 | Internal-network-only post-cryptominer incident | 744 |

**Notable reversals:** ADR-022 (Redis removal) *deprecated* — Redis re-allowed. ADR-718 (no Airflow)
superseded by ADR-720. MongoDB adopted (036) then removed (340). See §17 for the knowledge graph.

---

## 10. Performance Optimization History

**The metric of record changed.** Pre-May 2026 = HTTP RPS at the wrk client. From May 2026 = **views/sec**
(rows written to `character_valuation_views`, measured by DB delta in `load-test/run-v5-db-throughput.sh` +
`load_test_v5.py`).

### 10.1 RPS timeline (docs/06_Performance_Journey)

| Date | RPS/throughput | Change |
|---|---|---|
| 2026-01-20 | 223 RPS | Chaos baseline |
| 2026-01-24 | 97 RPS | SingleFlight regression (Semaphore acquired before cache lookup) |
| 2026-01-24 | 555 RPS | L1 Caffeine fast path |
| 2026-01-25 | 674 RPS | Write-behind buffer |
| 2026-01-26 | 965 RPS | Parallel preset calculation |
| 2026-01-27 | 325 RPS | V5 stateless trade-off (−53% for cross-instance consistency) |
| 2026-01-27 | 940 RPS | Auto-warmup (200 candidates) |
| Feb–Mar | 940→7,347 | Great Migration (3 DBs→1 PG) + Micro-Batching (3-5 DB round-trips→1) |
| 2026-03-19 | 7,347 RPS (real) / 10,994 (empty DB) | PostgreSQL LISTEN/NOTIFY |
| 2026-04→05 | 30–38 views/sec | PGMQ 3-stage pipeline + Supabase Pooler era |

Headline: **97 → 7,347 RPS (76×), p99 4,100ms → 36ms, errors 59.7% → 0%, 3 DBs → 1, 89+ conns → 25**
(docs/23_Incident_Response_Journey/12_7347_rps.md).

### 10.2 Known ceilings & root causes

- **Nexon API physical limit** ~230 RPS on cache miss (cache HIT 1,515). Server cap 500 req/s; OCID_LOOKUP measured 397/s. (`11_fanout_admission_control.md`, memory `reference_nexon_api_rate_limit`.)
- **ext-api writer** was ~60% single-core CPU-bound on gzip of 218 KB item-equipment payloads → fixed by gzip level 6→1 (`Deflater.BEST_SPEED`), **3× rate** (166→498 rec/s), PR #1453 / ADR-729. This is the single most important recent perf fix.
- **Endurance ceiling** 100–150 users/sec sustained (lifetime avg 137, max burst 651), capped by single `ChunkedSnapshotSink.runWriterLoop` thread (`ENDURANCE_THROUGHPUT_CEILING_20260702.md`).
- **PGMQ 27× journey**: 7 stacked bottlenecks removed in sequence — VT pinning, bulk JDBC upsert, semaphore removal, 3→1 preset, L2 DB removal, compute-key dedup, coroutine orchestration (25,466ms→864ms).
- **Connection pool**: HikariCP must equal Tomcat threads; 3 outbox schedulers stole 9/25 conns → PGMQ unification reclaimed ~7-11 conns/instance (docs/13_Connection_Pool_Journey).

**Endurance proof of stability:** 71h (2.28 B items, 0 calculator errors, no leak) and 82h (RSS drift <4%, disk cleanup equilibrium) runs.

---

## 11. Chaos Engineering, Incidents & Security

### 11.1 Chaos (`docs/02_Chaos_Engineering/`, `module-chaos-test`)

19 "Nightmare" scenarios (N01–N19) + core/network/resource/connection/data suites. `FLUSHALL` banned —
must use `safeDeleteKey` (realistic fault injection). Notable **failing** scenarios flagged for fixes:
N02 deadlock (2/3), N04 connection vampire (1/4), N06 timeout cascade (2/5), N07 MDL freeze (1/3);
N16 config-poisoning test file **missing**.

### 11.2 Incidents

- **Incident Response Journey** (13 chapters): cascade failure → Resilience4j → pool exhaustion → cache stampede → VT pinning → advisory lock → alert silence → like split-brain → Great Migration → zero-try-catch → 7,347 RPS.
- **P0 batch (2026-01-20):** N07 MDL freeze (`lock_wait_timeout` default 1yr → set 10s), N09 circular lock (strict ordering), N02 lock-order deadlock (multi-lock ordering + metrics).
- **airflow-db cryptominer compromise (2026-07-03)** — fully documented (`docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md`). Chain: internet-exposed `5433:5432` on public IP → weak superuser `airflow:airflow` → `COPY … TO PROGRAM` → `/tmp/kunt` dropper → miner + tokio watchdog → C2 `5.255.106.100`/`5.255.115.190`. **MTTD 18–24h dominated by a wrong "parallel-worker leak" diagnosis.** Fix: bind `127.0.0.1:5433`, strong password, nuke volume, ADR-744 internal-network-only. IOCs preserved in `evidence/airflow-db-20260703/`.

### 11.3 Security posture (`docs/16_Guardrails/security/`)

JWT (env-var fail-fast, no API key in payload, token-reuse detection), secrets management (Jasypt),
PII masking, input validation (path-traversal/SQLi/log-injection), Spring Security filters as `@Bean`
not `@Component` (P0 #238 CGLIB bug), CORS 3-stage validation, CSP, Prometheus IP allowlist (ADR-066),
NIST incident playbook. **Pending operator action** (from 2026-07-03): rotate all credentials, close
residual public ports, 2FA on Airflow, firewall default-deny.

### 11.4 Notable TODOs (traceable backlog)

- `AuthPortAdapter.kt:73` — admin allowlist check not implemented.
- `PriorityAdmissionControl.kt:175` — hot-character detection not implemented (relates to N05).
- `ExceptionTranslator.kt:13` — simplified error hierarchy pending task #9.
- PGMQ integration tests `@Disabled` (cascading missing beans).
- Issue #727 — view/DTO serialization divergence.

---

## 12. Testing Strategy

298 test files. Framework: **JUnit 5 + AssertJ + Mockito-Kotlin + Awaitility + ArchUnit**; jqwik
(property-based) in `module-core`; **Testcontainers PostgreSQL** (H2 banned for DB tests — dialect
divergence caused prod failures #715/#663). Base-class hierarchy rooted at `IntegrationTestBase`
(`@ActiveProfiles("pgtest")`, `DatabaseCleaner` TRUNCATE). Tag taxonomy excludes
`integration/chaos/nightmare/flaky/quarantine/pgmq/...` from default `./gradlew test`.

**Clarification on "no integration tests (Issue #207)":** Testcontainers *is* heavily used and mandated
(ADR-051, ADR-368). Issue #207 = CI flakiness/speed; the real rule is "integration tests run via separate
`integrationTest`/`chaosTest` source sets, not default `test`". So `./gradlew test` alone won't catch
DB-dependent regressions. ADR-061 = flaky-test quarantine (CI pass rate 85%→99.7%).

---

## 13. Known Limitations

1. **Single-writer bottleneck** — `ChunkedSnapshotSink.runWriterLoop` caps sustained throughput at 100–150 users/sec; recommended fix (2–3 writer threads) not yet merged.
2. **Alertmanager & node-exporter not running** — alerts evaluate but are not delivered; system-metric alerts have no data source.
3. **Small-file problem** — ~1.2K snapshot + 1.2K result files per run stress MinIO LIST/lifecycle and block Iceberg/Parquet; flush-time rollup proposed (ADR-743) but not implemented.
4. **Parquet migration rejected** — PoC recommends DO NOT MIGRATE (`docs/24_.../07_parquet_poc_benchmark.md`).
5. **Security debt** — post-incident credential rotation and public-port closure still pending operator action.
6. **`master` lags `develop` by 56 commits** — develop is the live branch (one-way cherry-pick).
7. **Legacy code duplication** — `DatabaseCleaner` in 4 locations; `module-app/src/test-legacy/` still runs; dead Mongo/MySQL metric wiring remains.
8. **Unimplemented TODOs** — admin allowlist, hot-character detection, error-hierarchy migration, disabled PGMQ tests (§11.4).

---

## 14. Future Improvements

- **Multi-writer snapshot sink** (2–3 threads) → projected 200–450 users/sec (`ENDURANCE_THROUGHPUT_CEILING`).
- **Flush-time rollup** for small-file problem (ADR-743): 10–100× file-count reduction, unblocks Iceberg.
- **Analytics platform** (ADR-735): Iceberg/Trino/ClickHouse evaluation completed as design tasks (#1395-#1421 closed); `IcebergSnapshotPort`/`AnalyticsQueryPort` interfaces stubbed.
- **Redis L2 for calculator** on multi-instance scale-out (allowed without new ADR per ADR-022 deprecation).
- **Wire alertmanager + node-exporter** onto `maple-network` for actual alert delivery.
- **Nexon HTTP pool 250→500** + re-test rate limit at 300–500 pps (now that gzip-CPU is no longer the cap).
- **Async projection** via Spring Event + lightweight PGMQ when scaling out the inline view-write (ADR-388 TODO).

---

## 15. FAQ

**Q: Which service do I run to fetch/compute/serve?**
Fetch = `external-api` (8081), compute = `calculator` (8082), sync-to-read-model = `synchronizer` (8083),
serve = `rest-controller` (8080) / query-server, GC = `cleanup` (8084). `module-app` is legacy.

**Q: Is it Kafka or PGMQ?**
The ETL pipeline (ext-api→calc→sync→cleanup) is **Kafka + MinIO**. PGMQ is the legacy in-DB queue path
in `module-app`/`module-infra` (workers for the V5 calculation queue). Both exist; ETL uses Kafka.

**Q: Where do calculation results live?**
Transiently in MinIO (`calculator/runs/...jsonl.gz`), permanently in Postgres read models
(`character_valuation_views`, `character_expectation_read_model`, `character_equipment_read_model`).

**Q: Is this real CQRS?**
No — it's "Async Materialized View" (ADR-388): read model written inline in the calculation TX, not via a
separate sync consumer.

**Q: Why not `.get()`/`.join()`?**
Banned (`.claude/rules/async-patterns.md`, ADR blocking-async-contract). Use CompletableFuture chaining or
Kotlin suspend. Blocking a Virtual Thread carrier (esp. inside `synchronized`) pins it.

**Q: Why did throughput "drop" to 30–38 views/sec?**
Different metric + different era. RPS = cache-hit read serving (7,347). views/sec = end-to-end cold write
pipeline through Supabase Pooler; bottlenecks are external pooler latency + Nexon I/O, not the app.

**Q: What's the single biggest recent perf win?**
gzip level 6→1 on the ext-api writer (PR #1453 / ADR-729): 3× writer throughput; the writer was ~60%
single-core CPU-bound on 218 KB item-equipment payloads.

**Q: What was the 2026-07-03 incident?**
airflow-db cryptominer via internet-exposed port 5433 + weak superuser password. Response = ADR-744
internal-network-only. Lesson: infrastructure defaults (`0.0.0.0`, `admin/admin`, `network_mode: host`)
are the weakest link.

**Q: How is the pipeline scheduled?**
Airflow control plane. `morning_chain_pipeline` at 03:00 KST chains ranking→ocid→character_basic→
item_equipment (infinite loop); hourly `cleanup_pipeline`. Kafka is the data plane.

**Q: Where are the rules I must follow?**
`.claude/rules/` (hexagonal boundaries, no try-catch, null-safety, async patterns, concurrency, MQ,
data-access, testing). They override user requests per `CLAUDE.md`.

---

## 16. Interview-Ready Explanation

> "It's a probabilistic valuation engine for MapleStory characters. Given a character name, it computes
> the *expected in-game currency cost* to reproduce that character's equipment enhancements — cube
> potentials, starforce, and flames — using dynamic-programming probability convolution. The hard part
> isn't the math; it's doing it for ~600K characters daily against a rate-limited external API, cheaply,
> on small hardware.
>
> The system is a Kotlin/Spring Boot **hexagonal** monorepo that started as a single service and was
> decomposed into five: `external-api` collects from the Nexon OpenAPI and writes gzipped JSONL chunks to
> MinIO; `calculator` consumes chunk-ready Kafka events, runs the DP calculation, and writes result
> chunks back to MinIO; `synchronizer` projects those into precomputed PostgreSQL read models plus Redis
> ranking sets; `cleanup` GCs the object storage; and a `rest-controller` serves reads. Kafka is the data
> plane; **Airflow** is the control plane that triggers phases and polls status.
>
> The defining architectural story is **collapse to PostgreSQL**. It began with Redis + MySQL + MongoDB.
> Over March 2026 we moved to a single Postgres 17 instance: MongoDB's CQRS read model became JSONB,
> MySQL was removed, Redis Streams became **PGMQ** (with same-transaction publishing), Redis Pub/Sub
> became `LISTEN/NOTIFY`, and Redisson locks became `pg_try_advisory_xact_lock`. That cut per-request
> DB hops and let us go stateless. Combined with a Caffeine L1 cache, SingleFlight, virtual threads, and
> micro-batching, read throughput went from 97 RPS to 7,347 RPS with p99 36 ms and zero errors.
>
> On the pipeline side, the recurring lesson is that bottlenecks are about **data flow and CPU, not
> infrastructure count**. The biggest single win was discovering the ext-api writer was 60% single-core
> CPU-bound on gzip of 218 KB payloads — dropping gzip to BEST_SPEED tripled throughput. The current
> ceiling is the single writer thread, and the next fix is parallelizing it.
>
> We take reliability seriously: 19 chaos 'nightmare' scenarios, 71h and 82h endurance runs with zero
> leaks, a transactional-outbox + idempotency baseline, and a documented incident-response journey — the
> most recent being a cryptominer that got in through an internet-exposed Postgres port with a weak
> password, which drove an internal-network-only hardening. If I had to name the one anti-pattern this
> codebase is organized against, it's blocking a virtual-thread carrier: no `.get()`, no `.join()`, no
> `runBlocking` in server code — everything is CompletableFuture chains or suspend functions."

---

## 17. Knowledge Graph

```
                          ┌──────────────────────────────────────────┐
                          │  Nexon MapleStory OpenAPI (rate-limited)  │
                          └───────────────────┬──────────────────────┘
                                              fetch │ (WebClient/Netty)
   Airflow control plane ──trigger/poll──▶ EXTERNAL-API ──gzip JSONL──▶ MinIO runs/
        (DAGs, sensors)                        │ Kafka chunk-ready
                                               ▼
                                          CALCULATOR ──DP cube/starforce──▶ MinIO calculator/
                                               │ Kafka result-ready       (off-heap CalculationCache)
                                               ▼
                                        SYNCHRONIZER ──inline TX upsert──▶ PostgreSQL read models
                                               │ Kafka chunk-consumed      + Redis ZSET ranking
                                               ▼
                                           CLEANUP ──deleteByPrefix──▶ MinIO GC
                                                                            ▲
                              REST-CONTROLLER / query-server ──reads──── PostgreSQL + Redis L2

  Foundations:
   Hexagonal (common→core←infra, web→core)  ·  LogicExecutor (no try-catch)  ·  Virtual Threads (IO) +
   Dispatchers.Default (CPU)  ·  PGMQ same-TX publish  ·  pg advisory locks  ·  LISTEN/NOTIFY cache inval
   ·  Tiered cache (Caffeine L1 + PG/Redis L2 + SingleFlight)  ·  Async Materialized View (ADR-388)
   ·  Coolify 3-layer self-healing  ·  Prometheus/Grafana/Loki  ·  internal-network-only (ADR-744)

  Evolution reversals:  Redis-removal(022)⊗  ·  Airflow-reject(718→720)  ·  Mongo(036→340)  ·  MySQL(→341)
```

**Decision cross-references:** ADR-314 (single-DB) ← enables ← {319 Redis→PG, 325 Mongo→JSONB, 316 PGMQ,
323 NOTIFY, 340/341 removals}. ADR-388 (async matview) ← supersedes ← {036/037/038 Mongo CQRS}. ADR-720
(Airflow) ← supersedes ← 718. ADR-744 (network) ← caused by ← 2026-07-03 incident. ADR-729/PR#1453 (gzip)
← diagnoses ← endurance ceiling report.

---

## 18. Source Index

**Root & rules:** `README.md`, `AGENTS.md`, `CLAUDE.md`, `.claude/rules/*.md`, `settings.gradle`,
`build.gradle`, `gradle/libs.versions.toml`.

**ADRs (docs/01_ADR/, ~190):** foundational — 003, 041, 043, 044, 045, 048, 053, 054, 058, 061, 314, 316,
317, 319, 323, 325, 340, 341, 355, 363, 368, 374, 375, 387, 388; recent — 717, 718, 719, 720, 723, 724,
725, 726, 729, 730, 731, 732, 733, 734, 737, 739, 740, 742, 743, 744.

**Performance:** `docs/06_Performance_Journey/` (00–13 chapters, README, step-trace),
`docs/05_Reports/05_06_Load_Tests/` (LOAD_TEST_*, V5_LOADTEST_REPORT, ENDURANCE_THROUGHPUT_CEILING_20260702),
`docs/13_Connection_Pool_Journey/`, `docs/endurance-test/` (71h, 82h), `load-test/run-v5-db-throughput.sh`,
`load_test_v5.py`.

**Chaos/incidents:** `docs/02_Chaos_Engineering/` (00_Overview…06_Nightmare), `docs/05_Reports/05_05_Incidents/`,
`docs/23_Incident_Response_Journey/` (00–12 + 2026-07-03-airflow-db-container-compromise), `docs/24_Troubleshooting_Casebook/` (00–07),
`docs/16_Guardrails/` (88 patterns), `evidence/airflow-db-20260703/`.

**Code entry points:** `module-external-api/.../ExternalApiApplication.kt`, `.../scheduler/ExternalApiScheduler.kt`,
`.../snapshot/{ChunkedSnapshotSink,GzipJsonlChunkWriter,SnapshotSinkEventPublisher}.kt`;
`module-calculator/.../CalculatorApplication.kt`, `.../processor/SnapshotChunkProcessor.kt`,
`.../processor/CalculationCache.kt`, `.../writer/CalculationResultWriter.kt`;
`module-synchronizer/.../adapter/chunk/ChunkPipelineOrchestrator.kt`, `.../repository/EquipmentReadModelRepository.kt`;
`module-cleanup/.../service/{RunCleanupService,StaleKafkaSkipService}.kt`;
`module-infra/.../infrastructure/executor/LogicExecutor.kt`, `.../pgmq/PgmqClient.kt`,
`.../lock/PostgresAdvisoryLockStrategy.kt`, `.../persistence/CharacterViewQueryServicePostgres.kt`.

**Data model:** `module-infra/src/main/resources/db/migration/V100…V128`, `docker/postgres/init.sql`,
JPA entities `module-infra/.../persistence/entity/*.kt`.

**Infra:** `docker-compose*.yml`, `docker/{Dockerfile.runtime,services/,minio/,prometheus/,grafana/,loki/,promtail/}`,
`.github/workflows/{ci,gradle,nightly}.yml`, `docker/airflow/dags/`, `query-server/`, `supabase/config.toml`.

**Project memory (`~/.claude/.../memory/`):** `project_ceiling_diagnosis`, `bug_calculator_empty_results`,
`project_adr022_deprecated`, `reference_nexon_api_rate_limit`, `project_airflow_db_parallel_leak`.

---

*Generated by parallel domain exploration (8 agents: architecture, ETL flow, data model, ADR timeline,
performance, infra/observability, chaos/incidents, git/tests) and synthesized into this knowledge base on
2026-07-06. Every section traces to the source pointers above.*
