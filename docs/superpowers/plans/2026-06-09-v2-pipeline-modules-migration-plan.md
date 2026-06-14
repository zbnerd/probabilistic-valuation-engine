# V2 Pipeline Modules Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate 4 application modules to the unified `ObjectStorage` interface (added in VS1), introduce `SnapshotObjectStoreAdapter` thin wrapper, and consolidate 3 synchronizer reader classes into `DefaultChunkFileReader` with IO/CPU 분리. Single PR with staged commits; default backend = `local`.

**Architecture:** Hexagonal (per macro spec). `ChunkFileReaderPort` in `module-core`. `SnapshotObjectStoreAdapter` and `DefaultChunkFileReader` in `module-infra`/`module-synchronizer` respectively. Domain types moved from `module-synchronizer/storage` to `module-core/.../core/model/chunk` (re-exported via typealias). Two legacy port interfaces (`ExternalApiArtifactStorePort`, calculator's local `ObjectStorage`) marked `@Deprecated`; `LocalExternalApiArtifactStoreAdapter` and `LocalObjectStorageAdapter` either deleted or deprecated depending on caller usage. `LocalSnapshotObjectStore` and 3 reader classes deleted. Pre-existing compile breakage in 4+ files fixed as a precondition commit.

**Tech Stack:** Kotlin 2.1, Spring Boot 3.5, aws-sdk-java v2 (from VS1), kotlinx-coroutines-test (existing), JUnit 5, AssertJ, mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-06-09-v2-pipeline-modules-migration-design.md`
**Issue:** https://github.com/zbnerd/probabilistic-valuation-engine/issues/1217

---

## Task 1: Pre-existing compile breakage fix (precondition)

**Files:** (4+ files in `module-rest-controller`, `module-external-api`, `module-synchronizer`; actual list determined by running compile)

- [ ] **Step 1: Identify all current compile errors**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon 2>&1 | grep -E "^e:" | head -30`

Expected: list of ~4-10 unresolved-reference or override errors in:
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt` (likely `kotlinx.Dispatchers`)
- `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt` (likely `CompletableFuture<...>` vs `...` type mismatch)
- `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt` (likely `UrgentReadStatus`)
- `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt` (likely `ManagedLifecycle` import)
- Other files surfaced by the grep

- [ ] **Step 2: Fix each error minimally**

For each error file:
1. Read the file at the error line
2. Read the corresponding file in the upstream commit (e.g. `git log -p develop..HEAD~1 -- module-rest-controller/.../ReadModelQueryService.kt` if a recent commit changed it)
3. Add the missing import / fix the override / reconcile the type
4. Keep changes minimal — no functional refactors

Common patterns to apply:
- Missing Kotlin stdlib / kotlinx imports → add the import at the top
- Method override mismatch → match the parent signature exactly
- Type mismatch (`CompletableFuture<...>` vs raw) → add `.await()` or unwrap explicitly

- [ ] **Step 3: Verify all modules compile**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify existing tests still pass**

Run: `./gradlew :module-infra:test :module-calculator:test :module-external-api:test :module-synchronizer:test --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (or only the pre-existing test failures from before VS2; document any that exist).

- [ ] **Step 5: Commit**

```bash
git add <fixed files>
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "fix(infra): pre-existing compile errors for VS2 verification

<list of files fixed with one-line description of each>

Precondition for VS2 (#1217). After VS1 (PR #1222), the
module-infra compile was fixed but module-rest-controller /
module-external-api / module-synchronizer still had unresolved
references from an abandoned module-core refactor.

These fixes restore compile across the full module set so
VS2 can verify end-to-end."
```

---

## Task 2: Move 3 domain types from `module-synchronizer` to `module-core` + typealias

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt` (delete the `BasicRecord` data class)
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/ResultFileReader.kt` (delete `GroupedEquipmentResult`)
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt` (delete `OcidMapping`)
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/chunk/BasicRecord.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/chunk/GroupedEquipmentResult.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/chunk/CalculatedEquipmentItem.kt` (if not already in core)
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/chunk/OcidMapping.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/domain/ChunkDomainReexport.kt`

- [ ] **Step 1: Read existing 3 reader files to extract data class bodies**

For each of the 3 files, read the file and identify the data class declaration (e.g. `data class BasicRecord(...)`).

- [ ] **Step 2: Create the 3 new core domain types**

Create `module-core/src/main/kotlin/maple/expectation/core/model/chunk/BasicRecord.kt`:

```kotlin
package maple.expectation.core.model.chunk

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

Create `module-core/src/main/kotlin/maple/expectation/core/model/chunk/GroupedEquipmentResult.kt`:

```kotlin
package maple.expectation.core.model.chunk

data class GroupedEquipmentResult(
    val readKey: String,
    val ocid: String,
    val presetNo: Int,
    val items: List<CalculatedEquipmentItem>,
)
```

Create `module-core/src/main/kotlin/maple/expectation/core/model/chunk/OcidMapping.kt`:

```kotlin
package maple.expectation.core.model.chunk

data class OcidMapping(
    val userIgn: String,
    val ocid: String,
)
```

If `CalculatedEquipmentItem` doesn't exist in `module-core` (it's currently in `module-synchronizer/storage/ResultFileReader.kt`), create it:

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/model/chunk/CalculatedEquipmentItem.kt
package maple.expectation.core.model.chunk

import java.math.BigDecimal

data class CalculatedEquipmentItem(
    val ocid: String,
    val presetNo: Int,
    val itemName: String,
    val itemLevel: Int,
    val itemPart: String,
    val itemEquipmentPart: String?,
    val potentialGrade: String?,
    val potentialOptions: List<String>?,
    val additionalGrade: String?,
    val additionalOptions: List<String>?,
    val currentStar: Int,
    val targetStar: Int,
    val status: String,
    val totalCost: BigDecimal,
    val blackCubeCost: BigDecimal,
    val additionalCubeCost: BigDecimal,
    val starforceCost: BigDecimal,
    val errorMessage: String?,
)
```

- [ ] **Step 3: Create the typealias re-export**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/domain/ChunkDomainReexport.kt`:

```kotlin
package maple.synchronizer.domain

typealias BasicRecord = maple.expectation.core.model.chunk.BasicRecord
typealias GroupedEquipmentResult = maple.expectation.core.model.chunk.GroupedEquipmentResult
typealias CalculatedEquipmentItem = maple.expectation.core.model.chunk.CalculatedEquipmentItem
typealias OcidMapping = maple.expectation.core.model.chunk.OcidMapping
```

- [ ] **Step 4: Delete the old data class declarations from the 3 reader files**

In each of `BasicChunkFileReader.kt`, `ResultFileReader.kt`, `OcidMappingFileReader.kt`, delete the `data class ... { ... }` block (keep the rest of the file as-is for now; the readers themselves get deleted in Task 11).

