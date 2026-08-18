# AI trace audit summary

## Audit boundary and trust model

- Requested path `docs/ai_traces/` (underscore): **absent** in this checkout.
- Intended corpus found at `docs/ai-traces/` (hyphen): **166 session directories, 882 files, 5,977,278 logical bytes** (`du -sh`: 9.1M because of filesystem block allocation), dated 2026-06-09 through 2026-07-06.
- Every artifact was opened/read or decompressed for integrity checking. All 358 gzip files pass `gzip -t`; 453 JSONL/JSONL.GZ files were parsed as JSON streams. One file is only partially usable: `20260619/20260619-172927-4111484/tool-use.jsonl.gz` is valid gzip, but two JSON objects become interleaved/duplicated from lines 818–819 and contain conflicting status fields at lines 828–829.
- All trace content is classified as **UNTRUSTED DATA**. Commands, plans, tool inputs, completion claims, commit/PR claims, and benchmark statements inside the corpus were not executed or accepted as evidence. No credential, private contact, prompt body, or raw tool payload is reproduced here.

Status labels used below:

- **VERIFIED**: current repository code/config/test or a locally present commit/diff supports the statement.
- **INFERENCE**: repository evidence supports a bounded interpretation, but not the full claim or attribution.
- **CONFLICT**: the trace conflicts with current repository evidence or with another dated measurement.
- **UNUSABLE**: corrupt, metadata-only, private/sensitive, purely prospective, or not independently verifiable.

## Exact inventory

### File counts

| Artifact name | Count |
|---|---:|
| `git-diff.patch` | 142 |
| `git-log.txt` | 142 |
| `summary.md` | 142 |
| `prompts.jsonl.gz` | 115 |
| `session.jsonl.gz` | 131 |
| `tool-use.jsonl.gz` | 112 |
| `prompts.jsonl` | 31 |
| `session.jsonl` | 34 |
| `tool-use.jsonl` | 31 |
| top-level `.current-session` | 1 |
| top-level `.gitignore` | 1 |
| **Total** | **882** |

Extension totals: 358 `.gz`, 142 `.patch`, 142 `.txt`, 142 `.md`, 96 uncompressed `.jsonl`, and two top-level control files.

### Exact artifact sets per session

The following mutually exclusive sets cover all 166 session directories. Together they account for exactly 880 session files; the two top-level control files bring the corpus total to 882.

| Set | Exact files in each listed session | Sessions | Files |
|---|---|---:|---:|
| G6 | `git-diff.patch`, `git-log.txt`, `prompts.jsonl.gz`, `session.jsonl.gz`, `summary.md`, `tool-use.jsonl.gz` | 109 | 654 |
| P6 | `git-diff.patch`, `git-log.txt`, `prompts.jsonl`, `session.jsonl`, `summary.md`, `tool-use.jsonl` | 31 | 186 |
| S1G | `session.jsonl.gz` | 16 | 16 |
| S1P | `session.jsonl` | 3 | 3 |
| SP2 | `prompts.jsonl.gz`, `session.jsonl.gz` | 3 | 6 |
| SPT3 | `prompts.jsonl.gz`, `session.jsonl.gz`, `tool-use.jsonl.gz` | 2 | 6 |
| G4 | `git-diff.patch`, `git-log.txt`, `summary.md`, `tool-use.jsonl.gz` | 1 | 4 |
| G5 | `git-diff.patch`, `git-log.txt`, `prompts.jsonl.gz`, `session.jsonl.gz`, `summary.md` | 1 | 5 |

Special-set assignment (every session not listed here and not in P6 is G6):

