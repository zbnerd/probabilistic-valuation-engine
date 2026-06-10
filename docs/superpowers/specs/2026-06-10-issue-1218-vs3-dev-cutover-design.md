# VS3: Dev e2e MinIO Validation — Design Spec

- Status: Draft → Approved (pending user review)
- Date: 2026-06-10
- Owner: zbnerd
- Parent spec: `docs/superpowers/specs/2026-06-09-v2-pipeline-modules-migration-design.md`
- Parent ADR: `docs/01_ADR/ADR-725_object-storage-minio-migration-vs1-vs2.md` (supersede note added in this slice)
- Implements: GitHub issue #1218 (VS3)
- Scope: dev-environment full cutover from `STORAGE_BACKEND=local` to `STORAGE_BACKEND=minio`, validated via a wrapper script + modified `pipeline-test` skill + validation report. **Production cutover remains VS4 (separate issue).**

---

## 1. Background

VS1 (PR #1222) shipped the unified `ObjectStorage` interface with two adapters (`LocalFsObjectStorage`, `MinioObjectStorage`). VS2 (PR #1217) migrated the four application modules to the unified interface and introduced `SnapshotObjectStoreAdapter` + `ChunkFileReaderPort` (with IO/CPU 분리). Default backend in VS2 is `local`.

ADR-725 originally scoped VS3 as a **dry-run** (write to MinIO, read from local). Issue #1218 reframes VS3 as a **full dev cutover** (`STORAGE_BACKEND=minio` in dev; 4 modules restart; e2e smoke + load-test + chaos all run against MinIO). A dry-run would not exercise the read path on MinIO, leaving the read-path risk un-validated until VS4 (prod cutover). Issue #1218 also acts as a manual gate — VS1+VS2 cannot be production-cut over without this gate.

VS3 is a **manual validation gate**, not a code-change slice. Deliverables: a wrapper script, a modified `pipeline-test` skill with MinIO awareness, a validation report (JSON + Markdown), and an ADR-725 supersede note. Application code remains unchanged in this slice.

## 2. Decision

Add a `scripts/validate-minio-vs3.sh` wrapper with subcommands `env`, `smoke`, `chaos`, `all`. Modify `.claude/skills/pipeline-test/SKILL.md` to be storage-backend aware: when `STORAGE_BACKEND=minio` is set, the skill adds `mc ready` + lifecycle rule pre-checks, verifies `components.minio.status=UP` in module health, and inspects `mc ls` prefixes after the smoke. Append a supersede note to ADR-725 reframing VS3 as full dev cutover. Generate a JSON+Markdown validation report. VS4 (prod cutover) is deferred to a separate issue.

## 3. Goals

1. `STORAGE_BACKEND=minio` works end-to-end in the dev environment for the 4 Spring Boot modules (`module-external-api`, `module-calculator`, `module-synchronizer`, `module-rest-controller`).
2. All 12 acceptance criteria in issue #1218 are checkable via a single wrapper script invocation (`./scripts/validate-minio-vs3.sh all`) or via its subcommands.
3. The `pipeline-test` skill remains backward-compatible with `STORAGE_BACKEND=local` and gains MinIO-specific checks when `STORAGE_BACKEND=minio` is set.
4. The wrapper script is **re-runnable**: each invocation overwrites the prior report (timestamped file name); failures are diagnosable from the report + per-module log files.
5. ADR-725's "VS3 = dry-run" risk is removed; the ADR explicitly states VS3 = dev full cutover and VS4 = prod cutover.
6. No application code change (issue #1218 states "no code change"). Skill modification is documentation-only.

## 4. Non-Goals

- **Production cutover** of `STORAGE_BACKEND=minio` (VS4, separate issue).
- **RPS/p99 baseline comparison** — issue #1218 acceptance criterion #7 references a local baseline that does not exist in this environment. The criterion is relaxed via issue comment to "record raw RPS/p99 only". Resolving the baseline gap is a separate issue.
- **CI automation** (GitHub Actions MinIO smoke on PR) — not in this slice. May be a follow-up.
- **Removal of deprecated ports** (`ExternalApiArtifactStorePort`, calculator's local `ObjectStorage`) — issue #1221.
- **Unit tests for shell functions** in `scripts/lib/*.sh` — ROI is low; the wrapper is a thin shell around `pipeline-test` + `mc` + `curl`. The pipeline-test skill is itself the integration test.
- **MinIO tuning** (bucket policy hardening, TLS, IAM) — out of scope; dev env uses root credentials over plain HTTP.
- **Pre-existing compile breakage** in modules not in VS3's path (orthogonal to this slice).

## 5. Architecture

### 5.1 New files

```
scripts/
  validate-minio-vs3.sh             # main entry, subcommand router
  lib/
    minio-checks.sh                 # mc CLI wrappers (bucket, lifecycle, health, prefix list)
    module-health.sh                # 4 modules /actuator/health polling + MinioHealthIndicator verify
    chaos-minio.sh                  # docker stop/start + DOWN/UP verification
docs/
  reports/
    vs3-validation-TEMPLATE.md      # 12 acceptance items as a fillable table
  superpowers/
    specs/
      2026-06-10-issue-1218-vs3-dev-cutover-design.md   # this file
```

**Modified files:**

```
.claude/skills/pipeline-test/SKILL.md   # MinIO awareness added
docs/01_ADR/ADR-725_object-storage-minio-migration-vs1-vs2.md   # supersede note appended
docs/reports/vs3-validation-TEMPLATE.md                          # NEW (template only; report generated at runtime)
```

### 5.2 Component responsibilities

| Component | Responsibility | Interface |
|-----------|----------------|-----------|
| `validate-minio-vs3.sh` | subcommand routing, `.env` load, exit code aggregation, JSON+Markdown report generation | `env\|smoke\|chaos\|all` |
| `lib/minio-checks.sh` | mc CLI calls, JSON parsing via `jq` | `check_minio_ready`, `check_bucket`, `check_lifecycle_rules`, `list_prefix` |
| `lib/module-health.sh` | poll 4 modules' `/actuator/health`; when `STORAGE_BACKEND=minio`, assert `components.minio.status=UP` | `check_module_health <module>` |
| `lib/chaos-minio.sh` | `docker compose stop minio` → poll DOWN → `docker compose start minio` → poll UP | `chaos_test [--chaos-timeout=30s]` |
| `pipeline-test` skill | (modified) storage-backend aware smoke E2E: MinIO pre-check, health indicator, prefix list post-check | existing interface |

### 5.3 Subcommand sequence (`all` mode)

```
1. preflight
   ├─ source .env (load MINIO_*, STORAGE_BACKEND, DB_URL)
   ├─ assert STORAGE_BACKEND == "minio"  (fail-fast if not, exit 1)
   └─ assert 4 module ports free (8080, 8081, 8082, 8083)

2. env checks (issue #1218 items 1-3)
   ├─ docker compose ps minio            → running
   ├─ mc ready (curl :9000/minio/health/ready) → 200
   ├─ mc ls local/maple-expectation/    → bucket exists
   └─ mc ilm ls local/maple-expectation/→ 4 rules present (snapshots/, runs/, calculator/, ocid-mapping/, 2-day expiry)

3. boot 4 modules (issue #1218 specifies 4; module-cleanup + Airflow are NOT in scope for VS3)
   ├─ ./gradlew :module-external-api:bootRun (background → logs/vs3-validation-{ts}-external-api.log)
   ├─ ./gradlew :module-calculator:bootRun (background)
   ├─ ./gradlew :module-synchronizer:bootRun (background)
   └─ ./gradlew :module-rest-controller:bootRun (background)
   └─ poll /actuator/health for each until UP (timeout 120s, 1 auto-retry on early fail)
   └─ module-cleanup (8084) and Airflow (8180) are skipped: cleanup schedulers run inside the 4 booted modules' scheduler beans, and Airflow is not required for storage validation. pipeline-test skill is modified to make cleanup + Airflow optional via STORAGE_BACKEND detection (see §6.7).

4. health check (item 4)
   ├─ for each of 4 modules: /actuator/health → status=UP
   └─ for each of 4 modules: components.minio.status=UP (or `components.minioHealthIndicator.status=UP`; exact key resolved during implementation by reading /actuator/health JSON once on first run — see §6.3 TBD callout)

5. smoke E2E (items 5-6, 8) — delegates to pipeline-test skill (with cleanup + Airflow skipped per §6.7)
   ├─ invoke pipeline-test skill (with MinIO env pre-loaded)
   │     skill runs: 4 modules already up → run-on-startup pipeline → snapshot consume → calculator
   ├─ assert pipeline-test result == pass
   ├─ assert: tail logs/ until "Calculation completed with result saved" (timeout 5m)
   ├─ assert: grep ERROR module-*/logs/ → 0 lines
   └─ assert: mc ls local/maple-expectation/{snapshots,runs,ocid-mapping,calculator/runs}/ → non-empty

6. load-test (item 7) — semi-automated
   ├─ echo manual command (user runs ./load-test/run-v5-db-throughput.sh)
   └─ user records raw RPS, p99 in Markdown report; no baseline comparison

7. cleanup scheduler dry-run (item 9)
   ├─ cleanup schedulers run inside the 4 booted modules (e.g. `CalculatorResultCleanupScheduler` in calculator, `ArtifactCleanupScheduler`/`ConsumedChunkCleanupScheduler` in external-api) — module-cleanup is NOT booted for VS3
   ├─ trigger cleanup cycle: wait for next scheduled tick (default 1m) OR invoke actuator endpoint if exposed
   └─ verify scheduler logs ran without error in calculator/external-api logs (look for "cleanup" + "dry-run" / "scanned" lines, no ERROR)

8. snapshot resume (item 10) — semi-automated
   ├─ trigger mechanism: TBD-during-impl. Candidate options (resolved at implementation time by inspecting the existing snapshot retry path):
   │     (a) Kafka publish to `snapshot.ready` topic via kafka-console-producer
   │     (b) REST endpoint (e.g. `POST /api/internal/snapshot/retry`) if exposed
   │     (c) Database update: set `calculation_jobs.status='SNAPSHOT_READY'` for one row + consumer pickup
   ├─ whichever mechanism is chosen, document the exact command in `docs/reports/vs3-validation-{ts}.md` "Manual step" section
   └─ verify logs show `SnapshotObjectStoreAdapter` reading from MinIO (`storageType="S3"` line in module-external-api log)

9. chaos (item 11)
   ├─ docker compose stop minio
   ├─ sleep 30, poll 4 modules /actuator/health → all DOWN (60s timeout, 3 retries)
   ├─ docker compose start minio
   ├─ poll 2m, expect 4 modules back to UP
   └─ if DOWN > 5m → fail

10. shutdown
    ├─ kill 4 bootRun processes
    └─ leave MinIO running (dev env reset is separate)
```

### 5.4 Exit codes

| Code | Meaning |
|------|---------|
| 0 | All checks passed |
| 1 | Preflight fail (`.env` missing, `STORAGE_BACKEND≠minio`, port conflict) |
| 2 | Env check fail (mc ready, bucket, lifecycle rules) |
| 3 | Boot fail (one or more modules did not reach UP within 120s + 1 retry) |
| 4 | Health check fail (one or more MinioHealthIndicator = DOWN) |
| 5 | Smoke E2E fail (no "Calculation completed" within 5m, OR ERROR in logs, OR empty MinIO prefix) |
| 6 | Chaos fail (DOWN > 60s, OR recovery > 5m) |
| 7 | Manual step incomplete (user did not run load-test, snapshot resume) |

### 5.5 Reports

**`docs/reports/vs3-validation-{timestamp}.json`** (machine-readable):

```json
{
  "validationId": "vs3-2026-06-10T14-30-00",
  "gitSha": "abc1234",
  "storageBackend": "minio",
  "minio": {
    "endpoint": "http://localhost:9000",
    "bucket": "maple-expectation",
    "lifecycleRules": 4,
    "consoleUrl": "http://localhost:9001"
  },
  "checks": [
    {"name": "mc_ready", "status": "pass", "durationMs": 120},
    {"name": "bucket_exists", "status": "pass", "durationMs": 80},
    {"name": "lifecycle_rules_4", "status": "pass", "durationMs": 90},
    {"name": "external_api_boot", "status": "pass", "durationMs": 45000},
    {"name": "minio_health_indicator", "status": "pass", "durationMs": 30},
    {"name": "smoke_e2e", "status": "pass", "durationMs": 180000, "ign": "아델"},
    {"name": "chaos_down_30s", "status": "pass", "durationMs": 32000},
    {"name": "chaos_recovery_2m", "status": "pass", "durationMs": 125000}
  ],
  "loadTest": {
    "status": "manual_pending",
    "rps": null,
    "p99Ms": null,
    "note": "Baseline comparison N/A (relaxation requested in issue #1218 comment)"
  },
  "manualSteps": [
    "minio_console_visual_inspect",
    "snapshot_resume_retry"
  ],
  "result": "pass",
  "exitCode": 0
}
```

**`docs/reports/vs3-validation-{timestamp}.md`** (human, auto-generated from JSON + template):
- Front matter: validation ID, date, Git SHA, operator
- 12 acceptance criteria table (pass / fail / manual_pending)
- Per-step timing + log excerpts
- Load-test section with placeholder for user-entered RPS/p99
- Manual step checklist (user fills in)
- VS4 entry criteria checklist (all must be true to proceed)

## 6. pipeline-test skill modifications

### 6.1 Pre-check (when `STORAGE_BACKEND=minio`)

Add a step before "Start modules" that:
- Sources `.env`, asserts `STORAGE_BACKEND=minio`
- Asserts `MINIO_ENDPOINT`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_BUCKET` are set
- `curl -sf ${MINIO_ENDPOINT}/minio/health/ready` → 200
- `mc alias set local ${MINIO_ENDPOINT} ${MINIO_ROOT_USER} ${MINIO_ROOT_PASSWORD}` (idempotent)
- `mc ls local/${MINIO_BUCKET}/` → bucket exists
- `mc ilm ls local/${MINIO_BUCKET}/` → 4 rules present (snapshots/, runs/, calculator/, ocid-mapping/)

### 6.2 Boot (unchanged structure, add MinIO env passthrough)

`source .env` already exposes `MINIO_*` to the JVM via Spring's environment binding. No script change needed beyond ensuring the env is exported.

### 6.3 Health check (item 4)

Replace single-status assertion with:

```bash
curl -s http://localhost:${PORT}/actuator/health | jq -e '
  .status == "UP" and
  (.components.minio.status == "UP" or .components.minioHealthIndicator.status == "UP")
'
```

**TBD (resolve at implementation time, before plan):** The exact JSON key under `.components.*` depends on the `MinioHealthIndicator` Spring bean name. During implementation:
1. Boot one module with `STORAGE_BACKEND=minio` in dev.
2. `curl http://localhost:8081/actuator/health | jq .components` → record the key (`minio` vs `minioHealthIndicator` vs other).
3. Hard-code the verified key in `lib/module-health.sh`. Document the verified key in the implementation plan.
4. If neither `minio` nor `minioHealthIndicator` is present in `.components`, treat as Health check fail (exit 4) — do not silently pass.

### 6.4 Post-check (item 6)

After the E2E smoke returns 200 or 202 + completion log:

```bash
# Verify MinIO objects exist under expected prefixes
for prefix in snapshots runs ocid-mapping calculator/runs; do
  count=$(mc ls --recursive local/${MINIO_BUCKET}/${prefix}/ | wc -l)
  [ "$count" -gt 0 ] || { echo "FAIL: empty prefix ${prefix}/"; exit 5; }
done
```

### 6.5 Result verification (item 5)

Existing section 9 in `pipeline-test` SKILL.md is kept; add:

```bash
# Storage-error log scan
for module in external-api calculator synchronizer; do
  errs=$(grep -E "ObjectStorage|MinIO|S3" logs/pipeline-test-${module}.log | grep -i "ERROR" | tail -5)
  [ -z "$errs" ] || { echo "ObjectStorage ERROR in ${module}: ${errs}"; exit 5; }
done
```

### 6.6 Backward compatibility

When `STORAGE_BACKEND` is unset or `local`, all new MinIO checks are skipped; existing local behavior is unchanged. The skill's existing pre-check (local PostgreSQL) takes precedence.

### 6.7 Module-cleanup + Airflow skip rule (VS3-specific)

When `STORAGE_BACKEND=minio` is set, the pipeline-test skill modifies its workflow:
- **Skip module-cleanup boot** (port 8084) — its schedulers run inside the 4 VS3 modules' scheduler beans. Booting a 5th module is out of issue #1218 scope.
- **Skip Airflow** (port 8180) — storage validation does not require the control plane. `run-on-startup: true` in local profile starts the pipeline immediately on boot, which is sufficient for the smoke E2E.
- **Skip `module-cleanup` references** in post-checks (Step 7 in §5.3 targets the right per-module scheduler logs, not a cleanup module log).

Detection: `STORAGE_BACKEND=minio` env var is the trigger. No new flag is introduced. The behavior is:
- `STORAGE_BACKEND=minio` → skip cleanup + Airflow (this slice's use case)
- `STORAGE_BACKEND=local` (or unset) → existing behavior (boot all 5 modules + Airflow)

DB selection: when `STORAGE_BACKEND=minio`, the skill uses `DB_URL` from `.env` (which points to the dev/staging DB). When `STORAGE_BACKEND=local`, the existing local PostgreSQL hardcoded path (`localhost:5432/maple_expectation`) is kept for local development. This split prevents the dev cloud DB from being polluted by local-only test runs.

## 7. ADR-725 supersede note

Append to `docs/01_ADR/ADR-725_object-storage-minio-migration-vs1-vs2.md` head matter (preserves original body):

```markdown
---

## ⚠️ Supersede Note (2026-06-10)

**Original VS3 scope** (this file, Section 3 Risk): "VS3 = dry-run (write to MinIO but read from local)".

**Revised VS3 scope** (per issue #1218 acceptance criteria + #1218 dev validation):
- VS3 = **full dev cutover**. `STORAGE_BACKEND=minio` set in dev; 4 modules restart cleanly; e2e smoke, load-test, chaos test all run against MinIO.
- VS4 = **production cutover** (atomic flip of `storage.backend` in prod compose).

**Why scope expanded:** The dry-run was a defensive option chosen in VS2 to limit blast radius. Issue #1218 reframed VS3 as a real validation gate ("proves VS1+VS2 work end-to-end with S3-compatible backend, before production cutover"). A dry-run would not exercise the read path on MinIO and would leave the read-path risk un-validated until VS4.

**Implications:**
- **Section 3 (Trade-offs), Risk** item "VS3+VS4 cutover regressions": now VS3 is the read-path validation; VS4 inherits the production-only risks (network latency to S3, IAM, prod bucket policy).
- **Section 4 (Result/Evidence)**: superseded by VS3 validation report at `docs/reports/vs3-validation-{ts}.json` (issue #1218 deliverable).
- **Section 3, Non-Risk "Production cutover"**: re-categorized — VS3 owns dev cutover risk; VS4 owns prod cutover risk.

**Decisions unchanged:**
- Single `ObjectStorage` interface (module-common).
- `LocalFsObjectStorage` and `MinioObjectStorage` adapters.
- `SnapshotObjectStore` port preserved via adapter.
- `ChunkFileReaderPort` with IO/CPU 분리.
- `storageType` field semantics.
```

No new ADR (ADR-726) is created. Justification: scope revision, not decision change. Risk assignment change tracked via supersede note.

## 8. Dependencies

**System:**
- `jq` (JSON parsing for health checks + report aggregation)
- `mc` (MinIO client, provided by `minio-init` Docker container or local install)
- `curl`, `docker compose`, `lsof`, `psql` (existing toolchain)
- `bash` 4+ (associative arrays for report aggregation)

**No new application dependencies.** No `build.gradle` changes.

## 9. Error handling

| Mode | Trigger | Behavior |
|------|---------|----------|
| Preflight fail | `.env` missing, `STORAGE_BACKEND≠minio`, port conflict | Exit 1, message points to fix |
| MinIO unavailable | `mc ready` fails, lifecycle rules count < 4 | Exit 2, `mc` output shown |
| Boot fail | `/actuator/health` not UP within 120s | Exit 3, last 30 log lines per failed module |
| Health fail | `components.minio.status != UP` | Exit 4, full health JSON dumped |
| Smoke fail | "Calculation completed" not seen within 5m, OR ERROR in logs, OR empty MinIO prefix | Exit 5, pipeline-test output + module logs shown |
| Chaos fail | DOWN > 60s, OR recovery > 5m | Exit 6, last 30 lines per module health log |
| Manual step | Load-test / snapshot resume not run | Exit 7, `MANUAL_PENDING: <step>` marker |

**Retry policy:**

| Situation | Retries |
|-----------|---------|
| boot fail | 1 auto-retry (early-cycle failures are often flaky) |
| health poll | 3 retries within 120s window |
| chaos DOWN | 60s × 3 polls before fail |
| smoke fail | 0 (DB consistency risk) |
| MinIO lifecycle rules missing | 0 (data plane config issue, fix-and-rerun) |

## 10. Testing

Pipeline-test skill is the integration test for this slice. The wrapper script is a thin shell and gets no unit tests. Test pyramid:

| Layer | Target | Method | Automation |
|-------|--------|--------|-----------|
| L1 | pipeline-test MinIO mode dry-run | Stop minio, run `validate-minio-vs3.sh env`, expect exit 2; restart, expect exit 0 | Manual, 1 run |
| L2 | `validate-minio-vs3.sh env` subcommand | Dev env 1 invocation, expect exit 0 | Manual |
| L3 | `validate-minio-vs3.sh all` (issue #1218 close) | Dev env 1 invocation, expect exit 0 + report generated | Manual, 1 run |
| L4 | pipeline-test local mode regression | `STORAGE_BACKEND=local` + local profile, ensure no regression | Manual, separate session |

L1 + L2 + L3 are required for issue #1218 close. L4 is orthogonal and is a follow-up issue if regression is found.

## 11. VS4 entry criteria

VS3 report must satisfy all of:
1. `result=pass` (exit 0 from `all` mode)
2. All 12 issue #1218 acceptance criteria marked `pass` or `manual_pending` with user-confirmed evidence
3. Chaos test: stop MinIO → 4 modules DOWN within 60s → start MinIO → 4 modules UP within 2m
4. Load-test raw RPS + p99 recorded (no baseline comparison)
5. No `ObjectStorage` errors in logs beyond normal noise
6. ADR-725 supersede note + validation report merged to `master`

## 12. Summary

> VS3 is a manual validation gate: `STORAGE_BACKEND=minio` runs end-to-end in dev, validated by a wrapper script + MinIO-aware `pipeline-test` skill + JSON+Markdown report. ADR-725 is updated (supersede note) to reflect VS3 = dev full cutover and VS4 = prod cutover. No application code change.
