# Design Decisions — Decision Rationale & Architecture Decision Map

> **Purpose.** Companion to `SYSTEM_KNOWLEDGE_BASE.md`. Every claim in the KB ends in "**why?**" —
> this document answers that question three ways:
>
> 1. **Why? — Decision Rationale Index.** Top design decisions, each answered with the constraint that
>    forced the choice, the alternatives rejected, and the trade-off accepted.
> 2. **Architecture Decision Maps.** Per-ADR walkthrough in the canonical 7-stage shape
>    (Requirement → Problem → Candidates → Chosen → Trade-offs → Perf Impact → Future Improvement).
> 3. **Predicted Interview Q&A.** What an interviewer would ask the maintainer of this project, with
>    model answers citing code paths and ADR numbers.
>
> Designed so an engineer can defend the design without memorising — they walk the rationale chain.

---

## Table of Contents

- [Part 1 — Decision Rationale Index](#part-1--decision-rationale-index)
- [Part 2 — Architecture Decision Maps (per ADR)](#part-2--architecture-decision-maps-per-adr)
- [Part 3 — Predicted Interview Q&A](#part-3--predicted-interview-qa)

---

# Part 1 — Decision Rationale Index

> 27 top design decisions. Each entry: **Constraint / Alternative rejected / Why we chose / Cost we accepted**.

---

### 1. Kafka (not PGMQ) for the ETL pipeline

**Constraint.** ETL carries ~218 KB gzipped chunks of equipment data; pipeline must drain sustained
~150 users/sec on commodity hardware; ordered-but-parallel consumers.

**Alternatives rejected.** PGMQ: payload cap ~8 KB (smaller than `EquipmentExpectationResponseV4`
~95 KB, ADR-388). Redis Streams: removed in ADR-316 era. RabbitMQ: extra operational surface.

**Why Kafka.** Built-in partitioning for fan-out (per-OCID ordering if needed), Consumer Groups for
horizontal worker scale, KRaft mode (no ZooKeeper), Confluent SDK on JVM, retention policy decoupled
from consumer liveness. Messages stay tiny (metadata only — payload on MinIO via Claim Check pattern).

**Cost.** Kafka cluster ops (3-node minimum for prod HA); consumer rebalancing complexity;
broker is another failure domain to monitor. Accepted because we already had Kafka ops muscle and the
throughput target justified the operational overhead.

Source: ADR-013, ADR-016 (outbox), ADR-355 (fan-out queue-driven).

---

### 2. PGMQ kept alongside Kafka

**Constraint.** Legacy `module-app` calculation queue (`expectation_calc_high/low`, `ocid_resolve`,
`result_ready`) had heavy PostgreSQL-integration value: same-TX publish, in-DB archive for replay,
no broker round-trip. Replacing it would have meant rewrites for very little perf gain on cold paths.

**Alternatives rejected.** Force-migrate everything to Kafka: hundreds of worker classes, no measured
benefit on those specific queues.

**Why both.** ETL = Kafka (throughput + cross-service fan-out). Calculation queue = PGMQ
(same-TX publish + DB-resident archive for audit). The two systems coexist because they serve
different shapes of work; PGMQ is not "deprecated", it's "right-sized".

**Cost.** Mental overhead of two queue runtimes; two observability surfaces; two failure modes.
Documented in `QueueNames.kt` to keep the boundary explicit.

Source: ADR-316, ADR-363, `module-infra/.../infrastructure/queue/QueueNames.kt`.

---

### 3. MongoDB removed in favor of PostgreSQL JSONB

**Constraint.** t3.small (2 vCPU / 2 GB) couldn't reliably run 3 databases (MySQL + Mongo + Redis).
Cross-DB consistency required distributed transactions; operational toil was eating dev velocity.

**Alternatives rejected.** MySQL Read Replicas (still bound by schema rigidity). PostgreSQL JSONB
(chosen!). Redis JSON (lacking indexing). Elasticsearch (overkill).

**Why PG JSONB.** `@>`, `jsonb_array_elements`, path queries cover the Mongo aggregation patterns we
actually used. GIN index on the `presets` column gave us ID lookup ~1-2 ms, array search ~5-10 ms —
parity with Mongo and sometimes better. ACID across read/write removed the Sync Worker class entirely.

**Cost.** JSONB queries are less ergonomic in code than Mongo aggregation pipelines; we use
typed `@JdbcTypeCode(SqlTypes.JSON)` mapping with `@JsonIgnoreProperties(ignoreUnknown = true)` to
keep Jackson happy. PG JSONB has no full-text search without `tsvector`, which we don't currently need.

Source: ADR-314, ADR-325, ADR-340, ADR-036 (superseded).

---

### 4. PostgreSQL `LISTEN/NOTIFY` over Redis Pub/Sub for cache invalidation

**Constraint.** Cross-instance L1 invalidation. Redis Pub/Sub previously did this; Redis removal was
on the roadmap.

**Alternatives rejected.** Stick with Redis Pub/Sub: contradicts ADR-022's removal direction.
Kafka topic per invalidation: too heavyweight, no per-key fan-out guarantees. Application-level
polling: defeats the purpose (latency + DB load).

**Why LISTEN/NOTIFY.** Zero infra (PG already exists); payload fits in 8 KB (one invalidation event
JSON-serialized); natural per-channel filtering; one publisher connection per instance, low cost.

**Cost.** Polling-based subscriber (no true push); reconnect logic; single channel `cache_invalidation`
limits per-key routing cleverness. We accept ~100 ms poll-interval latency for cross-instance L1
invalidation — it's a cache, not the source of truth.

Source: ADR-323, `PostgresNotifyPublisher.kt`, `PostgresNotifySubscriber.kt`.

---

### 5. CQRS → Async Materialized View (ADR-388)

**Constraint.** V5 endpoint `/api/v5/characters/{ign}/expectation` needs p99 < 50 ms; original strict
CQRS design (Spring Event → PGMQ `character-sync` → Consumer → View) was broken (event never published,
consumer never built) and would have hit the PGMQ ~8 KB payload cap.

**Alternatives rejected.** Spring Event + TransactionalEventListener (broken, abandoned). Full
CQRS with Kafka topic for view-sync (extra infra + eventual-consistency window). Read straight from
write tables (defeats the point — V5 query is 95 KB read).

**Why Inline View Write.** Worker writes the view row **in the same transaction** as the calculation
result. Atomic by construction. No separate consumer to maintain. Best-effort via
`ObjectProvider.getIfAvailable()` so V5-disabled deployments skip the work. Queue becomes a lightweight
job trigger; DB stays source of truth.

**Cost.** Tight coupling between calculation logic and view schema (one schema change = two table
updates in same TX). Scaling out the view write path requires explicit denormalisation if write rate
exceeds single-DB capacity. Future fix: Spring Event + lightweight PGMQ for projection (TODO in code).

Source: ADR-388, `CharacterViewQueryServicePostgres.upsert()`, ADR-079 (V5 flowchart).

---

### 6. gzip `BEST_SPEED` (level 1) on the ext-api writer

**Constraint.** ext-api ITEM_EQUIPMENT loop sustained ~98 files/s vs the 150-160 target. Writer
thread was ~60% single-core CPU-bound (StepTrace). 218 KB per record on average.

**Alternatives rejected.** Increase writer thread count (mask, not fix; contention). Network/MinIO
tuning (already async via `S3TransferManager` since `94cdd5685`). Switch to LZ4/Zstd (cross-stack
incompatibility; consumers expect gzip).

**Why level 1.** `Deflater.BEST_SPEED` = level 1. Empirically **3.0× rate** (166 → 498 rec/s), 3×
lower mean latency, ~15-20 % larger output (~11.5:1 → ~9-10:1 ratio). The compression ratio cost
is paid downstream anyway — storage is cheap, CPU is the wall.

**Cost.** ~20 % more bytes on the wire / in MinIO. With 2-day ILM and ~1.2 K files per run, this is
negligible (1.2 TB uncompressed × 0.2 = 240 GB more, vs disk equilibrium at 279 GB free in 71 h
endurance test).

Source: PR #1453 (commit `7c120d647`), ADR-729, `GzipJsonlChunkWriter.kt`,
`endurance-report-71h.md` (disk equilibrium).

---

### 7. Single writer thread is the current bottleneck

**Constraint.** `ChunkedSnapshotSink.runWriterLoop` owns Jackson serialize (~250 KB/record) + gzip
+ disk write. Per-record serialization caps the writer. Endurance ceiling report: sustained 100–150
users/sec, lifetime avg 137, max in-burst 651. Queue depth saturates at cap during bursts.

**Alternatives rejected earlier.** Increase queue capacity (only hides backpressure). Reduce chunk
size (more Kafka events, more overhead). Parallel fetchers only (already running, fetcher not the cap).

**Why it's the writer.** Two smoking-gun pieces of evidence:
1. `external_api_snapshot_sink_queue_depth` constantly near cap during burst, draining slowly in steady state.
2. `step-trace-load-test-report.md`: `pureCalculate` avg 1,054 ms / p99 3,292 ms is CPU-bound per-record;
   the writer loop owns this.

**Recommended fix (not yet merged).** 1 → 2–3 writer threads. Drain rate 2–3× → expected 200–450
users/sec sustained. ADR-729 PR #1321/#1322 were attempts that got superseded; the producer-side
serialize + OCID read-through work (`SnapshotChunkRecord.PreSerialized`) is staged in code but not
fully enabled.

**Cost of doing nothing.** Nexon supply ceiling (~150 users/sec) is the effective cap until fixed.

Source: `ENDURANCE_THROUGHPUT_CEILING_20260702.md` §6.3, ADR-729, PR #1452 (closed/regressed),
PR #1453 (the fix that did ship).

---

### 8. Airflow as Control Plane (not Data Plane)

**Constraint.** 20+ ad-hoc schedulers across 4 modules. No central state tracking. Manual recovery
the norm. 3 AM cron batches had no SLA observability. Horizontal scale plans exposed distributed
scheduler duplication risk.

**Alternatives rejected.** Custom orchestration framework (engineering cost). Prefect / Dagster
(greenfield adoption pain). Stick with Spring `@Scheduled` (no cross-service coordination).

**Why Airflow.** Battle-tested DAG orchestration, SLA tracking, alerting, history UI, sensible
deployment story. Already had Docker ecosystem familiarity. The trick: Airflow **triggers and
polls**, Kafka **carries data**. This Control-Plane / Data-Plane separation lets us add Airflow
without disturbing the data path.

**Cost.** Python + Airflow knowledge overhead. Two pipeline problems (Airflow scheduler metadata
DB → cryptominer incident) introduced new attack surface — fixed via ADR-744. Some operations
("trigger now") still mean SSH + curl.

**Why not data-plane Airflow.** Airflow operators push small batches; Kafka topics carry streaming
volume. Wrong tool.

Source: ADR-718 (initial reject), ADR-720 (supersede — adopt as control plane), ADR-726, ADR-734,
ADR-393.

---

### 9. Hexagonal Architecture is kept, not relaxed

**Constraint.** Multi-module monorepo (12 modules). Domain logic must be testable without Spring,
JPA, Kafka, MinIO. Future service extraction must be mechanical, not architectural.

**Alternatives rejected.** "Pragmatic" direct dependencies from controller to JPA repository
(dev velocity in week 1, 6-month refactor in month 6 — the `module-app` 347-file bloat precedent).
Clean architecture with separate `domain/` and `application/` sub-modules (still lets `module-app`
bleed framework code into domain).

**Why Hexagonal strict.** `module-core` is **Spring-free** (only `kotlin-jvm` plugin, no Spring deps
in build.gradle). Verified by `module-common`'s `verifyNoSpringDependency` Gradle task. ArchUnit
tests in `module-web`. The `module-web/build.gradle` comment is loud:
*"ADR-005: Hexagonal Architecture — Port 인터페이스만 의존 … module-infra removed: web layer must not depend on infrastructure"*.

**Cost.** Port proliferation (40+ outbound, 20+ inbound interfaces in `module-core`). Adapter
boilerplate. Initial feature takes ~30 % longer than "just call the repo". Pain worth it: we have
moved the cube/starforce/flame domain logic to `module-core` and back, twice, without touching it.

Source: ADR-041, ADR-317, ADR-353, ADR-347 (DIP violation fix), `verifyNoSpringDependency`,
`.claude/rules/module-boundaries.md`.

---

### 10. PostgreSQL as the single database (ADR-314)

**Constraint.** Operational cost. Cross-DB transactional consistency. t3.small hardware ceiling.

**Alternatives rejected.** Stay with MySQL+Mongo+Redis (3 DBs, 3 failure domains, 3 backup stories).
Move to Mongo-only (loses ACID for write side). Move to DynamoDB / managed NoSQL (vendor lock-in,
$$$).

**Why single PostgreSQL 17.** Postgres 16+ has JSONB, Advisory Lock, LISTEN/NOTIFY, and the PGMQ
extension — collectively replacing all 3 stores' roles. One backup. One monitoring stack. One
connection-pool story (HikariCP). One operator skill set.

**Cost.** PostgreSQL is more CPU-hungry than MySQL on simple key lookups. JSONB write amplification.
PGMQ poll connections share the HikariCP pool (forced PgBouncer introduction in `pgmq-kafka-migration`).

Source: ADR-314, ADR-319, ADR-316, `pgmq-kafka-migration`.

---

### 11. TieredCache (Caffeine L1 + Redis/PG L2 + SingleFlight)

**Constraint.** Cache miss hits Nexon API (200–500 ms cold). Hot keys (top 10% of requests hit 87%
of keys) cause stampede (100 concurrent requests → 100 API calls).

**Alternatives rejected.** Caffeine-only (no cross-instance hit). Redis-only (network RTT 3–5 ms;
serialization 5–10 ms; still stampede-prone without SingleFlight). Memcached (no Lua, harder to
implement single-flight atomically).

**Why the stack.** L1 in-memory < 1 ms (per-instance, may be stale). L2 Redis/PG ~3–5 ms
(cross-instance, fresher). SingleFlight (`ConcurrentHashMap<String, CompletableFuture<*>>`) collapses
N concurrent misses on the same key into 1 loader call.

**Cost.** Three layers to reason about. Stale L1 across instances required PG-NOTIFY invalidation
(ADR-323). Hot key still a problem at scale (currently mitigated by AdmissionControl Semaphore).

Source: ADR-003, ADR-043, ADR-058, ADR-360 (L1 version tag), `.claude/rules/architecture-guardrails.md`.

---

### 12. Virtual Threads + AbortPolicy (no CallerRuns)

**Constraint.** 1 MB per platform thread blocked 1000+ concurrent users. Tomcat 200 threads capped
RPS ~719. P1 incident #168: CallerRunsPolicy caused Tomcat thread exhaustion when ThreadPool
saturated — caller ran the task itself, blocking the Tomcat worker that should serve new requests.

**Alternatives rejected.** WebFlux/Reactor (learning curve, debugging cost). CallerRuns (caused the
P1). More Tomcat threads (memory + context-switch cost).

**Why VT + AbortPolicy.** Java 21 Virtual Threads gives 10,000+ concurrent ops on the same memory
footprint. AbortPolicy surfaces saturation as a 503 (reject fast) instead of cascading into Tomcat
death. Two-Phase Snapshot (data fetch on VT, calculation on ForkJoinPool) splits IO from CPU.

**Cost.** `synchronized` blocks pin carriers — must use `ReentrantLock` (PR #735 learned this the hard
way). All blocking API must be re-audited (`.get()/.join()/runBlocking` banned).

Source: ADR-045, ADR-048, ADR-088, `.claude/rules/async-patterns.md`, `blocking-async-contract`.

---

### 13. `PgmqClient.send()` requires an active transaction

**Constraint.** PGMQ poll-and-publish pattern loses messages if publish happens before commit
(receiver sees the message but the business transaction rolls back). AOP self-invocation hides
`@Transactional` and bypasses this check.

**Alternatives rejected.** Publish-after-commit pattern with listener: complicated, loses ordering,
can drop messages. Idempotent publisher (still has the race).

**Why the runtime assertion.** `TransactionSynchronizationManager.isActualTransactionActive()` raises
`PgmqPublishException` if not active. Catches the bug at runtime instead of in prod (1-2 hour outage).
**The comment in the source is loud**: *"AOP self-invocation / lambda bypass risk"* — humans will be
tempted, the runtime check stops them.

**Cost.** Slight ceremony; developers must understand TX boundaries (which they should anyway).

Source: ADR-316, `PgmqClient.kt`, ADR-389 (request-key dedup).

---

### 14. Inline View Write inside calculation `@Transactional`

See decision #5. Same logic — but worth repeating: we chose **strong consistency** over
**operational decoupling**. Calculation rollback ⇒ view rollback. The view is always in sync with
the calculation that produced it.

---

### 15. MinIO over local filesystem for object storage

**Constraint.** Multi-instance deployments need shared artifact storage. Local disk is per-instance.
Vertical scaling is bounded.

**Alternatives rejected.** Stay on local FS (blocks scale-out, breaks horizontal pipeline).
AWS S3 directly (vendor lock-in, no local-dev story). Pure Kubernetes PVC (no portability).

**Why MinIO.** S3 API compatible (use AWS SDK v2 — no lock-in). Runs locally for dev (`docker-compose`).
Production can be S3/R2/GCS with config flip. The `ObjectStorage` interface in `module-common` lets us
swap. MinIO SA-keys per service account = per-service blast radius for credentials.

**Cost.** Extra service. Two ports (API 9000, console 9001). Bootstrap complexity. Lifecycle-rule cost
scales with object count (motivates the small-file fix in ADR-743).

Source: ADR-719, ADR-725, ADR-728 (key rotation deferred), ADR-743, `MinioObjectStorage.kt`.

---

### 16. Per-module service-account MinIO keys

**Constraint.** Blast radius for credential leak. Auditability (who read what).

**Alternatives rejected.** Single root credential (god-mode; one leak = total compromise). One shared
service account (same problem in disguise). STS / temporary creds (extra infra, complexity).

**Why per-module.** Each app gets its own SA key. Bootstrapped by `docker/minio/bootstrap.sh` (4 keys
+ 4 ILM policies). Mounted as Docker secret at `/run/secrets/sa-<module>`. Rotation = regenerate that
key + redeploy that one module.

**Cost.** 4 secrets to manage, 4 policies to maintain, bootstrap idempotency matters (handled).

Source: `docker-compose.services.yml`, `docker/minio/bootstrap.sh`, ADR-725.

---

### 17. Internal-network-only bind (ADR-744)

**Constraint.** Post-2026-07-03 cryptominer compromise entered through internet-exposed
`5433:5432` with weak superuser password. The same `0.0.0.0` exposure pattern was on nearly every
service.

**Alternatives rejected.** Firewall the host (bypassable; Docker writes iptables directly).
`network_mode: host` for select services (defeats the purpose). VPN-only access (operational pain).

**Why bind to bridge + `127.0.0.1`.** Docker `0.0.0.0` publishes open up through UFW. Bridge-only
means `postgres:5432` is reachable only by services on `maple-network`. Operator UIs (Grafana,
Prometheus) bind to `127.0.0.1` and require SSH tunnel. Only Coolify `80/443` and SSH `22` are
internet-facing.

**Cost.** Operator workflow changed (need SSH tunnel for inspection). Some CI integration required
re-wiring (compose service-name DNS instead of `localhost`).

Source: ADR-744, `docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md`.

---

### 18. Coolify 3-layer self-healing

**Constraint.** Soft failures (process alive, `/health` DOWN) don't auto-recover. Docker restart only
on exit; Coolify v4.x has no K8s liveness probe. 5 infra containers had no healthcheck.

**Alternatives rejected.** K8s (overkill for current scale). Custom watchdog (engineering cost).
Manual ops (does not scale).

**Why 3 layers.** L1 Docker `restart: always` (process exit → restart). L2 Coolify Sentinel
(stopped container → restart). L3 `autoheal` sidecar (health_status=unhealthy → restart). Three
independent mechanisms; the failure of any one is caught by another.

**Cost.** Three restart policies to reason about. Ordering matters: autoheal starts *after* apps are
healthy (else it kills cold-starting apps).

Source: ADR-731, ADR-732, ADR-733, `docs/21_Operations/coolify-setup-guide.md`.

---

### 19. Two-tier Kafka architecture (Control Plane / Data Plane)

See decision #8. Worth repeating: **the architectural framing** matters more than the specific
tool. Airflow controls, Kafka carries. Same separation would hold with Prefect+Kinesis or
Dagster+Kafka.

---

### 20. `PythonOperator` over `HttpOperator` for Airflow triggers

**Constraint.** `HttpOperator.response_check` is unreachable for 4xx — `HttpHook.run()` raises
`AirflowException` before callback runs. 3 daily collection runs failed (06-12, 06-13) after
120 retries × 60s = 2h.

**Alternatives rejected.** `HttpOperator` with custom retry (didn't actually fire). Custom HTTP
sensor (more code).

**Why PythonOperator.** `requests.post()` returns the response object. We control 200/202/409
acceptance. `PythonSensor mode=reschedule` polls until terminal without blocking a worker slot.
`BaseHook.get_connection("external_api")` reads host/port from Connection — not hardcoded.

**Cost.** Python code in DAGs is less declarative than operators. DAG testing is harder.

Source: ADR-726, `docker/airflow/dags/phase_pipeline_factory.py`.

---

### 21. Metric-of-record: `views/sec` (not HTTP RPS)

**Constraint.** Async pipeline — `202 Accepted` is not "done". End-to-end correctness includes the DB
write landing.

**Alternatives rejected.** Stick with HTTP RPS (the 202 = done lie; hides downstream backpressure).
Queue depth as primary metric (reactive, not throughput).

**Why views/sec.** `load-test/run-v5-db-throughput.sh` polls `character_valuation_views.count` every
30 s, computes `delta / dt`. Reports queue depth + active `API_REQUESTED` jobs alongside. Honest
throughput from the user-perspective side.

**Cost.** 30 s sample window = can't see sub-30s regressions. Higher noise floor than RPS.

Source: `load-test/run-v5-db-throughput.sh`, `load_test_v5.py`,
`docs/06_Performance_Journey/13_pipeline_restructuring_supabase.md`.

---

### 22. JSONB + `@JdbcTypeCode(SqlTypes.JSON)` for typed columns

**Constraint.** MongoDB's typed-document ergonomics; need same flexibility in PG.

**Alternatives rejected.** String column with hand-rolled Jackson serialize (lossy; no schema).
`@Type(JsonBinaryType.class)` from Hibernate Types (third-party; less first-party support).

**Why `@JdbcTypeCode(SqlTypes.JSON)`.** First-party Hibernate 6 support. Type-safe at the Kotlin level.
Works with both `@JsonIgnoreProperties(ignoreUnknown = true)` for forward-compat and Jackson
`KotlinModule` for data-class serialization.

**Cost.** Must remember the annotation on every JSONB column. Schema-less migration risk (no DDL
enforcement of JSONB shape — covered by characterization tests, ADR-368).

Source: `.claude/rules/data-access.md`, `CharacterValuationViewEntity.kt`.

---

### 23. `module-rest-controller` re-introduces Redis (against ADR-022)

**Constraint.** V6 read API needs hot cache for popular characters, urgent-dedup, negative cache for
404s, ranking cache. Single-instance Caffeine doesn't share across rest-controller replicas.

**Alternatives rejected.** Caffeine-only (cross-instance duplication). PG-only cache (too slow for
~1ms target). Reintroduce across all modules (scope creep).

**Why scoped Redis.** V6 read path uses Redis only. ETL modules stay Redisson-free. ADR-022 was
*deprecated* (not superseded) — explicitly allowing Redis adoption without a new ADR.

**Cost.** Two memory stores to operate. Documentation must say "Redis only here, not in ETL".

Source: ADR-022 (deprecated), memory `project_adr022_deprecated.md`.

---

### 24. Backpressure as Semaphore + bounded queue (not unbounded async)

**Constraint.** 5 unbounded fan-out sites in ext-api (OcidLookupPhase/SnapshotFetchPhase =
`CompletableFuture.allOf()` × 1000; UrgentCharacterRequestConsumer unbounded; PriorityAdmissionControl
unbounded PriorityBlockingQueue; AdaptiveMicroBatchUserService Channel.UNLIMITED). Nexon HTTP pool
= 150. HikariCP limited.

**Alternatives rejected.** Unlimited async (DOS the upstream + DB). CallerRuns policy (Tomcat
death, P1 #168).

**Why bounded.** Semaphore(100) + `tryAcquire` with backoff for fetchers. Semaphore(30) +
`tryAcquire` fail-fast for urgent consumer. Channel.BUFFERED(200) + `maxInFlight` YAML externalized
for adaptive batch.

**Cost.** Some requests rejected with 503 (5 % budget). Better than service death.

Source: `ADR-backpressure-concurrency-limits.md`, `.claude/rules/async-concurrency.md`.

---

### 25. Async Materialized View (not strict CQRS) — see #5

Same.

---

### 26. Phase-separated Airflow DAGs (ADR-734)

**Constraint.** Single `daily_collection_pipeline` DAG with `branch_on_scope` made workflow intent
opaque — 17 task definitions even when 14 skipped. Manual triggers buried in branch conditions.

**Alternatives rejected.** One DAG per phase × every mode combination (DAG explosion). Bigger single
DAG with more branches (already tried — bad).

**Why 5 single-purpose DAGs.** `ranking_ocid_lookup`, `character_basic`, `item_equipment`,
`daily_full` (retired), `stop_loop`. Each DAG shows only its relevant tasks. Manual trigger = click
DAG → click task. `morning_chain_pipeline` chains them.

**Cost.** More DAG files. Slightly more cross-DAG orchestration logic.

Source: ADR-393, ADR-734, `docker/airflow/dags/`.

---

### 27. Three-rule test pyramid reboot (ADR-368)

**Constraint.** `./gradlew test` was unreliable in CI (random failures, slow). Default = unit;
integration separately.

**Alternatives rejected.** Force all tests into default `test` (slow CI, flake spikes). Drop
integration entirely (lost coverage).

**Why 3 rules.** `module-core`/`module-common` = no Spring, no Testcontainers, JUnit5 + AssertJ +
jqwik. `module-infra` = Testcontainers Singleton, `@DataJpaTest` slices. `module-app` =
`@WebMvcTest` for controllers. Source sets: `test` (unit), `integrationTest`, `chaosTest`,
`nightmareTest`. ADR-061 adds `@Tag("quarantine")` for flaky tests with 2-week expiry.

**Cost.** Tag discipline. Test-discovery chore. 4 separate Gradle tasks to remember.

Source: ADR-368, ADR-061, `.claude/rules/testing-conventions.md`.

---

# Part 2 — Architecture Decision Maps (per ADR)

> The canonical 7-stage decision map. Each ADR walks: **Requirement → Problem → Candidates (A/B/C) →
> Chosen Solution → Trade-offs → Performance Impact → Future Improvement**.

---

## ADR-314 — PostgreSQL as the single database

- **Requirement.** One operational database story on t3.small; cross-write-read ACID; eliminate
  distributed transactions.
- **Problem.** 3-DB setup (MySQL + MongoDB + Redis) costs ops attention, requires XA-style
  consistency, runs over hardware budget.
- **Candidates.**
  - A. **Status quo (MySQL + Mongo + Redis).** Best-of-breed per workload; high ops cost.
  - B. **PostgreSQL 17 + PGMQ + JSONB.** Replace all 3 stores with one. *(chosen)*
  - C. **Managed (RDS + DocumentDB + ElastiCache).** Lower ops; high $$.
- **Chosen.** B. PG 16+ has JSONB (Mongo role), Advisory Lock (Redisson role), LISTEN/NOTIFY (Redis
  Pub/Sub role), PGMQ (Kafka/Redis Streams role). One backup, one monitoring, one pool.
- **Trade-offs.**
  - ✅ Single ops surface; one skill set.
  - ✅ ACID across read+write eliminates Sync Worker (ADR-388).
  - ❌ PG more CPU on simple key lookups than MySQL.
  - ❌ JSONB writes more amplification than Mongo.
  - ❌ PGMQ poll connections share HikariCP → PgBouncer introduced (ADR-pgmq-kafka-migration).
- **Performance impact.** Allowed 7,347 RPS (vs 97 RPS pre-migration) by collapsing 3-5 DB hops to 1.
  Connection pool shrank from 89+ to 25.
- **Future improvement.** If/when PG single-instance capacity is hit → read replicas via
  PgBouncer (already in roadmap via Supabase pooler), or shard by `user_ign` hash.

## ADR-316 — PGMQ integration

- **Requirement.** Same-transaction publish to message queue; durable archive for audit/replay.
- **Problem.** Outbox table + relay polling is complex and adds latency. Kafka requires broker round-trip.
- **Candidates.**
  - A. **Outbox + relay polling (status quo).** Atomic but slow; polling table → publish loop.
  - B. **PGMQ extension.** `pgmq.send()` inside same TX; archive table for replay; visible_at for retry. *(chosen)*
  - C. **Kafka for everything.** Add another broker; loses same-TX guarantee.
- **Chosen.** B. `PgmqClient.send()` enforces `TransactionSynchronizationManager.isActualTransactionActive()`
  at runtime — catches AOP self-invocation bugs before they hit prod.
- **Trade-offs.**
  - ✅ Same-TX publish → no lost messages on rollback.
  - ✅ In-DB archive → trivial audit + replay (`dlq_replay_meta`).
  - ❌ ~8 KB payload cap → split pipeline for >8 KB messages.
  - ❌ Poll connections compete for HikariCP slots.
- **Performance impact.** Enabled 27× PGMQ pipeline optimization (VT pinning removal +
  bulk JDBC upsert + semaphore removal + preset dedup + L2 cache DB removal). Drain 3.3 →
  90 tasks/sec.
- **Future improvement.** `archiveBatch()` uses CTE (single round-trip); expand batch ops for
  `readWithLease()` and `setVisibilityTimeout()`.

## ADR-325 — MongoDB CQRS Read Model → PostgreSQL JSONB

- **Requirement.** V5 read model needs fast typed-document lookups; must be ACID with write side.
- **Problem.** MongoDB sync worker is a separate failure domain; eventual-consistency window between
  write and read; extra ops.
- **Candidates.**
  - A. **Keep MongoDB sync.** Independent scaling; consistent window.
  - B. **PostgreSQL JSONB with `@JdbcTypeCode(SqlTypes.JSON)`.** Typed via Jackson; GIN index. *(chosen)*
  - C. **Materialized view in PG.** Periodic refresh; not real-time.
- **Chosen.** B. `presets JSONB` column + GIN index + `@Version` optimistic lock.
- **Trade-offs.**
  - ✅ ID lookup ~1-2 ms (was ~10 ms Mongo).
  - ✅ ACID eliminates sync worker class.
  - ❌ Schema-less migrations (covered by characterization tests, ADR-368).
  - ❌ No Mongo aggregation pipeline (use Postgres JSONB operators instead).
- **Performance impact.** Net 7,347 RPS after combined with LISTEN/NOTIFY; removed ~42 files of Mongo
  sync code.
- **Future improvement.** If read workload outgrows single PG → add read replica with streaming
  replication (already proven feasible via Supabase pooler).

## ADR-319 — Redis features → PostgreSQL mapping

- **Requirement.** Remove Redis dependency without losing functionality.
- **Problem.** Each Redis role (lock, pub/sub, cache, sorted set, rate limit, streams) needs a PG
  replacement or proof-of-loss.
- **Candidates.**
  - A. **Stay with Redis.** Ops cost; contradicts ADR-022.
  - B. **Map each Redis role to a PG feature.** *(chosen)*
  - C. **Replace with managed equivalent (ElastiCache, etc.).** Cost.
- **Chosen.** B. Redisson lock → `pg_try_advisory_xact_lock`. Pub/Sub → LISTEN/NOTIFY.
  Cache → `cache_storage` JSONB + GIN + TTL. Sorted Set → `sorted_set_store` table. Rate limit
  → `rate_limit_counter` UPSERT. Streams → PGMQ.
- **Trade-offs.**
  - ✅ One less service.
  - ❌ Polling-based notification (vs Redis push).
  - ❌ 8 KB NOTIFY payload cap.
- **Performance impact.** L1+L2 cache hit ratio maintained; cross-instance invalidation ~100 ms
  (vs Redis push ~10 ms).
- **Future improvement.** Currently used Redis in V6 read API (ADR-022 deprecated = Redis allowed).

## ADR-388 — Inline View Write (Async Materialized View)

- **Requirement.** V5 endpoint returns <50 ms p99. Strict CQRS design broken (event never published).
- **Problem.** Original CQRS plan was Spring Event → PGMQ `character-sync` → Consumer → View. Event
  never published. Consumer never built. PGMQ ~8 KB < 95 KB payload.
- **Candidates.**
  - A. **Fix the original CQRS pipeline (Spring Event + consumer).** Build the missing pieces.
  - B. **Inline view write in calc TX (Async Materialized View).** *(chosen)*
  - C. **Read straight from write tables.** Drop CQRS entirely.
- **Chosen.** B. Worker writes `character_valuation_views` row in same `@Transactional` as the
  calculation. `ObjectProvider.getIfAvailable()` for best-effort.
- **Trade-offs.**
  - ✅ Atomicity guaranteed by DB.
  - ✅ No sync consumer class.
  - ❌ Tight coupling calc↔view schema (one change = two tables).
  - ❌ Scaling-out the write path requires explicit denormalisation.
- **Performance impact.** V5 endpoint: 202 (queued) or 200 (cached). p99 ~50 ms.
- **Future improvement.** Async projection via Spring Event + lightweight PGMQ when calc rate
  exceeds single-DB write capacity.

## ADR-720 — Airflow as Control Plane (supersedes ADR-718)

- **Requirement.** Central orchestration across 4 modules. SLA tracking. Cross-service triggering.
- **Problem.** 20+ ad-hoc `@Scheduled` Spring beans. No central state. Manual recovery the norm.
- **Candidates.**
  - A. **Keep ad-hoc Spring `@Scheduled`.** Status quo.
  - B. **Prefect / Dagster.** Modern stack; greenfield adoption cost.
  - C. **Apache Airflow as Control Plane; Kafka stays Data Plane.** *(chosen)*
  - D. **Build custom orchestration.** Engineering cost.
- **Chosen.** C. Airflow triggers and polls (HTTP/sensors); Kafka carries data. ADR-718 was the
  initial reject ("paradigm mismatch"); ADR-720 supersedes with the Control/Data split.
- **Trade-offs.**
  - ✅ DAG UI, SLA, alerts, history.
  - ✅ Python ecosystem (easy custom logic).
  - ❌ Airflow scheduler metadata DB = new attack surface (cryptominer incident).
  - ❌ Python + Java ops surfaces.
- **Performance impact.** Trigger latency ~30 s (sensor poll); not in data hot path.
- **Future improvement.** Sensor → push-based (Kafka offset watermark) instead of poll.

## ADR-729 — ext-api ITEM_EQUIPMENT loop throughput (gzip BEST_SPEED)

- **Requirement.** Close the gap between observed 98 files/s and reference 150-160 files/s.
- **Problem.** Two hotspots: (1) `ChunkedSnapshotSink.runWriterLoop` single-threaded serialize + gzip + write;
  (2) `OcidCacheProvider.loadFromRun` materializes ~600K records on every loop iteration.
- **Candidates.**
  - A. **Increase writer thread count.** Contention; doesn't fix the per-record cost.
  - B. **Producer-side serialization.** Pre-serialize on producer thread; sink does gzip + write only.
  - C. **Lower gzip level (`BEST_SPEED`).** *(chosen for the writer)*
- **Chosen.** PR #1453 = gzip level 6→1. ADR-729 = producer-side serialize + OCID read-through.
- **Trade-offs.**
  - ✅ 3× writer rate (166 → 498 rec/s); 3× lower mean latency.
  - ❌ ~15-20 % larger output (still gzipped; storage is cheap).
- **Performance impact.** Sustained throughput jumped; clears Nexon supply ceiling (~150/s).
- **Future improvement.** 1 → 2–3 writer threads (drain 2–3× → 200–450 users/s sustained).

## ADR-744 — Internal-Network-Only Service Binding

- **Requirement.** No internet-facing service ports after cryptominer incident.
- **Problem.** `0.0.0.0` host publishes bypass UFW (Docker writes `DOCKER` iptables chain).
- **Candidates.**
  - A. **Status quo (`0.0.0.0` for everything).** Pre-incident.
  - B. **Move all services to bridge (`maple-network`) + bind UIs to `127.0.0.1`.** *(chosen)*
  - C. **VPN-only access.** Operational pain.
- **Chosen.** B. Prom/airflow moved off `network_mode: host` onto `maple-network`. App modules,
  postgres, redis, kafka, minio, loki, cadvisor — host port removed. Grafana/Prometheus on
  `127.0.0.1` (SSH tunnel).
- **Trade-offs.**
  - ✅ Only Coolify `80/443` + SSH `22` internet-facing.
  - ❌ Operator workflow changed (SSH tunnel for inspection).
  - ❌ Some CI integration re-wiring.
- **Performance impact.** Negligible (intra-bridge latency same).
- **Future improvement.** mTLS between services for defense-in-depth (current: shared bridge trust).

## ADR-045/048 — Java 21 Virtual Threads + AbortPolicy

- **Requirement.** 1000+ concurrent users on 2 vCPU / 2 GB.
- **Problem.** 1 MB per platform thread; Tomcat 200 cap = 719 RPS. P1 #168 CallerRunsPolicy death.
- **Candidates.**
  - A. **WebFlux / Reactor.** Learning curve + debugging.
  - B. **More Tomcat threads.** Memory + context-switch cost.
  - C. **Java 21 Virtual Threads + AbortPolicy + Two-Phase Snapshot.** *(chosen)*
- **Chosen.** C. VT for IO-bound; ForkJoinPool for CPU calc; AbortPolicy rejects fast on saturation.
- **Trade-offs.**
  - ✅ 240 RPS single-instance t3.small target (achieved 7,347 with PG migration).
  - ❌ `synchronized` pins carriers (must use `ReentrantLock`).
  - ❌ All blocking API audit (`.get()/.join()/runBlocking` banned).
- **Performance impact.** Foundation for the entire 76× journey.
- **Future improvement.** Scoped values (JEP 446) for MDC replacement.

## ADR-022 (Deprecated) — Redis dependency removal

- **Requirement.** One ops story; minimal service surface.
- **Problem.** Redis added an extra service; 3-DB vision was to remove it.
- **Candidates.**
  - A. **Full Redis removal.** *(originally chosen)*
  - B. **Scoped Redis (V6 read path only).** *(superseded intent; Redis re-allowed)*
- **Chosen.** Originally A; deprecated 2026-06-09 because `module-rest-controller` still uses Redis
  live and would benefit from Redis L2 if calculator scales out. No supersede ADR per explicit user
  choice.
- **Trade-offs.**
  - ✅ ETL modules stay Redisson-free.
  - ❌ V6 read path depends on Redis; new engineer needs to know "Redis here, not there".
- **Performance impact.** None (Redis path unchanged).
- **Future improvement.** V6 read path can move to PG-only if Redis is no longer the bottleneck.

## ADR-036 → ADR-388 — CQRS evolution (MongoDB read side → Async Materialized View)

- **Requirement.** V5 read latency <50 ms; cross-instance consistency.
- **Problem.** V4 read latency 500 ms–30 s (write-table + multi-hop). Strict CQRS design broken.
- **Candidates.**
  - A. **Strict CQRS with MongoDB read side (ADR-036).** Independent scale; eventual consistency.
  - B. **Async Materialized View with PostgreSQL JSONB (ADR-388).** *(chosen)*
  - C. **No CQRS — read from write tables.** Simpler; slower.
- **Chosen.** B after A was rejected (Mongo removal). ADR-388 supersedes ADR-036/037/038/V5.
- **Trade-offs.** (see ADR-388 above)
- **Performance impact.** V5 endpoint: <50 ms p99.
- **Future improvement.** Spring Event + lightweight PGMQ projection at scale-out.

## ADR-058 — Caffeine L1 cache

- **Requirement.** Sub-millisecond local cache; 1000+ RPS target.
- **Problem.** No eviction in ConcurrentHashMap; Guava cache less optimal hit rate; Ehcache heavier.
- **Candidates.**
  - A. **ConcurrentHashMap.** No eviction.
  - B. **Guava cache.** First-gen W-TinyLFU implementation.
  - C. **Caffeine 3.1.8 (better W-TinyLFU + benchmarks).** *(chosen)*
- **Chosen.** C. Better hit rate via Window-TinyLFU. 1,000+ RPS with <1 ms local hit.
- **Trade-offs.** ✅ Best hit rate in benchmarks. ❌ Extra dependency (already in Spring Boot).
- **Performance impact.** L1 hit <1 ms (was 5-10 ms Redis-only).
- **Future improvement.** Native image (GraalVM) for cold-start.

## ADR-043 — TieredCache (Caffeine L1 + Redis L2 + SingleFlight)

- **Requirement.** Cross-instance cache; no stampede; <5 ms for L2.
- **Problem.** Cache miss → N concurrent requests → N API calls (stampede).
- **Candidates.**
  - A. **Caffeine only.** No cross-instance.
  - B. **Redis only.** Network RTT + stampede.
  - C. **L1 Caffeine + L2 Redis + SingleFlight in-memory.** *(chosen)*
- **Chosen.** C. Write order L2 → L1 for atomicity. SingleFlight via
  `ConcurrentHashMap<String, CompletableFuture<*>>`.
- **Trade-offs.** ✅ Best hit ratio + no stampede. ❌ Three layers to reason about. ❌ Hot key
  (popular character) still a problem at scale.
- **Performance impact.** p99 2,340 ms → 180 ms post-PR-#160.
- **Future improvement.** Distributed SingleFlight via PostgreSQL Advisory Lock + PGMQ (ADR-322,
  not yet implemented at scale).

## ADR-723 — IO/CPU split pattern

- **Requirement.** CPU-bound tasks not blocking VT carriers.
- **Problem.** 4 modules running IO and CPU on same VT carrier; gzip/JSON parse/SHA-256 blocking carrier.
- **Candidates.**
  - A. **All on VT.** Carrier pinning.
  - B. **VT for IO + Dispatchers.Default for CPU.** *(chosen)*
- **Chosen.** B. Algorithm-based classification: JSON parse/serialize, GZIP, hash, large collection
  transforms, math = CPU-bound.
- **Trade-offs.** ✅ No carrier pinning. ❌ Convention must be enforced in code review.
- **Performance impact.** 3.5× regression recovered from VT+Semaphore(64) on CPU path.
- **Future improvement.** Per-module dedicated CPU executor when `Dispatchers.Default` saturates
  (ADR-724, contingency).

## ADR-718 → ADR-720 (reversal) — Airflow evaluation

- **Requirement.** (Original) Evaluate Airflow.
- **Problem.** Pipeline was real-time event streaming; Airflow = batch orchestration paradigm.
- **Candidates.**
  - A. **Reject Airflow.** *(chosen initially)*
  - B. **Adopt Airflow with Control/Data split.** *(supersedes 4 weeks later)*
- **Chosen.** A initially (ADR-718, "paradigm mismatch"). Then B (ADR-720, "20+ schedulers unmanageable").
- **Trade-offs.** Initial reject was wrong; supersession came from operational pressure, not technical
  re-evaluation. Lesson: ADR supersession is healthy; document the reversal reason.
- **Performance impact.** None (data path unchanged).
- **Future improvement.** n/a

## ADR-393 — Per-Phase Airflow DAG

- **Requirement.** Airflow trigger for individual phases (not just full daily).
- **Problem.** Single `daily_collection_pipeline` made per-phase intent opaque.
- **Candidates.**
  - A. **Single DAG with `branch_on_scope`.** *(chosen initially)*
  - B. **Per-phase DAGs (5 of them) + chain DAG.** *(supersedes; ADR-734)*
- **Chosen.** A → B (ADR-734 supersedes with 5 single-purpose DAGs).
- **Trade-offs.** (see ADR-734)
- **Performance impact.** None (control plane).
- **Future improvement.** DAG versioning and promotion env (dev → prod).

## ADR-726 — Airflow trigger task design

- **Requirement.** Reliable trigger from Airflow to external-api.
- **Problem.** `HttpOperator.response_check` unreachable for 4xx.
- **Candidates.**
  - A. **HttpOperator with custom retry.** Doesn't fire.
  - B. **PythonOperator + `requests.post()` + PythonSensor.** *(chosen)*
- **Chosen.** B. Accept 200/202/409. `BaseHook.get_connection("external_api")` for host/port.
- **Trade-offs.** ✅ Reliable. ❌ Less declarative DAG code.
- **Performance impact.** None.
- **Future improvement.** Sensor → push-based (Kafka offset).

## ADR-042 — V2/V4 dual-generation architecture

- **Requirement.** Migrate V2 API → V4 without downtime.
- **Problem.** Cutover would lose legacy clients.
- **Candidates.**
  - A. **Big-bang cutover.** Risky.
  - B. **Dual-generation (V2 + V4 side-by-side).** *(chosen)*
- **Chosen.** B. Both API versions deployed; traffic gradually shifts; V2 deprecated after cutover
  completes.
- **Trade-offs.** ✅ Zero downtime. ❌ Two code paths to maintain. ❌ DTO duplication.
- **Performance impact.** None (parallel paths).
- **Future improvement.** n/a (cutover complete; legacy module-app remains for V4 only).

## ADR-317 — Hexagonal architecture adoption

- **Requirement.** Testable domain; future service extraction.
- **Problem.** `module-app` 347 files with 45-55% domain logic coupled to Spring/JPA.
- **Candidates.**
  - A. **Clean Architecture with separate domain/app sub-modules.** Less strict.
  - B. **Hexagonal with `module-core` Spring-free.** *(chosen)*
- **Chosen.** B. Plus `module-web` excludes `module-infra` dependency.
- **Trade-offs.** (see rationale #9)
- **Performance impact.** None.
- **Future improvement.** Extract `module-core` further per bounded context if service extraction needed.

## ADR-341/340 — MySQL/MongoDB driver removal

- **Requirement.** Single-DB strategy requires driver removal.
- **Problem.** Drivers in classpath add startup time + attack surface.
- **Candidates.**
  - A. **Keep drivers + conditional config.** *(interim)*
  - B. **Full removal.** *(chosen)*
- **Chosen.** B. Configuration switches MySQL/Mongo → PostgreSQL datasources.
- **Trade-offs.** ✅ Cleaner classpath. ❌ Harder rollback.
- **Performance impact.** Faster startup (~50 ms).
- **Future improvement.** n/a (one-way door).

---

# Part 3 — Predicted Interview Q&A

> What an interviewer would ask the maintainer of this system. Model answers cite code paths and ADR numbers.
> 8 categories × 4 questions each = 32 Q&A.

---

## A. System Design

**Q1. Walk me through how a single character request flows from the HTTP call to a calculated expectation.**

**A.** `module-rest-controller` (`ExpectationV6Controller.kt:8080`) receives GET `/api/v5/characters/{ign}/expectation`.
TieredCache lookup (Caffeine L1 → Redis L2 → PostgreSQL `character_valuation_views`). HIT = return 200.
MISS = enqueue `calculation_jobs` row (status `API_REQUESTED`) + PGMQ `expectation_calc_high` (in V5 cold path).
External-api phase chain (`ExternalApiScheduler.kt`) triggered by Airflow or directly:
`OCID_LOOKUP` → `CHARACTER_BASIC` → `ITEM_EQUIPMENT`. Each phase writes gzip JSONL chunks to MinIO and
emits `SnapshotChunkReadyEvent` to Kafka topic `external-api.snapshot.chunk-ready`. Calculator consumes,
runs DP cube/starforce via `CalculationCache` (off-heap), writes results to `calculator/runs/...jsonl.gz`,
emits `CalculatorResultChunkReadyEvent`. Synchronizer consumes, JDBC bulk-upserts into
`character_valuation_views` (inline in TX, ADR-388), emits `ChunkConsumedEvent`. Cleanup GC the MinIO
artifacts. Worker reads its result from the precomputed view; returns 200.

**Q2. Why split into 5 services instead of one big monolith?**

**A.** Independent horizontal scaling (calculator needs CPU; rest-controller needs IO). Independent
deployment cadence (calculator perf fixes don't risk rest-controller availability). Hexagonal architecture
(`module-core` Spring-free) makes the split mechanical — service modules pull `core+infra`, controllers
pull `core+web`. Memory ceiling (V4 monolith 89+ connections exhausted HikariCP). Source boundaries
align with `module-common`'s `verifyNoSpringDependency` enforcement.

**Q3. How do you ensure data consistency between calculation and view?**

**A.** Inline view-write in same `@Transactional` (ADR-388). Calculation rollback ⇒ view rollback.
`CharacterValuationViewEntity.@Version` adds optimistic-lock safety net. `pgmq_send_if_absent()` +
expression index on `userIgn` (V112) prevents duplicate enqueue. PGMQ's same-TX publish (ADR-316)
guarantees that an enqueued job means the business TX committed.

**Q4. What's your failure-detection strategy?**

**A.** Three layers: (1) application metrics → Prometheus → Alert rules (`alert_rules.yml`,
`lock-alerts.yml`, `offheap-alerts.yml`); (2) structured JSON logs → Loki via Promtail, MDC
`runId`/`chunkId` correlation; (3) chaos tests (`module-chaos-test`) run nightly in GitHub Actions
covering 19 nightmare scenarios (N01-N19). Plus Airflow sensors polling ext-api `/api/internal/run-status`
for stuck phases. Known gap: alertmanager is configured but not running (ADR-733 §3 risk).

---

## B. Kafka

**Q5. Why Kafka and not PGMQ for the ETL pipeline?**

**A.** ETL chunks are ~218 KB gzipped — exceeds PGMQ's ~8 KB payload cap. Kafka partitioning lets
us scale consumers horizontally per `runId` if needed. Consumer Groups give automatic rebalancing on
worker death. Retention policy decoupled from consumer liveness (lost consumer ≠ lost message).
PGMQ stayed for the calculation queue (`expectation_calc_high/low`) because that workload is
same-TX-friendly and small-payload. See ADR-316, ADR-355, ADR-013.

**Q6. How do you guarantee message delivery in the ETL path?**

**A.** Outbox not needed for ETL: messages carry metadata only (payload is in MinIO via Claim Check
pattern). Idempotency on the consumer side: `CalculatorChunkProcessingCoordinator.waitForSourceChunk`
polls MinIO `exists()` and republishes existing results on duplicate delivery. `ChunkConsumerTemplate`
state machine (`ChunkExecutionStatus`) is idempotent on Kafka redelivery. Cleanup's
`StaleKafkaSkipService` rewinds offsets by runId without committing (safe to run while live consumer
active).

**Q7. What's your Kafka topic strategy?**

**A.** Per-stage topics with clear producer/consumer mapping (table in §5.1 of SYSTEM_KNOWLEDGE_BASE.md):
- `external-api.snapshot.chunk-ready` (chunk-level)
- `calculator.result.chunk-ready` (result-chunk-level)
- `synchronizer.chunk.consumed` (cleanup signal)

Kafka key = `$runId:$endpoint:$chunkId` for ordered processing per chunk. Partitions = 3 default
(tunable). Separate consumer groups per service per topic (no cross-service group reuse).

**Q8. How do you handle backpressure on Kafka consumers?**

**A.** Each consumer runs on a bounded virtual-thread pool (size = corePoolSize from YAML). Per-batch
size limit (PGMQ-style `batchSize=50`). Manual ack after DB write. If consumer lags → Prometheus alert
on `kafka_consumer_lag`. We don't auto-scale consumers (K8s not adopted) — instead, scale-up is
operational decision based on lag + CPU metrics.

---

## C. PostgreSQL

**Q9. Why a single PostgreSQL instance instead of separate stores for each workload?**

**A.** PostgreSQL 17 + extensions replaces all 3 (MySQL + Mongo + Redis) on t3.small hardware.
JSONB replaces Mongo aggregation. Advisory Lock replaces Redisson. LISTEN/NOTIFY replaces Redis Pub/Sub.
PGMQ replaces Redis Streams. One backup, one monitoring, one ops skill set. ADR-314,
ADR-325/319/340/341. Connection-pool collapse: 89+ → 25. Read throughput 97 → 7,347 RPS.

**Q10. How do you handle JSONB columns in JPA?**

**A.** `@JdbcTypeCode(SqlTypes.JSON)` for typed columns. `@Column(columnDefinition = "jsonb")`.
`@JsonIgnoreProperties(ignoreUnknown = true)` on deserialization targets for forward-compat. Kotlin
data classes work via Jackson KotlinModule (Spring Boot autoconfigured). For string-typed payloads
(store as text but cast to JSONB), use `@JdbcTypeCode(SqlTypes.JSON) val x: String?`. Source:
`CharacterValuationViewEntity.kt`, `.claude/rules/data-access.md`.

**Q11. How does PGMQ give same-transaction publish?**

**A.** `pgmq.send(queue_name, message::jsonb)` is a SQL function — runs inside the calling
transaction. `PgmqClient.send()` enforces `TransactionSynchronizationManager.isActualTransactionActive()`
at runtime; raises `PgmqPublishException` if not. This catches the AOP self-invocation bug (calling
`@Transactional` method from same class) at runtime instead of in prod. Comment in `PgmqClient.kt` is
explicit: *"AOP self-invocation / lambda bypass risk"*.

**Q12. What's your connection pool sizing rationale?**

**A.** HikariCP must equal or exceed Tomcat threads (formula: `(CPU cores × 2) + effective_disk_count`,
t3.small gives 9). CI: 200. Local: 100. Prod: 25 (matches Tomcat). After PGMQ unification, outbox
schedulers reclaimed ~7-11 connections/instance (`13_Connection_Pool_Journey/07_pgmq_unification.md`).
PR #572 implemented the alignment. Prometheus alerts on `hikaricp_connections_pending > 0` sustained 5min.

---

## D. CQRS

**Q13. Is this true CQRS?**

**A.** No. It's "Async Materialized View" (ADR-388). Read model is updated **inline in the calculation
transaction** (same `@Transactional`), not via a separate sync consumer. Strong consistency by
construction. The original strict CQRS plan (Spring Event → PGMQ `character-sync` → Consumer → View)
was abandoned because (a) the event was never published, (b) the consumer was never built, (c) PGMQ
~8 KB payload cap < 95 KB `EquipmentExpectationResponseV4`.

**Q14. Why not use Kafka for view sync?**

**A.** Three reasons: (a) eventual-consistency window unacceptable for V5 endpoint's p99 <50 ms target;
(b) extra consumer class to maintain; (c) atomicity guarantee requires outbox+relay, which is exactly
what we removed in PGMQ unification. ADR-388 trade-off: tight coupling calc↔view schema (one schema
change = two tables in same TX). Future fix: Spring Event + lightweight PGMQ for projection at scale-out.

**Q15. How do you handle cache invalidation across instances?**

**A.** PostgreSQL `LISTEN/NOTIFY` (ADR-323). `PostgresNotifyPublisher.kt` runs `NOTIFY cache_invalidation, '<json>'`
on cache write. `PostgresNotifySubscriber.kt` runs `LISTEN cache_invalidation` on a dedicated
connection, polls notifications (100 ms), calls `tieredCacheManager.getL1CacheDirect(cacheName).evict(...)`.
Self-skip guard (instance-id matching) prevents self-invalidation. Reconnect loop on connection death.
Trade-off: ~100 ms latency vs Redis push ~10 ms.

**Q16. What's the optimistic-locking strategy on the view?**

**A.** `CharacterValuationViewEntity.@Version` → Hibernate-managed. `CharacterViewQueryServicePostgres.batchUpsertFromCalculations()`
uses `ON CONFLICT (message_id) DO UPDATE SET … WHERE … IS DISTINCT FROM` for skip-unchanged.
`Event version` + `last_applied_version` columns enable causal consistency. `document_hash` column
on `character_equipment_read_model` for the same purpose.

---

## E. Object Storage

**Q17. Why MinIO and not S3 directly?**

**A.** S3 API compatible (AWS SDK v2 = no lock-in). Runs locally for dev (`docker-compose`).
Production can be S3/R2/GCS with config flip. The `ObjectStorage` interface in `module-common`
lets us swap without code change. MinIO SA-keys per service account = per-service blast radius.
`MinioObjectStorage.kt` uses `S3TransferManager` for async multipart uploads. ADR-719, ADR-725.

**Q18. How do you handle large gzipped chunks?**

**A.** Temp-file upload pattern (ADR-730): stream gzip output to `Files.createTempFile(...)` →
`objectStorage.putFileAsync(chunkKey, tempFile)` → S3TransferManager handles multipart on its own
thread pool → temp deleted only on upload success. Replaced earlier `PipedInputStream`/`PipedOutputStream`
that produced **0-row gzip files** (`IOException: Read end dead` + `Deflater has been closed`) — see
`bug_calculator_empty_results.md`.

**Q19. What's your key-naming convention?**

**A.** Per-run path: `runs/{runId}/{endpoint}/chunks/part-NNNNNN.jsonl.gz` (+ `manifest.json`,
`_SUCCESS`, `_RUNNING`, `failed.jsonl`). OCID mapping: `runs/{runId}/ocid-lookup-mapping-{runId}.jsonl.gz`.
Calculator results: `calculator/runs/{runId}/{endpoint}/chunks/result-{chunkId}.jsonl.gz`. ILM 2-day expiry
on `snapshots/`, `runs/`, `calculator/`, `ocid-mapping/`. Cleanup (`RunCleanupService.deleteByPrefix`)
runs hourly.

**Q20. What's the small-file problem and what's the fix?**

**A.** ~1.2K snapshot + 1.2K result files per run (each ~218 KB). MinIO LIST API latency scales with
file count. Lifecycle-rule evaluation cost scales with object count. Blocks Iceberg/Parquet adoption.
**Fix (ADR-743, not yet implemented):** Flush-time rollup — N consecutive chunks → 1 merged file +
1 Kafka event. 10-100× file-count reduction. Documented as future improvement.

---

## F. Airflow

**Q21. Why Airflow and not a custom orchestrator?**

**A.** Battle-tested DAG orchestration, SLA tracking, alerting, history UI, sensible Docker deploy
story. Already had Docker ecosystem familiarity. **The framing** (ADR-720): Airflow = Control Plane
(trigger, poll, SLA, alert, history); Kafka = Data Plane (unchanged). This lets us add Airflow
without disturbing the data path.

**Q22. Why per-phase DAGs (5 of them)?**

**A.** Single `daily_collection_pipeline` DAG with `branch_on_scope` made workflow intent opaque —
17 task definitions even when 14 skipped (ADR-393, ADR-734). Per-phase DAGs show only relevant tasks.
Manual trigger = click DAG → click task. `morning_chain_pipeline` chains them via `TriggerDagRunOperator`.

**Q23. What's the loop-death bug and how did you fix it?**

**A.** ITEM_EQUIPMENT infinite loop reads `latestUpstreamRunId = getLastCompletedForPhase(OCID_LOOKUP)?.runId`.
Daily 03:00 KST morning_chain refreshes OCID_LOOKUP, slot non-terminal → `latestUpstreamRunId = null` →
`require(upstreamRunId != null)` throws → catch block treats as fatal → status=STOPPING → loop dies.
Real observed: iter=274 → "ITEM_EQUIPMENT requires upstreamRunId" → 273 normal iterations then death.
Fix (ADR-742): catch block distinguishes `upstream == null` → backoff + re-submit vs genuine failure →
existing fatal path. Backoff via `external-api.loop.upstream-retry-interval-seconds` (default 30s, YAML).

**Q24. How do you trigger Airflow tasks reliably?**

**A.** `PythonOperator` with `requests.post()` (ADR-726). `HttpOperator.response_check` is unreachable
for 4xx — `HttpHook.run()` raises before callback runs. Accept 200/202/409 as success. `PythonSensor
mode=reschedule` polls without blocking worker slot. `BaseHook.get_connection("external_api")` for
host/port from Connection, not hardcoded.

---

## G. Incident Response

**Q25. Walk me through the 2026-07-03 airflow-db compromise.**

**A.** Internet-exposed `5433:5432` on public-IP host → weak superuser `airflow:airflow` → `COPY …
TO PROGRAM` (superuser shell exec) → `/tmp/kunt` dropper (wget+curl fallback) → fetches `91.188.254.59/bot`
→ drops `/tmp/postgresql` (2 MB ELF, section-stripped) + `/tmp/systemd` (2.7 MB Rust/tokio watchdog)
→ mining pool connections `5.255.106.100:44999` and `5.255.115.190:48996`. **MTTD 18-24h dominated
by a wrong "parallel-worker leak" diagnosis** (the first PR was `max_parallel_workers=0`). Real
diagnosis came from `/proc/<pid>/exe` pointing at `/tmp/postgresql`. Fix: bind `127.0.0.1:5433` +
strong password + nuke volume. Then ADR-744 removed all `0.0.0.0` host publishes.

**Q26. What lessons did you learn from incident response?**

**A.** (a) Misdiagnosis is worse than slow diagnosis — `/etc/cron.hourly/free` (legitimate cache-flush)
briefly mis-flagged as malware. (b) Observability is a prerequisite for debugging — log retention
had to be raised 30m → 500m (ADR-741) *before* the loop-death bug became diagnosable. (c) AI-generated
defaults (`0.0.0.0` binds, `admin/admin`, `network_mode: host`) ship without security review.
(d) Realistic fault injection (`safeDeleteKey` not `FLUSHALL`). (e) Verification before concluding
(read the file, check `/proc/<pid>/exe`).

**Q27. How do you prevent data loss?**

**A.** Transactional outbox + idempotency + DLQ as triple safety net (N17/N19). Reconciliation key
+ invariant (atomic upsert with monotonic guard V112). `pgmq_send_if_absent()` prevents TOCTOU race
in dedup. PGMQ's same-TX publish guarantees enqueue ↔ business commit. Outbox events have
`publish_attempts` + `published` columns; relay polls every 1s (when consolidated pipeline disabled).
Recovery via `dlq_replay_meta` (replay_count < 3 partial index).

**Q28. What's your on-call playbook?**

**A.** NIST-based lifecycle: detect → contain → eradicate → recover → lessons (`docs/16_Guardrails/security/incident-response-playbook.md`).
Evidence collection script `scripts/security/collect-evidence.sh` captures app logs, container state,
network. **DON'Ts**: delay reporting, delete logs, skip RCA, no prevention plan. **DO**: preserve
evidence *before* cleanup, mandatory RCA + action items. Recovery actions: IDLE gate (`calculation_jobs`
non-terminal = 0) before deploy; port-bind check; SA-secret presence; rollback jars present.

---

## H. Performance Optimization

**Q29. What's the most impactful performance fix you've shipped?**

**A.** gzip level 6→1 on ext-api writer (PR #1453 / ADR-729). Writer thread was ~60% single-core
CPU-bound on gzip of 218 KB item-equipment payloads. Empirically 3.0× rate (166 → 498 rec/s), 3×
lower mean latency, ~15-20 % larger output. This unlocked clearing the Nexon supply ceiling
(~150/s). The original misdiagnosis was "writer 71/s + sequential PUT" which was mathematically
impossible (80h avg 132/s) — the real bottleneck was identified via StepTrace + endurance report.

**Q30. How did you go from 97 RPS to 7,347 RPS?**

**A.** A 13-chapter journey (docs/06_Performance_Journey):
1. L1 Caffeine fast path (97 → 555 RPS) — fix SingleFlight that acquired Semaphore before cache lookup
2. Write-behind buffer (555 → 674)
3. Parallel preset calculation (674 → 965)
4. V5 stateless trade-off (965 → 325, accepted for cross-instance consistency)
5. Auto-warmup (325 → 940)
6. Great Migration 3 DBs → 1 PG (940 → 7,347)
7. Micro-batching (3-5 DB round-trips → 1)
8. PostgreSQL LISTEN/NOTIFY for cross-instance cache invalidation
9. Fan-out admission control (Nexon 230 RPS ceiling protection)
Plus the PGMQ pipeline 27× in Apr 2026 (VT pinning + bulk JDBC + semaphore removal + preset dedup).

**Q31. What's the current performance ceiling?**

**A.** Sustained 100-150 users/sec on the pipeline (lifetime avg 137, max burst 651). Bottleneck =
single `ChunkedSnapshotSink.runWriterLoop` thread (`ENDURANCE_THROUGHPUT_CEILING_20260702.md`).
Recommended fix: 1 → 2-3 writer threads. Drain rate 2-3× → expected 200-450 users/sec sustained.
Other ceilings: Nexon API 230 RPS on cache miss (server cap 500 req/s; OCID_LOOKUP measured 397/s);
PGMQ unification reclaimed 7-11 HikariCP connections/instance; Supabase Pooler 1-7.5s hop latency
on `loadCalculationResults` is the new ceiling in pipeline era.

**Q32. How do you measure performance?**

**A.** Two metrics by era. Pre-May 2026: HTTP RPS via wrk (not Locust — GIL bottleneck). From May 2026:
**views/sec** (rows written to `character_valuation_views` per second, measured by DB delta in
`load-test/run-v5-db-throughput.sh` + `load_test_v5.py`). `load_test_v5.py` spawns Prometheus
poller + `ThreadPoolExecutor` request sender; computes p50/p95/p99/min/max/avg. Reports include
queue depths (`pgmq.q_external_api_queue`, `pgmq.q_result_ready_queue`, `pgmq.q_expectation_calc_high`)
+ active `API_REQUESTED` count. Honest throughput from user-perspective side — `202 Accepted` is
NOT done.

---

## End of Document

*Generated as the second companion to SYSTEM_KNOWLEDGE_BASE.md on 2026-07-06. Total scope: 27
decision rationales, 21 ADR decision maps, 32 interview Q&A across 8 categories. All citations
resolve to ADRs, code paths, and reports cited in the master KB.*