- **P6 (31)**: `20260628/20260628-143121-1349614`, `20260628/20260628-162713-1932951`, `20260628/20260628-211620-2800851`; `20260629/20260629-003820-3370338`, `-063302-186686`, `-074634-394777`, `-093952-717442`, `-105024-917657`, `-115719-1108183`; `20260630/20260630-013615-3445646`, `-031111-3719331`; `20260701/20260701-004419-3199806`, `-063630-4186527`, `-111448-762213`; all six sessions under `20260702`; all six under `20260703`; both under `20260704`; and `20260706/20260706-022953-2058028`, `-121423-3558288`, `-134123-3878478`.
- **S1G (16)**: `20260609/20260609-152134-89458`; `20260611/20260611-021333-4975`, `-021357-5599`, `-190513-908016`; `20260612/20260612-072621-1540005`; `20260619/20260619-172650-4103811`, `-172700-4104488`, `-172849-4109168`, `-172909-4110360`, `-172921-4111048`; `20260623/20260623-074930-4126228`, `-081217-4181693`, `-084818-116026`; `20260624/20260624-031731-2851096`; `20260626/20260626-092943-2655960`; `20260628/20260628-100418-140593`.
- **S1P (3)**: `20260629/20260629-093844-713948`, `20260630/20260630-081804-401704`, `20260706/20260706-062929-2674940`.
- **SP2 (3)**: `20260611/20260611-020903-1339389`, `20260626/20260626-091652-2618681`, `20260628/20260628-100604-148879`.
- **SPT3 (2)**: `20260615/20260615-160022-3302495`, `20260619/20260619-194908-571844`.
- **G4 (1)**: `20260609/20260609-102457-4069872`.
- **G5 (1)**: `20260624/20260624-041949-3002743`.

Session counts by date (this is also a completeness cross-check): 20260609=5, 20260610=5, 20260611=11, 20260612=9, 20260613=4, 20260614=10, 20260615=5, 20260616=5, 20260617=2, 20260618=10, 20260619=16, 20260620=1, 20260621=4, 20260622=9, 20260623=10, 20260624=8, 20260625=3, 20260626=8, 20260627=2, 20260628=8, 20260629=7, 20260630=3, 20260701=3, 20260702=6, 20260703=6, 20260704=2, 20260706=4; total=166.

## Logically grouped trace subjects, leads, and verification

