# ADR-745: Pipeline artifact identity and lifecycle ownership

- Status: Accepted
- Date: 2026-07-19
- Owner: maple-pipeline

---

## 1. Background / Problem

### Background

Artifact keys, LocalFS/MinIO semantics, source finalization, retention, and cleanup-inbox durability are split across `module-infra` and the four ETL executables. The executables all carry the storage implementation transitively through `module-infra`.

### Problem

Backend checksums and caller-file ownership are inconsistent, lifecycle rules are distributed, and active ETL modules cannot adopt a typed artifact contract without retaining broad infrastructure coupling.

### Goal

Create one ownership boundary for pipeline artifact identity and lifecycle while preserving every stored key, Kafka event field, and app/web compatibility path.

---

## 2. Decision

Create `module-pipeline-artifact`.

- Keep the backend-neutral `ObjectStorage` port in `module-common`; move its implementations and configuration to the new module.
- Introduce validated typed layouts and `ArtifactReceipt` while retaining existing object keys byte-for-byte.
- Keep required publication failures replayable with `_SUCCESS` plus `_RUNNING` state.
- Persist cleanup-inbox records at `cleanup/inbox/{eventId}.json` with conditional create.
- Keep `module-infra` only as the temporary compatibility boundary for app/web callers. Active ETL modules import the new module directly.
- Measure extraction against the detached base commit with resolved runtime-classpath bytes, executable JAR bytes, startup-to-health, shutdown, and fixed artifact workloads.

---

## 3. Trade-offs

### Sensitivity

- The extraction adds one library module and temporary compatibility facades.
- Object-key and event-fixture compatibility are strict gates; a string-only refactor can still be a breaking change.
- Evidence tests use a narrow `-PartifactEvidence` Gradle property so their worker JVM is exactly `-Xms1g -Xmx1g -XX:+UseG1GC`; ordinary test-worker defaults remain unchanged.
- The LocalFS evidence test deliberately uses a test-owned fixed pool of exactly 8 threads. Existing registry executors have different pool/queue semantics, and the existing bounded semaphore is suspend-only. This is a test-only measurement exception; `ExecutorService.use` owns and shuts down the pool.

### Trade-off

| Choice | Gain | Cost |
| --- | --- | --- |
| Dedicated artifact module (chosen) | typed identity, backend-neutral receipts, restart-safe cleanup, narrower ETL dependencies | one module plus temporary facades |
| Keep ownership in `module-infra` | no extraction work | broad coupling and distributed lifecycle rules remain |
| Generic ETL runtime | one shared runtime abstraction | mixes workload orchestration with artifact lifecycle and enlarges the migration |

### Risk

- Moving storage configuration can create missing or duplicate beans. Runtime boot checks and compatibility facades gate each migration.
- Backend ETags are not universally content hashes. `ArtifactReceipt` therefore keeps checksum provenance explicit.
- Required event publication can fail after durable upload. Completion markers and replay state must make that window recoverable.

### Non-Risk

- Existing object keys and Kafka JSON do not change in this decision.
- `module-common` remains the port owner; it does not gain AWS/S3 dependencies.
- App/web callers keep their `module-infra` compatibility path during extraction.
- The exact-concurrency executor exception is confined to evidence test source and changes no production executor convention.

---

## 4. Result / Evidence

### Metrics

Detached-base measurements at `a35809235de1f92cd7a7c546bd3bed060f62abab`:

| Executable | Runtime entries | Runtime bytes | Boot JAR bytes | MinIO startup / shutdown |
| --- | ---: | ---: | ---: | ---: |
| `module-external-api` | 249 | 146,863,467 | 147,427,322 | 30 s / 3 s |
| `module-calculator` | 252 | 147,581,557 | 148,002,474 | 32 s / 4 s |
| `module-synchronizer` | 255 | 152,042,966 | 152,462,231 | 34 s / 5 s |
| `module-cleanup` | 242 | 145,089,789 | 145,352,802 | 33 s / 6 s |

### Observed Result

- All four detached-base executable JARs reached health `UP` with the repository MinIO profile and stopped through their captured PIDs.
- The LocalFS profile exposed a pre-existing ambiguous `Executor` injection in external-api (9 candidates), calculator (2), and synchronizer (7); cleanup reached `UP` in 25 seconds.
- The external-api MinIO run reached `UP` but emitted 10 pre-existing ranking-fetch `RejectedExecutionException` warnings during shutdown. This is recorded as a strict log-audit failure, not hidden as a passing condition.
- Fixed-workload throughput, worker JVM details, fixture/output hashes, health JSON, and exact commands are recorded in [`2026-07-19-pipeline-artifact-extraction-evidence.md`](../05_Reports/2026-07-19-pipeline-artifact-extraction-evidence.md).

---

## 5. Summary

> `module-pipeline-artifact` owns typed artifact identity and lifecycle while `module-common` retains the storage port and `module-infra` temporarily shields app/web callers. Compatibility and measured runtime behavior gate the extraction.
