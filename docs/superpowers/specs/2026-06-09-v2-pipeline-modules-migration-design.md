# V2: Pipeline Modules Migration — Design Spec

- Status: Draft → Approved (pending user review)
- Date: 2026-06-09
- Owner: zbnerd
- Parent spec: `docs/superpowers/specs/2026-06-09-minio-storage-migration-design.md`
- Implements: GitHub issue #1217 (VS2)
- Scope: migration of 4 application modules to the unified `ObjectStorage` interface (added in VS1), plus `SnapshotObjectStore` thin wrapper, plus `ChunkFileReaderPort` + `DefaultChunkFileReader` with IO/CPU 분리. **Default backend = `local`** (atomic cutover to MinIO happens in VS3+VS4).

---

## 1. Background

VS1 (issue #1216, merged as PR #1222) shipped the unified `ObjectStorage` interface and both adapters (`LocalFsObjectStorage`, `MinioObjectStorage`). No application call sites were changed. The codebase still has three legacy port interfaces (`SnapshotObjectStore`, `ExternalApiArtifactStorePort`, calculator's local `ObjectStorage`) plus direct `Paths.get()` access in synchronizer readers and scheduler phases. This slice migrates the four application modules to the new interface, scoped as one PR with staged commits.

Pre-existing compile breakage in `module-rest-controller`, `module-external-api`, and `module-synchronizer` (unresolved references like `UrgentReadStatus`, `kotlinx.Dispatchers`, `GameCharacterMicroBatchAdapter` deps) blocks compile verification. This slice includes a precondition commit to fix the most-impacted files so that the full module set compiles after VS2.

## 2. Decision

Migrate 4 application modules to the unified `ObjectStorage` (default `local`). Add `SnapshotObjectStoreAdapter` thin wrapper preserving the existing port API. Add `ChunkFileReaderPort` in `module-core` and `DefaultChunkFileReader` in `module-synchronizer` (1 class, 3 methods) with **IO/CPU 분리**. Deprecate the two fully-replaced port interfaces (`ExternalApiArtifactStorePort` and calculator's local `ObjectStorage`); `SnapshotObjectStore` stays as a thin wrapper. Single PR with staged commits per module. Backend-specific `storageType` (`"S3"` or `"LOCAL"`).

## 3. Goals

1. All application code in `module-calculator`, `module-external-api`, `module-synchronizer`, `module-infra` flows through the unified `ObjectStorage`.
2. `SnapshotObjectStore` port preserved unchanged; `SnapshotObjectStoreAdapter` is the sole implementation.
3. `ChunkFileReaderPort` consolidates three reader interfaces; synchronizer consumer call sites inject this single port.
4. `DefaultChunkFileReader` separates IO (`Dispatchers.IO`) from CPU (`Dispatchers.Default`) — fixes the `runBlocking(Dispatchers.Default)` issue from spec §11.1 (CPU pool blocked on S3 network calls).
5. `ExternalApiArtifactStorePort` and calculator's local `ObjectStorage` are marked `@Deprecated(forRemoval=true)` with Javadoc pointing to the unified `ObjectStorage`. Removal is a separate cleanup (issue #1221).
6. Pre-existing compile breakage in 4 files is fixed as a precondition commit so that the full module set compiles after VS2.

## 4. Non-Goals

- Production cutover to `storage.backend=minio` (VS3+VS4 atomic cutover).
- Removal of deprecated ports (issue #1221, separate cleanup).
- New metrics (`object_storage_operation_total` etc. — spec §6.4 optional, deferred).
- Pre-existing compile errors **outside** the 4 files needed for VS2 verification (e.g., `module-rest-controller/urgent/UrgentCharacterNotFoundConsumer.kt` is not in VS2 path; left for a separate issue).
- `SnapshotObjectStore` port signature change (preserved unchanged).
- Synchronizer consumer call site logic changes (only the storage dependency changes; the consumer's `runBlocking` pattern is unchanged).

## 5. Architecture

### 5.1 New types in `module-core`

```kotlin
// module-core/.../core/port/out/ChunkFileReaderPort.kt (NEW)
package maple.expectation.core.port.out

import maple.expectation.core.model.chunk.BasicRecord
import maple.expectation.core.model.chunk.GroupedEquipmentResult
import maple.expectation.core.model.chunk.OcidMapping

/**
 * Consolidated chunk file reader for the synchronizer pipeline.
 * Replaces 3 separate reader classes (BasicChunkFileReader, ResultFileReader,
 * OcidMappingFileReader). All methods delegate to the unified ObjectStorage.
 *
 * Implementations: DefaultChunkFileReader in module-synchronizer.
 */
interface ChunkFileReaderPort {
    fun readBasicChunk(objectKey: String): List<BasicRecord>
    fun readResultChunk(objectKey: String): List<GroupedEquipmentResult>
    fun readOcidMapping(manifestPath: String): List<OcidMapping>
}
```

```kotlin
// module-core/.../core/model/chunk/BasicRecord.kt (MOVED from module-synchronizer/storage/BasicRecord.kt)
package maple.expectation.core.model.chunk

import maple.expectation.util.GzipUtils
import maple.expectation.util.HashUtils
import com.fasterxml.jackson.databind.JsonNode

data class BasicRecord(
    val userIgn: String,
    val ocid: String,
    val worldName: String?,
    val characterClass: String?,
    val characterLevel: Int?,
    val guildName: String?,
    val compressedBody: ByteArray,
    val bodyHash: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
```

```kotlin
// module-core/.../core/model/chunk/GroupedEquipmentResult.kt (MOVED)
package maple.expectation.core.model.chunk

data class GroupedEquipmentResult(
    val readKey: String,
    val ocid: String,
    val presetNo: Int,
    val items: List<CalculatedEquipmentItem>,
)
```

```kotlin
// module-core/.../core/model/chunk/OcidMapping.kt (MOVED)
package maple.expectation.core.model.chunk

data class OcidMapping(
    val userIgn: String,
    val ocid: String,
)
```

`module-synchronizer` re-exports the moved types (so existing imports like `maple.synchronizer.domain.BasicRecord` keep working):

```kotlin
// module-synchronizer/.../domain/ChunkDomainReexport.kt (NEW)
// Re-exports from module-core to preserve consumer-side import paths
typealias BasicRecord = maple.expectation.core.model.chunk.BasicRecord
typealias GroupedEquipmentResult = maple.expectation.core.model.chunk.GroupedEquipmentResult
typealias OcidMapping = maple.expectation.core.model.chunk.OcidMapping
```

### 5.2 New adapter: `SnapshotObjectStoreAdapter` (module-infra)

```kotlin
// module-infra/.../infrastructure/external/snapshot/SnapshotObjectStoreAdapter.kt (NEW)
package maple.expectation.infrastructure.external.snapshot

import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.core.port.out.SnapshotObjectStoreResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Thin wrapper: SnapshotObjectStore port → ObjectStorage unified.
 * 3 callers (ExternalApiWorker, NexonApiWorker, SnapshotCleanupWorker)
 * continue using SnapshotObjectStore port unchanged.
 *
 * storageType is determined at Spring context init from `storage.backend`.
 * CalculationSnapshot.storageType is populated by callers; this wrapper
 * does not overwrite that field. The active backend is logged at init
 * time for debugging.
 */
@Component
class SnapshotObjectStoreAdapter(
    private val objectStorage: ObjectStorage,
    @Value("\${storage.backend:local}") private val storageBackend: String,
) : SnapshotObjectStore {

    private val log = LoggerFactory.getLogger(SnapshotObjectStoreAdapter::class.java)

    init {
        log.info("[SnapshotStore] active backend: storageType={}", activeStorageType())
    }

    override fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
        val result: PutResult = objectStorage.put(snapshot.objectKey, data)
        return SnapshotObjectStoreResult(
            objectKey = result.key,
            compressedSize = result.size,
            // Local: SHA-256 hex; MinIO: S3 ETag. Caller treats as opaque hash.
            hash = result.checksum,
        )
    }

    override fun get(objectKey: String): ByteArray = objectStorage.get(objectKey)

    override fun delete(objectKey: String) = objectStorage.delete(objectKey)

    private fun activeStorageType(): String = when (storageBackend) {
        "minio" -> "S3"
        else -> "LOCAL"
    }
}
```

The existing `LocalSnapshotObjectStore` is **deleted**. The `SnapshotObjectStore` port interface in `module-core` is preserved (its `put(snapshot, data)` signature is unchanged per Decision §2).

### 5.3 New: `DefaultChunkFileReader` (module-synchronizer)

```kotlin
// module-synchronizer/.../storage/DefaultChunkFileReader.kt (NEW)
package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.core.model.chunk.BasicRecord
import maple.expectation.core.model.chunk.GroupedEquipmentResult
import maple.expectation.core.model.chunk.OcidMapping
import maple.expectation.core.port.out.ChunkFileReaderPort
import maple.synchronizer.domain.BasicRecord  // typealias to module-core
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.domain.OcidMapping
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Consolidated chunk reader with IO/CPU 분리 (per spec §11.1).
 *
 * - IO (objectStorage.get) runs on Dispatchers.IO (VT-friendly for network calls)
 * - CPU (GZIP decompress + JSON parse + dedup) runs on Dispatchers.Default
 *
 * This separation matters for MinIO where objectStorage.get is a 50-200ms
 * blocking network call; running it on Dispatchers.Default would block the
 * CPU pool (size = availableProcessors) and starve other CPU work.
 *
 * Local FS: sub-ms, both dispatchers equivalent.
 */
@Component
class DefaultChunkFileReader(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
    private val readerMetrics: SynchronizerReaderMetrics,
    @Qualifier("basicChunkMissingFieldThreshold")
    private val missingFieldThreshold: Int,
) : ChunkFileReaderPort {

    override fun readBasicChunk(objectKey: String): List<BasicRecord> = runBlocking {
        val rawBytes = withContext(Dispatchers.IO) { objectStorage.get(objectKey) }
        withContext(Dispatchers.Default) { parseBasicChunk(rawBytes, objectKey) }
    }

    override fun readResultChunk(objectKey: String): List<GroupedEquipmentResult> = runBlocking {
        val rawBytes = withContext(Dispatchers.IO) { objectStorage.get(objectKey) }
        withContext(Dispatchers.Default) { parseResultChunk(rawBytes, objectKey) }
    }

    override fun readOcidMapping(manifestPath: String): List<OcidMapping> = runBlocking {
        val rawBytes = withContext(Dispatchers.IO) { objectStorage.get(manifestPath) }
        withContext(Dispatchers.Default) { parseOcidMapping(rawBytes, manifestPath) }
    }

    // Private parse methods: GZIPInputStream decode + JSON parse + dedup.
    // Body adapted from the existing BasicChunkFileReader / ResultFileReader /
    // OcidMappingFileReader. AtomicLong counters + readerMetrics.incrementXxx()
    // call sites preserved.
    private fun parseBasicChunk(rawBytes: ByteArray, objectKey: String): List<BasicRecord> {
        // ... (existing BasicChunkFileReader.read logic, no I/O)
    }
    // ... similar for parseResultChunk, parseOcidMapping
}
```

The 3 existing readers (`BasicChunkFileReader`, `ResultFileReader`, `OcidMappingFileReader`) are **deleted**. Their test classes (if any) are also deleted.

### 5.4 Module-by-module migration

#### 5.4.1 module-calculator (4 files modified + 2 deleted)

| File | Change |
|------|--------|
| `CalculatorChunkProcessingCoordinator.kt` | Inject `ObjectStorage` (common). `objectStorage.exists()` and `getStream()` replace direct FS access. |
| `CalculationResultWriter.kt` | `objectStorage.putStream()` replaces `LocalObjectStorageAdapter.openOutputStream()`. |
| `SnapshotChunkProcessor.kt` | `objectStorage.getStream()` replaces direct FS `Files.newInputStream()`. |
| `CalculatorResultCleanupScheduler.kt` | `listByPrefix()`, `deleteByPrefix()`, `calculatePrefixSize()`, `getLastModified()` replace `listDirectories()`, `deleteDirectory()`, `calculateDirectorySize()`. |
| `CalculatorChunkProcessingCoordinatorTest.kt` | Mock target updated: `maple.calculator.storage.ObjectStorage` → `maple.expectation.common.storage.ObjectStorage`. |
| `LocalObjectStorageAdapter.kt` | **Deleted** (replaced by VS1's `LocalFsObjectStorage`). |
| `storage/ObjectStorage.kt` | **Deleted** (replaced by VS1's `ObjectStorage` in `module-common`). |

The deleted `ObjectStorage` interface is replaced by `@Deprecated` annotation in its original file location for one commit, then deleted. (Single commit deletes both — VS2 spec uses single commit per module; deprecation was an alternative considered but rejected for cleanness.)

#### 5.4.2 module-external-api (5 files modified + 2 deprecated)

| File | Change |
|------|--------|
| `RankingFetchPhase.kt` | `objectStorage.putStream()` for `runs/.../chunks/...` write. |
| `OcidLookupPhase.kt` | `objectStorage.putStream()` for `ocid-mapping/...` write. |
| `OcidCacheProvider.kt` | `objectStorage.getStream()` for ocid-mapping read + `listByPrefix()` for finding latest file. |
| `ArtifactCleanupScheduler.kt` | Use `ObjectStorage` (common) for all 9 method calls (mapped from `ExternalApiArtifactStorePort`). |
| `ConsumedChunkCleanupScheduler.kt` | `objectStorage.delete()` for both `objectKey` and `sourceObjectKey`. |
| `ExternalApiArtifactStorePort.kt` | `@Deprecated(forRemoval=true, since="2026-06-09")` with Javadoc pointing to `ObjectStorage`. |
| `LocalExternalApiArtifactStoreAdapter.kt` | `@Deprecated(forRemoval=true, since="2026-06-09")`. Kept (still functional as Local-only fallback) until #1221 removes it. |

#### 5.4.3 module-synchronizer (5 files modified + 3 deleted)

| File | Change |
|------|--------|
| `KafkaResultChunkConsumer.kt` | Inject `ChunkFileReaderPort` (was `ResultFileReader`). Call `readResultChunk(objectKey)`. |
| `BasicSnapshotChunkConsumer.kt` | Inject `ChunkFileReaderPort` (was `BasicChunkFileReader`). Call `readBasicChunk(objectKey)`. |
| `OcidLookupRunConsumer.kt` | Inject `ChunkFileReaderPort` (was `OcidMappingFileReader`). Call `readOcidMapping(manifestPath)`. |
| `domain/ChunkDomainReexport.kt` | New: typealiases for the moved `BasicRecord` / `GroupedEquipmentResult` / `OcidMapping`. |
| `storage/DefaultChunkFileReader.kt` | New: implements `ChunkFileReaderPort` with IO/CPU 분리 (see §5.3). |
| `storage/BasicChunkFileReader.kt` | **Deleted**. |
| `storage/ResultFileReader.kt` | **Deleted**. |
| `storage/OcidMappingFileReader.kt` | **Deleted**. |

`SynchronizerReaderMetrics`, `BasicChunkMissingFieldThreshold` qualifier, and other infrastructure are preserved.

#### 5.4.4 module-infra (1 new + 1 deleted + 3 unchanged)

| File | Change |
|------|--------|
| `external/snapshot/SnapshotObjectStoreAdapter.kt` | **New** (see §5.2). |
| `external/snapshot/LocalSnapshotObjectStore.kt` | **Deleted**. |
| `worker/ExternalApiWorker.kt` | No code change (still uses `SnapshotObjectStore` port, now resolved to `SnapshotObjectStoreAdapter`). |
| `worker/NexonApiWorker.kt` | No code change. |
| `job/SnapshotCleanupWorker.kt` | No code change. |

### 5.5 Pre-existing compile breakage — 4 files to fix

The following 4 files have unresolved references that block module compile. Fixes are part of the VS2 PR (precondition commit, separate from VS2 functional changes).

| File | Issue | Fix |
|------|-------|-----|
| `module-rest-controller/.../read/ReadModelQueryService.kt` | `Unresolved reference 'kotlinx'` (3 imports + `Dispatchers`) | Add `import kotlinx.coroutines.Dispatchers` and related; the file imports appear to be missing the `kotlinx.coroutines` package. |
| `module-external-api/.../scheduler/phase/RankingFetchPhase.kt` | `Unresolved reference 'EventPublisher'` (was used for the new snapshot event system, may be refactored) | Either remove unused import or restore the binding. |
| `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt` | Pre-existing `ManagedLifecycle` import may be unused; the class is being refactored in VS2 anyway | Cleanup during the VS2 file edit. |
| `module-synchronizer/.../reader/LocalRequestBuffer.kt` (or similar) | May reference deleted reader infrastructure | Cleanup as part of the VS2 reader deletion. |

(These 4 files are illustrative; the actual list will be determined during VS2 implementation by reading the current `git diff develop..HEAD~` for the abandoned refactor's effect on the compile graph. The "pre-existing fix" commit in the VS2 PR addresses all of them with `git status` driven approach.)

## 6. Data Flow

### 6.1 External-api → Calculator (chunk flow, MinIO mode)

```
RankingFetchPhase.execute()
  └─> objectStorage.putStream("runs/{runId}/ranking-overall/chunks/{shard}/{key}.jsonl.gz", gzip)
  └─> kafka publish SnapshotChunkReadyEvent { objectKey }

CalculatorChunkProcessingCoordinator (calculator)
  └─> objectStorage.exists(event.objectKey)         // 1 RTT
  └─> objectStorage.getStream(inputKey)             // IO: 1 RTT
  └─> SnapshotChunkProcessor.process(...)           // CPU
  └─> objectStorage.putStream(resultKey, gzip)     // IO: 1 RTT
  └─> kafka publish ChunkResultEvent { objectKey }
```

### 6.2 Calculator → Synchronizer (result flow, IO/CPU 분리)

```
KafkaResultChunkConsumer (synchronizer)
  └─> chunkFileReaderPort.readResultChunk(objectKey)            // runBlocking {
         val rawBytes = withContext(Dispatchers.IO) {            // 1 RTT
             objectStorage.get(objectKey)
         }
         withContext(Dispatchers.Default) { parseResultChunk() } // CPU
     }
  └─> ChunkProcessor → character_valuation_views (DB)
  └─> kafka publish ChunkConsumedEvent
```

### 6.3 External-api ocid cache (read + list)

```
OcidCacheProvider.refresh()
  └─> objectStorage.listByPrefix("ocid-mapping/")         // find latest
  └─> sort by name desc, take first
  └─> objectStorage.getStream(latest)                    // read bytes
  └─> parse → cacheRef.set(ign → ocid map)
```

### 6.4 Snapshot write/read (thin wrapper indirection)

```
ExternalApiWorker (infra)
  └─> snapshotStore.put(snapshot, equipmentResponseBytes)   // [port]
      └─> SnapshotObjectStoreAdapter.put                      // [wrapper]
          └─> objectStorage.put(snapshot.objectKey, data)     // [unified]
              └─> LocalFsObjectStorage or MinioObjectStorage (per storage.backend)

SnapshotCleanupWorker
  └─> snapshotStore.delete(snapshot.objectKey)
      └─> SnapshotObjectStoreAdapter.delete
          └─> objectStorage.delete(...)
```

### 6.5 Cleanup (still in MinIO target; for VS2 default=local)

```
CalculatorResultCleanupScheduler (calculator)
  └─> objectStorage.listByPrefix("calculator/runs/")             // find runs
  └─> for each runId:
      └─> objectStorage.getLastModified("calculator/runs/{runId}")   // active check
      └─> if active → skip
      └─> apply ADR-390 retention (5 recent + 48h)
      └─> objectStorage.deleteByPrefix("calculator/runs/{runId}/")

ArtifactCleanupScheduler (external-api)
  └─> objectStorage.listByPrefix("runs/")                       // find runs
  └─> for each runId:
      └─> objectStorage.exists("runs/{runId}/_RUNNING")          // active check
      └─> apply retention
      └─> objectStorage.deleteByPrefix("runs/{runId}/")

ConsumedChunkCleanupScheduler (external-api)
  └─> @KafkaListener on synchronizer.chunk.consumed
  └─> for each event: objectStorage.delete(event.objectKey) + (sourceObjectKey)
```

## 7. Error Handling

### 7.1 Error semantics

| Operation | Local | MinIO | Caller expectation |
|-----------|-------|-------|--------------------|
| `ObjectStorage.get` (not found) | `NoSuchFileException` | `NoSuchKeyException` | Caller catches via `LogicExecutor.executeOrCatch` |
| `ObjectStorage.delete` (not found) | no-op | no-op | OK |
| `SnapshotObjectStore.put` (size mismatch) | rare; underlying PutResult carries size | rare; MinIO returns size | `SnapshotObjectStoreResult.compressedSize` is correct |
| `DefaultChunkFileReader` parse error (malformed JSON line) | `JsonProcessingException` re-thrown | same | Caller's `runCatching` swallows and increments `readerMetrics.incrementParseError` |

### 7.2 Boot-time behavior

`SnapshotObjectStoreAdapter.init { }` logs the active backend. This is informational, not a fail-fast. The boot-time fatal (MinIO bucket validation) is `MinioObjectStorage.@PostConstruct validateBucket` — already in VS1, unchanged here.

### 7.3 IO/CPU dispatch — failure isolation

If `objectStorage.get` fails on `Dispatchers.IO`, the `runCatching` (or the `LogicExecutor` wrapper around the consumer) handles the exception. The `Dispatchers.Default` parse path is not entered. CPU pool is not affected.

## 8. Configuration

No new application yaml keys. The 4 modules already have `storage.*` block from VS1.

Migration consumers may need their Spring `@Component` bean graph updated. Specifically:
- `LocalObjectStorageAdapter` had `@Component` annotation. After deletion, no replacement needed (VS1's `LocalFsObjectStorage` already wires the local backend).
- `LocalExternalApiArtifactStoreAdapter` keeps `@Component` but is `@Deprecated` — Spring still loads it. If `storage.backend=minio`, the adapter still loads but is unused (no caller). Acceptable (will be removed in #1221).
- `SnapshotObjectStoreAdapter` is `@Component` (default). Replaces `LocalSnapshotObjectStore`'s `@Component`.

## 9. Testing Strategy

### 9.1 Unit tests (CI, always runs)

- `DefaultChunkFileReaderTest` (new):
  - Inject `ObjectStorage` mock + `SynchronizerReaderMetrics` + `ObjectMapper`
  - `readBasicChunk`: assert parse correctness (sample gzipped JSONL → expected `BasicRecord` list)
  - `readBasicChunk`: assert `Dispatchers.IO` invoked for `objectStorage.get` (via `kotlinx-coroutines-test` `TestDispatcher` instrumentation)
  - `readBasicChunk`: assert parse runs on `Dispatchers.Default`
  - `readResultChunk` / `readOcidMapping`: similar coverage
  - Error path: malformed JSON line throws (existing behavior preserved)

- `SnapshotObjectStoreAdapterTest` (new):
  - Inject `ObjectStorage` mock + `@Value("minio")` / `@Value("local")` for backend
  - `put`: assert delegate to `objectStorage.put` with the right `objectKey`
  - `get` / `delete`: assert delegate
  - `init` log: assert correct `storageType` based on backend property

- `CalculatorChunkProcessingCoordinatorTest` (existing, modified):
  - Mock target rename: `maple.calculator.storage.ObjectStorage` → `maple.expectation.common.storage.ObjectStorage`
  - The 14 existing test methods (or however many) continue passing

### 9.2 Integration test (env-gated)

- `DefaultChunkFileReaderIT` (new, `@EnabledIfEnvironmentVariable(INTEGRATION_MINIO=true)`):
  - Use a real `LocalFsObjectStorage` (no docker needed) writing sample chunks to a `@TempDir`
  - Verify round-trip read

- `MinioBootSmokeIT` (existing from VS1) continues to pass — verifies the storage subgraph is wired correctly.

### 9.3 Pre-existing breakage verification

After the precondition commit:
- `./gradlew :module-rest-controller:compileKotlin` clean
- `./gradlew :module-external-api:compileKotlin` clean
- `./gradlew :module-synchronizer:compileKotlin` clean
- `./gradlew :module-calculator:compileKotlin` clean
- `./gradlew :module-infra:compileKotlin` clean
- `./gradlew compileKotlin compileJava --continue` all modules clean

## 10. Trade-offs

### 10.1 Sensitivity

- **Chunk size** (1–8MB gzipped): MinIO `objectStorage.get` is 50–200ms. IO/CPU 분리로 CPU pool 점유 회피. Hot path throughput 영향 <5%.
- **Reader concurrency** (4 concurrent consumers × ~1000 records): IO pool (default ForkJoinPool common, 8 threads) handles 4 consumers × 1 in-flight get each. OK.
- **Domain type 이동**: 3 small data class (BasicRecord, GroupedEquipmentResult, OcidMapping) 을 module-core로. Synchronizer는 typealias로 backward compat. 영향: 거의 없음.

### 10.2 Trade-off Table

| Choice | Gain | Cost |
|--------|------|------|
| Single PR + staged commits (vs 4 sequential PRs) | One spec/plan cycle, atomic review | Larger PR (~20 files) |
| `SnapshotObjectStore.put(snapshot, data)` 유지 (vs simplify to key) | 0 caller diff | `CalculationSnapshot` domain type 유지 — Port가 domain 알게 됨 (already was) |
| `ChunkFileReaderPort` 1 class, 3 methods (vs 3 sub-classes) | Simple DI, single mock in tests | One class 3 responsibilities (but parseXxx is private; 3 method entry points are 1-line) |
| `storageType` backend-specific (vs generic) | Active backend 식별 가능 | 이력 데이터 backend 종속 (acceptable) |
| Domain type을 module-core로 이동 (vs raw String in Port) | Port는 도메인 표현력 유지, type-safe | 3 data class 이동 (small), typealias re-export |
| Pre-existing fix를 VS2 PR에 포함 (vs 별도 issue) | VS2 검증 가능 | VS2 PR 사이즈 증가 (실제 fix 양은 ~3-4 files 작은 변경) |

### 10.3 Risks

- **Pre-existing fix의 scope**: 4 파일에 국한. 더 많은 pre-existing 에러가 발견되면 별도 issue로 분리.
- **Domain type 이동 시 import 업데이트 누락**: 3 type을 사용하는 synchronizer/infra 모듈의 import가 typealias로 forwarding 되어야 함. `grep "import maple.synchronizer.storage.BasicRecord"` 등으로 검증.
- **`runBlocking` + `withContext` 중첩**: `DefaultChunkFileReader`에서 `runBlocking { withContext(IO) { ... }; withContext(Default) { ... } }`. 첫 `withContext` 종료 시 IO는 release되고 Default가 시작됨. Coroutine 구조 명확.
- **`DefaultChunkFileReader` 테스트가 kotlinx-coroutines-test 의존성 필요**: project에 이미 있음 (`kotlinx-coroutines-test` is in `gradle/libs.versions.toml`).

### 10.4 Non-Risks

- **`SnapshotObjectStore` port signature 보존**: caller 0 diff. 3 caller (ExternalApiWorker, NexonApiWorker, SnapshotCleanupWorker) 그대로.
- **`LocalFsObjectStorage` (VS1) 재사용**: Calculator, External-api, Synchronizer 모두 local backend. VS1에서 이미 `LocalFsObjectStorage`가 wired.
- **Hot path throughput**: IO/CPU 분리로 VS1 대비 throughput 유지 (regression 없음).
- **Production cutover 영향 없음**: VS2는 default=local. MinIO 전환은 VS3+VS4 atomic cutover.

## 11. Migration Cutover

This slice ships in **default backend = local**. No production cutover. The atomic cutover to `storage.backend=minio` is VS3 (#1218) + VS4 (#1219).

Rollback: `git revert` the merge commit. All VS2 changes are additive (delete the 3 old readers, replace the LocalFsX adapters with the new wrapper); reverting restores the old direct-FS call sites.

## 12. Documentation

- This spec at `docs/superpowers/specs/2026-06-09-v2-pipeline-modules-migration-design.md`
- `docs/01_ADR/ADR-720_object-storage-minio-migration.md` (from macro spec §12) — final ADR capturing VS1+VS2 trade-off summary
- README: storage backend section updates (deprecation note for 2 ports)

## 13. Definition of Done

- [ ] `ChunkFileReaderPort` interface declared in `module-core` under `maple.expectation.core.port.out`
- [ ] 3 domain types (`BasicRecord`, `GroupedEquipmentResult`, `OcidMapping`) moved from `module-synchronizer/storage` to `module-core/.../core/model/chunk`
- [ ] `DefaultChunkFileReader` in `module-synchronizer` implements `ChunkFileReaderPort` with IO/CPU 분리 (`Dispatchers.IO` for get, `Dispatchers.Default` for parse)
- [ ] `DefaultChunkFileReaderTest` verifies parse correctness + IO/CPU dispatcher instrumentation (using `kotlinx-coroutines-test`)
- [ ] `SnapshotObjectStoreAdapter` in `module-infra` wraps `ObjectStorage`, preserves `SnapshotObjectStore` port API
- [ ] `LocalSnapshotObjectStore` deleted
- [ ] 3 old reader classes (`BasicChunkFileReader`, `ResultFileReader`, `OcidMappingFileReader`) deleted
- [ ] 3 synchronizer consumer call sites updated to inject `ChunkFileReaderPort`
- [ ] `CalculatorChunkProcessingCoordinator`, `CalculationResultWriter`, `SnapshotChunkProcessor`, `CalculatorResultCleanupScheduler` updated to use common `ObjectStorage`
- [ ] Calculator's local `ObjectStorage` interface + `LocalObjectStorageAdapter` deleted
- [ ] `CalculatorChunkProcessingCoordinatorTest` mock target updated; tests pass
- [ ] `RankingFetchPhase`, `OcidLookupPhase`, `OcidCacheProvider`, `ArtifactCleanupScheduler`, `ConsumedChunkCleanupScheduler` updated to use common `ObjectStorage`
- [ ] `ExternalApiArtifactStorePort` and `LocalExternalApiArtifactStoreAdapter` marked `@Deprecated(forRemoval=true, since="2026-06-09")`
- [ ] Pre-existing compile breakage in 4 files (or however many discovered during impl) fixed as a precondition commit
- [ ] All 4 modules + `module-infra` + `module-common` compile clean (`./gradlew compileKotlin compileJava --continue` → BUILD SUCCESSFUL)
- [ ] `./gradlew test` clean across all modules (with `INTEGRATION_MINIO=true` for IT tests against local MinIO if available)
- [ ] `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.*"` passes (LocalFsObjectStorageTest 14 cases, StorageConfigTest, MinioObjectStorageIT 11 cases, MinioHealthIndicatorTest 2 cases, MinioBootSmokeIT 2 cases, DefaultChunkFileReaderTest, SnapshotObjectStoreAdapterTest)
- [ ] ADR-720 finalized and committed
- [ ] No new `!!`, `try-catch`, `join()/get()/runBlocking` outside the explicit `runBlocking { withContext(IO) { ... }; withContext(Default) { ... } }` pattern in `DefaultChunkFileReader`
- [ ] `module-core` has zero Spring imports (verified by Gradle `verifyNoSpringDependency`)
- [ ] Issue #1217 closed by the merge commit

## 14. Out of Scope (Future)

- Production cutover to `storage.backend=minio` (VS3 #1218 + VS4 #1219)
- Removal of `@Deprecated` ports (issue #1221, separate cleanup)
- New metrics surface (spec §6.4 optional)
- Module-rest-controller's `UrgentCharacterNotFoundConsumer` and other pre-existing fixes not required for VS2 verification
- Synchronizer's `LocalRequestBuffer` refactor (CPU offload already done in #1130)
- `ChunkFileReaderPort` streaming for 10k+ objects (not needed; current chunks < 10MB)