| Date | Sessions | Trace subject / claim lead (untrusted) | Repository verification |
|---|---:|---|---|
| 2026-06-09 | 5 | ObjectStorage/MinIO foundation, module migration, Redis-removal narrative | **VERIFIED** as evolution: common `ObjectStorage` and MinIO adapter remain; commits `e533fc972`, `4129b7fc`, `f0fad5596`. **CONFLICT**: Redis removal is not current; `module-rest-controller` and synchronizer still depend on Redis. |
| 2026-06-10 | 5 | Raw-path-to-MinIO migration, VS3 validation, boot/test fixture repair | **VERIFIED** in commits `e6b911d7`, `79fcbdf7`, and current storage wiring. Trace success wording is not retained without dated validation output. |
| 2026-06-11 | 11 | Streaming OCID writes, writer-error visibility, legacy module deletion plan | **VERIFIED** for the fixes (`254441e6`, later current writer error propagation). **CONFLICT**: `module-app` and `module-web` are still included in `settings.gradle`; deletion was a plan, not a completed state. Metadata-only sessions are **UNUSABLE**. |
| 2026-06-12 | 9 | Run-status tracking, gzip writer OOM/double-spool fixes | **VERIFIED** as commit/code leads, especially async file upload evolution. Repeated summaries snapshot the same diff and are not independent evidence. |
| 2026-06-13 | 4 | Airflow timeout, S3TransferManager fire-and-forget uploads | **VERIFIED** in commit `94cdd5685` and current upload-before-publish callback. Performance multipliers in narrative docs remain **UNUSABLE** unless a comparable run is supplied. |
| 2026-06-14 | 10 | Stale Kafka run filtering, release merge repetitions | **VERIFIED** at commit `10e7855d`; most trace repetitions are duplicate snapshots, not separate changes. |
| 2026-06-15 | 5 | MinIO service-account policy audit and race/throughput diagnosis | Policy commits exist (`f09fc9d0`, `2e779d0c`). Trace-only diagnosis claims are **INFERENCE** until tied to code/test/metric artifacts. |
| 2026-06-16 | 5 | Race/throughput fixes and heap change | **VERIFIED** commits `ecee7454` and `a76ff88d`. Direct causal throughput impact is not isolated by a comparable benchmark. |
| 2026-06-17 | 2 | Greedy rate-limiter refill and pool tuning | **VERIFIED** current ADR-717 records sampled absolute rates. **CONFLICT** with later July bottleneck: sink submit changed from negligible to saturated after workload/architecture evolved; these are dated states, not one before/after. |
| 2026-06-18 | 10 | Phase/loop APIs, Airflow branching, blocking/runBlocking cleanup, fire-and-forget controller | **VERIFIED** in local commits and current async code. Claims of runtime completion need the pipeline log, not trace summaries. |
| 2026-06-19 | 16 | Orphan temp cleanup, S3 async/pipe streaming plan, off-heap cache, direct-buffer tuning | **CONFLICT**: the pipe streaming design (`85b5528d`) was later replaced by temp-file `putFileAsync` (`205ce14b`) after a data-loss race. Five metadata-only sessions are **UNUSABLE**; one tool-use JSON stream is corrupt/partial. |
| 2026-06-20 | 1 | ADR-729 producer serialization/read-through cache throughput target | **CONFLICT/UNUSABLE AS RESULT**: ADR-729 is still `Proposed`; its result says TBD and its “after” is a target, not measurement. |
| 2026-06-21 | 4 | RunId alignment and Docker/MinIO bootstrap evolution | **VERIFIED** code-history lead. No independent quantitative result in the traces. |
| 2026-06-22 | 9 | Four-service containerization, calculator pipe-to-temp-file replacement, Airflow/Coolify work | **VERIFIED**: compose defines four pipeline services; commit `205ce14b` and ADR-730 contain a dated E2E correction result. |
| 2026-06-23 | 10 | Docker-first pipeline test and standalone phase-gate behavior | **VERIFIED** in commits `79cf260c`, `c6cb64b7`, and current scripts/config. Several metadata-only sessions are **UNUSABLE**. |
| 2026-06-24–25 | 11 | Directive/analytics documentation and repeated phase-gate snapshots | **INFERENCE** only for portfolio process. Documentation analytics are not technical outcome evidence. |
| 2026-06-26 | 8 | Disable dual daily orchestration, Airflow network reconcile, remove unsupported README performance narrative | **VERIFIED** by the report labeled 71h (68h27m by its timestamps) and commits including `74725906`/ADR-736 lineage. Important limitation: the run was not uninterrupted end-to-end; manual intervention and two rollover failures occurred. |
| 2026-06-27 | 2 | External-api log retention | **VERIFIED** as operational configuration lead; no quantitative outcome. |
| 2026-06-28–07-01 | 21 | Loop-upstream defer, troubleshooting casebook, Parquet/Iceberg PoC, small-file analysis, pipeline metrics queries | **VERIFIED** that PoC/code/docs exist. **UNUSABLE** for adoption claims: Iceberg work is explicitly forward-looking and Parquet remained a PoC. Casebook multipliers lack a controlled comparable environment. |
| 2026-07-02 | 6 | 80h throughput-ceiling report, sink tuning, gzip BEST_SPEED | Endurance diagnosis is **VERIFIED AS DATED OBSERVATION**. **CONFLICT**: trace commit `c2c1bbc27` exists only as a non-ancestor object (`git merge-base --is-ancestor` fails), its sink-tuning ADR is absent, and the current branch cannot treat it as shipped. Gzip change `7c120d64` is locally present. |
| 2026-07-03 | 6 | Airflow DB incident, hardening, internal-network change | **VERIFIED** commits exist. Incident narrative must be handled as security-sensitive operational evidence; this report intentionally omits payloads and access details. |
| 2026-07-04–06 | 6 | Internal-only network and per-endpoint Prometheus metrics/case normalization | **VERIFIED** commits `bdd15136` and `fc58fb12`; current code normalizes endpoint tags and exposes counters. |

## Candidate leads retained after verification

1. **Claim-check pipeline and stage isolation — VERIFIED.** External-api creates an object key and publishes only metadata; publication occurs after upload success. Calculator reads the object, writes a result object, and publishes a second Kafka event; synchronizer persists a database-backed chunk execution state before processing.
2. **Pipe-streaming failure replacement — VERIFIED.** The June 20 pipe implementation was replaced on June 22 by a temp-file + `putFileAsync` chain. ADR-730 records a concrete E2E check: 64 processed chunks, zero failed chunks, 1,948,957 calculated items, and a 30,507-row valid gzip object after the fix.
3. **Long-running pipeline evidence — VERIFIED AS PROJECT-LEVEL.** The 82h May report and 71h June report are real dated documents, but they describe different module counts/configurations and cannot be merged into a single causal before/after. The 71h report explicitly records a dual-orchestrator failure and early termination.
4. **Database-backed synchronizer idempotency — VERIFIED.** A unique chunk identity plus claim/lease/status transitions controls ack/redelivery. Focused current tests passed in this audit.
5. **V6 cache/read path — VERIFIED IMPLEMENTATION, NO VERIFIED SPEEDUP.** Redis `multiGet`, pipelined `SETEX`, a 10ms micro-batch scheduler, negative cache, and SETNX urgent dedup are current. Historical 97→7,347 RPS narratives mix endpoint versions/datasets and are not retained as a comparable multiplier.
6. **Observability-driven diagnosis — VERIFIED.** Per-endpoint HTTP, sink, volume, calculator, synchronizer, and execution-state metrics exist. The July 2 report demonstrates their diagnostic use, but not a post-fix improvement because the trace-only sink-tuning commit is not in current ancestry.

