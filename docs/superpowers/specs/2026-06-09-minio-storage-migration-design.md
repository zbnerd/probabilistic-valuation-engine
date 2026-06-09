# MinIO Storage Migration — Design Spec

- Status: Draft → Approved (pending user review)
- Date: 2026-06-09
- Owner: zbnerd
- Supersedes scope: extends ADR-719 (abstraction) → adds MinIO adapter + migration
- Related ADRs: ADR-719 (object-storage abstraction), ADR-390 (artifact retention), ADR-715/716 (cache_storage)

---

## 1. Background

ADR-719 Accepted (2026-05-22) declared a two-phase plan: (1) unify three local filesystem storage interfaces under one `ObjectStorage` abstraction, (2) add a MinIO/S3 adapter when scale-out begins. Phase 1 was never executed. Today, modules communicate via three independent filesystem abstractions (calculator's `ObjectStorage`, external-api's `ExternalApiArtifactStorePort`, infra's `SnapshotObjectStore`) plus direct `Paths.get()` calls in synchronizer readers, scheduler phases, and cleanup schedulers. All paths resolve to `../data`.

The scale-out trigger from ADR-719 is now met: k8s/Coolify deployment is in progress and replica counts > 1 are imminent. Local-disk dependency blocks scale-out because consumers must run on the same host as the producer that wrote the artifact.

## 2. Decision

Replace the local filesystem (`../data`) shared between modules with MinIO (S3-compatible object storage) deployed alongside the four Spring Boot services in docker-compose (Coolify-managed in production). Introduce a single `ObjectStorage` interface in `module-common`, ship two adapters (Local, MinIO) in `module-infra`, switch each module's storage call sites to the new interface, gate the cutover behind `storage.backend=local|minio` feature flag, and migrate the cleanup schedulers to use the unified interface.

## 3. Goals

1. One storage interface, one source of truth for object key conventions.
2. MinIO production target; Local remains a "hot spare" for fast rollback.
3. No data loss during cutover; 24h TTL data can be abandoned on switch.
4. ADR-390 retention policy preserved (48h + keep recent 5 + ocid-mapping exempt).
5. Application cleanup is primary; MinIO bucket lifecycle is a 7-day safety net.

## 4. Non-Goals

- Multi-bucket layout (single bucket + prefix convention chosen for simplicity).
- Cross-region replication (single-node MinIO only for now).
- Migrating historical `../data` to MinIO (abandoned on cutover).
- Replacing `cache_storage` PostgreSQL L2 cache (separate concern, ADR-715/716).
- Replacing `character_valuation_views` PostgreSQL read model.

## 5. Architecture

### 5.1 Single ObjectStorage Interface (module-common)

```kotlin
package maple.expectation.common.storage

interface ObjectStorage {
    fun put(key: String, data: ByteArray): PutResult
    fun putStream(key: String, input: InputStream): PutResult
    fun get(key: String): ByteArray
    fun getStream(key: String): InputStream
    fun delete(key: String)
    fun exists(key: String): Boolean
    fun listByPrefix(prefix: String): List<ObjectInfo>
    fun deleteByPrefix(prefix: String): Long
    fun calculatePrefixSize(prefix: String): Long
    fun getLastModified(key: String): Instant?
}

data class ObjectInfo(val key: String, val size: Long, val lastModified: Instant, val etag: String? = null)
data class PutResult(val key: String, val size: Long, val checksum: String?)
```

`PutResult.checksum` is `sha256Hex(data)` for Local and S3 ETag for MinIO. Both are hex strings; callers must not assume SHA-256 unless they re-hash.

### 5.2 Adapters (module-infra)

Two `@Component` classes, each with `@ConditionalOnProperty(storage.backend=local|minio)`:

| Adapter | Backend | Module |
|---------|---------|--------|
| `LocalFsObjectStorage` | local FS | module-infra |
| `MinioObjectStorage` | MinIO/S3 (aws-sdk-java v2) | module-infra |

`LocalFsObjectStorage` is the unified replacement for the three existing local adapters; it consolidates `LocalObjectStorageAdapter`, `LocalExternalApiArtifactStoreAdapter`, and `LocalSnapshotObjectStore` logic.

`MinioObjectStorage` uses aws-sdk-java v2 (`software.amazon.awssdk:s3` + `apache-client`) with path-style access (required by MinIO). Retry policy: 3 attempts, exponential backoff 100ms/200ms/400ms + jitter, only for 5xx and `IOException`; 4xx fails immediately.

### 5.3 Port Mapping (deprecated → unified)

| Existing (deprecated) | New (ObjectStorage call) |
|----------------------|--------------------------|
| `SnapshotObjectStore.put/get/delete` | `put/get/delete` |
| `ExternalApiArtifactStorePort.store/read/listStoredKeys/listRuns/deleteRun/deleteAll/fileExists/calculateDirectorySize` | `put/get/listByPrefix` (caller filters) / `listByPrefix` / `deleteByPrefix` / `deleteByPrefix` / `exists` / `calculatePrefixSize` |
| `ObjectStorage (calculator).openInputStream/openOutputStream/exists/listDirectories/deleteDirectory/calculateDirectorySize` | `getStream/putStream/exists/listByPrefix/deleteByPrefix/calculatePrefixSize` |
| Synchronizer 3 readers direct FS | New port `ChunkFileReaderPort` → `ObjectStorage.getStream` |
| Phase schedulers (RankingFetch, OcidLookup) direct FS | `ObjectStorage.putStream` |
| Cleanup schedulers (ConsumedChunk, Artifact, CalculatorResult) direct FS | `ObjectStorage.delete/deleteByPrefix/listByPrefix/exists/calculatePrefixSize/getLastModified` |

### 5.4 New Port for Synchronizer Readers

```kotlin
// module-core/port/out/ChunkFileReaderPort.kt (new)
interface ChunkFileReaderPort {
    fun readBasicChunk(objectKey: String): List<BasicRecord>
    fun readResultChunk(objectKey: String): List<GroupedEquipmentResult>
    fun readOcidMapping(manifestPath: String): List<OcidMapping>
}
```

`DefaultChunkFileReader` (module-synchronizer) implements it using `ObjectStorage.getStream`. Replaces `BasicChunkFileReader`, `ResultFileReader`, `OcidMappingFileReader` and absorbs their CPU offload (Dispatchers.Default) pattern from issue #1129.

### 5.5 Single Bucket + Prefix Convention

```
s3://maple-expectation/
├── snapshots/{yyyy}/{MM}/{dd}/{jobId}.gz              # equipment response snapshots
├── runs/{runId}/_RUNNING                              # active run marker
├── runs/{runId}/ranking-overall/chunks/{shard}/{key}.jsonl.gz
├── runs/{runId}/ocid-lookup/...                       # legacy path
├── ocid-mapping/ocid-mapping-{runId}.jsonl.gz         # ign→ocid cache seed
├── calculator/runs/{runId}/{inputKey}.gz             # calculator inputs
└── calculator/runs/{runId}/{outputKey}.gz            # calculator results
```

Sharding (`{shard}` = first 2 chars of key) preserved from `LocalExternalApiArtifactStoreAdapter.resolvePath` for `runs/` prefix only; calculator and snapshot paths do not shard.

### 5.6 Module Placement & Dependencies

- `module-common`: `ObjectStorage` interface (pure Kotlin, no Spring)
- `module-infra`: `LocalFsObjectStorage`, `MinioObjectStorage`, `StorageConfig` (Spring wiring + `@ConditionalOnProperty`)
- `module-calculator`: depends on `ObjectStorage` interface; switches call sites
- `module-external-api`: depends on `ObjectStorage` interface; switches call sites
- `module-synchronizer`: depends on new `ChunkFileReaderPort`
- `module-infra` (worker/job): `SnapshotObjectStore` remains as a thin wrapper delegating to `ObjectStorage`. The wrapper absorbs `storageType` semantics: when invoked via `MinioObjectStorage`, it stamps `CalculationSnapshot.storageType = "S3"` (or generic `"OBJECT_STORE"`); when invoked via `LocalFsObjectStorage`, it stamps `"LOCAL"`. Callers (`ExternalApiWorker`, `NexonApiWorker`, `SnapshotCleanupWorker`) do not need to know the active backend.
- `gradle/libs.versions.toml`: add `aws-sdk-java-bom:2.28.x`; modules `s3`, `auth`, `regions`, `apache-client` (no `netty-nio-client`, no `spring-cloud-aws`)

## 6. Data Flow (MinIO Mode)

### 6.1 Write Path

```
external-api/scheduler/phase/RankingFetchPhase
  └─> objectStorage.putStream("runs/{runId}/ranking-overall/chunks/{shard}/{key}.jsonl.gz", gzip)
  └─> Kafka publish SnapshotChunkReadyEvent (objectKey only)

calculator/consumer/KafkaSnapshotChunkReadyConsumer
  └─> objectStorage.exists(event.objectKey)
  └─> Coordinator.executeChunk
      └─> objectStorage.getStream(inputKey)         # chunk read
      └─> SnapshotChunkProcessor.process(...)
      └─> objectStorage.putStream(resultKey, gzip)  # chunk write
      └─> Kafka publish ChunkResultEvent (objectKey only)

synchronizer/consumer/KafkaResultChunkConsumer
  └─> chunkFileReaderPort.readResultChunk(objectKey) # via ObjectStorage
      └─> processor → DB (character_valuation_views)
      └─> Kafka publish ChunkConsumedEvent (objectKey + sourceObjectKey)

external-api/cleanup/ConsumedChunkCleanupScheduler
  └─> objectStorage.delete(event.objectKey)
  └─> objectStorage.delete(event.sourceObjectKey)
```

### 6.2 Snapshot Path

```
infra/worker/ExternalApiWorker
  └─> snapshotStore.put(snapshot, equipmentResponseBytes)
      # SnapshotObjectStore → internally objectStorage.put
      # key = "snapshots/{yyyy}/{MM}/{dd}/{jobId}.gz"
  └─> DB save CalculationSnapshotEntity (objectKey, hash)

infra/job/SnapshotCleanupWorker
  └─> DB query: expiresAt < now
  └─> snapshotStore.delete(snapshot.objectKey)  # ObjectStorage.delete
  └─> DB delete metadata
```

### 6.3 OCID Mapping Path

```
external-api/scheduler/phase/OcidLookupPhase
  └─> objectStorage.putStream("ocid-mapping/ocid-mapping-{runId}.jsonl.gz", gzip)

external-api/cache/OcidCacheProvider.refresh()
  └─> objectStorage.listByPrefix("ocid-mapping/")  # sorted, last
  └─> objectStorage.getStream(latest)
  └─> parse → cacheRef.set(map)
```

### 6.4 Cleanup (ADR-390 unchanged, MinIO target)

| Scheduler | Module | Frequency | Operation |
|-----------|--------|----------:|-----------|
| `ArtifactCleanupScheduler` | external-api | 6h | `listByPrefix("runs/")` + retention filter + `deleteByPrefix("runs/{runId}/")` |
| `CalculatorResultCleanupScheduler` | calculator | 6h | `listByPrefix("calculator/runs/")` + `getLastModified` + retention + `deleteByPrefix` |
| `ConsumedChunkCleanupScheduler` | external-api | 1h (Kafka event) | `delete(objectKey)`, `delete(sourceObjectKey)` |
| `SnapshotCleanupWorker` | infra | 1h (DB-driven) | `snapshotStore.delete(objectKey)` (delegates to `ObjectStorage.delete`) |

Retention policy (ADR-390): keep if `_RUNNING` marker present, OR in most recent 5 runs, OR within 48h. Throttled: max 10 runs/cycle, 5GB/cycle, 60s/cycle. Dry-run by default.

### 6.5 MinIO Lifecycle Safety Net (2nd layer)

Applied via `mc ilm` during MinIO init job:

| Prefix | Expiry |
|--------|-------:|
| `snapshots/` | 2 days |
| `runs/` | 2 days |
| `calculator/` | 2 days |
| `ocid-mapping/` | 2 days |

Application cleanup is always faster (6h throttled) and more precise. MinIO lifecycle is a safety net if application cleanup is broken.

## 7. Configuration

### 7.1 docker-compose additions

```yaml
minio:
  image: minio/minio:latest
  command: server /data --console-address ":9001"
  environment:
    MINIO_ROOT_USER: ${MINIO_ROOT_USER}
    MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
  volumes:
    - minio_data:/data
  networks: [maple-network]
  healthcheck:
    test: ["CMD", "mc", "ready", "local"]
    interval: 5s
    timeout: 5s
    retries: 5

minio-init:
  image: minio/mc:latest
  depends_on:
    minio:
      condition: service_healthy
  networks: [maple-network]
  entrypoint: |
    /bin/sh -c "
    mc alias set local http://minio:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD;
    mc mb -p local/maple-expectation || true;
    mc anonymous set none local/maple-expectation;
    mc ilm add local/maple-expectation --expiry-days 2 --prefix 'snapshots/';
    mc ilm add local/maple-expectation --expiry-days 2 --prefix 'runs/';
    mc ilm add local/maple-expectation --expiry-days 2 --prefix 'calculator/';
    mc ilm add local/maple-expectation --expiry-days 2 --prefix 'ocid-mapping/';
    "
```

### 7.2 Application YAML

```yaml
storage:
  backend: ${STORAGE_BACKEND:local}      # local | minio
  local:
    base-path: ${STORE_BASE_PATH:../data}
  minio:
    endpoint: ${MINIO_ENDPOINT:http://minio:9000}
    region: ${MINIO_REGION:us-east-1}
    access-key: ${MINIO_ACCESS_KEY}
    secret-key: ${MINIO_SECRET_KEY}
    bucket: ${MINIO_BUCKET:maple-expectation}
    path-style-access: true
```

Existing per-module `*-api.yml` keys (`external-api.store.base-path`, `calculator.store.input-base-path`, `synchronizer.store.base-path`, `snapshot.store.local.base-path`) are deprecated and ignored when `storage.backend=minio`.

### 7.3 .env additions

```
STORAGE_BACKEND=local
MINIO_ROOT_USER=maple
MINIO_ROOT_PASSWORD=<from-secret>
MINIO_ACCESS_KEY=maple
MINIO_SECRET_KEY=<from-secret>
MINIO_ENDPOINT=http://minio:9000
MINIO_BUCKET=maple-expectation
```

## 8. Error Handling

### 8.1 Retry Policy (MinioObjectStorage)

3 attempts, exponential backoff 100ms → 200ms → 400ms + random jitter ≤50ms. Retry on 5xx `S3Exception` and `IOException` only. 4xx fails immediately. `NoSuchKeyException` → `exists()` returns false (catch and convert).

### 8.2 Idempotency

S3 put overwrites same key (idempotent). Local put uses atomic temp-file move (idempotent on retry). Both safe to retry.

### 8.3 Race Conditions

- **Cleanup vs read within same prefix**: S3 strong consistency. Within a run, `_RUNNING` marker prevents premature deletion. Without marker, synchronizer has finished read.
- **Snapshot TTL vs resume**: 24h TTL, 1h cleanup interval. Resume uses DB `objectKey` to read; if cleanup deletes first, resume retries (idempotent — external API re-fetch). No mitigation needed.
- **Cross-prefix race**: not possible; prefixes are disjoint.

### 8.4 Cold Start

`minio-init` job creates bucket and applies lifecycle rules before Spring Boot apps start (via `depends_on.condition: service_healthy`). Idempotent (`|| true` on `mc mb`).

### 8.5 Boot-time Validation (Fatal)

`MinioObjectStorage` constructor (or `@PostConstruct` hook) calls `s3.headBucket()`. On `S3Exception` (4xx/5xx) or `SdkClientException` (network unreachable), the bean construction throws. Spring application context fails. Coolify/K8s crash backoff triggers restart.

Rationale: a silent app start with no MinIO would consume Kafka messages, fail every read/write, and appear "healthy" to liveness probes — pipeline halts but no recovery. Boot-time fatal forces the orchestrator to retry until MinIO is reachable, and Prometheus alerts on repeated crash loops.

`MinioHealthIndicator` (Spring Boot `HealthIndicator`) is exposed at `/actuator/health` for runtime visibility — but is **not** a liveness gate.

### 8.6 Metrics

- `object_storage_operation_total{op, backend, result}` (Counter)
- `object_storage_operation_duration_seconds{op, backend}` (Timer)
- `object_storage_retry_total{op, reason}` (Counter)
- `object_storage_bytes_total{op}` (Counter, size sum)

Scraped via existing Prometheus endpoints (8081, 8082, 8083).

## 9. Trade-offs

### 9.1 Sensitivity

- Chunk size (1–8MB gzipped) — S3 API latency vs local FS
- Throughput (250 chunks/s peak) — S3 rate limit
- MinIO single-node reliability — SPOF
- Cleanup scheduler iteration count (synchronizer 3 readers + cleanup 2)

### 9.2 Trade-off Table

| Choice | Gain | Cost |
|--------|------|------|
| Single bucket + prefix | Simple ops; one lifecycle config | One bucket failure affects all |
| App cleanup + MinIO lifecycle | Two-layer safety | Debug correlation harder |
| Feature flag + per-module migration | Zero-downtime; per-module rollback | More YAML, more config |
| aws-sdk-java v2 (not minio-java) | Vendor-flexible (S3/R2/NCloud later) | Larger dep; more boilerplate |
| Abandon in-flight data | Zero migration code; no duplication | Lose < 24h of in-flight artifacts |
| Single spec (not decomposed) | One PR sequence; no integration risk | Larger spec doc |

### 9.3 Risks

- `ObjectStorage` interface may not satisfy all 3 domains → mitigated by 4 unit tests covering each operation
- aws-sdk-v2 + Kotlin coroutine integration needs `runBlocking` carefully scoped → all calls go through sync `ObjectStorage` interface (no coroutine in adapter)
- Synchronizer reader refactor is the largest single change → kept in its own PR with port contract test

### 9.4 Non-Risks

- Local disk performance: unchanged (Local adapter is reference impl; same Paths.get internally)
- Existing pipeline behavior: interface only; logic unchanged
- MinIO → S3 migration: endpoint URL swap (aws-sdk-v2 is S3-compatible)
- Data loss: bounded to 24h TTL artifacts; in-flight by definition

## 10. Cutover Sequence (Atomic)

Cross-module artifact reads (`external-api` → `calculator` → `synchronizer`) make per-module cutover broken: writer and reader must share the same backend. Single atomic cutover with maintenance window is the only safe path.

Single spec, but implemented across multiple PRs:

| Step | PR | Action | Risk | Rollback |
|------|---:|--------|------|----------|
| W0.1 | 1 | Add `ObjectStorage` interface in `module-common` + unit tests | Interface design bug | Git revert |
| W0.2 | 2 | Add `LocalFsObjectStorage` + `MinioObjectStorage` in `module-infra` + `StorageConfig` | Adapter bug | Revert |
| W0.3 | 3 | Add MinIO + minio-init services to `docker-compose.yml` | Compose error | Compose down |
| W1.0 | 4 | Switch `module-calculator` call sites to `ObjectStorage` (default `storage.backend=local`) | Calculator regression | `storage.backend=local` |
| W1.5 | 5 | Switch `module-external-api` (phases, cleanup) to `ObjectStorage` | ext-api regression | `storage.backend=local` |
| W1.7 | 6 | Switch `module-infra` `SnapshotObjectStore` to thin-wrapper (delegates to `ObjectStorage`) | Snapshot regression | `storage.backend=local` |
| W2.0 | 7 | Add `ChunkFileReaderPort`; switch `module-synchronizer` 3 readers to `DefaultChunkFileReader` (IO/CPU 분리 per §11.1) | Synchronizer regression | `storage.backend=local` |
| W2.5 | 8 | Dev/staging environment: set `storage.backend=minio`; run `load-test` for performance regression; full e2e checklist (§11.3) | Performance regression | `storage.backend=local` |
| W3.0 | 9 | **PRODUCTION ATOMIC CUTOVER**: maintenance window. All 4 modules restart with `storage.backend=minio`. `../data` abandoned; in-flight data on Local is unreachable (acceptable: 24h TTL). | Total pipeline halt if rollback needed | Restart all modules with `storage.backend=local`; rebuild any new artifacts (Nexon API re-fetch idempotent) |
| W3.5 | 10 | Default `storage.backend=minio` in production yaml; deprecate 2 fully-replaced ports (`ExternalApiArtifactStorePort`, calculator's `ObjectStorage`). `SnapshotObjectStore` stays as a thin wrapper per §5.5. | — | Revert |
| W4 | 11 | (Optional) Remove deprecated ports + `Local*` adapter code | — | Revert |

**Cutover semantics**: From W0.1 to W2.5 the system runs entirely on `local`. At W3.0 all 4 modules flip simultaneously. There is no mixed-backend runtime state in production.

## 11. Testing Strategy

### 11.1 Unit Tests (CI)

- `LocalFsObjectStorageTest` (`module-common` test): put/get round-trip, atomic put (tmp → move), exists, listByPrefix nested, deleteByPrefix, getLastModified, calculatePrefixSize
- All existing `ObjectStorage` mock tests in `module-calculator/CalculatorChunkProcessingCoordinatorTest.kt` and `module-app/SkipEquipmentL2CacheContextTest.java` continue working (mock target renamed)

**`DefaultChunkFileReader` IO/CPU 분리 pattern (regression test)**:

The existing synchronizer readers conflate IO and CPU inside `runBlocking(Dispatchers.Default)`. With Local FS this is harmless (`Files.newInputStream` is sub-millisecond). With MinIO the S3 `getObject` call is a 50–200ms blocking network call, which would block the `Default` pool (size = `availableProcessors()`) and starve CPU work.

The new `DefaultChunkFileReader` MUST separate the two:

```kotlin
// module-synchronizer/storage/DefaultChunkFileReader.kt
fun readBasicChunk(objectKey: String): List<BasicRecord> = runBlocking {
    val rawBytes = withContext(Dispatchers.IO) {
        objectStorage.get(objectKey)              // IO on IO pool (VT-friendly)
    }
    withContext(Dispatchers.Default) {            // CPU on Default pool
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            // JSON parse, dedup, etc.
        }
    }
}
```

- Unit test: `DefaultChunkFileReaderTest` mocks `ObjectStorage.get` to return a known GZIP-compressed JSONL. Verifies (a) parse correctness, (b) `Dispatchers.Default` is invoked for CPU, (c) `Dispatchers.IO` is invoked for IO (via `TestDispatcher` instrumentation if feasible; otherwise verify the call is non-blocking by timeout assertion).
- Existing CPU-offload tests for `BasicChunkFileReader`, `ResultFileReader`, `OcidMappingFileReader` (issue #1129) are superseded.

### 11.2 Component Tests (CI skipped, dev only)

`@EnabledIfEnvironmentVariable(INTEGRATION_MINIO=true)`:
- `MinioObjectStorageIT` against localhost:9000 (docker-compose MinIO)
- Covers put/get round-trip, getStream, listByPrefix, deleteByPrefix, _RUNNING marker semantics, NoSuchKey, retry on simulated 5xx

### 11.3 Dev Environment (manual gate)

Pre-merge per `workflow-rules.md` + this spec:

1. `docker compose up -d` (4 modules + MinIO + minio-init) — all healthy
2. `mc ls local/maple-expectation/` — bucket exists
3. `mc ilm ls local/maple-expectation/` — 4 lifecycle rules
4. `curl http://localhost:8080/actuator/health` — all indicators UP (including MinIO)
5. `curl http://localhost:8080/api/v5/characters/{ign}/expectation` — 202 + follow logs
6. MinIO console (`:9001`) — confirm `snapshots/.../jobId.gz`, `runs/.../chunks/...`, `ocid-mapping/...` exist
7. Calculator + Synchronizer logs — `Calculation completed with result saved`, no `ERROR`
8. `CalculatorResultCleanupScheduler` and `ArtifactCleanupScheduler` dry-run logs — prefixes scanned

### 11.4 Load Test (optional, recommended)

Run existing `load-test/run-v5-db-throughput.sh` with `storage.backend=minio`. Compare RPS, p99 latency, MinIO container disk usage to baseline (Local). Expected: <5% RPS regression.

## 12. Documentation

- `docs/01_ADR/ADR-720_object-storage-minio-migration.md` (new, Accepted) — captures this spec's trade-off summary, references ADR-719 as the abstraction decision and ADR-390 as the retention policy
- `docs/01_ADR/ADR-719_object-storage-abstraction-minio-readiness.md` — annotate "Phase 2 in progress" (do not Supersede; ADR-720 is the phase 2 follow-up)
- README.md — note `STORAGE_BACKEND` env var and docker-compose changes

## 13. Definition of Done

- [ ] `ObjectStorage` interface merged with unit tests
- [ ] `LocalFsObjectStorage` and `MinioObjectStorage` merged with tests
- [ ] `StorageConfig` with `@ConditionalOnProperty` merged
- [ ] docker-compose MinIO + minio-init merged
- [ ] All 4 modules switched to `ObjectStorage`; `ExternalApiArtifactStorePort` and calculator's `ObjectStorage` marked `@Deprecated` (see §5.5 for `SnapshotObjectStore` rationale)
- [ ] Synchronizer 3 readers consolidated into `DefaultChunkFileReader` implementing `ChunkFileReaderPort`
- [ ] Cleanup schedulers updated to use `ObjectStorage` (no direct `Paths.get()`)
- [ ] `.env.example` updated with `STORAGE_BACKEND` and `MINIO_*` variables
- [ ] Dev e2e verification checklist (11.3) passed
- [ ] `load-test` run with MinIO shows < 5% RPS regression vs Local
- [ ] ADR-720 written and committed
- [ ] No new `!!`, `try-catch`, `join()/get()/runBlocking` introduced (project policy)
- [ ] No `module-web → module-infra` direct import (Hexagonal preserved)
- [ ] CI: `./gradlew compileKotlin compileJava --continue` clean
- [ ] CI: `./gradlew test` clean

## 14. Out of Scope (Future)

- **Backup / Disaster Recovery**: single-node MinIO is a single point of failure. This spec assumes MinIO node durability and Coolify-level host availability. Backup strategy (e.g., `mc mirror` cron to S3 Glacier, or MinIO distributed mode 4-node with erasure coding) is a follow-up spec. The project deploys via Coolify (self-hosted PaaS) rather than managed k8s, so backup/DR decisions defer to Coolify's host-level strategy.
- Replacing `SnapshotObjectStore` port with direct `ObjectStorage` use (current plan keeps it as a thin wrapper for legacy code stability)
- Cross-region MinIO replication
- MinIO KMS / encryption at rest
- Pre-signed URL generation for client-side download
- `ObjectStorage` S3 Select / S3 Glacier tiering
