# V1: ObjectStorage Foundation — Design Spec

- Status: Draft → Approved (pending user review)
- Date: 2026-06-09
- Owner: zbnerd
- Parent spec: `docs/superpowers/specs/2026-06-09-minio-storage-migration-design.md`
- Implements: GitHub issue #1216 (VS1)
- Scope: foundation only — interface, both adapters, configuration, docker-compose, env

---

## 1. Background

The macro spec (parent) defines a 6-slice migration from local filesystem (`../data`) to MinIO for artifact storage. This is **slice VS1**: establish the `ObjectStorage` interface and ship both adapters (Local + MinIO) plus the docker-compose / .env configuration. No application call sites are changed in this slice — VS2 migrates the four pipeline modules to use the new interface.

Two micro-decisions were resolved during brainstorming:

1. **Retry strategy**: aws-sdk-java v2 built-in retry only. `RetryPolicy.defaultRetryPolicy()` in the SDK; no custom `withRetry` wrapper.
2. **Configuration binding**: `@ConfigurationProperties("storage.minio")` data class (multi-field config; matches the proven `CalculatorCleanupProperties` pattern in the repo).

## 2. Decision

Ship a single `ObjectStorage` interface in `module-common` (pure Kotlin, no Spring imports), two `@Component` adapters in `module-infra` (`LocalFsObjectStorage` and `MinioObjectStorage`), wired by a `StorageConfig` that selects the active backend via `storage.backend` property. `MinioObjectStorage` validates the bucket in `@PostConstruct` and fails the Spring application context on validation error (boot-time fatal). Add MinIO and `minio-init` services to `docker-compose.yml` with 2-day lifecycle policies matching ADR-390's 48h retention.

## 3. Goals

1. `ObjectStorage` interface exists in `module-common` with a clear, complete method set.
2. Both adapters functional and testable independently.
3. MinIO health validated at boot; silent failure modes eliminated.
4. docker-compose runs MinIO + minio-init with idempotent bucket init and lifecycle rules.
5. No application call sites change in this slice (deferred to VS2).

## 4. Non-Goals

- Migrating any module's storage call sites to `ObjectStorage` (VS2).
- `SnapshotObjectStore` thin wrapper (VS2).
- `ChunkFileReaderPort` / `DefaultChunkFileReader` (VS2).
- Production cutover, deprecation, removal of legacy ports (VS3-VS6).
- Backup / DR strategy for MinIO single-node (out of scope per macro spec §14).
- Pagination beyond 1000 objects per `listByPrefix` call (acceptable for VS1; revisit when object counts grow).

## 5. Architecture

### 5.1 Interface (module-common, pure Kotlin)

```kotlin
package maple.expectation.common.storage

import java.io.InputStream
import java.time.Instant

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

data class ObjectInfo(
    val key: String,
    val size: Long,
    val lastModified: Instant,
    val etag: String? = null,
)

data class PutResult(
    val key: String,
    val size: Long,
    val checksum: String?,  // SHA-256 hex (Local) | S3 ETag (MinIO). Caller must not assume algorithm.
)
```

`module-common` constraint preserved: zero Spring imports, verified by Gradle `verifyNoSpringDependency` task.

### 5.2 Adapters (module-infra, Spring `@Component`)

#### LocalFsObjectStorage

- `put`: write to `${key}.tmp.{uuid}`; `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` to final path
- `PutResult.checksum = sha256Hex(data)` (SHA-256 hex string)
- `get`/`getStream`: `Files.readAllBytes` / `Files.newInputStream`
- `delete`: `Files.deleteIfExists` (no-op if absent)
- `exists`: `Files.exists`
- `listByPrefix`: `Files.walk`; collect all `ObjectInfo` (eager, depth unlimited)
- `deleteByPrefix`: walk tree, sum bytes deleted
- `calculatePrefixSize`: walk tree, sum file sizes
- `getLastModified`: `Files.getLastModifiedTime`; `null` if `!exists`
- Optional `MeterRegistry` (Spring `@Autowired(required = false)`) — when present, records metrics

#### MinioObjectStorage