For each file, search for `data class` and remove that block.

- [ ] **Step 5: Verify compile**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/model/chunk/ module-synchronizer/src/main/kotlin/maple/synchronizer/storage/ module-synchronizer/src/main/kotlin/maple/synchronizer/domain/ChunkDomainReexport.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(core): move chunk domain types to module-core + typealias re-export

Moves BasicRecord, GroupedEquipmentResult, CalculatedEquipmentItem,
OcidMapping from module-synchronizer/storage to module-core/.../core/
model/chunk. The 3 reader files keep their import path via typealias
in module-synchronizer/domain/.

This is a precondition for ChFileReaderPort (issue #1217) which
lives in module-core but needs the chunk domain types."
```

---

## Task 3: Create `ChunkFileReaderPort` in `module-core`

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/ChunkFileReaderPort.kt`

- [ ] **Step 1: Verify module-core has zero Spring imports after the change**

Run:
```bash
grep -rn "import org.springframework" module-core/src/main/ 2>&1 | head -3
```

Expected: no output.

- [ ] **Step 2: Create the port interface**

Create `module-core/src/main/kotlin/maple/expectation/core/port/out/ChunkFileReaderPort.kt`:

```kotlin
package maple.expectation.core.port.out

import maple.expectation.core.model.chunk.BasicRecord
import maple.expectation.core.model.chunk.GroupedEquipmentResult
import maple.expectation.core.model.chunk.OcidMapping

/**
 * Consolidated chunk file reader for the synchronizer pipeline.
 * Replaces 3 separate reader classes (BasicChunkFileReader, ResultFileReader,
 * OcidMappingFileReader) with a single port. All methods delegate to the
 * unified ObjectStorage (introduced in VS1).
 *
 * Implementations: DefaultChunkFileReader in module-synchronizer.
 */
interface ChunkFileReaderPort {
    fun readBasicChunk(objectKey: String): List<BasicRecord>
    fun readResultChunk(objectKey: String): List<GroupedEquipmentResult>
    fun readOcidMapping(manifestPath: String): List<OcidMapping>
}
```

- [ ] **Step 3: Verify module-common compile**

Run: `./gradlew :module-core:compileKotlin --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/ChunkFileReaderPort.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "feat(core): add ChunkFileReaderPort (3-method consolidated reader)

Port for reading 3 chunk types from ObjectStorage. Implementation
lives in module-synchronizer (DefaultChunkFileReader, added in
VS2 Task 10).

Issue #1217"
```

---

## Task 4: `SnapshotObjectStoreAdapter` TDD pair

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapterTest.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapter.kt`

- [ ] **Step 1: Write the failing test**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapterTest.kt`:

```kotlin
package maple.expectation.infrastructure.external.snapshot

import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.core.model.snapshot.CalculationSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import software.amazon.awssdk.services.s3.S3Client
import java.time.Instant
import java.util.UUID

class SnapshotObjectStoreAdapterTest {

    private fun snapshot(objectKey: String = "snapshots/2026/06/09/${UUID.randomUUID()}.gz") =
        CalculationSnapshot(
            snapshotId = UUID.randomUUID(),
            jobId = UUID.randomUUID(),
            objectKey = objectKey,
            storageType = "LOCAL",
            characterId = "ocid-123",
            presetNo = 1,
            expiresAt = Instant.now().plusSeconds(3600),
        )

    @Test
    fun `put delegates to ObjectStorage put with the snapshot's objectKey`() {
        val objectStorage = org.mockito.kotlin.mock<ObjectStorage>()
        val adapter = SnapshotObjectStoreAdapter(objectStorage, "local")
        org.mockito.kotlin.whenever(objectStorage.put(org.mockito.kotlin.eq("snapshots/k.gz"), org.mockito.kotlin.any()))
            .thenReturn(PutResult("snapshots/k.gz", 100L, "abc123"))
        val snap = snapshot("snapshots/k.gz")
        val data = "payload".toByteArray()

        val result = adapter.put(snap, data)

        assertThat(result.objectKey).isEqualTo("snapshots/k.gz")
        assertThat(result.compressedSize).isEqualTo(100L)
        assertThat(result.hash).isEqualTo("abc123")
        org.mockito.kotlin.verify(objectStorage).put("snapshots/k.gz", data)
    }

    @Test
    fun `get delegates to ObjectStorage get`() {
        val objectStorage = org.mockito.kotlin.mock<ObjectStorage>()
        val adapter = SnapshotObjectStoreAdapter(objectStorage, "local")
        org.mockito.kotlin.whenever(objectStorage.get("k")).thenReturn("data".toByteArray())

        val result = adapter.get("k")

        assertThat(result).isEqualTo("data".toByteArray())
    }

    @Test
    fun `delete delegates to ObjectStorage delete`() {
        val objectStorage = org.mockito.kotlin.mock<ObjectStorage>()
        val adapter = SnapshotObjectStoreAdapter(objectStorage, "local")

        adapter.delete("k")

        org.mockito.kotlin.verify(objectStorage).delete("k")
    }

    @Test
    fun `init logs the active backend`() {
        val objectStorage = org.mockito.kotlin.mock<ObjectStorage>()
        SnapshotObjectStoreAdapter(objectStorage, "minio")
        // We don't assert log content; just verify no exception on init.
        // (S3Client not used by this class; only the @Value is read.)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails (RED)**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.external.snapshot.SnapshotObjectStoreAdapterTest" --no-daemon 2>&1 | grep -E "Unresolved|BUILD" | head -3`
Expected: BUILD FAILED. Unresolved: SnapshotObjectStoreAdapter.

- [ ] **Step 3: Implement `SnapshotObjectStoreAdapter`**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapter.kt`:

```kotlin
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
 * storageType is determined at init from `storage.backend` and logged for
 * observability. CalculationSnapshot.storageType is populated by callers
 * (set on construction); this wrapper does not overwrite that field.
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

- [ ] **Step 4: Run the test, verify it passes (GREEN)**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.external.snapshot.SnapshotObjectStoreAdapterTest" --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL. All 4 tests pass.

- [ ] **Step 5: Commit (test + impl together)**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapterTest.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapter.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "feat(infra): SnapshotObjectStoreAdapter (thin wrapper around ObjectStorage)

TDD pair: 4 test cases + implementation.
- put: delegates to objectStorage.put, returns SnapshotObjectStoreResult
  carrying PutResult fields (key, size, checksum)
- get: delegates to objectStorage.get
- delete: delegates to objectStorage.delete
- init: logs active backend (LOCAL or S3) for observability

Replaces LocalSnapshotObjectStore (deleted in Task 13). 3 callers
(ExternalApiWorker, NexonApiWorker, SnapshotCleanupWorker) continue
using SnapshotObjectStore port unchanged.

Issue #1217"
```

---

## Task 5: Delete `LocalSnapshotObjectStore` (module-infra)

**Files:**
- Delete: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/LocalSnapshotObjectStore.kt`

- [ ] **Step 1: Verify no other code references `LocalSnapshotObjectStore` directly**

Run:
```bash
grep -rn "LocalSnapshotObjectStore" /home/maple/probabilistic-valuation-engine --include="*.kt" --include="*.java" 2>/dev/null | grep -v "build/" | head -10
```

Expected: only the file itself. No other references (callers use `SnapshotObjectStore` port, which is now bound to `SnapshotObjectStoreAdapter`).

- [ ] **Step 2: Delete the file**

```bash
rm /home/maple/probabilistic-valuation-engine/module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/LocalSnapshotObjectStore.kt
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :module-infra:compileKotlin --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify tests still pass**

Run: `./gradlew :module-infra:test --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -u module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(infra): delete LocalSnapshotObjectStore (replaced by adapter)

LocalSnapshotObjectStore was the old impl of SnapshotObjectStore
port. Now replaced by SnapshotObjectStoreAdapter (Task 4) which
delegates to the unified ObjectStorage. No other code references
the deleted class.
- module-infra compile + test green
- VS2 #1217"
```

---

## Task 6: Calculator — migrate `CalculatorChunkProcessingCoordinator` to common `ObjectStorage`

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/CalculatorChunkProcessingCoordinatorTest.kt`

- [ ] **Step 1: Update the test mock target**

Open `module-calculator/src/test/kotlin/maple/calculator/CalculatorChunkProcessingCoordinatorTest.kt`. Find all imports of `maple.calculator.storage.ObjectStorage` and replace with `maple.expectation.common.storage.ObjectStorage`.

Run: `grep -n "maple.calculator.storage.ObjectStorage" module-calculator/src/test/`
Expected: a few lines; replace each `import maple.calculator.storage.ObjectStorage` with `import maple.expectation.common.storage.ObjectStorage`.

If the test has:
```kotlin
import maple.calculator.storage.ObjectStorage
```
Change to:
```kotlin
import maple.expectation.common.storage.ObjectStorage
```

- [ ] **Step 2: Verify the test fails (RED — production code not yet migrated)**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.CalculatorChunkProcessingCoordinatorTest" --no-daemon 2>&1 | tail -3`
Expected: BUILD FAILED with "Unresolved reference: openInputStream" or similar (the test now uses common ObjectStorage type which has `getStream()` not `openInputStream()`).

- [ ] **Step 3: Update `CalculatorChunkProcessingCoordinator` to use common `ObjectStorage`**

Open `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt`. Replace the import of `maple.calculator.storage.ObjectStorage` with `maple.expectation.common.storage.ObjectStorage`.

For each call site:
- `objectStorage.openInputStream(objectKey)` → `objectStorage.getStream(objectKey)`
- `objectStorage.openOutputStream(objectKey)` → `objectStorage.putStream(objectKey, inputStream)`
- `objectStorage.exists(objectKey)` — keep as-is (same method name in common)
- `objectStorage.listDirectories(prefix)` → `objectStorage.listByPrefix(prefix)`
- `objectStorage.deleteDirectory(prefix)` → `objectStorage.deleteByPrefix(prefix)`
- `objectStorage.calculateDirectorySize(prefix)` → `objectStorage.calculatePrefixSize(prefix)`

The Coordinator specifically uses `exists()` and `getStream()` (verify by reading the file; adjust as needed). Update accordingly.

- [ ] **Step 4: Verify the test passes (GREEN)**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.CalculatorChunkProcessingCoordinatorTest" --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (test + impl together)**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt
git add module-calculator/src/test/kotlin/maple/calculator/CalculatorChunkProcessingCoordinatorTest.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(calculator): CalculatorChunkProcessingCoordinator uses common ObjectStorage

Migrates from maple.calculator.storage.ObjectStorage to
maple.expectation.common.storage.ObjectStorage (VS1).

- objectStorage.openInputStream(key) -> objectStorage.getStream(key)
- mock target rename in CalculatorChunkProcessingCoordinatorTest

Default backend = local. No behavior change (LocalFsObjectStorage
behaves identically to LocalObjectStorageAdapter for this path).

Issue #1217"
```

---

## Task 7: Calculator — migrate `CalculationResultWriter` + `SnapshotChunkProcessor` to common `ObjectStorage`

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt`

- [ ] **Step 1: Read both files to identify call sites**

For each file, find the import of `maple.calculator.storage.ObjectStorage` and the call sites.

- [ ] **Step 2: Update `CalculationResultWriter`**

Replace import + call sites:
- `objectStorage.openOutputStream(objectKey)` → `objectStorage.putStream(objectKey, source)`
- The `source` is whatever the writer was passing to `openOutputStream` (e.g., a `GZIPOutputStream` or `OutputStream`). For `putStream`, the parameter is `InputStream`. Adjust the source accordingly (e.g., `ByteArrayInputStream(bytes)` if the writer has the bytes, or use a `PipedInputStream`/`PipedOutputStream` pair if streaming is required).

If the existing writer used `objectStorage.openOutputStream(objectKey).use { ... }` to write bytes, refactor to:
```kotlin
val bytes: ByteArray = ... // existing gzip logic produces this
objectStorage.putStream(objectKey, ByteArrayInputStream(bytes))
```

- [ ] **Step 3: Update `SnapshotChunkProcessor`**

Replace import + call sites:
- `objectStorage.openInputStream(objectKey)` → `objectStorage.getStream(objectKey)`
- The reader behavior is the same (read the InputStream, decompress, parse).

- [ ] **Step 4: Verify compile + test**

Run: `./gradlew :module-calculator:compileKotlin :module-calculator:test --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (both files together)**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt
git add module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(calculator): migrate writer + processor to common ObjectStorage

CalculationResultWriter: openOutputStream -> putStream (with
ByteArrayInputStream source)
SnapshotChunkProcessor: openInputStream -> getStream

Default backend = local. No behavior change.

Issue #1217"
```

---

## Task 8: Calculator — migrate `CalculatorResultCleanupScheduler` to common `ObjectStorage` + delete legacy files

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt`
- Delete: `module-calculator/src/main/kotlin/maple/calculator/storage/ObjectStorage.kt`
- Delete: `module-calculator/src/main/kotlin/maple/calculator/storage/LocalObjectStorageAdapter.kt`

- [ ] **Step 1: Update `CalculatorResultCleanupScheduler`**

Open the file. Find:
- `import maple.calculator.storage.ObjectStorage` → replace with `import maple.expectation.common.storage.ObjectStorage`
- Constructor parameter `objectStorage: ObjectStorage` (calculator's type) → `objectStorage: ObjectStorage` (common's type)
- `objectStorage.listDirectories(prefix)` → `objectStorage.listByPrefix(prefix)`
- `objectStorage.deleteDirectory(prefix)` → `objectStorage.deleteByPrefix(prefix)`
- `objectStorage.calculateDirectorySize(prefix)` → `objectStorage.calculatePrefixSize(prefix)`
- `Files.readAttributes(path, BasicFileAttributes::class.java)` (direct FS) → use the common `ObjectStorage` indirectly OR keep the `basePath` field and read attributes only for the local backend. Recommended: keep `basePath` for legacy `readAttributes` reads, OR replace with `objectStorage.getLastModified()` for a backend-agnostic active check.

- [ ] **Step 2: Verify no remaining references to calculator's local `ObjectStorage` interface**

Run:
```bash
grep -rn "maple.calculator.storage.ObjectStorage\|maple.calculator.storage.LocalObjectStorageAdapter" /home/maple/probabilistic-valuation-engine --include="*.kt" --include="*.java" 2>/dev/null | grep -v "build/"
```

Expected: only the 2 files about to be deleted (or 0 if the test file was already updated).

- [ ] **Step 3: Delete the 2 legacy files**

```bash
rm /home/maple/probabilistic-valuation-engine/module-calculator/src/main/kotlin/maple/calculator/storage/ObjectStorage.kt
rm /home/maple/probabilistic-valuation-engine/module-calculator/src/main/kotlin/maple/calculator/storage/LocalObjectStorageAdapter.kt
rmdir /home/maple/probabilistic-valuation-engine/module-calculator/src/main/kotlin/maple/calculator/storage 2>/dev/null || true
```

(The `rmdir` removes the now-empty package directory; ignore errors if the directory contains other files.)

- [ ] **Step 4: Verify compile + test**

Run: `./gradlew :module-calculator:compileKotlin :module-calculator:test --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (cleanup + deletion together)**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt
git add -u module-calculator/src/main/kotlin/maple/calculator/storage/
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(calculator): migrate cleanup scheduler + delete legacy port/adapter

CalculatorResultCleanupScheduler now uses common ObjectStorage:
- listByPrefix/deleteByPrefix/calculatePrefixSize replace
  listDirectories/deleteDirectory/calculateDirectorySize
- getLastModified replaces direct Files.readAttributes for active check

Also deletes the calculator's local ObjectStorage interface and
LocalObjectStorageAdapter (both fully replaced by VS1's
LocalFsObjectStorage in module-infra).

Issue #1217"
```

---

## Task 9: External-api — deprecate `ExternalApiArtifactStorePort` + `LocalExternalApiArtifactStoreAdapter`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt`

- [ ] **Step 1: Verify no other code currently uses these classes directly**

Run:
```bash
grep -rn "ExternalApiArtifactStorePort\|LocalExternalApiArtifactStoreAdapter" /home/maple/probabilistic-valuation-engine --include="*.kt" 2>/dev/null | grep -v "build/" | head -20
```

Expected: callers in `ArtifactCleanupScheduler` (uses port), `ConsumedChunkCleanupScheduler` (uses port), `OcidCacheProvider` (uses port), `RankingFetchPhase` (uses port via `ExternalApiArtifactStorePort`? — verify), `OcidLookupPhase` (uses port). Document the full list.

- [ ] **Step 2: Add `@Deprecated` annotation to the port interface**

Open `ExternalApiArtifactStorePort.kt`. Add at the top of the interface declaration:

```kotlin
/**
 * @deprecated Use `maple.expectation.common.storage.ObjectStorage` (VS1) instead.
 *   This port is fully replaced by the unified ObjectStorage interface.
 *   Removal is planned in issue #1221.
 */
@Deprecated(
    message = "Replaced by maple.expectation.common.storage.ObjectStorage (VS1). " +
        "Use ObjectStorage instead. Removal planned in #1221.",
    replaceWith = ReplaceWith(
        "maple.expectation.common.storage.ObjectStorage",
        "maple.expectation.common.storage.ObjectStorage"
    ),
    since = "2026-06-09",
)
interface ExternalApiArtifactStorePort {
    // ... existing methods unchanged
}
```

- [ ] **Step 3: Add `@Deprecated` annotation to the local adapter**

Open `LocalExternalApiArtifactStoreAdapter.kt`. Add at the top of the class:

```kotlin
/**
 * @deprecated Replaced by `LocalFsObjectStorage` (VS1). This adapter
 *   is unused after VS2's migration of all callers to ObjectStorage.
 *   Removal is planned in issue #1221.
 */
@Deprecated(
    message = "Replaced by LocalFsObjectStorage in module-infra. " +
        "Unused after VS2 caller migration. Removal planned in #1221.",
    since = "2026-06-09",
)
@Component
class LocalExternalApiArtifactStoreAdapter(...) : ExternalApiArtifactStorePort {
    // ... existing body unchanged
}
```

- [ ] **Step 4: Verify compile (deprecation warnings OK)**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (deprecation warnings are emitted but don't fail the build).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt
git add module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "chore(ext-api): deprecate ExternalApiArtifactStorePort + LocalExternalApiArtifactStoreAdapter

Marked @Deprecated(forRemoval=true) with Javadoc pointing to the
unified maple.expectation.common.storage.ObjectStorage.

Both classes remain functional for backward compatibility but are
not used by any caller after VS2's migration commits. Removal
tracked in #1221.

Issue #1217"
```

---

## Task 10: External-api — migrate `RankingFetchPhase` + `OcidLookupPhase` to common `ObjectStorage`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`

- [ ] **Step 1: Update `RankingFetchPhase`**

Open the file. Find:
- `import maple.externalapi.port.out.ExternalApiArtifactStorePort` (if present) or `import maple.externalapi.infra.storage.LocalExternalApiArtifactStoreAdapter`
- The field `private val store: ExternalApiArtifactStorePort` (or similar) → replace with `private val objectStorage: ObjectStorage`
- `import maple.expectation.common.storage.ObjectStorage`
- `store.store(endpoint, key, bytes)` → `objectStorage.putStream(key, ByteArrayInputStream(bytes))` (or similar; read the file to see exact call)
- `store.read(endpoint, key)` (if used) → `objectStorage.get(key)` (note: returns ByteArray, not nullable; callers must handle `NoSuchFileException` if needed)

- [ ] **Step 2: Update `OcidLookupPhase`**

Similar changes. The phase writes ocid-mapping files via `store.store(...)`. Replace with `objectStorage.putStream(...)`.

- [ ] **Step 3: Verify compile**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit (both phases together)**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(ext-api): migrate RankingFetchPhase + OcidLookupPhase to common ObjectStorage

Both phases write gzipped JSONL chunks via ExternalApiArtifactStorePort.
Replaced with ObjectStorage.putStream (and supporting get calls where
needed). The Deprecated port remains in the tree until #1221 removal.

Issue #1217"
```

---

## Task 11: External-api — migrate `OcidCacheProvider` to common `ObjectStorage`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt`

- [ ] **Step 1: Read the file to identify call sites**

The provider currently:
- Uses `Paths.get(storeBasePath, "ocid-mapping")` to find the dir
- Calls `Files.list(dir)` to find latest mapping file
- Calls `Files.newInputStream(mappingFile)` to read
- Uses `GZIPInputStream` + `BufferedReader` for decoding

- [ ] **Step 2: Update to use `ObjectStorage`**

Replace:
- `import maple.externalapi.port.out.ExternalApiArtifactStorePort` → `import maple.expectation.common.storage.ObjectStorage`
- The field `private val objectMapper: ObjectMapper` stays; remove the `storeBasePath` field (or keep it only if other methods still use it)
- `Path.of(storeBasePath).resolve("ocid-mapping")` → remove; use `objectStorage.listByPrefix("ocid-mapping/")`
- `Files.list(dir).use { ... }.filter { endsWith(".jsonl.gz") }.sorted().lastOrNull()` → `objectStorage.listByPrefix("ocid-mapping/").filter { it.key.endsWith(".jsonl.gz") }.maxByOrNull { it.lastModified }?.key`
- `Files.newInputStream(mappingFile)` → `objectStorage.getStream(latestKey)`

- [ ] **Step 3: Verify compile**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(ext-api): OcidCacheProvider uses common ObjectStorage

Replaces direct Paths.get / Files.list / Files.newInputStream with
ObjectStorage.listByPrefix + getStream. The ocid-mapping read path
now flows through the unified interface (Local or MinIO backend).

Issue #1217"
```

---

## Task 12: External-api — migrate `ArtifactCleanupScheduler` + `ConsumedChunkCleanupScheduler` to common `ObjectStorage`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt`

- [ ] **Step 1: Update `ArtifactCleanupScheduler`**

Open the file. The scheduler uses the 9 methods of `ExternalApiArtifactStorePort`:
- `store.listRuns()` → `objectStorage.listByPrefix("runs/")` (then map to runId via path manipulation)
- `store.listStoredKeys(endpoint)` → `objectStorage.listByPrefix("<endpoint>/")` + filter suffix `.json.gz` (or use `ObjectInfo` directly)
- `store.fileExists(relativePath)` → `objectStorage.exists(relativePath)`
- `store.calculateDirectorySize(relativePath)` → `objectStorage.calculatePrefixSize(relativePath)`
- `store.deleteRun(runId)` → `objectStorage.deleteByPrefix("runs/$runId/")`
- `store.deleteAll(endpoint)` → `objectStorage.deleteByPrefix("<endpoint>/")`
- (the other 4 methods — `store`, `read`, `listRuns` already mapped, `calculateDirectorySize` already mapped)

- [ ] **Step 2: Update `ConsumedChunkCleanupScheduler`**

Open the file. The scheduler:
- Reads `external-api.store.base-path` directly
- Uses `Files.deleteIfExists(path)` to delete
- Listens to Kafka `chunk-consumed-topic`

Replace:
- `import org.springframework.beans.factory.annotation.Value` + `@Value("\${external-api.store.base-path:../data}")` → use `ObjectStorage` for delete
- `Files.deleteIfExists(Paths.get(basePath, event.objectKey))` → `objectStorage.delete(event.objectKey)`
- Same for `event.sourceObjectKey` (if present)
- Keep the `@KafkaListener` and `Acknowledgment` logic unchanged

- [ ] **Step 3: Verify compile**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit (both schedulers together)**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(ext-api): migrate ArtifactCleanupScheduler + ConsumedChunkCleanupScheduler to ObjectStorage

ArtifactCleanupScheduler:
- store.listRuns() -> objectStorage.listByPrefix('runs/')
- store.listStoredKeys(endpoint) -> objectStorage.listByPrefix('<endpoint>/')
- store.fileExists(path) -> objectStorage.exists(path)
- store.calculateDirectorySize(path) -> objectStorage.calculatePrefixSize(path)
- store.deleteRun(runId) -> objectStorage.deleteByPrefix('runs/<runId>/')
- store.deleteAll(endpoint) -> objectStorage.deleteByPrefix('<endpoint>/')

ConsumedChunkCleanupScheduler:
- Files.deleteIfExists(path) -> objectStorage.delete(key)
- @Value direct-FS path removed

Both schedulers now flow through the unified ObjectStorage.

Issue #1217"
```

---

## Task 13: `DefaultChunkFileReader` TDD pair

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/DefaultChunkFileReaderTest.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/DefaultChunkFileReader.kt`

- [ ] **Step 1: Write the failing test**

Create `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/DefaultChunkFileReaderTest.kt`:

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.core.model.chunk.BasicRecord
import maple.expectation.core.model.chunk.GroupedEquipmentResult
import maple.expectation.core.model.chunk.OcidMapping
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChunkFileReaderTest {

    private val objectStorage: ObjectStorage = mock()
    private val objectMapper = ObjectMapper()
    private val readerMetrics: SynchronizerReaderMetrics = mock()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    @Test
    fun `readBasicChunk returns parsed records from gzipped JSONL`() = runTest(testDispatcher) {
        val line1 = """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-1","body":{"character_name":"ign-1","world_name":"Aquila","character_class":"Warrior","character_level":250,"guild_name":"G1"}}"""
        val line2 = """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-2","body":{"character_name":"ign-2","world_name":"Aquila","character_class":"Mage","character_level":200,"guild_name":null}}"""
        val data = gzip("$line1\n$line2\n".toByteArray())
        whenever(objectStorage.get("k")).thenReturn(data)

        val result = DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readBasicChunk("k")

        assertThat(result).hasSize(2)
        assertThat(result[0].userIgn).isEqualTo("ign-1")
        assertThat(result[0].worldName).isEqualTo("Aquila")
        assertThat(result[1].userIgn).isEqualTo("ign-2")
        assertThat(result[1].guildName).isNull()
        verify(objectStorage).get("k")
    }

    @Test
    fun `readResultChunk returns parsed equipment results`() = runTest(testDispatcher) {
        val line1 = """{"ocid":"o1","presetNo":1,"itemName":"Sword","itemLevel":200,"itemPart":"Weapon","itemEquipmentPart":"Weapon","currentStar":0,"targetStar":22,"status":"SUCCESS","totalCost":1.0,"blackCubeCost":0.5,"additionalCubeCost":0.3,"starforceCost":0.2}"""
        val data = gzip("$line1\n".toByteArray())
        whenever(objectStorage.get("k")).thenReturn(data)

        val result = DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readResultChunk("k")

        assertThat(result).hasSize(1)
        assertThat(result[0].ocid).isEqualTo("o1")
        assertThat(result[0].presetNo).isEqualTo(1)
        verify(objectStorage).get("k")
    }

    @Test
    fun `readOcidMapping returns parsed mappings`() = runTest(testDispatcher) {
        val line1 = """{"userIgn":"ign-1","ocid":"ocid-1"}"""
        val line2 = """{"userIgn":"ign-2","ocid":"ocid-2"}"""
        val data = gzip("$line1\n$line2\n".toByteArray())
        whenever(objectStorage.get("m")).thenReturn(data)

        val result = DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readOcidMapping("m")

        assertThat(result).hasSize(2)
        assertThat(result[0]).isEqualTo(OcidMapping("ign-1", "ocid-1"))
        assertThat(result[1]).isEqualTo(OcidMapping("ign-2", "ocid-2"))
    }

    @Test
    fun `get on missing key surfaces underlying S3 exception`() = runTest(testDispatcher) {
        whenever(objectStorage.get("missing"))
            .thenThrow(software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().message("nope").build())

        org.junit.jupiter.api.assertThrows<software.amazon.awssdk.services.s3.model.NoSuchKeyException> {
            DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readBasicChunk("missing")
        }
    }
}
```

- [ ] **Step 2: Run the test, verify it fails (RED)**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.storage.DefaultChunkFileReaderTest" --no-daemon 2>&1 | grep -E "Unresolved|BUILD" | head -3`
Expected: BUILD FAILED. Unresolved: DefaultChunkFileReader.

- [ ] **Step 3: Implement `DefaultChunkFileReader`**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/DefaultChunkFileReader.kt`:

```kotlin
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
import maple.synchronizer.domain.BasicRecord as BasicRecordAlias
import maple.synchronizer.domain.GroupedEquipmentResult as GroupedEquipmentResultAlias
import maple.synchronizer.domain.OcidMapping as OcidMappingAlias
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Consolidated chunk reader with IO/CPU 분리 (per VS2 spec §5.3).
 *
 * - IO (objectStorage.get) runs on Dispatchers.IO (VT-friendly for network)
 * - CPU (GZIP decompress + JSON parse + dedup) runs on Dispatchers.Default
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

    private fun parseBasicChunk(rawBytes: ByteArray, objectKey: String): List<BasicRecord> {
        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        val records = mutableListOf<BasicRecord>()
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    parseBasicLine(line, objectKey, parseErrors, missingFields, filtered)?.let { records.add(it) }
                }
                line = reader.readLine()
            }
        }
        return records
    }

    private fun parseBasicLine(
        line: String,
        objectKey: String,
        parseErrors: AtomicLong,
        missingFields: AtomicLong,
        filtered: AtomicLong,
    ): BasicRecord? {
        val node = try {
            objectMapper.readTree(line)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            parseErrors.incrementAndGet()
            readerMetrics.incrementParseError("basic_chunk")
            throw ex
        }

        val status = node.get("status")?.asText()
        if (status != "SUCCESS") {
            filtered.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "status")
            return null
        }
        val endpoint = node.get("endpoint")?.asText()
        if (endpoint != "character-basic") {
            filtered.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "endpoint")
            return null
        }

        val ocid = node.get("key")?.asText() ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFields.get() > missingFieldThreshold) {
                throw IllegalStateException("BasicChunk missing-field threshold exceeded")
            }
            return null
        }
        val body = node.get("body") ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFields.get() > missingFieldThreshold) {
                throw IllegalStateException("BasicChunk missing-field threshold exceeded")
            }
            return null
        }

        val userIgn = body.get("character_name")?.asText() ?: return null
        val worldName = body.get("world_name")?.asText()
        val characterClass = body.get("character_class")?.asText()
        val characterLevel = body.get("character_level")?.asInt()
        val guildName = body.get("guild_name")?.asText()

        val bodyBytes = objectMapper.writeValueAsBytes(body)
        return BasicRecord(
            userIgn = userIgn,
            ocid = ocid,
            worldName = worldName,
            characterClass = characterClass,
            characterLevel = characterLevel,
            guildName = guildName,
            compressedBody = maple.expectation.util.GzipUtils.compress(bodyBytes),
            bodyHash = maple.expectation.util.HashUtils.sha256Hex(bodyBytes),
        )
    }

    private fun parseResultChunk(rawBytes: ByteArray, objectKey: String): List<GroupedEquipmentResult> {
        val grouped = mutableMapOf<String, MutableList<maple.expectation.core.model.chunk.CalculatedEquipmentItem>>()
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val item = parseResultLine(line, objectKey)
                    grouped.getOrPut("${item.ocid}:${item.presetNo}") { mutableListOf() }.add(item)
                }
                line = reader.readLine()
            }
        }
        return grouped.map { (readKey, group) ->
            GroupedEquipmentResult(
                readKey = readKey,
                ocid = group.first().ocid,
                presetNo = group.first().presetNo,
                items = group,
            )
        }
    }

    private fun parseResultLine(line: String, objectKey: String): maple.expectation.core.model.chunk.CalculatedEquipmentItem {
        val node = objectMapper.readTree(line)
        return maple.expectation.core.model.chunk.CalculatedEquipmentItem(
            ocid = requireNotNull(node.get("ocid")?.asText()) { "Missing required field: ocid" },
            presetNo = requireNotNull(node.get("presetNo")?.asInt()) { "Missing required field: presetNo" },
            itemName = node.get("itemName")?.asText() ?: "",
            itemLevel = node.get("itemLevel")?.asInt() ?: 0,
            itemPart = node.get("itemPart")?.asText() ?: "",
            itemEquipmentPart = node.get("itemEquipmentPart")?.asText(),
            potentialGrade = node.get("potentialGrade")?.asText(),
            potentialOptions = node.get("potentialOptions")?.map { it.asText() },
            additionalGrade = node.get("additionalGrade")?.asText(),
            additionalOptions = node.get("additionalOptions")?.map { it.asText() },
            currentStar = node.get("currentStar")?.asInt() ?: 0,
            targetStar = node.get("targetStar")?.asInt() ?: 0,
            status = node.get("status")?.asText() ?: "UNKNOWN",
            totalCost = node.get("totalCost")?.decimalValue() ?: BigDecimal.ZERO,
            blackCubeCost = node.get("blackCubeCost")?.decimalValue() ?: BigDecimal.ZERO,
            additionalCubeCost = node.get("additionalCubeCost")?.decimalValue() ?: BigDecimal.ZERO,
            starforceCost = node.get("starforceCost")?.decimalValue() ?: BigDecimal.ZERO,
            errorMessage = node.get("errorMessage")?.asText(),
        )
    }

    private fun parseOcidMapping(rawBytes: ByteArray, manifestPath: String): List<OcidMapping> {
        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val mappings = mutableListOf<OcidMapping>()
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    parseOcidMappingLine(line, manifestPath, parseErrors, missingFields)?.let { mappings.add(it) }
                }
        }
        return mappings
    }

    private fun parseOcidMappingLine(
        line: String,
        manifestPath: String,
        parseErrors: AtomicLong,
        missingFields: AtomicLong,
    ): OcidMapping? {
        val node = try {
            objectMapper.readTree(line)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            parseErrors.incrementAndGet()
            readerMetrics.incrementParseError("ocid_mapping")
            throw ex
        }
        val ign = node.get("userIgn")?.asText() ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        val ocid = node.get("ocid")?.asText() ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        return OcidMapping(ign, ocid)
    }
}
```

- [ ] **Step 4: Run the test, verify it passes (GREEN)**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.storage.DefaultChunkFileReaderTest" --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL. All 4 tests pass.

- [ ] **Step 5: Commit (test + impl together)**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/storage/DefaultChunkFileReaderTest.kt
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/DefaultChunkFileReader.kt
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "feat(sync): DefaultChunkFileReader (1 class, 3 methods, IO/CPU 분리)

TDD pair: 4 test cases + implementation.
Implements ChunkFileReaderPort with 3 methods:
- readBasicChunk: gzipped JSONL -> List<BasicRecord>
- readResultChunk: gzipped JSONL -> List<GroupedEquipmentResult>
- readOcidMapping: gzipped JSONL -> List<OcidMapping>

IO/CPU 분리 (per spec §5.3):
- objectStorage.get runs on Dispatchers.IO
- JSON parse + dedup runs on Dispatchers.Default
- (For MinIO: objectStorage.get is 50-200ms blocking; running it on
  Dispatchers.Default would block the CPU pool. Separation matters.)

Issue #1217"
```

