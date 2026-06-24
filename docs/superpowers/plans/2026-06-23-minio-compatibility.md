# Plan: MinIO Compatibility Validation

- Parent Issue: #1338
- Spec: `docs/superpowers/specs/2026-06-23-minio-compatibility.md`
- Date: 2026-06-23

---

## Phase 0 — Preflight (5 min)

### T0.1 Confirm local MinIO is running

- **Files touched**: none
- **Verify**:
  ```bash
  source .env   # provides MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_ENDPOINT
  curl -sf http://localhost:9000/minio/health/live && echo OK
  mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
  mc admin info local
  ```
- **Credentials sourcing**: All scripts load `source .env` before invoking `mc`/`aws s3api`. Per the project's `.claude/rules/critical-rules.md`, `.env` is read-only and never modified. If `MINIO_ROOT_USER/PASSWORD` are absent (e.g. CI without bootstrap), fall back to the per-module SA in `docker/services/secrets/sa-<module>.key` (read-only) for read-only tests; bucket-create/ILM/versioning tests still require root.
- **Rollback**: N/A (read-only)
- **Owner**: investigator

### T0.2 Create test bucket

- **Files touched**: none (test bucket lives in MinIO only)
- **Verify**:
  ```bash
  mc mb local/maple-iceberg-test
  mc anonymous set none local/maple-iceberg-test
  ```
- **Rollback**: `mc rb --force local/maple-iceberg-test`
- **Owner**: investigator

### T0.3 Create scripts directory

- **Files touched**: `scripts/minio-compat/.gitkeep`
- **Verify**: `ls scripts/minio-compat/`
- **Rollback**: `rm -rf scripts/minio-compat/`
- **Owner**: investigator

---

## Phase 1 — MinIO Version & Configuration Capture (10 min)

### T1.1 Capture MinIO server version + release date

- **Files touched**: `scripts/minio-compat/01-version.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/01-version.sh
  # expected output: Version: RELEASE.2024-XX-XX, ReleaseDate: YYYY-MM-DD
  ```
- **Rollback**: N/A
- **Owner**: investigator

### T1.2 Document current bucket configuration

- **Files touched**: `scripts/minio-compat/02-bucket-config.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/02-bucket-config.sh
  # outputs: versioning status, lifecycle rules, retention, ILM prefixes
  ```
- **Rollback**: N/A
- **Owner**: investigator

---

## Phase 2 — Tier 1 API Smoke Tests (20 min)

### T2.1 Baseline 7 APIs (ListObjectsV2, GetObject, PutObject, CopyObject, DeleteObjects, HeadObject, HeadBucket)

- **Files touched**: `scripts/minio-compat/03-tier1-baseline.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/03-tier1-baseline.sh
  # expected: all 7 exit 0; output line per API: "PASS <api-name>"
  ```
- **Rollback**: `mc rm --recursive --force local/maple-iceberg-test`
- **Owner**: investigator

### T2.2 Multipart upload (single + multi-part)

- **Files touched**: `scripts/minio-compat/04-multipart.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/04-multipart.sh
  # expected: 5MB single-part upload PASS, 12MB 3-part multipart PASS
  ```
- **Rollback**: `mc rm --recursive --force local/maple-iceberg-test`
- **Owner**: investigator

---

## Phase 3 — Tier 2 Conditional Writes + Versioning (20 min)

### T3.1 Enable versioning on test bucket

- **Files touched**: `scripts/minio-compat/05-versioning.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/05-versioning.sh
  # expected: VersioningConfiguration Status=Enabled
  mc version info local/maple-iceberg-test
  ```
- **Rollback**: `mc version suspend local/maple-iceberg-test`
- **Owner**: investigator

### T3.2 If-Match conditional PUT

- **Files touched**: `scripts/minio-compat/06-conditional-ifmatch.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/06-conditional-ifmatch.sh
  # expected:
  #   1st PUT (no precondition) → 200, eTag captured
  #   2nd PUT (If-Match=<etag>) → 200
  #   3rd PUT (If-Match=wrong) → 412 PreconditionFailed
  ```
- **Rollback**: N/A
- **Owner**: investigator

### T3.3 If-None-Match: * (PUT-create)

- **Files touched**: `scripts/minio-compat/07-conditional-ifnonematch.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/07-conditional-ifnonematch.sh
  # expected:
  #   1st PUT (If-None-Match: *) → 200
  #   2nd PUT (If-None-Match: *) → 412
  ```
- **Rollback**: N/A
- **Owner**: investigator

### T3.4 Multipart copy

- **Files touched**: `scripts/minio-compat/08-multipart-copy.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/08-multipart-copy.sh
  # expected: upload-part-copy on each part returns 200; complete-multipart-upload returns 200
  ```
