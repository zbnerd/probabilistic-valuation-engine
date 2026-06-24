# Spec: MinIO Compatibility Validation

- Status: Proposed
- Date: 2026-06-23
- Owner: Architecture Team
- Parent Issue: #1338
- Parent ADR: ADR-735

---

## 1. Goal

Validate that the existing MinIO deployment supports the full S3 API surface required by the four candidate analytics engines called out in ADR-735 §2:

- Apache Iceberg (S3FileIO)
- Trino (Hive connector)
- Spark (S3A connector)
- ClickHouse (S3 disk type)

Produce a compatibility matrix, capture MinIO version + configuration, and surface gaps as follow-up implementation tickets. Investigation only.

## 2. Non-Goals

- Upgrading MinIO to a newer release (separate ticket)
- Modifying bucket policies
- Data migration or backfill
- Standing up Iceberg/Trino/Spark/ClickHouse in production
- Modifying Calculator's MinIO writer path (Calculator is untouched per ADR-735 §2)

## 3. Background

ADR-735 §2 escalates the analytics tier through PG → ClickHouse → Iceberg+Trino → Iceberg+Spark. Phase 2 trigger T6 (cross-source SQL join on MinIO + PG) implies Iceberg adoption on top of the existing MinIO bucket.

Per ADR-735 §4 evidence, MinIO RELEASE 2024-05+ is required for safe concurrent writer commits via `If-Match` conditional writes. Today's deployed image is `minio/minio:latest` (rolling tag — actual version unknown until inspected).

MinIO is currently used by Calculator for stateless JSONL.gz chunk artifacts under `data/runs/{runId}/...` and by External API for `snapshots/`. Lifecycle rules are configured for `snapshots/`, `runs/`, `calculator/`, `ocid-mapping/` (2-day expiry, idempotent bootstrap).

## 4. Design

### 4.1 Test Bucket Isolation

All compatibility tests run against a separate bucket `maple-iceberg-test` so the production `maple-expectation` bucket is never polluted.

### 4.2 Test Environment

- Local MinIO from `docker-compose.yml` at `http://localhost:9000`
- Test bucket created by the test scripts (not the bootstrap script)
- Operator tools: `mc` (MinIO client) + `aws s3api` (AWS CLI v2)

### 4.3 S3 API Test Matrix

**Tier 1 (Baseline — required by all four engines)**

| API | Test Method | Required For |
|-----|-------------|--------------|
| `ListObjectsV2` | `aws s3api list-objects-v2 --bucket ... --max-keys 1000` | All |
| `GetObject` | `aws s3api get-object` (single + range) | All |
| `PutObject` (single) | `aws s3api put-object` < 5MB | All |
| `CopyObject` | `aws s3api copy-object` | Iceberg, ClickHouse |
| `DeleteObjects` (batch) | `aws s3api delete-objects --delete ...` | Iceberg, Spark |
| `HeadObject` | `aws s3api head-object` | All |
| `HeadBucket` | `aws s3api head-bucket` (health check) | All |

**Tier 2 (Correctness — required by ≥1 engine)**

| API | Test Method | Required For |
|-----|-------------|--------------|
| `If-Match` conditional PUT | `aws s3api put-object --if-match <etag>` | Iceberg (safe concurrent writer) |
| `If-None-Match: *` PUT | `aws s3api put-object --if-none-match "*"` | ClickHouse S3PlainRewritable |
| Object versioning | `aws s3api put-bucket-versioning --versioning-configuration Status=Enabled` | Iceberg (snapshot isolation) |
| Multipart upload (5MB+ part) | `aws s3api create-multipart-upload` + `upload-part` + `complete` | All engines for >threshold |
| Multipart copy | `aws s3api upload-part-copy` | Iceberg |

**Tier 3 (Operational)**

| API | Test Method | Required For |
|-----|-------------|--------------|
| Lifecycle / ILM | `mc ilm ls local/<bucket>` | All (cost / retention) |
| Bucket policy review | `mc admin policy info` | Security |
| Server-side encryption | `aws s3api put-object --server-side-encryption AES256` | Optional; flag as gap |