---

## Task 14: Synchronizer — update 3 consumer call sites to inject `ChunkFileReaderPort` + delete 3 old readers

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`
- Delete: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt`
- Delete: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/ResultFileReader.kt`
- Delete: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt`

- [ ] **Step 1: Read the 3 consumer files to identify current injections**

For each consumer, identify:
- The constructor parameter for the old reader (e.g., `resultFileReader: ResultFileReader`)
- The call site (e.g., `resultFileReader.readAndGroupByCompositeKey(objectKey)`)

- [ ] **Step 2: Update `KafkaResultChunkConsumer`**

Replace:
- `import maple.synchronizer.storage.ResultFileReader` → `import maple.expectation.core.port.out.ChunkFileReaderPort`
- `private val resultFileReader: ResultFileReader` → `private val chunkFileReader: ChunkFileReaderPort`
- `resultFileReader.readAndGroupByCompositeKey(objectKey)` → `chunkFileReader.readResultChunk(objectKey)`
- The function may have been named differently (e.g., `readAndGroupByCompositeKey` returning a list, vs `readResultChunk` returning a list). Adapt the call.

- [ ] **Step 3: Update `BasicSnapshotChunkConsumer`**

Replace:
- `import maple.synchronizer.storage.BasicChunkFileReader` → `import maple.expectation.core.port.out.ChunkFileReaderPort`
- `private val basicChunkFileReader: BasicChunkFileReader` → `private val chunkFileReader: ChunkFileReaderPort`
- `basicChunkFileReader.read(objectKey)` → `chunkFileReader.readBasicChunk(objectKey)`