- **Rollback**: `mc rm --recursive --force local/maple-iceberg-test`
- **Owner**: investigator

---

## Phase 4 — Lifecycle & Policy Review (15 min)

### T4.1 Capture current ILM for `data/runs/{runId}/...`

- **Files touched**: `scripts/minio-compat/09-lifecycle-capture.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/09-lifecycle-capture.sh
  # outputs: existing ILM rules for snapshots/, runs/, calculator/, ocid-mapping/;
  #          recommendation for data/runs/{runId}/... prefix
  ```
- **Rollback**: N/A
- **Owner**: investigator

### T4.2 Review existing SA policies (ext-api, calculator, synchronizer, cleanup)

- **Files touched**: `scripts/minio-compat/10-policy-review.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/10-policy-review.sh
  # outputs: each SA policy JSON; gap statement for analyst-readonly scenario
  ```
- **Rollback**: N/A
- **Owner**: investigator

---

## Phase 5 — Per-Engine Smoke (60 min)

### T5.1 Iceberg smoke

- **Files touched**: `scripts/minio-compat/11-iceberg-smoke.sh`, `scripts/minio-compat/iceberg-requirements.txt` (PyIceberg==0.9.0, s3fs==2024.6.0, pyarrow)
- **Catalog**: **Hadoop catalog** (`type=hadoop`, `s3.endpoint`, `s3.path-style-access=true`) — no REST catalog service required for smoke. PyIceberg CLI handles metadata writes via `s3fs` → `boto3` → MinIO. REST catalog (Polaris/Nessie) deferred to Phase 3.
- **Verify**:
  ```bash
  bash scripts/minio-compat/11-iceberg-smoke.sh
  # expected:
  #   - PyIceberg installed in venv
  #   - catalog init at s3://maple-iceberg-test/iceberg-smoke/db/tbl/
  #   - pyiceberg append writes 100 rows → metadata.json v2 created
  #   - pyiceberg read returns 100 rows
  #   - aws s3api put-object --bucket maple-iceberg-test --key iceberg-smoke/db/tbl/metadata/... --if-match <stale> → 412
  #   - aws s3api put-object --bucket maple-iceberg-test --key iceberg-smoke/db/tbl/metadata/... --if-match <current> → 200
  ```
- **Rollback**: `mc rm --recursive --force local/maple-iceberg-test/iceberg-smoke/ && rm -rf .venv-minio-compat/`
- **Owner**: investigator

### T5.2 Trino Hive connector smoke

- **Files touched**:
  - `scripts/minio-compat/12-trino-smoke.sh`
  - `scripts/minio-compat/trino-compose.yml` (compose overlay: trino:435 + hive metastore; volumes; env: `S3_ENDPOINT=http://host.docker.internal:9000`, `S3_PATH_STYLE_ACCESS=true`)
  - `scripts/minio-compat/trino-catalog/hive.properties` (connector.name=hive, hive.metastore.uri, hive.s3.endpoint, hive.s3.path-style-access=true)
- **Verify**:
  ```bash
  bash scripts/minio-compat/12-trino-smoke.sh
  # expected:
  #   - docker compose -f trino-compose.yml up -d (idempotent)
  #   - wait until `docker compose exec trino trino --execute 'SHOW CATALOGS'` returns hive
  #   - CREATE SCHEMA hive.test WITH (location='s3://maple-iceberg-test/trino/');
  #   - CREATE TABLE hive.test.t1 (id INT) WITH (format='PARQUET', external_location='s3://maple-iceberg-test/trino/t1/');
  #   - INSERT INTO hive.test.t1 VALUES (1),(2),(3); SELECT * FROM hive.test.t1; → 3 rows
  ```
- **Rollback**: `docker compose -f scripts/minio-compat/trino-compose.yml down -v && mc rm --recursive --force local/maple-iceberg-test/trino/`
- **Owner**: investigator

### T5.3 Spark S3A smoke