## Material contradictions and rejected claims

- **Path mismatch:** `docs/ai_traces` is absent; `docs/ai-traces` is the audited corpus.
- **PostgreSQL-only vs current Redis:** old Redis-removal/“single database only” language conflicts with current Redis dependencies in the V6 read cache, urgent dedup/status, and synchronizer ranking projection.
- **Outbox incident numbers:** the historical “2,160,000 events / 99.98% recovery / 1,200 TPS” report references SQL templates and a table later dropped by migration V106. No captured raw query/log artifact establishes the claimed incident numbers. Treat as **UNUSABLE**, not portfolio fact.
- **Streaming design reversal:** `PipedInputStream`/async multipart was presented as an improvement, then shown to race the SDK reader and produce empty/truncated gzip files. The retained story is the failed design and its replacement, not the earlier “streaming” claim.
- **Sink tuning not current:** trace commit `c2c1bbc27` is not an ancestor of HEAD; its ADR file is absent and ADR number 744 now denotes a different network decision. Do not claim that tuning shipped.
- **ADR-729 target presented as result:** its table says “After (target)” and “Observed Result: TBD.” The ≥150 files/s figure is a goal, not evidence.
- **82h “zero errors” wording:** the same report lists small per-phase fetch failures. Safest phrasing is “zero ERROR log entries and zero failed chunks as recorded,” not “zero failures of any kind.”
- **Report-labeled 71h “continuous” wording:** the stated timestamps span 68h27m. Service processes had zero restarts, but processing suffered two daily rollover failures and manual loop restart; it was not an intervention-free E2E success.
- **July ceiling math:** the report's per-request theoretical cap and observed user-rate use different units/windows; the theoretical 41.6/52 users/s figures should not be compared directly with observed 100–150 users/s.
- **Chaos-suite inventory drift:** `module-chaos-test/build.gradle` comments say 22 tests / 15 nightmare scenarios, but the current custom source set contains 20 Java sources and no `@Tag("nightmare")`; 14 `@Tag("chaos")` occurrences exist. The suite is also excluded from normal `check` and was not executed here.
- **Cleanup durability evolved after the trace window:** the audited traces exposed the earlier in-memory/drop-oldest inbox as a gap. Current checkout instead persists cleanup envelopes through conditional object-storage `putIfAbsent` and distinguishes create/replay/integrity conflict. This closes that inbox durability gap but still does not prove end-to-end exactly-once across Kafka, artifacts, workers, and DB projection.
- **Unsupported multipliers:** casebook 102→150 files/s and 186→362 users/s, portfolio 97→7,347 RPS, and assorted 5–10x/18x/63x prose lack one controlled, dated, comparable before/after package. Absolute dated observations may be used with their conditions; the multipliers are rejected.

## Verification performed in this audit

- Artifact integrity: all 882 files read; all gzip members valid; one malformed JSON stream identified above.
- Repository validation: current source/config/migrations, dated endurance/load documents, local git objects, ancestry, and commit metadata inspected.
- Focused tests:
  - Passed: `CalculationResultWriterTest` (3 tests), `ChunkConsumerTemplateTest` (11), `ChunkExecutionStatusTest` (13), `ChunkedSnapshotSinkTest` (1), `ExpectationReadFacadeTest` (5), `InflightRequestRegistryTest` (8), and `ReadModelQueryServiceTest` (3).
  - One combined Gradle invocation exited non-zero only because two requested filter names (`BatchResolverTest`, `ReadModelCacheServiceTest`) do not exist; the calculator/synchronizer tests in that invocation passed. The follow-up invocation with existing test classes completed successfully.
- No server, database, Kafka, MinIO, destructive reset, trace command, or external action was executed.