### 4.4 Version Matrix

| Component | Test Version | Source |
|-----------|--------------|--------|
| MinIO server | `mc admin info` output (capture actual) | Live |
| `mc` client | `latest` (Alpine) | Docker |
| AWS CLI v2 | `latest` | pip / brew |
| Apache Iceberg | 1.6.x (latest stable at test time) | Maven Central |
| Trino | 435+ (Hive connector support) | trino.io |
| Spark | 3.5.x | spark.apache.org |
| ClickHouse | 24.x LTS | clickhouse.com |

### 4.5 Compatibility Matrix Output

Produce a single table mapping each engine → required S3 API → MinIO version status (SUPPORTED / PARTIAL / UNSUPPORTED / NOT-TESTED). Document under `docs/03_Technical_Guides/minio-compatibility-report.md`.

### 4.6 Per-Engine Smoke Tests

For each engine, run a 5-minute smoke that exercises the engine against `maple-iceberg-test`:

- Iceberg: write a sample table → read it back → conditional write on metadata.json
- Trino: create Hive schema backed by S3 → `SELECT` from a parquet file
- Spark: write a parquet via S3A → read it back
- ClickHouse: create S3PlainRewritable disk → INSERT → SELECT

Smoke scripts live under `scripts/minio-compat/<engine>-smoke.sh` (committed to repo).

## 5. Acceptance Criteria

- [ ] MinIO version + release date captured (`mc admin info` output saved)
- [ ] Tier 1 S3 APIs (7 APIs) all tested and pass
- [ ] Tier 2 conditional writes (`If-Match`, `If-None-Match:*`) tested
- [ ] Object versioning enabled on `maple-iceberg-test` and verified
- [ ] Multipart upload threshold + part size documented
- [ ] Lifecycle policy for `data/runs/{runId}/...` captured (current + recommended)
- [ ] Bucket policy / IAM reviewed for read-only analyst scenario (gap statement)
- [ ] Per-engine smoke tests executed; results captured
- [ ] Compatibility matrix published at `docs/03_Technical_Guides/minio-compatibility-report.md`
- [ ] Test scripts committed at `scripts/minio-compat/`
- [ ] Each identified gap → separate implementation issue, cross-referenced to #1338

## 6. Trade-offs

### Sensitivity

- MinIO server version (rolling tag `latest` introduces variability)
- Iceberg / Trino / Spark / ClickHouse version pinned at test time
- Local docker-compose MinIO vs production deployment (single-node vs distributed)
- Test bucket path-style access dependency

### Trade-off

| Choice | Get | Give up |
|--------|-----|---------|
| Separate `maple-iceberg-test` bucket | Production `maple-expectation` untouched | Need to bootstrap test bucket manually |
| Rolling `minio/minio:latest` for tests | Always tests latest compatibility | Reproducibility drift between runs |
| Per-engine smoke (not full integration) | Quick validation of API surface | Misses engine-specific quirks |
| Investigation only (no upgrade) | Aligned with parent issue scope | Any gap found → separate ticket |

### Risk

- MinIO version older than 2024-05 → conditional writes unsupported → Iceberg Phase 2 blocked. Mitigation: gap ticket immediately surfaces upgrade need.
- Single-node MinIO (local compose) hides distributed-mode behavior. Mitigation: flag gap for production-mode validation.

### Non-Risk

- Calculator throughput / writer path (out of scope, untouched)
- Production bucket data (test bucket is separate)

## 7. References

- Parent issue: #1338
- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- Issue: ADR-735 §4 (MinIO 2024-05+ requirement)
- Source: Apache Iceberg S3FileIO javadoc
- Source: Trino Hive connector docs
- Source: Spark S3A docs
- Source: ClickHouse S3 disk docs
- Bootstrap script: `docker/minio/bootstrap.sh`
- Current adapter: `module-infra/.../storage/MinioObjectStorage.kt`