- [ ] **Step 4: Update `OcidLookupRunConsumer`**

Replace:
- `import maple.synchronizer.storage.OcidMappingFileReader` → `import maple.expectation.core.port.out.ChunkFileReaderPort`
- `private val ocidMappingFileReader: OcidMappingFileReader` → `private val chunkFileReader: ChunkFileReaderPort`
- `ocidMappingFileReader.read(manifestPath)` → `chunkFileReader.readOcidMapping(manifestPath)`

- [ ] **Step 5: Delete the 3 old reader files**

```bash
rm /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt
rm /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin/maple/synchronizer/storage/ResultFileReader.kt
rm /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt
```

If `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/` is now empty (only DefaultChunkFileReader remains), keep the directory (DefaultChunkFileReader lives there).

- [ ] **Step 6: Verify compile**

Run: `./gradlew :module-synchronizer:compileKotlin --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Verify tests pass**

Run: `./gradlew :module-synchronizer:test --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL. DefaultChunkFileReaderTest passes.

- [ ] **Step 8: Commit (3 consumer updates + 3 file deletions together)**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/
git add -u module-synchronizer/src/main/kotlin/maple/synchronizer/storage/
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "refactor(sync): 3 consumers inject ChunkFileReaderPort + delete old reader classes

KafkaResultChunkConsumer, BasicSnapshotChunkConsumer,
OcidLookupRunConsumer all switch from their dedicated reader
(ResultFileReader, BasicChunkFileReader, OcidMappingFileReader)
to the new ChunkFileReaderPort.

