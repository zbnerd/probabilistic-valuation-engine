# Plan: Iceberg Feasibility Study (Issue #1337)

- Spec: `docs/superpowers/specs/2026-06-23-iceberg-feasibility.md`
- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- Parent Issue: #1337
- Investigation only — no module code changes

---

## Phase 1 — Environment Verification

**Goal:** Confirm MinIO version and prepare test namespace.

**Precondition gate:** Phase 2 MUST NOT start until Phase 1 exit criterion (RELEASE ≥ 2024-05-10 confirmed OR upgrade plan documented with scheduled maintenance window ≤ 30 days) is satisfied. If pre-2024-05 and no upgrade plan, Phase 2 entry is BLOCKED — record blocker in pilot-report.md and escalate to architecture owner via issue comment on #1337.

### Task 1.1 — MinIO version audit
- **Owner:** Pilot lead
- **Touch:**
  - `docker-compose.yml` (read-only inspection)
  - Cluster (`mc admin info minio`)
- **Verify:** `mc admin info` output shows RELEASE ≥ 2024-05-10; record exact RELEASE string in `tools/iceberg-pilot/pilot-report.md`
- **Rollback:** N/A (read-only)
- **Exit criterion:** RELEASE confirmed OR upgrade plan documented in pilot report with scheduled maintenance window

### Task 1.2 — Create dedicated test bucket
- **Owner:** Pilot lead
- **Touch:** MinIO `mc mb` (no file edits)
- **Verify:** `mc ls minio/iceberg-pilot` returns empty bucket; `mc ilm ls minio/iceberg-pilot` confirms no expiration rules
- **Rollback:** `mc rb --force minio/iceberg-pilot`
- **Exit criterion:** Bucket exists, lifecycle disabled, isolated from `data/runs/...` serving path
- **Namespace isolation check:** `mc ls minio/data/runs | head -5` confirms serving bucket unaffected; serving PG/Kafka untouched

---

## Phase 2 — Catalog Deployment

**Goal:** Deploy Polaris (primary) or Nessie (fallback) in test namespace.

### Task 2.1 — Author `docker-compose.test.yml`
- **Owner:** Pilot lead
- **Touch:**
  - `tools/iceberg-pilot/docker-compose.test.yml` (new file, scoped to tools/ to keep repo root compose clean)
  - `.env` (no edits; values from existing `MINIO_*`)
- **Verify:** `docker compose -f tools/iceberg-pilot/docker-compose.test.yml config` exits 0; review no ports collide with serving stack (8081/8082/8083/8080)
- **Rollback:** `docker compose -f tools/iceberg-pilot/docker-compose.test.yml down -v`
- **Exit criterion:** Compose file validates; services defined on non-conflicting ports
- **Credentials audit:** Confirm no new credentials written to repo `.env`; pilot uses ephemeral test credentials injected via `docker compose ... --env-file tools/iceberg-pilot/.env.test` (gitignored)

### Task 2.2 — Deploy Polaris with Postgres backend
- **Owner:** Pilot lead
- **Touch:**
  - Polaris bootstrap config in `tools/iceberg-pilot/docker-compose.test.yml`
  - Postgres service (separate from serving PG; distinct port e.g. 5433 vs serving 6543)
- **Verify:** `curl -fsS http://localhost:8181/healthcheck` returns 200 for 5 consecutive pings within 30s each; `docker compose ... exec polaris-db pg_isready` confirms catalog DB isolated
- **Rollback:** `docker compose -f tools/iceberg-pilot/docker-compose.test.yml down -v`
- **Exit criterion:** Polaris health green; catalog DB on non-serving port

### Task 2.3 — (Fallback) Deploy Nessie
- **Owner:** Pilot lead
- **Touch:** Same compose file, alternate profile
- **Verify:** `curl -fsS http://localhost:19120/api/v1/health` returns 200
- **Rollback:** Profile-level down
- **Exit criterion:** Nessie ready (only if Polaris fails)