- `S3Client.builder()` with:
  - `endpointOverride(URI.create(props.endpoint))`
  - `region(Region.of(props.region))`
  - `credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))`
  - `serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())` (path-style required by MinIO)
  - `httpClient(ApacheHttpClient.builder().build())` (no Netty)
  - `overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.defaultRetryPolicy()).build())` — SDK built-in retry only
- `put`: `s3.putObject(req, RequestBody.fromBytes(data))`; `headObject` returns `eTag` for `PutResult.checksum`
- `putStream`: write input to temp file, `s3.putObject(req, tempFile)`, delete temp; `eTag` from `headObject`
- `get`: `s3.getObjectAsBytes(...)`
- `getStream`: `s3.getObject(...)` returns `ResponseInputStream`
- `delete`: `s3.deleteObject(...)` (idempotent — no error on missing key)
- `exists`: try `headObject`; catch `NoSuchKeyException` → false
- `listByPrefix`: `ListObjectsV2Request` with `prefix`; collect all (S3 pagination up to 1000 per call; SDK handles continuation transparently for v2's `ListObjectsV2`)
- `deleteByPrefix`: `ListObjectsV2` paginated; `DeleteObjectsRequest` (S3 max 1000 keys/req)
- `calculatePrefixSize`: `ListObjectsV2` paginated; sum `size()`
- `getLastModified`: `headObject.lastModified()`; `null` on `NoSuchKeyException`
- Optional `MeterRegistry` — same metrics surface as Local

### 5.3 StorageConfig + MinioProperties (module-infra)

```kotlin
@ConfigurationProperties("storage.minio")
data class MinioProperties(
    val endpoint: String,           // required (no default)
    val region: String = "us-east-1",
    val accessKey: String,          // required
    val secretKey: String,          // required
    val bucket: String,             // required
    val pathStyleAccess: Boolean = true,
)

@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${storage.local.base-path}") basePath: String,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = LocalFsObjectStorage(basePath, meterRegistry)

    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioObjectStorage(
        props: MinioProperties,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = MinioObjectStorage(props, meterRegistry)
}
```

`@ConfigurationProperties` chosen over `@Value` for multi-field config (6 fields), matching the `CalculatorCleanupProperties.kt` pattern in the repo.

### 5.4 Module Placement Summary

| Component | Module | Spring | Reason |
|-----------|--------|--------|--------|
| `ObjectStorage` interface | module-common | No | Pure Kotlin, no Spring (per module-common rule) |
| `ObjectInfo`, `PutResult` | module-common | No | Same as above |
| `LocalFsObjectStorage` | module-infra | `@Component` | `@Value` injection + optional MeterRegistry |
| `MinioObjectStorage` | module-infra | `@Component` | S3 client lifecycle, `@PostConstruct` |
| `MinioHealthIndicator` | module-infra | `@Component` | Spring Boot `HealthIndicator` |
| `MinioProperties` | module-infra | `@ConfigurationProperties` | Spring binding |
| `StorageConfig` | module-infra | `@Configuration` | Bean wiring |

`module-common` is **unchanged** for VS1 other than adding the interface + data classes (zero Spring imports).

## 6. Error Handling

### 6.1 Error Semantics

| Operation | Local | MinIO | Caller expectation |
|-----------|-------|-------|--------------------|
| `put` (key exists) | Atomic overwrite | S3 overwrite (idempotent) | Returns fresh `PutResult` |
| `put` (parent dir missing) | `mkdirs()` | S3 auto-creates prefix | OK |
| `get` (not found) | Throws `NoSuchFileException` | Throws `NoSuchKeyException` | Caller catches via LogicExecutor |
| `delete` (not found) | `Files.deleteIfExists` no-op | `s3.deleteObject` no error | OK (idempotent) |
| `exists` (not found) | `false` | `false` (catch `NoSuchKeyException`) | Boolean |
| `getLastModified` (not found) | `null` | `null` (catch `NoSuchKeyException`) | Nullable Instant |
| `listByPrefix` (prefix not found) | `emptyList()` | `emptyList()` | OK |

`get`/`getStream` on missing key throws → caller's `LogicExecutor.executeOrCatch` handles. No silent failure.

### 6.2 Boot-time Fatal (MinIO)

```kotlin
@Component
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
class MinioObjectStorage(...) : ObjectStorage {

    @PostConstruct
    fun validateBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
            log.info("[MinIO] bucket validated: {}", props.bucket)
        } catch (e: S3Exception) {
            throw IllegalStateException(
                "MinIO bucket '${props.bucket}' unreachable at ${props.endpoint} (status=${e.statusCode()}): ${e.message}", e
            )
        } catch (e: SdkClientException) {
            throw IllegalStateException(
                "MinIO endpoint '${props.endpoint}' unreachable: ${e.message}", e
            )
        }
    }
}
```

`S3Exception` (any 4xx/5xx) and `SdkClientException` (network) both fail boot. Spring application context fails to start. K8s/Coolify crash backoff restarts. No silent boot.

### 6.3 HealthIndicator (runtime visibility only)

```kotlin
@Component
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
class MinioHealthIndicator(
    private val props: MinioProperties,
    private val s3: S3Client,
) : HealthIndicator {
    override fun health(): Health = try {
        s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
        Health.up().withDetail("bucket", props.bucket).withDetail("endpoint", props.endpoint).build()
    } catch (e: Exception) {
        Health.down(e).withDetail("bucket", props.bucket).build()
    }
}
```

Exposed at `GET /actuator/health`. **Not** used as a liveness gate.

### 6.4 Metrics (Micrometer, optional registry)

When `MeterRegistry` is injected (Spring-managed), record on every operation:

- `object_storage_operation_total{op, backend, result}` (Counter) — `op`: put, putStream, get, getStream, delete, exists, listByPrefix, deleteByPrefix, calculatePrefixSize, getLastModified; `backend`: local, minio; `result`: success, error
- `object_storage_operation_duration_seconds{op, backend, result}` (Timer)
- `object_storage_bytes_total{op, backend}` (Counter) — sum of bytes for `put`/`putStream`/`delete`/`deleteByPrefix`

When `MeterRegistry` is null (e.g., in test contexts without Spring), metrics calls are no-ops via `?.` safe call.

## 7. Configuration

### 7.1 application-{module}.yml snippet

Each of the 4 active modules (`module-rest-controller`, `module-external-api`, `module-calculator`, `module-synchronizer`) gains:

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

`access-key`, `secret-key`, `bucket` have **no default**. Spring fails to bind `MinioProperties` when missing.

### 7.2 .env.example additions

```bash
# Storage backend (VS1)
STORAGE_BACKEND=local
STORE_BASE_PATH=../data

# MinIO (when STORAGE_BACKEND=minio)
MINIO_ROOT_USER=maple
MINIO_ROOT_PASSWORD=changeme
MINIO_ACCESS_KEY=maple
MINIO_SECRET_KEY=changeme
MINIO_ENDPOINT=http://minio:9000
MINIO_BUCKET=maple-expectation
MINIO_REGION=us-east-1
```

### 7.3 gradle/libs.versions.toml additions

```toml
[versions]
aws-sdk = "2.28.16"  # or latest stable as of implementation

[libraries]
aws-sdk-bom = { module = "software.amazon.awssdk:bom", version.ref = "aws-sdk" }
aws-sdk-s3 = { module = "software.amazon.awssdk:s3" }
aws-sdk-auth = { module = "software.amazon.awssdk:auth" }
aws-sdk-regions = { module = "software.amazon.awssdk:regions" }
aws-sdk-apache-client = { module = "software.amazon.awssdk:apache-client" }
```

### 7.4 module-infra/build.gradle additions

```groovy
dependencyManagement {
    imports {
        mavenBom libs.aws.sdk.bom.get()
    }
}

dependencies {
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)
    implementation(libs.aws.sdk.apache.client)
    // ... existing deps unchanged
}
```

Other modules consume `ObjectStorage` interface only; no direct aws-sdk deps needed.

## 8. Docker Compose

`docker-compose.yml` adds two services:

```yaml
minio:
  image: minio/minio:latest
  command: server /data --console-address ":9001"
  environment:
    MINIO_ROOT_USER: ${MINIO_ROOT_USER}
    MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
  volumes:
    - minio_data:/data
  networks:
    - maple-network
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
  networks:
    - maple-network
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

`volumes.minio_data` declared at the top-level `volumes:` section.

## 9. Testing Strategy

### 9.1 LocalFsObjectStorageTest (CI, always runs)

Path: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt`

JUnit 5 + `@TempDir` (JUnit 5 built-in):

- `put` → `get` round-trip
- `put` with checksum verification: `PutResult.checksum` is 64-char SHA-256 hex
- `put` with parent dir auto-created
- `exists`: true after put; false on fresh key
- `get` on missing key: throws `NoSuchFileException`
- `delete` on missing key: no-op (no exception)
- `deleteByPrefix`: nested dirs deleted; returned byte count matches
- `listByPrefix`: nested, returns full depth
- `getLastModified`: null for missing; non-null `Instant` for present
- `calculatePrefixSize`: matches sum of file sizes

### 9.2 MinioObjectStorageIT (integration test, env-gated)

Path: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt`

```kotlin
@EnabledIfEnvironmentVariable(named = "INTEGRATION_MINIO", matches = "true")
class MinioObjectStorageIT { ... }
```

Requires a running MinIO at `${MINIO_ENDPOINT}` (default `http://localhost:9000`) with valid credentials. Tests use a per-test-run prefix `minio-it-{UUID}/` to avoid cross-test interference.

- `put` → `get` round-trip with checksum verification
- `putStream` with large input
- `getStream` returns readable `InputStream`
- `exists`: true after put; false on fresh key
- `delete` on missing key: no error
- `listByPrefix`: nested, returns full depth
- `deleteByPrefix`: removes all matches; byte count matches
- `getLastModified`: null for missing; non-null for present
- `calculatePrefixSize`: matches sum of object sizes

CI: skip without env var. Dev: `INTEGRATION_MINIO=true ./gradlew :module-infra:test` against docker-compose MinIO.

### 9.3 Boot-time Fatal (manual smoke in VS3)

The `@PostConstruct` validation is best tested in a `SpringBootTest` slice, which is part of VS3's e2e validation. VS1's unit/integration tests verify the adapter methods work; VS3 verifies the boot-time fatal path end-to-end.

## 10. Trade-offs

### 10.1 Sensitivity

- **Object count per prefix** — `listByPrefix` is eager; 10k+ objects may cause memory pressure. Acceptable for VS1; revisit when counts grow.
- **MinIO network latency** — 5–50ms per call vs <1ms for local FS. Acceptable for foundation; performance impact assessed in VS3 with load-test.
- **Bucket lifecycle vs app cleanup** — lifecycle is a 2-day safety net; app cleanup is primary (per macro spec §6.5).

### 10.2 Trade-off Table

| Choice | Gain | Cost |
|--------|------|------|
| SDK built-in retry (no custom wrapper) | Single source of retry truth; less code | Cannot customize jitter/timing precisely |
| `@ConfigurationProperties` (vs `@Value`) | Type-safe; multi-field binding; proven pattern | One more class to maintain |
| Boot-time fatal (vs degraded) | No silent failure; orchestrator restarts cleanly | One extra restart on MinIO transient outage |
| `LocalFsObjectStorage` in module-infra (vs module-common) | Spring `@Value` + MeterRegistry injection | Couples adapter to Spring |
| `module-common` no Spring imports | Hexagonal purity; Gradle-verified | Interface cannot use Spring types |

### 10.3 Risks

- **AWS SDK version drift**: 2.28.x may have new releases before VS1 impl. Pin to a known-good version; bump via PR.
- **S3 `getObject` returns `ResponseInputStream` that holds a connection until closed**: callers must close the stream. Document in interface contract.
- **`PutResult.checksum` is not SHA-256 for MinIO**: S3 ETag is MD5 for single-part, composite for multipart. Callers must not assume algorithm. Documented in macro spec §5.1.

### 10.4 Non-Risks

- **Local disk performance**: unchanged from current code; same `Files.getLastModifiedTime` and `Files.walk` semantics.
- **`module-common` Spring-free**: Gradle `verifyNoSpringDependency` task enforces; no new deps.
- **Existing port interfaces untouched**: no migration in this slice; legacy ports still functional.

## 11. Migration Cutover

This slice introduces the new infrastructure but does **not** change any application call sites. Default `storage.backend=local` means all four modules continue to use their existing local filesystem adapters (`LocalSnapshotObjectStore`, `LocalExternalApiArtifactStoreAdapter`, calculator's `LocalObjectStorageAdapter`, direct FS in synchronizer readers). VS2 migrates each call site.

Rollback: delete the new code, revert yml, revert docker-compose, revert gradle. No production behavior change.

## 12. Documentation

- This spec at `docs/superpowers/specs/2026-06-09-v1-object-storage-foundation-design.md`
- Parent spec referenced in PR description
- Macro spec section §5 (Architecture), §7 (Configuration), §8 (Error Handling) already covers VS1 design — this spec refines and locks in micro-decisions
- No new ADR (ADR-720 covers the macro migration; VS1 is one slice)

## 13. Definition of Done

- [ ] `ObjectStorage` interface + `ObjectInfo` + `PutResult` declared in `module-common` under `maple.expectation.common.storage`
- [ ] `LocalFsObjectStorage` in `module-infra` implements `ObjectStorage`, all 10 methods, atomic put, SHA-256 checksum
- [ ] `MinioObjectStorage` in `module-infra` implements `ObjectStorage`, all 10 methods, `@PostConstruct` validates bucket, uses aws-sdk-java v2 with `RetryPolicy.defaultRetryPolicy()` and path-style access
- [ ] `MinioProperties` as `@ConfigurationProperties("storage.minio")` data class (6 fields, 4 with defaults)
- [ ] `StorageConfig` with two `@Bean` definitions gated by `@ConditionalOnProperty(storage.backend=...)`, default `local`
- [ ] `MinioHealthIndicator` exposes bucket status at `/actuator/health`, not a liveness gate
- [ ] `LocalFsObjectStorageTest` covers all 10 methods, passes in CI without external services
- [ ] `MinioObjectStorageIT` integration test, env-gated, passes against local docker-compose MinIO
- [ ] `docker-compose.yml` adds `minio` (with `mc ready` healthcheck) + `minio-init` (bucket + 4 lifecycle rules `--expiry-days 2`)
- [ ] `.env.example` includes `STORAGE_BACKEND`, `STORE_BASE_PATH`, `MINIO_*` variables
- [ ] All 4 active modules' `application*.yml` files include `storage.{backend,local,minio}` block
- [ ] `gradle/libs.versions.toml` adds `aws-sdk-*` entries
- [ ] `module-infra/build.gradle` adds aws-sdk deps with BOM
- [ ] `./gradlew compileKotlin compileJava --continue` clean across all modules
- [ ] `./gradlew :module-infra:test` clean
- [ ] `./gradlew :module-infra:test -Dorg.gradle.jvmargs="-DINTEGRATION_MINIO=true"` (with local docker-compose MinIO running) passes
- [ ] No new `!!`, `try-catch`, `join()/get()/runBlocking` (project policy)
- [ ] `module-common` has zero Spring imports (verified by Gradle `verifyNoSpringDependency` task)
- [ ] Boot smoke: with `STORAGE_BACKEND=minio` and a running MinIO, all 4 modules boot successfully; with MinIO stopped and `STORAGE_BACKEND=minio`, all 4 modules fail to start with the expected `IllegalStateException` in logs

## 14. Out of Scope (Future)

- Migration of any module's storage call sites (VS2)
- `SnapshotObjectStore` thin wrapper (VS2)
- `ChunkFileReaderPort` / `DefaultChunkFileReader` (VS2)
- `ObjectStorage` S3 Select / Glacier tiering
- `ObjectStorage` streaming pagination for 10k+ objects per prefix
- MinIO distributed mode 4-node + erasure coding (Backup/DR)
- `mc mirror` cron to external S3 for backup
- MinIO KMS / encryption at rest