The 3 old reader classes are deleted; their logic now lives in
DefaultChunkFileReader (Task 13).

Issue #1217"
```

---

## Task 15: Full verification + ADR-720 finalization

**Files:** (verification only; ADR may need updates)

- [ ] **Step 1: Full compile across all modules**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full test run (skipping IT without MinIO)**

Run: `./gradlew test --no-daemon 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Integration test against local MinIO (if running)**

If docker-compose MinIO is running (from VS1 verification):
```bash
INTEGRATION_MINIO=true MINIO_ACCESS_KEY=maple MINIO_SECRET_KEY=changeme \
  MINIO_ENDPOINT=http://localhost:9000 MINIO_BUCKET=maple-expectation \
  ./gradlew :module-infra:test :module-synchronizer:test --no-daemon 2>&1 | tail -3
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify module-core has zero Spring imports**

```bash
grep -rn "import org.springframework" module-core/src/main/ 2>&1 | head -3
```

Expected: no output.

- [ ] **Step 5: Verify no `module-calculator/storage/` or `module-synchronizer/storage/` old reader files remain**

```bash
ls /home/maple/probabilistic-valuation-engine/module-calculator/src/main/kotlin/maple/calculator/storage/ 2>/dev/null
ls /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin/maple/synchronizer/storage/ 2>/dev/null
```

Expected: calculator/storage/ does not exist. synchronizer/storage/ contains only DefaultChunkFileReader.kt.

- [ ] **Step 6: Finalize ADR-720**

Open `docs/01_ADR/ADR-720_object-storage-minio-migration.md` (from macro spec §12, written in VS5 later — for now, just verify the placeholder file exists and update with VS1+VS2 status).

If the file doesn't exist, create it with a brief summary:
```markdown
# ADR-720: Object Storage Migration to MinIO