---

## Phase 3 — Table Creation & Ingest

**Goal:** Create test table; ingest one `character_basic` chunk.

### Task 3.1 — Author PyIceberg writer script
- **Touch:**
  - `tools/iceberg-pilot/ingest_chunk.py` (new file)
  - `tools/iceberg-pilot/requirements.txt` (PyIceberg 0.9+)
- **Verify:** `python -m py_compile tools/iceberg-pilot/ingest_chunk.py` exits 0
- **Rollback:** Delete script
- **Exit criterion:** Script compiles, accepts `--chunk-path` argument

### Task 3.2 — Create test table DDL
- **Touch:** `tools/iceberg-pilot/create_table.sql` (new file)
- **Verify:** SQL parses with Iceberg spec; partition spec validates
- **Rollback:** `DROP TABLE landing.character_basic_pilot`
- **Exit criterion:** Table created with `days(ingest_ts), bucket(64, character_id)` partition spec

### Task 3.3 — Ingest one chunk end-to-end
- **Touch:** None (runtime operation)
- **Verify:** Table row count = source chunk row count (exact match)
- **Rollback:** `TRUNCATE TABLE landing.character_basic_pilot`
- **Exit criterion:** Row count match recorded

---

## Phase 4 — Compaction Validation

**Goal:** Validate `rewrite-data-files` produces target file sizes.

### Task 4.1 — Run nightly compaction 3 times
- **Owner:** Pilot lead
- **Touch:** None (runtime)
- **Verify:** `rewrite-data-files` exit 0 each run; Parquet median ≥128MB
- **Rollback:** Snapshot preserved; old files remain queryable
- **Exit criterion:** 3 consecutive runs pass

### Task 4.2 — Measure file size distribution
- **Owner:** Pilot lead
- **Touch:** None
- **Verify:** Recorded min/p50/p95/max file sizes in pilot report
- **Rollback:** N/A (measurement)
- **Exit criterion:** Distribution documented

### Task 4.3 — Compaction crash recovery test (NEW from grill-me)
- **Owner:** Pilot lead
- **Touch:** None
- **Failure mode tested:** `rewrite-data-files` process killed (SIGKILL) mid-run after writing N/2 files
- **Verify:** After restart, Iceberg `snapshots` table shows last snapshot IN_PROGRESS or last successful snapshot queryable; no orphan data files; `procedures` table shows rollback semantics
- **Rollback:** Snapshot at procedure start remains queryable; drop incomplete data files via `remove_orphan_files` procedure
- **Exit criterion:** Recovery procedure documented in pilot-report.md; orphan-file runbook present

---

## Phase 5 — Retention Validation

**Goal:** Validate snapshot retention policy.

### Task 5.1 — 7-day rolling raw retention
- **Touch:** None (runtime)
- **Verify:** `expire_snapshots` with `retain_last=7` removes older raw snapshots
- **Rollback:** N/A (retention is reversible by retaining snapshots)
- **Exit criterion:** Snapshot list shows ≤7 raw snapshots

### Task 5.2 — 365-day serving read-model policy documented
- **Touch:** `docs/03_Technical_Guides/iceberg-evaluation.md` (draft)
- **Verify:** Doc section present, retention parameters stated
- **Rollback:** Doc edit revert
- **Exit criterion:** Doc section complete

---

## Phase 6 — Time-Travel & Cross-Engine Validation

**Goal:** Validate time-travel queries and engine interop.

### Task 6.1 — Time-travel query benchmark
- **Touch:** None (runtime)
- **Verify:** `SELECT ... AS OF TIMESTAMP '-7d'` returns within 30s
- **Rollback:** N/A
- **Exit criterion:** Latency ≤30s

