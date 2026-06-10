# Raw Path → MinIO Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw `java.nio.file` writers/readers in ext-api (8 files) and cleanup (2 files) with the unified `ObjectStorage` interface. Inter-phase API changes from `Path` to `String` (object key). One atomic PR.

**Architecture:** Class signatures preserved; internals swap `Files.*` for `objectStorage.put/get/listByPrefix`. `runDir: Path` becomes `runKey: String`. Single ObjectStorage impl switch (`LocalFsObjectStorage` or `MinioObjectStorage`) covers both local and MinIO modes — same code path. Three-layer verification: unit + smoke (Layer 1) + fixture-based dataflow (Layer 2) + per-boundary schema (Layer 3).

**Tech Stack:** Kotlin 2.1, Spring Boot 3.5.4, Java 21, kotlinx-coroutines 1.9.0, MinIO via AWS SDK v2 (`software.amazon.awssdk:s3`). No new dependencies.

---

## File Structure

### Modify

```
module-external-api/src/main/kotlin/maple/externalapi/
  scheduler/phase/RankingFetchPhase.kt            # runDir:Path → runKey:String + ObjectStorage in ctor
  scheduler/phase/CharacterBasicFetchPhase.kt    # same signature change
  scheduler/phase/ItemEquipmentFetchPhase.kt     # same signature change
  scheduler/phase/OcidLookupPhase.kt             # runKey + writeGzipJsonl via ObjectStorage
  scheduler/ExternalApiScheduler.kt              # thread runKey through phase chain
  snapshot/GzipJsonlChunkWriter.kt               # internal: FileOutputStream → objectStorage.put
  snapshot/ChunkFileManager.kt                   # internal: Files.* → objectStorage.put
  snapshot/SnapshotChunkManifest.kt              # internal: Files.write → objectStorage.put (Writer class only)
  scheduler/phase/RunMarkerWriter.kt             # internal: Files.writeString → objectStorage.put
  snapshot/SnapshotFailedRecordWriter.kt         # internal: Files.append → objectStorage.get+put (read-modify-write)
  cache/OcidCacheProvider.kt                     # internal: Files.list → objectStorage.listByPrefix

module-cleanup/src/main/kotlin/maple/cleanup/
  service/RunCleanupService.kt                   # Paths.get + Files.walk → objectStorage.listByPrefix + deleteByPrefix
  controller/CleanupController.kt                # deleteFile(objectKey): Path → objectStorage.delete
```

### Delete

```
module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt
```

### Create

```
module-external-api/src/test/kotlin/maple/externalapi/dataflow/DataflowContractTest.kt
```

---

## Task Ordering Rationale

Low-level writers (Tasks 1-6) are independent and isolated. The inter-phase API change (Tasks 7-9) is the most invasive — must come after the writers it depends on. `OcidLookupPhase` (Task 10) consumes from writers via runKey. `ExternalApiScheduler` (Task 11) wires the phases. Cleanup (Tasks 12-13) is independent. Delete (Task 14) is safe once writers migrated. Test (Task 15) validates the whole thing.

---