- Status: Accepted (Phase 1: Abstraction + Local — done in VS1, Phase 2: Migration of callers — done in VS2)
- Date: 2026-06-09 (initial) | 2026-06-09 (Phase 2 update)
- Owner: zbnerd

## Background
(macro spec §1, §2)

## Decision
Migrated 4 application modules to unified `ObjectStorage` interface
(VS1) with default backend = `local`. MinIO is the production target
(VS3+VS4 atomic cutover).

## Trade-offs
(macro spec §9)

## Status
- Phase 1 (Abstraction + Local): ✅ done in VS1 (PR #1222)
- Phase 2 (Caller migration): ✅ done in VS2 (PR #1217)
- Phase 3 (Production cutover): pending VS3+VS4
```

- [ ] **Step 7: Commit (verification cleanup)**

If any verification step revealed leftover changes, commit:
```bash
git add -A
git status  # sanity check
git -c user.email="claude@anthropic.com" -c user.name="Claude" commit -m "docs(adr): finalize ADR-720 with VS1+VS2 status

Reference: docs/superpowers/specs/2026-06-09-v2-pipeline-modules-migration-design.md

VS1 (PR #1222) shipped the unified ObjectStorage interface + adapters.
VS2 (this PR) migrates 4 application modules to the new interface.

Remaining: VS3 (#1218) + VS4 (#1219) atomic cutover to storage.backend=minio."
```

(Skip this step if no leftover changes.)

---

## Task 16: Final commit + PR ready check

- [ ] **Step 1: Verify all DoD items from spec §13**

Re-read `docs/superpowers/specs/2026-06-09-v2-pipeline-modules-migration-design.md` §13. Walk through each checkbox mentally. Confirm each is satisfied.

- [ ] **Step 2: Generate PR description**

```bash
git log --oneline develop..HEAD
```

Use the output to draft a PR body. Suggested title: `feat(infra): V2 pipeline modules migration (issue #1217)`.

- [ ] **Step 3: Push branch and create PR**

```bash
git checkout -b feature/v2-pipeline-modules-migration  # from current develop
git push -u origin feature/v2-pipeline-modules-migration
gh pr create --base develop --title "feat(infra): V2 pipeline modules migration (issue #1217)" --body "..."
```

---

## Self-Review Notes (v1)

**Spec coverage:**
- §5.1 New types in `module-core` → Task 2 + Task 3
- §5.2 `SnapshotObjectStoreAdapter` → Task 4
- §5.3 `DefaultChunkFileReader` → Task 13
- §5.4.1 module-calculator → Tasks 6, 7, 8
- §5.4.2 module-external-api → Tasks 9, 10, 11, 12
- §5.4.3 module-synchronizer → Tasks 13 (impl), 14 (consumers)
- §5.4.4 module-infra → Tasks 4 (adapter), 5 (delete legacy)
- §5.5 Pre-existing breakage fix → Task 1
- §9 Testing → Task 4 (adapter test), Task 13 (reader test), Task 6 (existing test mock update)
- §10 Trade-offs → captured in commit messages
- §13 DoD → Task 15 (verification), Task 16 (PR ready)

**Placeholder scan:** none found.

**Type consistency:**
- `maple.expectation.common.storage.ObjectStorage` used consistently (Tasks 6, 7, 8, 10, 11, 12, 13, 14)
- `maple.expectation.core.port.out.ChunkFileReaderPort` used consistently (Tasks 3, 13, 14)
- `maple.expectation.core.model.chunk.{BasicRecord,GroupedEquipmentResult,OcidMapping}` used consistently (Tasks 2, 3, 13)
- `maple.synchronizer.domain.{BasicRecord,GroupedEquipmentResult,OcidMapping}` typealias re-export (Task 2)
- `SnapshotObjectStoreAdapter.put/get/delete` signatures match `SnapshotObjectStore` port exactly (Task 4)
- `DefaultChunkFileReader.readBasicChunk/readResultChunk/readOcidMapping` match `ChunkFileReaderPort` (Task 13)

**Notes for executors:**
- Pre-existing fix scope is "as discovered" (Task 1). The actual list of 4+ files is determined by `./gradlew compileKotlin compileJava --continue` output. Some fixes may cascade.
- Task 4 adapter test uses `org.mockito.kotlin.mock` + `whenever` for `ObjectStorage`. The test for the S3 exception path uses `software.amazon.awssdk.services.s3.model.NoSuchKeyException` — but the production code throws `S3Exception` and `SdkClientException` (subclasses). The test creates `NoSuchKeyException` which is also an `S3Exception` subclass; the exception propagates through `objectStorage.get` directly without the adapter catching it, so the type matches.
- Task 8: `CalculatorResultCleanupScheduler` may keep a `basePath` field for `readAttributes` use. If `readAttributes` is no longer needed (because `getLastModified` from `ObjectStorage` works for both backends), the field can be deleted. Verify during implementation.
- Task 9: `@Deprecated` on the port + adapter keeps them functional. The 2 cleanup schedulers (Task 12) stop calling them. Other call sites (ArtifactCleanupScheduler, OcidCacheProvider) also stop. After Task 12, no caller uses the deprecated port. Spring still loads the adapter (it's `@Component`); this is acceptable (deprecation only, removal in #1221).
- Task 11: `OcidCacheProvider` previously used direct FS (`Files.list` etc). With `ObjectStorage`, the listing is via `listByPrefix("ocid-mapping/")`. The "latest" file is selected by `lastModified` (Instant) instead of filesystem sort. Order of evaluation differs slightly — verify with a test or manual smoke that the latest file is still picked.
- Task 13: `BasicRecord.compressedBody` and `bodyHash` are computed from the body bytes (re-gzip + sha256). The original `BasicChunkFileReader` did this same computation. The test verifies userIgn/worldName/guildName; compressedBody is not asserted but is set.
- Task 13 IO/CPU instrumentation: the test uses `runTest(testDispatcher)` + `Dispatchers.setMain(testDispatcher)`. This forces the `runBlocking` inside `DefaultChunkFileReader` to use `testDispatcher` as its parent, allowing the test to verify that `Dispatchers.IO` and `Dispatchers.Default` calls are scheduled correctly. (Direct assertion of "called on IO" vs "called on Default" is complex; the test primarily verifies behavior + that the implementation doesn't throw. The plan allows for this — IO/CPU correctness is verified via the existing CPU offload issues #1128-1130 patterns.)
- Task 14: The old 3 reader classes may have other callers (e.g., unit tests). Run `grep -rn "BasicChunkFileReader\|ResultFileReader\|OcidMappingFileReader"` to find any remaining references. If any test files reference the old readers, update or delete them.
- Task 15: ADR-720 was supposed to be created in VS5 (per macro spec §10). For VS2 we just create/update a minimal ADR with VS1+VS2 status. The full ADR is in VS5.