### Task 6.2 — Trino reads Polaris-managed table
- **Touch:** `tools/iceberg-pilot/trino-query.sql` (new file)
- **Verify:** `trino --execute < trino-query.sql` row count matches PyIceberg write
- **Rollback:** N/A
- **Exit criterion:** Cross-engine read row count matches

---

## Phase 7 — Manifest Monitoring

**Goal:** Track manifest list size for 7 days.

### Task 7.1 — Daily manifest size measurement
- **Touch:** `tools/iceberg-pilot/measure_manifest.sh` (new file)
- **Verify:** Script runs daily; output captured to `tools/iceberg-pilot/measurements/`
- **Rollback:** Stop cron
- **Exit criterion:** 7 consecutive days measured

### Task 7.2 — Alarm threshold proposal
- **Touch:** `docs/03_Technical_Guides/iceberg-evaluation.md`
- **Verify:** Section present with threshold + runbook
- **Rollback:** Doc edit revert
- **Exit criterion:** Threshold + runbook documented

---

## Phase 8 — Recommendation & Companion Doc

**Goal:** Publish feasibility study; close parent issue.

### Task 8.1 — Author `iceberg-evaluation.md`
- **Owner:** Pilot lead + architecture reviewer
- **Touch:** `docs/03_Technical_Guides/iceberg-evaluation.md` (new file)
- **Verify:** Doc references all acceptance criteria AC1–AC12; contains recommendation + cost/effort/FTE table
- **Required sections (NEW from grill-me):**
  - Storage cost estimate (GB stored at end of pilot × $/GB-month)
  - Compute cost estimate (Polaris + Trino node-hours × $/hour × pilot duration)
  - FTE estimate for standing up compaction-as-a-service (target ≤0.5 FTE)
  - Migration effort estimate (engineer-weeks for Phase 2 cutover, target ≤13 weeks per ADR-735)
  - Storage budget consumed (pilot data capped at ≤10GB MinIO)
- **Rollback:** Doc delete (no production effect)
- **Exit criterion:** Doc published, all AC items addressed, cost/effort/FTE captured

### Task 8.2 — Record recommendation on #1337
- **Owner:** Pilot lead
- **Touch:** Issue #1337 comment (via `gh issue comment`)
- **Verify:** Comment present with GO/NO-GO/DEFER + trigger conditions + link to companion doc
- **Rollback:** Edit comment
- **Exit criterion:** Recommendation posted

### Task 8.3 — Mark ADR-735 Action Item #1 complete
- **Owner:** Architecture owner
- **Touch:** `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` (checklist line)
- **Verify:** Markdown checkbox flipped to `[x]` in action items
- **Rollback:** Revert edit
- **Exit criterion:** ADR Action Item #1 marked complete

### Task 8.4 — Pilot teardown (NEW from grill-me)
- **Owner:** Pilot lead
- **Touch:** None (runtime teardown)
- **Verify:** `docker compose -f tools/iceberg-pilot/docker-compose.test.yml down -v` succeeds; `mc rb --force minio/iceberg-pilot` removes test bucket; `tools/iceberg-pilot/.env.test` deleted; `tools/iceberg-pilot/measurements/` archived to `docs/03_Technical_Guides/iceberg-evaluation-pilot-data-{date}.tar.gz`
- **Rollback:** N/A (teardown is terminal)
- **Exit criterion:** No residual test resources; pilot artifacts preserved in companion doc appendix
- **Storage budget consumed (NEW):** Pilot must not consume >10GB MinIO storage; if exceeded, document reason and cost in companion doc

---

## Definition of Done