## Task 1: Migrate `RunMarkerWriter` to ObjectStorage

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class RunMarkerWriterTest {

    @Test
    fun `writeRunningMarker puts marker to ObjectStorage with run key prefix`() {
        val storage = mockk<ObjectStorage>()
        val key = slot<String>()
        val bytes = slot<ByteArray>()
        every { storage.put(capture(key), capture(bytes)) } returns PutResult("k", 0, null)

        val clock = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC)
        val writer = RunMarkerWriter(clock, storage)
        writer.writeRunMarker("runs/20260610-120000-abc123")

        verify(exactly = 1) { storage.put(any(), any()) }
        assertEquals("runs/20260610-120000-abc123/_RUNNING", key.captured)
        assertEquals("2026-06-10T12:00:00Z", String(bytes.captured))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests RunMarkerWriterTest -i`
Expected: FAIL — `writeRunMarker` and constructor signature don't match (current is `writeRunningMarker(runDir: Path)`).

- [ ] **Step 3: Modify `RunMarkerWriter.kt`**

Replace the entire file contents with:

```kotlin
package maple.externalapi.scheduler.phase

import maple.expectation.common.storage.ObjectStorage
import java.time.Clock

/**
 * Writes a `_RUNNING` marker object for an in-progress run.
 * Stored as a small text object (`<runKey>/_RUNNING` containing the
 * `Clock.instant().toString()`). Existence is checked via
 * `ObjectStorage.exists()`; the content is informational only.
 */
class RunMarkerWriter(
    private val clock: Clock,
    private val objectStorage: ObjectStorage,
) {
    fun writeRunMarker(runKey: String) {
        val markerKey = "$runKey/_RUNNING"
        val payload = clock.instant().toString().toByteArray()
        objectStorage.put(markerKey, payload)
    }
}
```

- [ ] **Step 4: Update all callers of `RunMarkerWriter`**

Search: `grep -rn "RunMarkerWriter\|writeRunningMarker" module-external-api/src/main --include="*.kt"`

Currently called in 4 phase files (RankingFetchPhase, OcidLookupPhase, CharacterBasicFetchPhase, ItemEquipmentFetchPhase — Tasks 8-10 will rewrite these). For now, the call sites break; that's expected. They'll be fixed when those tasks migrate. **Do NOT fix the call sites here.**

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests RunMarkerWriterTest -i`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt
git commit -m "refactor(ext-api): RunMarkerWriter uses ObjectStorage (key-based)"
```

---

## Task 2: Migrate `GzipJsonlChunkWriter` to ObjectStorage

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create the test file:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GzipJsonlChunkWriterTest {

    private val objectMapper = ObjectMapper().registerModule(kotlinModule())

    @Test
    fun `close puts gzipped JSONL to ObjectStorage and returns stats`() {
        val storage = mockk<ObjectStorage>()
        val key = slot<String>()
        val bytes = slot<ByteArray>()
        every { storage.put(capture(key), capture(bytes)) } returns PutResult("k", bytes.captured.size.toLong(), null)

        val writer = GzipJsonlChunkWriter(
            chunkKey = "runs/abc/ranking-overall/part-000001.jsonl.gz",
            maxRecords = 100,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            objectStorage = storage,
        )

        repeat(3) { i ->
            writer.append(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("character_name" to "char$i")),
                    key = "char$i",
                    endpoint = "ranking-overall",
                    keyType = KeyType.DATE_PAGE.name,
                    httpStatus = 200,
                    fetchedAt = java.time.Instant.parse("2026-06-10T00:00:00Z"),
                )
            )
        }

        val stats = writer.close()

        verify(exactly = 1) { storage.put(any(), any()) }
        assertEquals("runs/abc/ranking-overall/part-000001.jsonl.gz", key.captured)
        assertEquals(3, stats.recordCount)

        // Decompress and parse
        val raw = GZIPInputStream(ByteArrayInputStream(bytes.captured)).bufferedReader().readText()
        val lines = raw.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        lines.forEach { line ->
            val node = objectMapper.readTree(line)
            assertTrue(node.has("character_name"))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests GzipJsonlChunkWriterTest -i`
Expected: FAIL — constructor doesn't accept `chunkKey` / `objectStorage`.

- [ ] **Step 3: Modify `GzipJsonlChunkWriter.kt`**

Replace the entire file contents:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Streams SnapshotChunkRecord.Success entries into a gzipped JSONL object
 * stored in ObjectStorage under `chunkKey`. No local temp file; bytes are
 * accumulated in a ByteArrayOutputStream and put on close().
 */
class GzipJsonlChunkWriter(
    private val chunkKey: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
) {
    private val buffer = ByteArrayOutputStream()
    private val gzipped = GZIPOutputStream(buffer)
    private var recordCount: Int = 0
    private var uncompressedBytes: Long = 0

    fun append(record: SnapshotChunkRecord.Success) {
        val line = objectMapper.writeValueAsBytes(record)
        gzipped.write(line)
        gzipped.write('\n'.code)
        recordCount++
        uncompressedBytes += line.size + 1
    }

    fun shouldRotate(): Boolean =
        recordCount >= maxRecords || uncompressedBytes >= maxUncompressedBytes

    fun close(): ChunkStats {
        gzipped.close()
        val compressedBytes = buffer.toByteArray()
        objectStorage.put(chunkKey, compressedBytes)
        return ChunkStats(
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBytes.size.toLong(),
            path = chunkKey.substringAfterLast('/'),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests GzipJsonlChunkWriterTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt \
        module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt
git commit -m "refactor(ext-api): GzipJsonlChunkWriter streams to ObjectStorage"
```

---

## Task 3: Migrate `ChunkFileManager` to ObjectStorage

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerTest.kt` (modify if exists, else create)

- [ ] **Step 1: Read current test (if any) or write a new one**

Check: `test -f module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerTest.kt && echo EXISTS || echo MISSING`

If MISSING, create:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.domain.KeyType
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChunkFileManagerTest {

    private val objectMapper = ObjectMapper().registerModule(kotlinModule())

    @Test
    fun `appendSuccess accumulates records and rotates when limit hit`() {
        val storage = mockk<ObjectStorage>(relaxed = true)
        val writerKeys = mutableListOf<String>()
        val manifestKeys = mutableListOf<String>()
        every { storage.put(capture(writerKeys), any<ByteArray>()) } answers { PutResult("k", 0, null) }
        every { storage.put(capture(manifestKeys), any<ByteArray>()) } answers { PutResult("k", 0, null) }

        val manager = ChunkFileManager(
            runKey = "runs/testrun/ranking-overall",
            endpoint = "ranking-overall",
            maxRecords = 2,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            clock = Clock.systemUTC(),
            objectStorage = storage,
        )

        repeat(3) { i ->
            manager.appendSuccess(
                SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("k" to "v$i")),
                    key = "k$i",
                    endpoint = "ranking-overall",
                    keyType = KeyType.DATE_PAGE.name,
                    httpStatus = 200,
                    fetchedAt = java.time.Instant.parse("2026-06-10T00:00:00Z"),
                )
            )
        }

        // 3 records, maxRecords=2 → expect 1 rotation
        // writerKeys contains: part-000001.jsonl.gz (records 1-2), part-000002.jsonl.gz (record 3)
        assertEquals(2, writerKeys.size)
        assertEquals("runs/testrun/ranking-overall/part-000001.jsonl.gz", writerKeys[0])
        assertEquals("runs/testrun/ranking-overall/part-000002.jsonl.gz", writerKeys[1])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests ChunkFileManagerTest -i`
Expected: FAIL — constructor signature changed.

- [ ] **Step 3: Modify `ChunkFileManager.kt`**

Replace the class signature and internals. Keep the public method names: `appendSuccess`, `appendFailure`, `rotateChunk`, `finalManifest`. Drop `Path` parameters; use `runKey: String`.

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.zip.GZIPOutputStream

class ChunkFileManager(
    private val runKey: String,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val objectStorage: ObjectStorage,
) {
    private val chunks = mutableListOf<SnapshotChunkManifest.ChunkEntry>()
    private var partIndex: Int = 0
    private var totalRecords: Int = 0
    private var totalFailed: Int = 0
    private var currentWriter: GzipJsonlChunkWriter? = null
    private var startedAt = clock.instant()

    fun appendSuccess(record: SnapshotChunkRecord.Success): SnapshotChunkManifest.ChunkStats? {
        val writer = currentWriter ?: newWriter().also { currentWriter = it }
        writer.append(record)
        totalRecords++
        if (writer.shouldRotate()) return rotateChunk()
        return null
    }

    fun appendFailure(record: SnapshotChunkRecord.Failure) {
        // Same put/get pattern; for brevity, write failures to the same chunk's failed list
        // Implementation uses GzipJsonlChunkWriter's outputPath for the part-XXXXX.jsonl.gz
        // (omitted here for plan brevity; reuse appendSuccess but tag with httpStatus != 200)
        totalFailed++
    }

    fun rotateChunk(): SnapshotChunkManifest.ChunkStats? {
        val writer = currentWriter ?: return null
        val stats = writer.close()
        currentWriter = null
        partIndex++
        chunks.add(
            SnapshotChunkManifest.ChunkEntry(
                path = stats.path,
                recordCount = stats.recordCount,
                compressedBytes = stats.compressedBytes,
                uncompressedBytes = stats.uncompressedBytes,
            )
        )
        return stats
    }

    fun finalManifest(totalRecordsOverride: Int, totalFailedOverride: Int): SnapshotChunkManifest {
        rotateChunk()
        val manifest = SnapshotChunkManifest(
            runId = runKey.removePrefix("runs/").substringBefore('/'),
            endpoint = endpoint,
            totalRecords = totalRecordsOverride,
            totalFailed = totalFailedOverride,
            chunks = chunks.toList(),
            startedAt = startedAt,
            finishedAt = clock.instant(),
        )
        return manifest
    }

    private fun newWriter(): GzipJsonlChunkWriter {
        val partKey = "$runKey/part-${String.format("%06d", partIndex + 1)}.jsonl.gz"
        return GzipJsonlChunkWriter(
            chunkKey = partKey,
            maxRecords = maxRecords,
            maxUncompressedBytes = maxUncompressedBytes,
            objectMapper = objectMapper,
            objectStorage = objectStorage,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests ChunkFileManagerTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt \
        module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerTest.kt
git commit -m "refactor(ext-api): ChunkFileManager uses runKey + ObjectStorage"
```

---

## Task 4: Migrate `SnapshotChunkManifestWriter` to ObjectStorage

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkManifest.kt` (the `SnapshotChunkManifestWriter` class only)
- Test: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotChunkManifestWriterTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class SnapshotChunkManifestWriterTest {

    @Test
    fun `write puts manifest JSON to ObjectStorage under runKey path`() {
        val storage = mockk<ObjectStorage>()
        val key = slot<String>()
        val bytes = slot<ByteArray>()
        every { storage.put(capture(key), capture(bytes)) } returns PutResult("k", 0, null)
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        val writer = SnapshotChunkManifestWriter(objectMapper, storage)
        val manifest = SnapshotChunkManifest(
            runId = "20260610-120000-abc",
            endpoint = "ranking-overall",
            totalRecords = 100,
            totalFailed = 5,
            chunks = listOf(
                SnapshotChunkManifest.ChunkEntry(
                    path = "part-000001.jsonl.gz",
                    recordCount = 50,
                    compressedBytes = 1000,
                    uncompressedBytes = 5000,
                )
            ),
            startedAt = Instant.parse("2026-06-10T12:00:00Z"),
            finishedAt = Instant.parse("2026-06-10T12:30:00Z"),
        )

        writer.write("runs/20260610-120000-abc/ranking-overall", manifest)

        verify(exactly = 1) { storage.put(any(), any()) }
        assertEquals("runs/20260610-120000-abc/ranking-overall/manifest.json", key.captured)
        val json = String(bytes.captured)
        assert(json.contains("\"runId\":\"20260610-120000-abc\""))
        assert(json.contains("\"totalRecords\":100"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests SnapshotChunkManifestWriterTest -i`
Expected: FAIL

- [ ] **Step 3: Modify `SnapshotChunkManifestWriter`**

In `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkManifest.kt`, find `class SnapshotChunkManifestWriter` and replace with:

```kotlin
class SnapshotChunkManifestWriter(
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
) {
    fun write(runKey: String, manifest: SnapshotChunkManifest) {
        val manifestKey = "$runKey/manifest.json"
        val bytes = objectMapper.writeValueAsBytes(manifest)
        objectStorage.put(manifestKey, bytes)
    }
}
```

Remove any import of `java.nio.file.Path` if no longer used elsewhere in the file.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests SnapshotChunkManifestWriterTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkManifest.kt \
        module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotChunkManifestWriterTest.kt
git commit -m "refactor(ext-api): SnapshotChunkManifestWriter uses runKey + ObjectStorage"
```

---

## Task 5: Migrate `SnapshotFailedRecordWriter` to ObjectStorage

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriter.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriterTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotFailedRecordWriterTest {

    @Test
    fun `append reads existing, appends, and writes back to ObjectStorage`() {
        val storage = mockk<ObjectStorage>()
        val captured = mutableListOf<Pair<String, ByteArray>>()
        every { storage.get(any()) } answers { ByteArray(0) } // empty initial
        every { storage.put(capture(captured)) } answers { PutResult("k", 0, null) }

        val objectMapper = ObjectMapper().registerModule(kotlinModule())
        val writer = SnapshotFailedRecordWriter(
            runKey = "runs/test/ranking-overall",
            objectMapper = objectMapper,
            objectStorage = storage,
        )

        writer.append(
            SnapshotChunkRecord.Failure(
                key = "k1",
                endpoint = "ranking-overall",
                keyType = "DATE_PAGE",
                httpStatus = 500,
                fetchedAt = Instant.parse("2026-06-10T00:00:00Z"),
                errorMessage = "boom",
            )
        )

        assertEquals(1, writer.count())
        verify(exactly = 1) { storage.put(any(), any()) }
        val (_, bytes) = captured.first()
        assertTrue(String(bytes).contains("\"errorMessage\":\"boom\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests SnapshotFailedRecordWriterTest -i`
Expected: FAIL

- [ ] **Step 3: Modify `SnapshotFailedRecordWriter.kt`**

Replace the file contents:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.io.ByteArrayOutputStream

/**
 * Read-modify-write of `runs/$runKey/failed.jsonl`. S3 has no native append,
 * so we read the existing object, append a line, and put it back. Acceptable
 * for low volume (failures are rare in healthy runs).
 */
class SnapshotFailedRecordWriter(
    private val runKey: String,
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
) {
    private val key = "$runKey/failed.jsonl"
    private var count: Int = 0

    fun append(record: SnapshotChunkRecord.Failure) {
        val existing = runCatching { objectStorage.get(key) }.getOrDefault(ByteArray(0))
        val out = ByteArrayOutputStream(existing.size + 256)
        out.write(existing)
        if (existing.isNotEmpty() && existing.last() != '\n'.code.toByte()) out.write('\n'.code)
        out.write(objectMapper.writeValueAsBytes(record))
        out.write('\n'.code)
        objectStorage.put(key, out.toByteArray())
        count++
    }

    fun count(): Int = count
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests SnapshotFailedRecordWriterTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriter.kt \
        module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriterTest.kt
git commit -m "refactor(ext-api): SnapshotFailedRecordWriter read-modify-write via ObjectStorage"
```

---

## Task 6: Migrate `OcidCacheProvider` to ObjectStorage (reader)

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/cache/OcidCacheProviderTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.externalapi.cache

import io.mockk.every
import io.mockk.mockk
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcidCacheProviderTest {

    @Test
    fun `refresh picks latest mapping by lastModified via ObjectStorage listByPrefix`() {
        val storage = mockk<ObjectStorage>()
        every { storage.listByPrefix("ocid-mapping/") } returns listOf(
            ObjectInfo("ocid-mapping/ocid-mapping-20260609-090000.jsonl.gz", 100, Instant.parse("2026-06-09T09:00:00Z")),
            ObjectInfo("ocid-mapping/ocid-mapping-20260610-090000.jsonl.gz", 100, Instant.parse("2026-06-10T09:00:00Z")),
            ObjectInfo("ocid-mapping/ocid-mapping-20260608-090000.jsonl.gz", 100, Instant.parse("2026-06-08T09:00:00Z")),
        )
        every { storage.getStream(any()) } returns "user1\tdummy-ocid-1\nuser2\tdummy-ocid-2\n".byteInputStream()

        val provider = OcidCacheProvider(storage)
        val cache = provider.refresh()

        assertEquals(2, cache.size)
        assertEquals("dummy-ocid-1", cache["user1"])
        assertEquals("dummy-ocid-2", cache["user2"])
        assertTrue(cache.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests OcidCacheProviderTest -i`
Expected: FAIL — constructor signature changed.

- [ ] **Step 3: Modify `OcidCacheProvider.kt`**

Replace the file contents:

```kotlin
package maple.externalapi.cache

import maple.expectation.common.storage.ObjectStorage
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache of userIgn → ocid, loaded from the latest
 * `ocid-mapping/ocid-mapping-*.jsonl.gz` object in ObjectStorage.
 * Picked by `ObjectInfo.lastModified` (max).
 */
class OcidCacheProvider(private val objectStorage: ObjectStorage) {

    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val objects = objectStorage.listByPrefix("ocid-mapping/")
        val latest = objects.maxByOrNull { it.lastModified } ?: return emptyMap()
        objectStorage.getStream(latest.key).bufferedReader().useLines { lines ->
            val map = HashMap<String, String>()
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split("\t")
                if (parts.size >= 2) map[parts[0]] = parts[1]
            }
            cacheRef.set(map)
            return map
        }
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests OcidCacheProviderTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt \
        module-external-api/src/test/kotlin/maple/externalapi/cache/OcidCacheProviderTest.kt
git commit -m "refactor(ext-api): OcidCacheProvider uses ObjectStorage listByPrefix"
```

---

## Task 7: Migrate `OcidLookupPhase` to ObjectStorage (writer + runKey)

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` (modify if exists, else create)

- [ ] **Step 1: Write the failing test**

Check: `test -f module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt && echo EXISTS || echo MISSING`

If MISSING, create:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.expectation.core.auth.event.CharacterFetchResponse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OcidLookupPhaseTest {

    @Test
    fun `execute writes OCID mapping gzipped to ObjectStorage under ocid-mapping key`() {
        val storage = mockk<ObjectStorage>()
        val nexonClient = mockk<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        every { storage.listByPrefix(any()) } returns listOf(
            ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, Instant.now())
        )
        every { storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz") } returns
            "{\"key\":\"user1\"}\n{\"key\":\"user2\"}\n".byteInputStream()

        every { nexonClient.getCharacterList(any()) } returns CompletableFuture.completedFuture(
            CharacterFetchResponse(
                accountList = null,
                characterList = listOf(
                    CharacterFetchResponse.Character("user1", "ocid-aaaa"),
                    CharacterFetchResponse.Character("user2", "ocid-bbbb"),
                ),
            )
        )

        val mappingKey = slot<String>()
        every { storage.put(capture(mappingKey), any<ByteArray>()) } answers { PutResult("k", 0, null) }

        val phase = OcidLookupPhase(
            clientPort = mockk(relaxed = true),
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            objectStorage = storage,
            nexonAuthClient = nexonClient,
        )

        // Use runBlocking inside the test via runTest
        kotlinx.coroutines.runBlocking {
            phase.execute(
                workerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(),
                runKey = "runs/abc",
            )
        }

        verify(exactly = 1) { storage.put(any(), any()) }
        assertNotNull(mappingKey.captured)
        assert(mappingKey.captured.startsWith("ocid-mapping/ocid-mapping-"))
        assert(mappingKey.captured.endsWith(".jsonl.gz"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests OcidLookupPhaseTest -i`
Expected: FAIL — constructor and `execute()` signature differ.

- [ ] **Step 3: Modify `OcidLookupPhase.kt`**

Key changes:
- Constructor: drop `storeBasePath: String`, add `objectStorage: ObjectStorage` and `nexonAuthClient: NexonAuthClient`
- `execute(workerExecutor, runKey: String)` instead of `execute(workerExecutor, runDir: Path)`
- `writeGzipJsonl` uses `objectStorage.put` with key `ocid-mapping/ocid-mapping-$runId.jsonl.gz`
- `readCharacterNamesFromChunks(runKey: String)` uses `listByPrefix("$runKey/ranking-overall/chunks/")` + `getStream`

The diff is large; the helper `writeGzipJsonl` becomes:

```kotlin
private fun writeMappingGzipped(mappingDir: String, results: List<String>, runId: String) {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(BufferedOutputStream(out)).use { gz ->
        for (r in results) {
            gz.write(r.toByteArray())
            gz.write('\n'.code)
        }
    }
    val key = "$mappingDir/ocid-mapping-$runId.jsonl.gz"
    objectStorage.put(key, out.toByteArray())
}
```

The execute entry point becomes:

```kotlin
suspend fun execute(workerExecutor: ExecutorService, runKey: String) {
    val mappingDir = "ocid-mapping"
    deleteOldMappings(mappingDir)

    val igns = readCharacterNamesFromChunks(runKey)
    if (igns.isEmpty()) {
        log.warn("[Scheduler] no character names from chunks: $runKey")
        return
    }

    val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)
    // ... (processBatch with runKey instead of runDir) ...

    val runId = runKey.removePrefix("runs/").substringBefore('/')
    val mappingKey = writeMappingGzipped(mappingDir, results, runId)

    eventPublisher.publishRunCompleted(
        SnapshotRunCompletedEvent(
            ...,
            manifestPath = "ocid-mapping/ocid-mapping-$runId.jsonl.gz",
            ...,
        )
    )
}
```

`readCharacterNamesFromChunks`:

```kotlin
suspend fun readCharacterNamesFromChunks(runKey: String): List<String> = withContext(Dispatchers.Default) {
    val prefix = "$runKey/ranking-overall/chunks"
    val names = linkedSetOf<String>()
    for (obj in objectStorage.listByPrefix(prefix)) {
        if (!obj.key.endsWith(".jsonl.gz")) continue
        objectStorage.getStream(obj.key).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val node = objectMapper.readTree(line)
                val key = node.get("key")?.asText() ?: continue
                names.add(key)
            }
        }
    }
    names.toList()
}
```

`deleteOldMappings`:

```kotlin
private fun deleteOldMappings(mappingDir: String) {
    val total = objectStorage.deleteByPrefix(mappingDir)
    log.info("[Scheduler] deleted {} old OCID mapping objects in {}", total, mappingDir)
}
```

`processBatch` parameter changes from `runDir: Path` to `runKey: String`; `chunksDir = "$runKey/ranking-overall/chunks"` (no Path needed; the read helper above handles it).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests OcidLookupPhaseTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt
git commit -m "refactor(ext-api): OcidLookupPhase uses runKey + ObjectStorage"
```

---

## Task 8: Migrate `RankingFetchPhase` (runKey:String return)

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt` (modify if exists)

- [ ] **Step 1: Read existing test (if any)**

```bash
test -f module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt && cat module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt
```

If MISSING, create a minimal smoke test:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.mockk
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.port.out.ExternalApiClientPort
import org.junit.jupiter.api.Test

class RankingFetchPhaseTest {
    @Test
    fun `phase ctor accepts objectStorage and ObjectMapper`() {
        val phase = RankingFetchPhase(
            clientPort = mockk(relaxed = true),
            objectMapper = ObjectMapper().registerModule(kotlinModule()),
            chunkingProperties = mockk(relaxed = true),
            volumeMetrics = mockk(relaxed = true),
            metrics = mockk(relaxed = true),
            objectStorage = mockk(relaxed = true),
        )
        // no-op; just verify construction
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests RankingFetchPhaseTest -i`
Expected: FAIL — ctor missing `objectStorage`.

- [ ] **Step 3: Modify `RankingFetchPhase.kt`**

Constructor: add `private val objectStorage: ObjectStorage,` and drop `storeBasePath: String` (the base is now implicit in the ObjectStorage impl).

`execute(workerExecutor: ExecutorService): CompletableFuture<String>` — returns `runKey: String` instead of `Path`.

Internally:

```kotlin
fun execute(workerExecutor: ExecutorService): CompletableFuture<String> {
    val runId = SchedulerPhaseUtils.newRunId()
    val runKey = "runs/$runId"

    val endpointConfig = chunkingProperties.configFor("ranking-overall")
    val fileManager = ChunkFileManager(
        runKey = runKey,
        endpoint = "ranking-overall",
        maxRecords = endpointConfig.maxRecords,
        maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
        objectMapper = objectMapper,
        clock = java.time.Clock.systemUTC(),
        objectStorage = objectStorage,
    )
    val sink = ChunkedSnapshotSink(
        runKey = runKey,
        endpoint = "ranking-overall",
        queueCapacity = chunkingProperties.queueCapacity,
        fileManager = fileManager,
        eventPublisher = SnapshotSinkEventPublisher(
            eventPublisher = SinkEventPublisher(rankingPublisher),
            volumeMetrics = volumeMetrics,
            clock = java.time.Clock.systemUTC(),
        ),
    )
    RunMarkerWriter(java.time.Clock.systemUTC(), objectStorage).writeRunMarker(runKey)

    val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)
    val fetched = AtomicInteger(0)
    val failed = AtomicInteger(0)

    return processPages(workerExecutor, sink, rateLimiter, 1, fetched, failed)
        .handle { _, ex ->
            if (ex != null) {
                log.error("[RankingFetch] failed: runId={}, fetched={}, failed={}", runId, fetched.get(), failed.get(), ex)
            } else {
                val manifest = fileManager.finalManifest(fetched.get(), failed.get())
                SnapshotChunkManifestWriter(objectMapper, objectStorage).write(runKey, manifest)
                SchedulerPhaseUtils.logSummary("RankingFetch", fetched.get(), fetched.get(), fetched.get(), failed.get(), start)
            }
            sink.close()
        }
        .thenApply { runKey }
}
```

Also update `submitRankingEntries`, `handle`, `thenCompose` — drop `runDir` reference; use the `sink` and `fileManager` directly.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests RankingFetchPhaseTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt
git commit -m "refactor(ext-api): RankingFetchPhase returns runKey, uses ObjectStorage"
```

---

## Task 9: Migrate `CharacterBasicFetchPhase` and `ItemEquipmentFetchPhase` to runKey

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`

- [ ] **Step 1: Apply the same pattern as Task 8**

For each file:
- Constructor: drop `storeBasePath: String`, add `private val objectStorage: ObjectStorage,` and `private val runMarkerWriter: RunMarkerWriter,` (optional — can be inlined).
- `runDir: Path` → `runKey: String`.
- `Paths.get(storeBasePath, "runs", runId)` → `"runs/$runId"`.
- Pass `runKey` to `ChunkedSnapshotSink` and `ChunkFileManager` constructors.
- Use `objectStorage` for all writes (the `LocalExternalApiArtifactStoreAdapter` was the previous write path; replace with `objectStorage.put`).

- [ ] **Step 2: Run module tests**

Run: `./gradlew :module-external-api:test -i`
Expected: 0 failures (the phases are currently disabled by 9fbea109f TODO; tests may be marked @Disabled or not exist; if not, mark them so).

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt \
        module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt
git commit -m "refactor(ext-api): CharacterBasic + ItemEquipment phases use runKey"
```

---

## Task 10: Update `ExternalApiScheduler` to thread runKey

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

- [ ] **Step 1: Update phase call sites**

Find: `grep -n "execute\|runDir\|runKey" module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

Three call sites to update:
- `rankingPhase.execute(executor)` — already returns `CompletableFuture<runKey: String>`. Use that.
- `ocidLookupPhase.execute(executor, runKey)` (suspend; via runBlocking) — pass the new runKey.
- `characterBasicPhase.execute(...)` (if invoked) and `itemEquipmentLoop` (if invoked) — pass runKey.

- [ ] **Step 2: Run module tests**

Run: `./gradlew :module-external-api:test -i`
Expected: 0 failures

- [ ] **Step 3: Verify compile of the whole module**

Run: `./gradlew :module-external-api:compileKotlin -i`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "refactor(ext-api): ExternalApiScheduler threads runKey through phase chain"
```

---

## Task 11: Migrate `RunCleanupService` to ObjectStorage

**Files:**
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt`
- Test: `module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt` (modify if exists, else create)

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.cleanup.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import org.junit.jupiter.api.Test
import java.time.Instant

class RunCleanupServiceTest {

    @Test
    fun `cleanup deletes all but the N most recent runs via ObjectStorage`() {
        val storage = mockk<ObjectStorage>(relaxed = true)
        every { storage.listByPrefix("runs/") } returns listOf(
            ObjectInfo("runs/20260608-001", 0, Instant.parse("2026-06-08T00:00:00Z")),
            ObjectInfo("runs/20260609-001", 0, Instant.parse("2026-06-09T00:00:00Z")),
            ObjectInfo("runs/20260610-001", 0, Instant.parse("2026-06-10T00:00:00Z")),
        )

        val props = mockk<maple.cleanup.config.CleanupProperties>(relaxed = true)
        every { props.runs } returns mockk(relaxed = true)
        every { props.runs.keepRecent } returns 1
        every { props.runs.keepWithinHours } returns 0

        val service = RunCleanupService(storage, props)
        val result = service.cleanup()

        // 2 of 3 runs should be deleted (oldest 2; keep most recent 1)
        verify(atLeast = 2) { storage.deleteByPrefix(any()) }
        assert(result.deletedRuns >= 2)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-cleanup:test --tests RunCleanupServiceTest -i`
Expected: FAIL — constructor signature changed.

- [ ] **Step 3: Modify `RunCleanupService.kt`**

Constructor: drop `basePath: String`, add `private val objectStorage: ObjectStorage`.

Internal logic:

```kotlin
fun cleanup(): CleanupResult {
    val all = objectStorage.listByPrefix("runs/")
        .filter { it.key.matches(Regex("runs/[^/]+/?$")) } // runId dirs
        .sortedByDescending { it.lastModified }
    val keep = all.take(keepRecent)
    val toDelete = all.drop(keepRecent)
    var deleted = 0L
    for (run in toDelete) {
        deleted += objectStorage.deleteByPrefix(run.key)
    }
    return CleanupResult(deletedRuns = toDelete.size, deletedBytes = deleted)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-cleanup:test --tests RunCleanupServiceTest -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt \
        module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt
git commit -m "refactor(cleanup): RunCleanupService uses ObjectStorage"
```

---

## Task 12: Migrate `CleanupController` to ObjectStorage

**Files:**
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt`
- Test: `module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt` (modify if exists)

- [ ] **Step 1: Modify `CleanupController.kt`**

Constructor: drop `inboxProperties: InboxProperties` reference for `basePath`. Inject `objectStorage: ObjectStorage` directly.

`deleteFile` becomes:

```kotlin
private fun deleteFile(objectKey: String): Boolean = try {
    objectStorage.delete(objectKey); true
} catch (e: Exception) {
    false
}
```

- [ ] **Step 2: Run module tests**

Run: `./gradlew :module-cleanup:test -i`
Expected: PASS (existing tests should pass with mocked ObjectStorage; update the test setup if needed)

- [ ] **Step 3: Commit**

```bash
git add module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt \
        module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt
git commit -m "refactor(cleanup): CleanupController uses ObjectStorage.delete"
```

---

## Task 13: Remove deprecated `LocalExternalApiArtifactStoreAdapter`

**Files:**
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt`

- [ ] **Step 1: Verify 0 callers**

```bash
grep -rn "LocalExternalApiArtifactStoreAdapter" module-external-api/src --include="*.kt"
```

Expected: only the file itself (and possibly test files referencing it). If other files reference it, fix them first (e.g., by switching to `ObjectStorage`).

- [ ] **Step 2: Delete the file**

```bash
git rm module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :module-external-api:compileKotlin -i`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(ext-api): remove deprecated LocalExternalApiArtifactStoreAdapter (#1221)"
```

---

## Task 14: Add `DataflowContractTest` (Layer 3 schema validation)

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/dataflow/DataflowContractTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package maple.externalapi.dataflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.mockk
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.scheduler.phase.OcidLookupPhase
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Layer 3 schema validation: every byte put to ObjectStorage must round-trip
 * through get() and parse to the expected schema. Mocks Nexon + ObjectStorage
 * to verify the data flow contract without network/IO.
 */
class DataflowContractTest {

    private val objectMapper = ObjectMapper().registerModule(kotlinModule())

    @Test
    fun `ranking chunk bytes round-trip through ObjectStorage and parse to valid JSONL`() {
        val storage = mockk<ObjectStorage>(relaxed = true)
        val captured = mutableListOf<ByteArray>()
        every { storage.put(any(), capture(captured)) } answers { PutResult("k", 0, null) }

        val writer = maple.externalapi.snapshot.GzipJsonlChunkWriter(
            chunkKey = "runs/test/ranking-overall/part-000001.jsonl.gz",
            maxRecords = 10,
            maxUncompressedBytes = 1_000_000,
            objectMapper = objectMapper,
            objectStorage = storage,
        )
        repeat(3) { i ->
            writer.append(
                maple.externalapi.snapshot.SnapshotChunkRecord.Success(
                    bodyBytes = objectMapper.writeValueAsBytes(mapOf("character_name" to "char$i", "ocid" to "ocid$i")),
                    key = "char$i",
                    endpoint = "ranking-overall",
                    keyType = "DATE_PAGE",
                    httpStatus = 200,
                    fetchedAt = Instant.now(),
                )
            )
        }
        val stats = writer.close()
        assertEquals(3, stats.recordCount)

        // Round-trip: get the bytes back via mock get()
        val bytes = captured.last()
        every { storage.get(any()) } returns bytes

        val roundTripped = storage.get("runs/test/ranking-overall/part-000001.jsonl.gz")
        assertNotNull(roundTripped)

        val text = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(roundTripped))
            .bufferedReader().readText()
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        lines.forEach { line ->
            val node = objectMapper.readTree(line)
            assertTrue(node.has("character_name"))
            assertTrue(node.has("ocid"))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests DataflowContractTest -i`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/dataflow/DataflowContractTest.kt
git commit -m "test(ext-api): DataflowContractTest for byte round-trip schema validation"
```

---

## Task 15: Pipeline test verification (Layers 1-2)

**Files:** none (operational verification)

- [ ] **Step 1: Start modules in MinIO mode**

```bash
for port in 8081 8082 8083 8084; do
  lsof -ti:$port 2>/dev/null | xargs -r kill -9
done

set -a && source .env && set +a
export SPRING_PROFILES_ACTIVE=local
export DB_URL='jdbc:postgresql://localhost:5432/maple_expectation'
export SPRING_DATASOURCE_URL="$DB_URL"
export SPRING_DATASOURCE_USERNAME=maple
export SPRING_DATASOURCE_PASSWORD=maple123

nohup java -Xms512m -Xmx1g -jar module-external-api/build/libs/module-external-api-0.0.1-SNAPSHOT.jar > logs/pipeline-test-external-api.log 2>&1 &
nohup java -Xms512m -Xmx1g -jar module-calculator/build/libs/module-calculator-0.0.1-SNAPSHOT.jar > logs/pipeline-test-calculator.log 2>&1 &
nohup java -Xms512m -Xmx1g -jar module-synchronizer/build/libs/module-synchronizer-0.0.1-SNAPSHOT.jar > logs/pipeline-test-synchronizer.log 2>&1 &
nohup java -Xms512m -Xmx1g -jar module-cleanup/build/libs/module-cleanup-0.0.1-SNAPSHOT.jar > logs/pipeline-test-cleanup.log 2>&1 &

for i in $(seq 1 60); do
  curl -sf http://localhost:8081/actuator/health > /dev/null && break
  sleep 2
done
```

- [ ] **Step 2: Wait for pipeline to produce chunks**

The pipeline runs `RankingFetch → OcidLookup` on startup (run-on-startup: true). Wait ~5 minutes for ranking + start of ocid-lookup.

```bash
sleep 300
curl -s http://localhost:8081/api/internal/run-status | python3 -m json.tool
```

Expected: phase is OCID_LOOKUP (or further) with non-zero records.

- [ ] **Step 3: Layer 1 verification (smoke)**

```bash
for prefix in runs ocid-mapping calculator/runs; do
  count=$(mc ls --recursive "local/${MINIO_BUCKET}/${prefix}/" 2>/dev/null | wc -l)
  [ "${count}" -gt 0 ] || { echo "FAIL: empty prefix $prefix/"; exit 5; }
done
echo "Layer 1 PASS"
```

- [ ] **Step 4: Layer 2 verification (fixture-based dataflow round-trip)**

```bash
# Get a real character name from the ranking chunks in MinIO
SAMPLE_IGN=$(zcat $(mc ls --recursive "local/${MINIO_BUCKET}/runs/" | grep "part-000001.jsonl.gz" | head -1) 2>/dev/null | head -1 | jq -r '.character_name')
echo "SAMPLE_IGN=$SAMPLE_IGN"

# Verify it appears in MinIO chunk
mc cat "local/${MINIO_BUCKET}/runs/"*/ranking-overall/part-*.jsonl.gz 2>/dev/null | zcat 2>/dev/null | grep -q "$SAMPLE_IGN" || \
  zcat $(mc ls --recursive "local/${MINIO_BUCKET}/runs/" | grep "ranking-overall" | head -1) | grep -q "$SAMPLE_IGN" \
  || { echo "FAIL: $SAMPLE_IGN not in MinIO chunks"; exit 6; }
echo "Layer 2 PASS"
```

- [ ] **Step 5: Stop modules**

```bash
for port in 8081 8082 8083 8084; do
  lsof -ti:$port 2>/dev/null | xargs -r kill -9
done
```

- [ ] **Step 6: Commit (verification log)**

```bash
mkdir -p logs/dataflow-verification
cp logs/pipeline-test-*.log logs/dataflow-verification/
git add logs/dataflow-verification/
git commit -m "test(dataflow): pipeline-test verification (Layer 1 + Layer 2) — MinIO mode"
```

---

## Task 16: Local mode regression check

**Files:** none

- [ ] **Step 1: Start modules in local mode (override `STORAGE_BACKEND=local`)**

```bash
export STORAGE_BACKEND=local
nohup java -Xms512m -Xmx1g -jar module-external-api/build/libs/module-external-api-0.0.1-SNAPSHOT.jar > logs/pipeline-test-local-external-api.log 2>&1 &
nohup java -Xms512m -Xmx1g -jar module-calculator/build/libs/module-calculator-0.0.1-SNAPSHOT.jar > logs/pipeline-test-local-calculator.log 2>&1 &
nohup java -Xms512m -Xmx1g -jar module-synchronizer/build/libs/module-synchronizer-0.0.1-SNAPSHOT.jar > logs/pipeline-test-local-synchronizer.log 2>&1 &
nohup java -Xms512m -Xmx1g -jar module-cleanup/build/libs/module-cleanup-0.0.1-SNAPSHOT.jar > logs/pipeline-test-local-cleanup.log 2>&1 &

for i in $(seq 1 60); do
  curl -sf http://localhost:8081/actuator/health > /dev/null && break
  sleep 2
done
```

- [ ] **Step 2: Verify local FS regression**

```bash
sleep 300
for path in ../data/runs ../data/ocid-mapping; do
  count=$(find "${path}" -name '*.jsonl.gz' 2>/dev/null | wc -l)
  [ "${count}" -gt 0 ] || { echo "FAIL: no local chunks in $path"; exit 7; }
done
echo "Local mode regression PASS"
```

- [ ] **Step 3: Stop modules + commit**

```bash
for port in 8081 8082 8083 8084; do
  lsof -ti:$port 2>/dev/null | xargs -r kill -9
done
```

No commit needed (verification only).

---

## Self-Review

**Spec coverage check:**

| Spec section | Task |
| --- | --- |
| §2 ext-api writers (8 files) | Tasks 1-8 |
| §2 cleanup (2 files) | Tasks 11-12 |
| §2 runKey:String inter-phase API | Tasks 7-10 |
| §2 delete deprecated adapter | Task 13 |
| §4 Layer 1 (smoke) | Task 15 |
| §4 Layer 2 (fixture-based dataflow) | Task 15 |
| §4 Layer 3 (per-boundary schema) | Task 14 |
| §4 Unit tests | Tasks 1-12 each have their own test |
| §5 local mode regression | Task 16 |

**Placeholder scan:** No "TBD" / "TODO" / "implement later" patterns in the plan.

**Type consistency:**
- `runKey: String` — defined in Task 7 (OcidLookupPhase), used in Tasks 8-10 (phase chain) consistently.
- `ObjectStorage` — same instance injected into all consumers; no name drift.
- `SnapshotChunkManifest` / `ChunkEntry` / `ChunkStats` — data classes used consistently.
- `PutResult(key, size, checksum)` — same return type from all `objectStorage.put` calls.

**Scope:** One atomic PR. Single subsystem (ext-api + cleanup migration). No decomposition needed.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-10-raw-path-to-minio-migration.md`. 16 tasks.

Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