- **Files touched**: `scripts/minio-compat/13-spark-smoke.sh`, `scripts/minio-compat/Dockerfile.spark` (FROM apache/spark:3.5.1-python3, ADD hadoop-aws-3.3.4.jar + aws-java-sdk-bundle-1.12.262.jar to /opt/spark/jars/)
- **Version coupling**: Spark 3.5.1 requires hadoop-aws-3.3.4 (matching Spark's bundled hadoop-common) and aws-java-sdk-bundle-1.12.262 (pinned to avoid transitive drift). Both jars bundled in Docker image to skip runtime download.
- **Config**: `spark.hadoop.fs.s3a.endpoint=http://minio:9000`, `spark.hadoop.fs.s3a.path.style.access=true`, `spark.hadoop.fs.s3a.connection.ssl.enabled=false`, `spark.hadoop.fs.s3a.access.key`/`secret.key` from env.
- **Verify**:
  ```bash
  bash scripts/minio-compat/13-spark-smoke.sh
  # expected:
  #   - docker build -f Dockerfile.spark -t spark-minio:smoke .
  #   - spark-submit writes parquet to s3a://maple-iceberg-test/spark-smoke/
  #   - spark-submit reads it back; row count matches
  #   - fs.s3a.multipart.size=104857600 (100MB) and fs.s3a.multipart.threshold=104857600 confirmed via Spark conf dump
  ```
- **Rollback**: `docker rmi spark-minio:smoke && mc rm --recursive --force local/maple-iceberg-test/spark-smoke/`
- **Owner**: investigator

### T5.4 ClickHouse S3 disk smoke

- **Files touched**: `scripts/minio-compat/14-clickhouse-smoke.sh`
- **Verify**:
  ```bash
  bash scripts/minio-compat/14-clickhouse-smoke.sh
  # expected:
  #   - ClickHouse container started with S3 disk config
  #   - CREATE TABLE ... ENGINE=MergeTree() SETTINGS storage_policy='s3_policy'
  #   - INSERT → SELECT roundtrip succeeds
  #   - conditional PUT (If-None-Match: *) on new metadata → 200
  ```
- **Rollback**: `docker compose -f scripts/minio-compat/clickhouse-compose.yml down -v`
- **Owner**: investigator

---

## Phase 6 — Compatibility Matrix & Report (30 min)

### T6.1 Build compatibility matrix

- **Files touched**: `docs/03_Technical_Guides/minio-compatibility-report.md` (new)
- **Verify**:
  ```bash
  ls -la docs/03_Technical_Guides/minio-compatibility-report.md
  head -50 docs/03_Technical_Guides/minio-compatibility-report.md
  # expected: matrix table populated with SUPPORTED/PARTIAL/UNSUPPORTED/NOT-TESTED
  ```
- **Rollback**: `rm docs/03_Technical_Guides/minio-compatibility-report.md`
- **Owner**: investigator

### T6.2 Open gap issues

- **Files touched**: GitHub issues created via `gh issue create`
- **Verify**:
  ```bash
  gh issue list --label minio-compat-gap --state open
  # expected: ≥1 issue per Tier 2/3 gap found
  ```
- **Rollback**: close any premature issues
- **Owner**: investigator

---

## Phase 7 — Cleanup (5 min)

### T7.1 Remove test bucket

- **Files touched**: none
- **Verify**:
  ```bash
  mc rb --force local/maple-iceberg-test
  mc ls local/ | grep -v maple-expectation || echo CLEAN
  ```
- **Rollback**: N/A (test data only)
- **Owner**: investigator

---

## Verification Summary

- `./gradlew compileKotlin compileJava --continue` — N/A (no app code changed; scripts only)
- Smoke scripts: each `bash <script>.sh` exits 0 and prints PASS
- Compatibility report: matrix complete, gaps triaged
- GitHub issues: each gap → issue with `parent: #1338` reference

## Cleanup-on-Exit Contract

Every smoke script (T2.x, T3.x, T4.x, T5.x) MUST begin with a `trap` block so partial failures do not leave orphan containers, dangling test data, or half-stopped compose stacks:

```bash
#!/usr/bin/env bash
set -euo pipefail
source .env
mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

cleanup() {
  rc=$?
  echo "[cleanup] trap EXIT rc=$rc"
  mc rm --recursive --force local/maple-iceberg-test/${SCRIPT_PREFIX:-}/ 2>/dev/null || true
  [[ -n "${COMPOSE_FILE:-}" ]] && docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
  [[ -n "${IMAGE_TAG:-}" ]] && docker rmi "$IMAGE_TAG" 2>/dev/null || true
  exit $rc
}
trap cleanup EXIT INT TERM
```

`SCRIPT_PREFIX`, `COMPOSE_FILE`, `IMAGE_TAG` are set per script (e.g. T5.2 sets `COMPOSE_FILE=scripts/minio-compat/trino-compose.yml`). This guarantees the test environment is reset even if `set -e` aborts mid-run.

## Rollback Summary

- Test bucket: `mc rb --force local/maple-iceberg-test`
- Versioning: `mc version suspend local/maple-iceberg-test` (irrelevant after bucket delete)
- Scripts: `rm -rf scripts/minio-compat/`
- Report: `rm docs/03_Technical_Guides/minio-compatibility-report.md`
- Gap issues: close if opened in error
- Orphan containers from aborted smoke: `docker compose -f scripts/minio-compat/{trino,clickhouse}-compose.yml down -v`; `docker rmi spark-minio:smoke`