- [ ] All acceptance criteria AC1–AC12 from spec pass
- [ ] All 8 phase exit criteria above satisfied (Phase 4.3 crash recovery + Phase 8.4 teardown added)
- [ ] Companion doc `docs/03_Technical_Guides/iceberg-evaluation.md` published with cost/effort/FTE/migration-effort sections
- [ ] Recommendation recorded on issue #1337 with GO/NO-GO/DEFER + trigger conditions + companion doc link
- [ ] ADR-735 Action Item #1 marked complete
- [ ] No module code modified
- [ ] No production deployment of Polaris/Nessie
- [ ] Test namespace isolated; serving PG/Kafka untouched; pilot teardown verified
- [ ] Verification log saved to `tools/iceberg-pilot/pilot-report.md`
- [ ] Cost captured: storage GB consumed (≤10GB budget), compute hours, FTE estimate, migration weeks
- [ ] Pilot teardown completed; no residual test resources on cluster

## Rollback Strategy (per phase)

| Phase | Rollback |
|---|---|
| 1 | Delete bucket; revert compose changes |
| 2 | `docker compose -f tools/iceberg-pilot/docker-compose.test.yml down -v` |
| 3 | `DROP TABLE landing.character_basic_pilot`; delete scripts |
| 4 | Snapshot preserved; restore from prior snapshot ID; for crash recovery, drop orphan files via `remove_orphan_files` |
| 5 | N/A (retention reversible) |
| 6 | N/A (read-only) |
| 7 | Stop cron; archive measurements dir |
| 8 | Doc delete; ADR checkbox revert; issue comment edit; full teardown per 8.4 |

## Verification Commands

```bash
# Phase 1
mc admin info minio | grep -i release
mc ls minio/iceberg-pilot
mc ilm ls minio/iceberg-pilot  # confirm no lifecycle rules
mc admin info minio | grep -i release
mc ls minio/iceberg-pilot

# Phase 2
curl -fsS http://localhost:8181/healthcheck  # Polaris
curl -fsS http://localhost:19120/api/v1/health  # Nessie fallback

# Phase 3
docker compose -f tools/iceberg-pilot/docker-compose.test.yml exec trino trino --execute "SELECT count(*) FROM iceberg.landing.character_basic_pilot"
diff <(wc -l source_chunk.jsonl) <(trino --execute "SELECT count(*) FROM iceberg.landing.character_basic_pilot")

# Phase 4
docker compose -f tools/iceberg-pilot/docker-compose.test.yml exec spark-iceberg spark-sql -e "CALL iceberg.system.rewrite_data_files('landing.character_basic_pilot')"
aws s3api list-objects-v2 --bucket iceberg-pilot --query 'Contents[].Size' | jq 'sort | (length/2|floor) as $m | .[$m]'
# Phase 4.3 crash recovery
docker compose -f tools/iceberg-pilot/docker-compose.test.yml exec spark-iceberg bash -c 'pkill -9 -f rewrite_data_files; sleep 5'
docker compose -f tools/iceberg-pilot/docker-compose.test.yml exec spark-iceberg spark-sql -e "CALL iceberg.system.remove_orphan_files('landing.character_basic_pilot')"

# Phase 5
docker compose -f tools/iceberg-pilot/docker-compose.test.yml exec spark-iceberg spark-sql -e "CALL iceberg.system.expire_snapshots('landing.character_basic_pilot', retain_last => 7)"

# Phase 6
docker compose -f tools/iceberg-pilot/docker-compose.test.yml exec trino trino --execute "SELECT count(*) FROM iceberg.landing.character_basic_pilot FOR SYSTEM_TIME AS OF -7d"
time trino --execute "SELECT ... FROM iceberg.landing.character_basic_pilot"

# Phase 7
ls -la tools/iceberg-pilot/measurements/

# Phase 8
gh issue view 1337 --comments | grep -i "GO\|NO-GO\|DEFER"
grep -c "AC1[0-2]" docs/03_Technical_Guides/iceberg-evaluation.md
grep "\[x\]" docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md | grep -i iceberg
# Phase 8.4 teardown
docker compose -f tools/iceberg-pilot/docker-compose.test.yml down -v
mc rb --force minio/iceberg-pilot
rm -f tools/iceberg-pilot/.env.test
```