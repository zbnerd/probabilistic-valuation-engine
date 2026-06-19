# Issue #1312 Streaming Writer + CF Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `CalculationResultWriter.write()`'s `ByteArrayOutputStream` buffering with a CF chain (Flow → gzip → 8MB pipe → `putStreamMultipart`), reducing heap peak by ~40MB. Migrate the single caller (`SnapshotChunkProcessor.process()`) to a single `.await()` at the coroutine→CF boundary.

**Architecture:** Two-part refactor. (1) New `ObjectStorage.putStreamMultipart` interface backed by `S3AsyncClient.putObject` chunked transfer (Minio) or temp-file + `putFile` (LocalFs). (2) `CalculationResultWriter.write()` returns `CompletableFuture<WriteResult>` via `producerScope.future { Flow.collect → gzip → pipe }` composed with `putStreamMultipart` via `thenCombine`. Caller keeps `suspend fun` signature.

**Tech Stack:** Kotlin 1.9+, Spring Boot 3, AWS SDK v2 (`S3AsyncClient`, `AsyncRequestBody`), Jackson `JsonGenerator`, Kotlin `CoroutineScope.future {}` (kotlinx-coroutines-jdk8, already in build at `module-calculator/build.gradle:21`), JUnit 5 + AssertJ + `@TempDir`.

**Spec:** `docs/superpowers/specs/2026-06-19-issue-1312-streaming-writer-cf-chain-design.md`

**Predecessors (already merged):** Phase 1 (`-XX:MaxDirectMemorySize=512m` in calculator `bootRun` JVM args, see `module-calculator/build.gradle:62-66`). `S3AsyncClient` bean already wired (`module-infra/.../storage/StorageConfig.kt:62-84`).

---

## File Structure

| File | Responsibility |
|------|----------------|
| `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt` | NEW public class. Wraps `OutputStream` with `AtomicLong counter`. Replaces buggy nested impl. |
| `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt` | NEW public class. 3 `AtomicLong` fields (records, uncompressedBytes, compressedBytes). Thread-safe shared state. |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` | MODIFY. Rewrite `write()` to CF chain. Drop nested `CountingOutputStream`. Drop `suspend` keyword. Add `producerScope` ctor param. |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` | MODIFY. Migrate caller: `val writeFuture = resultWriter.write(...)` BEFORE `coroutineScope`, single `.await()` at boundary. |
| `module-calculator/src/test/kotlin/maple/calculator/writer/CountingOutputStreamTest.kt` | NEW unit test. |
| `module-calculator/src/test/kotlin/maple/calculator/writer/WriteCountersTest.kt` | NEW unit test. |
| `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt` | NEW unit test. Bytewise equivalence + error path. |
| `module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt` | NEW test fixture. `open class` with `NotImplementedError` defaults. |
| `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` | MODIFY. Add `putStreamMultipart(key, input): CompletableFuture<PutResult>`. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt` | MODIFY. Add `s3Async: S3AsyncClient` ctor param. Implement `putStreamMultipart` using `AsyncRequestBody.fromInputStream(input, -1L)`. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt` | MODIFY. Add `uploadExecutor: Executor` ctor param. Implement `putStreamMultipart` as `supplyAsync({ copy + putFile }, executor).whenComplete { cleanup }`. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt` | MODIFY. Wire `s3AsyncClient` into `minioObjectStorage` bean. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt` | MODIFY. Add `uploadExecutor` bean (virtual thread). |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageFactory.kt` | NEW helper. Creates `LocalFsObjectStorage` with `uploadExecutor` injected. Replaces direct `@Component` constructor. |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsPutStreamMultipartTest.kt` | NEW unit test. |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StubMinioProperties.kt` | NEW test fixture. |

---

## Task 1: Promote `CountingOutputStream` to public class

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/writer/CountingOutputStreamTest.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` (drop the nested `CountingOutputStream` class)

- [ ] **Step 1.1: Write the failing test**

Create `module-calculator/src/test/kotlin/maple/calculator/writer/CountingOutputStreamTest.kt`:

```kotlin
package maple.calculator.writer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CountingOutputStreamTest {

    @Test
    fun `write single byte increments counter by 1`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        cos.write(0x42)
        assertThat(cos.count).isEqualTo(1L)
        assertThat(sink.toByteArray()).containsExactly(0x42.toByte())
    }

    @Test
    fun `write array increments by length`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        cos.write(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(cos.count).isEqualTo(5L)
    }

    @Test
    fun `write array with offset and length counts only specified range`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        cos.write(byteArrayOf(1, 2, 3, 4, 5), 1, 3)
        assertThat(cos.count).isEqualTo(3L)
        assertThat(sink.toByteArray()).containsExactly(2, 3, 4)
    }

    @Test
    fun `concurrent writes from multiple threads produce correct total`() {
        val sink = ByteArrayOutputStream()
        val cos = CountingOutputStream(sink)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..100).map {
            pool.submit { cos.write(ByteArray(1024)) }
        }
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertThat(cos.count).isEqualTo(1024L * 100)
    }
}
```

- [ ] **Step 1.2: Run test to verify it fails**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.CountingOutputStreamTest" 2>&1 | tail -20`

Expected: compilation failure — `CountingOutputStream` is not yet a top-level public class.

- [ ] **Step 1.3: Create the public class**

Create `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt`:

```kotlin
package maple.calculator.writer

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * OutputStream wrapper that counts bytes written through it. Thread-safe via
 * [AtomicLong]. Used by [CalculationResultWriter] to track uncompressed /
 * compressed byte counts for the streaming gzip → S3 upload path.
 */
class CountingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {

    private val counter = AtomicLong(0)

    /** Total bytes written through this stream. */
    val count: Long get() = counter.get()

    override fun write(b: Int) {
        delegate.write(b)
        counter.incrementAndGet()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        counter.addAndGet(len.toLong())
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
```

- [ ] **Step 1.4: Run test to verify it passes**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.CountingOutputStreamTest" 2>&1 | tail -10`

Expected: 4 tests pass.

- [ ] **Step 1.5: Drop the nested class from `CalculationResultWriter`**

In `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt`, delete the entire `private class CountingOutputStream` block (lines 79-103). The class will be unused but the file will still compile (we'll rewrite the file in Task 6).

- [ ] **Step 1.6: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt \
        module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt \
        module-calculator/src/test/kotlin/maple/calculator/writer/CountingOutputStreamTest.kt
git commit -m "feat(calculator): promote CountingOutputStream to public class

Thread-safe AtomicLong-backed counter. Replaces the buggy nested impl
in CalculationResultWriter (count was mutable plain Long, not atomic).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Create `WriteCounters`

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/writer/WriteCountersTest.kt`

- [ ] **Step 2.1: Write the failing test**

Create `module-calculator/src/test/kotlin/maple/calculator/writer/WriteCountersTest.kt`:

```kotlin
package maple.calculator.writer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WriteCountersTest {

    @Test
    fun `initial values are zero`() {
        val c = WriteCounters()
        assertThat(c.records.get()).isZero()
        assertThat(c.uncompressedBytes.get()).isZero()
        assertThat(c.compressedBytes.get()).isZero()
    }

    @Test
    fun `concurrent increment from many threads yields correct sum`() {
        val c = WriteCounters()
        val pool = Executors.newFixedThreadPool(8)
        val perThread = 1000
        val threadCount = 8
        val futures = (1..threadCount).map {
            pool.submit {
                repeat(perThread) { c.records.incrementAndGet() }
            }
        }
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertThat(c.records.get()).isEqualTo((perThread * threadCount).toLong())
    }
}
```

- [ ] **Step 2.2: Run test to verify it fails**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.WriteCountersTest" 2>&1 | tail -20`

Expected: compilation failure — `WriteCounters` does not exist.

- [ ] **Step 2.3: Create the class**

Create `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt`:

```kotlin
package maple.calculator.writer

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe counters for the streaming write path.
 *
 * Producer (Flow.collect on a dedicated dispatcher) increments [records] and
 * [uncompressedBytes]. Consumer (CF callback from putStreamMultipart) reads
 * the final values. All fields are [AtomicLong] so producer and consumer
 * can update independently without locks.
 */
class WriteCounters {
    val records: AtomicLong = AtomicLong(0)
    val uncompressedBytes: AtomicLong = AtomicLong(0)
    val compressedBytes: AtomicLong = AtomicLong(0)
}
```

- [ ] **Step 2.4: Run test to verify it passes**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.WriteCountersTest" 2>&1 | tail -10`

Expected: 2 tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt \
        module-calculator/src/test/kotlin/maple/calculator/writer/WriteCountersTest.kt
git commit -m "feat(calculator): add WriteCounters for streaming write

AtomicLong-backed records/uncompressedBytes/compressedBytes counters.
Shared state between producer coroutine and consumer CF callback.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Add `putStreamMultipart` to `ObjectStorage` interface

**Files:**
- Modify: `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` (add method after `putFileAsync` at line 54)

- [ ] **Step 3.1: Locate the insertion point**

```bash
grep -n "putFileAsync\|putFile\|fun get" module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt
```

Expected: `putFileAsync` declared at line 54, with the existing kdoc block ending at line 53.

- [ ] **Step 3.2: Add the new method**

In `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt`, insert AFTER the `putFileAsync` declaration (line 54) and BEFORE the `get` method (line 57):

```kotlin
    /**
     * Async streaming upload. Accepts an [InputStream] of arbitrary length
     * and uploads without buffering the full content in heap. The caller is
     * responsible for closing [input] only after the returned future
     * completes (success or failure).
     *
     * Implementations:
     * - Minio: [software.amazon.awssdk.services.s3.S3AsyncClient.putObject]
     *   with [software.amazon.awssdk.core.async.AsyncRequestBody.fromInputStream]
     *   and `contentLength = -1L` (chunked transfer encoding, no
     *   intermediate ByteArray drain).
     * - LocalFs: drain [input] to a temp file, then call [putFile] on a
     *   virtual-thread executor; delete the temp file on completion.
     *
     * On failure the future completes exceptionally with the underlying
     * cause. On success the future completes with a [PutResult] whose
     * `size` is the byte count actually uploaded (or -1L for chunked
     * transfer where size is unknown a priori).
     */
    fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult>
```

- [ ] **Step 3.3: Deprecate the existing `putStream`**

The existing `putStream` (line 22 of `ObjectStorage.kt`) buffers the full stream in heap via `readBytes()`. Mark it `@Deprecated` to signal the correct path for new code, but keep it binary-compatible for the one remaining legacy caller (`module-external-api/.../OcidLookupPhase.kt:118` — out of scope for this issue).

Replace the existing declaration at line 22:

```kotlin
    /** Put data from a stream. Caller is responsible for closing `input`. */
    fun putStream(key: String, input: InputStream): PutResult
```

with:

```kotlin
    /**
     * Put data from a stream. Caller is responsible for closing `input`.
     *
     * **Deprecated** since issue #1312. Buffers the full stream in heap
     * via `readBytes()` (see `MinioObjectStorage.putStream` and
     * `LocalFsObjectStorage.putStream` for the heap-drain paths),
     * defeating the purpose of streaming uploads for chunks > 1MB.
     * Use [putStreamMultipart] instead, which uses S3 chunked transfer
     * encoding (Minio) or temp-file + putFile (LocalFs) with bounded
     * heap.
     *
     * The remaining legacy caller is
     * `module-external-api/.../OcidLookupPhase.kt:118` which has the
     * same heap problem and should migrate to `putStreamMultipart` in a
     * separate follow-up issue.
     */
    @Deprecated(
        message = "Buffers full stream in heap. Use putStreamMultipart for chunks > 1MB.",
        replaceWith = ReplaceWith("putStreamMultipart"),
    )
    fun putStream(key: String, input: InputStream): PutResult
```

- [ ] **Step 3.4: Verify module-common compiles**

Run: `./gradlew :module-common:compileKotlin :module-common:compileJava --continue 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL. (The interface is additive; existing impls in module-infra will fail to compile until Task 10 wires them.)

- [ ] **Step 3.5: Commit**

```bash
git add module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt
git commit -m "feat(storage): add putStreamMultipart + deprecate putStream

Adds CompletableFuture<PutResult> putStreamMultipart(key, input)
using S3AsyncClient chunked transfer (Minio) or temp-file + putFile
(LocalFs). Additive — existing putStream retained but marked
@Deprecated with explanation + OcidLookupPhase follow-up note.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Add `uploadExecutor` bean to `ConcurrencyConfiguration`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt`

- [ ] **Step 4.1: Locate the bean declarations**

```bash
grep -n "@Bean\|fun " module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt
```

Expected: Beans at lines 10-32 (`executorRegistry`, `executorSelector`, `threadLauncher`, `backpressureLimiter`, `asyncGuard`).

- [ ] **Step 4.2: Add the `uploadExecutor` bean**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt`, add a new bean AFTER `asyncGuard()` (after line 32):

```kotlin
    /**
     * Virtual-thread executor for IO-bound async upload work (currently
     * used by [maple.expectation.infrastructure.storage.LocalFsObjectStorage.putStreamMultipart]
     * to drain an InputStream to a temp file then call putFile). Virtual
     * threads are suitable because the work is mostly blocking (file
     * I/O) and we want unbounded concurrency without a thread-pool size
     * config to maintain.
     */
    @Bean
    fun uploadExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()
```

Add the import at the top of the file (next to the other imports):

```kotlin
import java.util.concurrent.Executor
import java.util.concurrent.Executors
```

- [ ] **Step 4.3: Verify module-infra compiles**

Run: `./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt
git commit -m "feat(infra): add virtual-thread uploadExecutor bean

Used by LocalFsObjectStorage.putStreamMultipart to drain an
InputStream to a temp file. Virtual threads suit blocking file I/O
without a fixed pool size.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Implement `LocalFsObjectStorage.putStreamMultipart`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt` (add `uploadExecutor` ctor param + `putStreamMultipart` method)
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsPutStreamMultipartTest.kt`

- [ ] **Step 5.1: Write the failing test**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsPutStreamMultipartTest.kt`:

```kotlin
package maple.expectation.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class LocalFsPutStreamMultipartTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStorage(): LocalFsObjectStorage =
        LocalFsObjectStorage(
            basePath = tempDir.toString(),
            uploadExecutor = Executors.newVirtualThreadPerTaskExecutor(),
            meterRegistry = null,
        )

    @Test
    fun `putStreamMultipart writes file and returns PutResult with size and checksum`() {
        val storage = newStorage()
        val data = "hello streaming world".toByteArray()
        val cf = storage.putStreamMultipart("test/stream.txt", ByteArrayInputStream(data))
        val result = cf.get()  // .get() OK in test only
        assertThat(result.key).isEqualTo("test/stream.txt")
        assertThat(result.size).isEqualTo(data.size.toLong())
        assertThat(result.checksum).isNotNull().hasSize(64)  // SHA-256 hex
    }

    @Test
    fun `putStreamMultipart cleans up temp file on success`() {
        val storage = newStorage()
        val data = "abc".toByteArray()
        storage.putStreamMultipart("test.txt", ByteArrayInputStream(data)).get()

        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `putStreamMultipart cleans up temp file on failure`() {
        val storage = newStorage()
        // Force a failure by passing a stream that throws on read
        val badStream = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("simulated read failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw java.io.IOException("simulated read failure")
        }
        org.junit.jupiter.api.assertThrows<java.util.concurrent.ExecutionException> {
            storage.putStreamMultipart("test.txt", badStream).get()
        }
        val tmpFiles = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `putStreamMultipart content matches input bytes`() {
        val storage = newStorage()
        val data = (0..255).map { it.toByte() }.toByteArray()
        storage.putStreamMultipart("bytes.bin", ByteArrayInputStream(data)).get()
        val read = storage.get("bytes.bin")
        assertThat(read).isEqualTo(data)
    }
}
```

- [ ] **Step 5.2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsPutStreamMultipartTest" 2>&1 | tail -20`

Expected: compilation failure — `putStreamMultipart` and the new 3-arg ctor do not exist yet.

- [ ] **Step 5.3: Modify `LocalFsObjectStorage`**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt`. Replace the entire class declaration at line 28-32:

```kotlin
@Component
class LocalFsObjectStorage(
    @Value("\${storage.local.base-path:../data}") private val basePath: String,
    private val uploadExecutor: Executor,
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {
```

Add the import at the top of the file (with the other imports):

```kotlin
import java.util.concurrent.Executor
```

After the `putFileAsync` method (line 65-70), add the new method:

```kotlin
    override fun putStreamMultipart(
        key: String,
        input: java.io.InputStream,
    ): CompletableFuture<PutResult> {
        // Drain the InputStream to a temp file (bounded heap — the temp
        // file is on disk, not in memory), then call putFile on a
        // virtual-thread executor. The single whenComplete guarantees
        // temp-file cleanup on both success and failure (no double-cleanup
        // race).
        val tempFile = Files.createTempFile("objstore-", ".tmp")
        return CompletableFuture.supplyAsync({
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
            putFile(key, tempFile)
        }, uploadExecutor).whenComplete { _, _ ->
            runCatching { Files.deleteIfExists(tempFile) }
        }
    }
```

- [ ] **Step 5.4: Update the `StorageConfig` `localObjectStorage` bean to inject the executor**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`. Replace the `localObjectStorage` bean (lines 34-39):

```kotlin
    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "local", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${storage.local.base-path:../data}") basePath: String,
        uploadExecutor: java.util.concurrent.Executor,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = LocalFsObjectStorage(basePath, uploadExecutor, meterRegistry)
```

- [ ] **Step 5.5: Run the test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsPutStreamMultipartTest" 2>&1 | tail -10`

Expected: 4 tests pass.

- [ ] **Step 5.6: Verify existing `LocalFsObjectStorageTest` still passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.LocalFsObjectStorageTest" 2>&1 | tail -10`

Expected: existing tests pass (the test at `LocalFsObjectStorageTest.kt:15-16` constructs `LocalFsObjectStorage(basePath, meterRegistry = null)` — this will need to be updated to add the `uploadExecutor` param).

If compilation fails, update `LocalFsObjectStorageTest.kt:15-16` to:

```kotlin
    private fun newStorage(basePath: String = tempDir.toString()): LocalFsObjectStorage =
        LocalFsObjectStorage(basePath, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), meterRegistry = null)
```

- [ ] **Step 5.7: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsPutStreamMultipartTest.kt
git commit -m "feat(storage): LocalFs.putStreamMultipart via temp file

Drains InputStream to a temp file, then calls putFile on a virtual
thread. Single whenComplete cleanup on success/failure — no
double-cleanup race. Tests verify temp-file cleanup on both paths.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Implement `MinioObjectStorage.putStreamMultipart`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt` (add `s3Async` ctor param + `putStreamMultipart` method)
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt` (wire `s3AsyncClient` into `minioObjectStorage` bean)

- [ ] **Step 6.1: Modify the `MinioObjectStorage` class**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`. Replace the constructor (lines 39-45):

```kotlin
class MinioObjectStorage(
    private val props: MinioProperties,
    private val s3: S3Client,
    private val s3Async: S3AsyncClient,
    private val transferManager: S3TransferManager,
    @Autowired(required = false)
    private val meterRegistry: MeterRegistry?,
) : ObjectStorage {
```

`S3AsyncClient` is already imported at line 17. `AsyncRequestBody` at line 9.

Add the new method AFTER `putFileAsync` (after line 161):

```kotlin
    override fun putStreamMultipart(
        key: String,
        input: java.io.InputStream,
    ): CompletableFuture<PutResult> {
        // Async chunked transfer: S3AsyncClient.putObject with
        // AsyncRequestBody.fromInputStream(input, -1L) tells the SDK to
        // send chunks without knowing the total length. The SDK
        // internally wraps the InputStream in
        // SdkChunkedEncodingInputStream, sends 5MB chunks via
        // multipart, and tracks checksums per chunk.
        //
        // Why not sync putObject: the sync S3Client.putObject marshals
        // Content-Length from RequestBody.contentLength() and throws
        // IAE("Content-length must not be negative") for unknown length
        // (see putStream() below for the full history of this attempt).
        //
        // Retry: SDK built-in RetryPolicy.defaultRetryPolicy (3 retries,
        // configured in StorageConfig.s3AsyncClient).
        val req = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType("application/octet-stream")
            .build()

        return s3Async.putObject(req, AsyncRequestBody.fromInputStream(input, -1L))
            .handleAsync { resp, err ->
                if (err != null) {
                    throw RuntimeException(
                        "putStreamMultipart failed for key=$key",
                        err,
                    )
                }
                // Size is unknown with chunked transfer (-1L).
                PutResult(key, -1L, resp.eTag())
            }
    }
```

`PutObjectResponse.eTag()` is the existing return path (used at line 73, 159).

- [ ] **Step 6.2: Wire `s3AsyncClient` into the `minioObjectStorage` bean**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`. Replace the `minioObjectStorage` bean (lines 97-104):

```kotlin
    @Bean
    @ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
    fun minioObjectStorage(
        props: MinioProperties,
        s3: S3Client,
        s3AsyncClient: S3AsyncClient,
        transferManager: S3TransferManager,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): ObjectStorage = MinioObjectStorage(props, s3, s3AsyncClient, transferManager, meterRegistry)
```

- [ ] **Step 6.3: Verify module-infra compiles**

Run: `./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt
git commit -m "feat(storage): Minio.putStreamMultipart via S3AsyncClient chunked

S3AsyncClient.putObject + AsyncRequestBody.fromInputStream(input,
-1L) sends chunks without knowing total length. Avoids the full-
ByteArray drain of the legacy putStream. size=-1L since chunked
transfer reports no upfront size.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Create `StubObjectStorage` test fixture

**Files:**
- Create: `module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt`

- [ ] **Step 7.1: Create the fixture**

Create `module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt`:

```kotlin
package maple.calculator.writer

import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Stub [ObjectStorage] for unit tests. All methods throw
 * [NotImplementedError] by default. Tests override only the methods they
 * exercise. Avoids a mocking-framework dependency.
 */
open class StubObjectStorage : ObjectStorage {

    /** If set, [putStreamMultipart] copies the input into this buffer. */
    var capturedStream: ByteArray? = null

    /** Override to inject behavior into [putStreamMultipart]. */
    open fun handlePutStreamMultipart(key: String, input: InputStream): PutResult {
        val bytes = input.readBytes()
        capturedStream = bytes
        return PutResult(key, bytes.size.toLong(), "stub-etag-${UUID.randomUUID()}")
    }

    final override fun putStreamMultipart(
        key: String,
        input: InputStream,
    ): CompletableFuture<PutResult> = CompletableFuture.completedFuture(
        handlePutStreamMultipart(key, input),
    )

    // --- Unused methods throw to surface accidental test dependencies ---

    override fun put(key: String, data: ByteArray): PutResult = throw NotImplementedError()
    override fun putStream(key: String, input: InputStream): PutResult = throw NotImplementedError()
    override fun putFile(key: String, path: Path): PutResult = throw NotImplementedError()
    override fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult> = throw NotImplementedError()
    override fun get(key: String): ByteArray = throw NotImplementedError()
    override fun getStream(key: String): InputStream = throw NotImplementedError()
    override fun delete(key: String) = throw NotImplementedError()
    override fun exists(key: String): Boolean = throw NotImplementedError()
    override fun listByPrefix(prefix: String): List<ObjectInfo> = throw NotImplementedError()
    override fun deleteByPrefix(prefix: String): Long = throw NotImplementedError()
    override fun calculatePrefixSize(prefix: String): Long = throw NotImplementedError()
    override fun getLastModified(key: String): Instant = throw NotImplementedError()
}
```

- [ ] **Step 7.2: Verify it compiles**

Run: `./gradlew :module-calculator:compileTestKotlin 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7.3: Commit**

```bash
git add module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt
git commit -m "test(calculator): add StubObjectStorage test fixture

Open class with NotImplementedError defaults. Tests override
putStreamMultipart to capture the input stream. No mocking framework
dependency.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Write `CalculationResultWriterTest` (failing)

**Files:**
- Create: `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt`

- [ ] **Step 8.1: Write the test**

Create `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt`:

```kotlin
package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.util.concurrent.ExecutionException
import java.util.zip.GZIPInputStream

/**
 * Verifies the CF-chain streaming write: the gzipped bytes captured by
 * [StubObjectStorage] decompress back to the original JSONL with no data
 * loss. Also exercises the error path (ObjectStorage failure) and the
 * counter accuracy.
 *
 * Plain unit test (no @SpringBootTest) — uses Spring's
 * [Jackson2ObjectMapperBuilder] directly to satisfy the code-style rule
 * forbidding `new ObjectMapper()`. ~10ms startup vs ~1500ms for a
 * SpringBootTest.
 */
class CalculationResultWriterTest {

    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder()
        .modules(KotlinModule.Builder().build(), JavaTimeModule())
        .build()

    @Test
    fun `streaming gzip output decompresses to expected JSONL`() {
        val stub = StubObjectStorage()
        val writer = CalculationResultWriter(stub, objectMapper)
        val results = flowOf(
            sampleResult(ocid = "ocid-1"),
            sampleResult(ocid = "ocid-2"),
            sampleResult(ocid = "ocid-3"),
        )

        val cf = writer.write("test/chunk.jsonl.gz", results)
        val writeResult = cf.get()  // .get() OK in test only

        assertThat(writeResult.objectKey).isEqualTo("test/chunk.jsonl.gz")
        assertThat(writeResult.resultCount).isEqualTo(3L)
        assertThat(writeResult.compressedBytes).isGreaterThan(0L)
        assertThat(writeResult.uncompressedBytes).isGreaterThan(writeResult.compressedBytes)
        assertThat(writeResult.etag).startsWith("stub-etag-")

        // Bytewise equivalence: decompress the captured stream and verify content.
        val gz = stub.capturedStream!!
        val decompressed = GZIPInputStream(gz.inputStream()).bufferedReader().readText()
        assertThat(decompressed).contains("\"ocid\":\"ocid-1\"")
        assertThat(decompressed).contains("\"ocid\":\"ocid-2\"")
        assertThat(decompressed).contains("\"ocid\":\"ocid-3\"")
        // Each record terminated with newline
        assertThat(decompressed.lines().filter { it.isNotBlank() }).hasSize(3)
    }

    @Test
    fun `streaming write with empty flow produces gzip header only`() {
        val stub = StubObjectStorage()
        val writer = CalculationResultWriter(stub, objectMapper)

        val cf = writer.write("empty.jsonl.gz", emptyFlow())
        val writeResult = cf.get()

        assertThat(writeResult.resultCount).isZero()
        assertThat(writeResult.compressedBytes).isGreaterThan(0L)
        // gzip magic: 1f 8b
        val gz = stub.capturedStream!!
        assertThat(gz[0]).isEqualTo(0x1f.toByte())
        assertThat(gz[1]).isEqualTo(0x8b.toByte())
    }

    @Test
    fun `write failure propagates via CompletableFuture exceptionally`() {
        val stub = object : StubObjectStorage() {
            override fun handlePutStreamMultipart(key: String, input: java.io.InputStream): PutResult {
                throw RuntimeException("simulated upload failure")
            }
        }
        val writer = CalculationResultWriter(stub, objectMapper)
        val results = flowOf(sampleResult(ocid = "ocid-1"))

        val ex = assertThrows<ExecutionException> {
            writer.write("test.jsonl.gz", results).get()
        }
        assertThat(ex.cause).hasMessageContaining("streaming write failed")
    }

    private fun sampleResult(ocid: String): maple.calculator.model.CalculationResult =
        maple.calculator.model.CalculationResult(
            ocid = ocid,
            presetNo = 0,
            itemName = "Test Item",
            itemLevel = 200,
            itemPart = null,
            itemEquipmentPart = null,
            potentialGrade = null,
            potentialOptions = emptyList(),
            additionalGrade = null,
            additionalOptions = emptyList(),
            currentStar = 0,
            targetStar = 0,
            status = "SUCCESS",
            totalCost = 1000.0,
            blackCubeCost = null,
            additionalCubeCost = null,
            starforceCost = null,
            errorMessage = null,
        )
}
```

Also create `module-calculator/src/test/kotlin/maple/calculator/writer/TestObjectMapperConfig.kt` (no — see Step 8.2 below; we switched to plain unit test with `Jackson2ObjectMapperBuilder` directly).

- [ ] **Step 8.2: Run test to verify it fails**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.CalculationResultWriterTest" 2>&1 | tail -20`

Expected: compilation failure — the new `CalculationResultWriter` ctor signature with `injectedProducerScope` is not yet present, and `write()` still returns `suspend` `WriteResult`.

- [ ] **Step 8.2: Drop `TestObjectMapperConfig`**

Delete the placeholder note for `TestObjectMapperConfig` from the plan — the test is now a plain unit test (Step 8.1 updated) and does not need a Spring config. No file is created.

---

## Task 9: Rewrite `CalculationResultWriter` (CF chain)

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` (full rewrite)

- [ ] **Step 9.1: Replace the file**

Replace the entire contents of `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` with:

```kotlin
package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.future
import maple.calculator.model.CalculationResult
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CalculationResultWriter(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
    // Nullable so tests can inject a TestScope without conflicting with
    // @PreDestroy. If null, we create + own a default scope and cancel
    // it on bean destroy. If injected, the caller owns the lifecycle.
    private val injectedProducerScope: CoroutineScope? = null,
) {
    private val log = LoggerFactory.getLogger(CalculationResultWriter::class.java)

    private val producerScope: CoroutineScope =
        injectedProducerScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val ownsProducerScope: Boolean = injectedProducerScope == null

    @PreDestroy
    fun close() {
        if (ownsProducerScope) {
            producerScope.cancel()
        }
    }

    data class WriteResult(
        val objectKey: String,
        val resultCount: Long,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
        val etag: String?,
    )

    /**
     * Stream calculation results through gzip → [ObjectStorage.putStreamMultipart].
     *
     * Producer (on [producerScope], IO dispatcher) collects the Flow and
     * writes to a pipe. Consumer is the S3 async client (or LocalFs
     * virtual-thread executor). The 8MB pipe provides natural backpressure:
     * when the consumer stalls, the pipe fills, the producer's
     * `pipeOutput.write()` blocks, the gzip blocks, the JsonGenerator
     * blocks, and `Flow.collect` suspends — no unbounded heap growth.
     *
     * Returns [CompletableFuture]. Callers MUST chain via
     * `thenApply` / `thenAccept` — never `.join()` / `.get()`.
     */
    fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): CompletableFuture<WriteResult> {
        val pipeInput = java.io.PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOutput = java.io.PipedOutputStream(pipeInput)
        val counters = WriteCounters()

        // Wrap pipeOutput in CountingOutputStream to track compressed bytes
        // (gzip output → pipe). Minio's putStreamMultipart reports
        // size=-1L for chunked transfer, so this counter is the only
        // source of truth for compressed bytes in the Minio path.
        val compressedCounter = CountingOutputStream(pipeOutput)

        // Producer: collect the Flow into the pipe via gzip.
        val producerFuture: CompletableFuture<Unit> = producerScope.future {
            try {
                GZIPOutputStream(compressedCounter).use { gz ->
                    CountingOutputStream(gz).use { cgz ->
                        objectMapper.factory.createGenerator(cgz).use { gen ->
                            results.collect { result ->
                                counters.records.incrementAndGet()
                                gen.writeObject(result)
                                gen.writeRaw('\n')
                            }
                            counters.uncompressedBytes.set(cgz.count)
                        }
                    }
                }
                counters.compressedBytes.set(compressedCounter.count)
            } finally {
                runCatching { pipeOutput.close() }  // signal EOF to consumer
            }
            Unit
        }

        // Consumer: pipe → ObjectStorage.putStreamMultipart (chunked transfer).
        val uploadFuture = objectStorage.putStreamMultipart(objectKey, pipeInput)

        // Deadlock guard: if the upload fails before draining the pipe,
        // the producer blocks on pipeOut.write() forever. Closing
        // pipeInput makes the next pipeOut.write() throw IOException,
        // which unblocks the producer's coroutine. The pipe becomes
        // effectively a one-shot channel with explicit error propagation.
        uploadFuture.whenComplete { _, err ->
            if (err != null) {
                runCatching { pipeInput.close() }
            }
        }

        // Compose: producer done + upload done → WriteResult.
        val composed = producerFuture.thenCombine(uploadFuture) { _, putResult ->
            WriteResult(
                objectKey = putResult.key,
                resultCount = counters.records.get(),
                uncompressedBytes = counters.uncompressedBytes.get(),
                compressedBytes = if (putResult.size >= 0) {
                    putResult.size  // LocalFs: real size from putFile
                } else {
                    counters.compressedBytes.get()  // Minio: chunked transfer, size=-1L
                },
                etag = putResult.checksum,
            )
        }

        // Cleanup the pipe on any path (success or failure).
        return composed.whenComplete { _, _ ->
            runCatching { pipeInput.close() }
        }.exceptionally { err ->
            log.error(
                "[CalculationResultWriter] write failed for key={}",
                objectKey,
                err,
            )
            throw RuntimeException("streaming write failed for key=$objectKey", err)
        }
    }

    companion object {
        /** Pipe buffer = 8MB. Backs pressure the producer when S3 stalls. */
        private const val PIPE_BUFFER_BYTES: Int = 8 * 1024 * 1024
    }
}
```

- [ ] **Step 9.2: Run the test to verify it passes**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.CalculationResultWriterTest" 2>&1 | tail -15`

Expected: 3 tests pass.

- [ ] **Step 9.3: Verify all writer-package tests still pass**

Run: `./gradlew :module-calculator:test --tests "maple.calculator.writer.*" 2>&1 | tail -10`

Expected: all writer tests pass (CountingOutputStream, WriteCounters, CalculationResultWriter).

- [ ] **Step 9.4: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt \
        module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt \
        module-calculator/src/test/kotlin/maple/calculator/writer/TestObjectMapperConfig.kt
git commit -m "feat(calculator): CF-chain streaming write (Flow → gzip → pipe → S3)

Replaces ByteArrayOutputStream buffering with CompletableFuture chain:
producerScope.future { Flow.collect → gzip → 8MB pipe } composed with
ObjectStorage.putStreamMultipart via thenCombine. No join/get/await in
production code. Backpressure via 8MB pipe buffer.

Heap reduction: 4 x chunk-size peak eliminated (~40MB).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Migrate `SnapshotChunkProcessor` caller

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` (lines 91-93, 95-105)

- [ ] **Step 10.1: Locate the call site**

```bash
grep -n "resultWriter.write\|async.*Dispatchers.IO" module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt
```

Expected: the `val writeResult = async(Dispatchers.IO) { resultWriter.write(...) }.await()` block at lines 91-93.

- [ ] **Step 10.2: Replace the call site**

Edit `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt`. Replace lines 91-93:

```kotlin
        val writeResult = async(Dispatchers.IO) {
            resultWriter.write(resultObjectKey, channelAsFlow(resultChannel))
        }.await()
```

with:

```kotlin
        // Start the write CF BEFORE waiting for parse+calc to finish —
        // the CF drains resultChannel in the background via
        // producerScope.future, so it overlaps with the parse+calc workers
        // (same overlap the original async { write() } provided).
        val writeFuture = resultWriter.write(resultObjectKey, channelAsFlow(resultChannel))
        val writeResult = writeFuture.await()  // single .await() at coroutine→CF boundary
```

The `.thenCombine` from the original `async { write }` pattern is no longer needed: `writeFuture` is created inside the `coroutineScope` block (it's a child coroutine conceptually, but the CF chain runs in the background), and the await blocks the outer coroutine until the write CF completes.

- [ ] **Step 10.3: Verify module-calculator compiles**

Run: `./gradlew :module-calculator:compileKotlin :module-calculator:compileJava --continue 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.4: Run the full calculator test suite**

Run: `./gradlew :module-calculator:test 2>&1 | tail -15`

Expected: all tests pass (some legacy tests may exist; if they reference the old `suspend fun write()` signature, they need to be updated — see Step 10.5).

- [ ] **Step 10.5: Check for legacy callers**

Run: `grep -rn "resultWriter.write\|\.write(" module-calculator/src/ --include="*.kt" | grep -v Test | grep -v writer/CalculationResultWriter`

If any caller (other than `SnapshotChunkProcessor.kt:91`) still calls `write()` with a `runBlocking { }` wrapper or `.join()` / `.get()` on the result, update it to use the CF chain pattern (`.thenAccept { ... }`).

- [ ] **Step 10.6: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt
git commit -m "refactor(calculator): migrate SnapshotChunkProcessor to CF-based write

writeFuture is created BEFORE coroutineScope waits for parse+calc, so
the write CF drains resultChannel in the background (same overlap as
the original async { write() }). Single .await() at the coroutine->CF
boundary. process() remains suspend fun ChunkResult.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 11: Full compile + test sweep

**Files:** (none modified — verification only)

- [ ] **Step 11.1: Compile all modules with --continue**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL. Fix any module that fails (likely candidates: module-rest-controller if it depends on the writer signature).

- [ ] **Step 11.2: Run all unit tests (excluding integration/quarantine/flaky/pgmq tags)**

Run: `./gradlew test 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL. Fix any failing tests.

- [ ] **Step 11.3: Verify no regressions in module-infra storage tests**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.storage.*" 2>&1 | tail -10`

Expected: LocalFsObjectStorageTest + LocalFsPutStreamMultipartTest pass.

- [ ] **Step 11.4: Commit (no code changes)**

If no changes are needed, skip this step. If a test was fixed or a module needed adjustment, commit those changes:

```bash
git add -A
git commit -m "chore: address compile/test fallout from issue #1312

[describe what was fixed]

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 12: Live runtime verification (per workflow-rules.md §10)

**Files:** (none modified — runtime check only)

- [ ] **Step 12.1: Load .env and start the calculator module**

```bash
set -a && source .env && set +a
./gradlew :module-calculator:bootRun
```

Wait for "Started CalculatorApplication" in `module-calculator/logs/app.log` (or stdout).

- [ ] **Step 12.2: Trigger the streaming write path via the v5 API**

```bash
curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

Expected: HTTP 202 (async accept). Note: 202 only means accepted, NOT success.

- [ ] **Step 12.3: Verify the streaming write completed without error**

```bash
grep "Calculation completed" module-calculator/logs/app.log | tail -5
grep "ERROR" module-calculator/logs/app.log | tail -10
```

Expected:
- `Calculation completed` log present (chunk finished)
- `ERROR` empty (no write failures)

- [ ] **Step 12.4: Stop the calculator**

Find the bootRun PID:

```bash
pgrep -af "gradlew :module-calculator:bootRun\|CalculatorApplication"
```

Kill the bootRun process (the gradle wrapper, not the Java process — `kill <pid>` on the gradle wrapper).

- [ ] **Step 12.5: Commit (no code changes)**

If no fixes were needed, skip. If a runtime config or YAML was adjusted, commit:

```bash
git add -A
git commit -m "chore: runtime config adjustment for issue #1312

[describe]

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 13: Live bytewise smoke test

**Files:** (none modified — runtime check only)

- [ ] **Step 13.1: Capture a reference run BEFORE deploying to other environments**

(Must be done before the first deploy. If reference already exists, skip.)

```bash
# From a working calculator deployment on MinIO:
mc cp local/maple-expectation/calculator/runs/<runId>/<chunk>.jsonl.gz /tmp/reference.jsonl.gz
```

- [ ] **Step 13.2: Deploy this branch and run the pipeline**

```bash
git push origin <branch>
# CI deploys to staging
# Trigger a pipeline run via the v5 API:
curl -X POST "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

Wait for `Calculation completed` log per Task 12.3.

- [ ] **Step 13.3: Capture the new run**

```bash
mc cp local/maple-expectation/calculator/runs/<runId>/<chunk>.jsonl.gz /tmp/new_run.jsonl.gz
```

- [ ] **Step 13.4: Bytewise diff against the reference**

```bash
diff <(gunzip -c /tmp/new_run.jsonl.gz | head -1000) <(gunzip -c /tmp/reference.jsonl.gz | head -1000)
```

Expected: no diff. (Compare a larger sample, e.g. `head -10000` or the full file, to be thorough.)

- [ ] **Step 13.5: Verify heap reduction**

```bash
curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{application="calculator"}' | jq
```

Expected: `jvm_memory_used_bytes{area="heap"}` < 200MB sustained (down from ~414MB baseline).

---

## Self-Review

**Spec coverage:**

- §2 Architecture (writer CF chain + caller Flow+CF) → Task 9 (writer), Task 10 (caller)
- §3.1 ObjectStorage.putStreamMultipart → Task 3 (interface), Task 5 (LocalFs), Task 6 (Minio)
- §3.2 MinioObjectStorage with s3Async → Task 6
- §3.2 LocalFsObjectStorage with uploadExecutor → Task 4 (bean), Task 5 (impl)
- §3.2 StorageConfig wiring → Task 5 (LocalFs), Task 6 (Minio)
- §3.3 CountingOutputStream public → Task 1
- §3.3 WriteCounters → Task 2
- §3.3 CalculationResultWriter CF chain → Task 9
- §3.3 SnapshotChunkProcessor caller migration → Task 10
- §3.4 CalculationResultWriterTest → Task 8 (test), Task 9 (impl)
- §3.4 CountingOutputStreamTest → Task 1
- §3.4 WriteCountersTest → Task 2
- §3.4 StubObjectStorage → Task 7
- §3.4 LocalFsObjectStorage putStreamMultipart test → Task 5
- §6 Live smoke → Task 12, Task 13

**Placeholder scan:** No TBD/TODO. All code blocks complete.

**Type consistency:**

- `ObjectStorage.putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult>` — defined Task 3, used Task 5 (LocalFs), Task 6 (Minio), Task 7 (StubObjectStorage), Task 9 (writer)
- `WriteCounters` — defined Task 2, used Task 9
- `CountingOutputStream` — defined Task 1, used Task 9
- `CalculationResultWriter.write(...): CompletableFuture<WriteResult>` — defined Task 9, called Task 10
- `StubObjectStorage.handlePutStreamMultipart` — defined Task 7, overridden Task 8 (test)
- `LocalFsObjectStorage(basePath, uploadExecutor, meterRegistry)` — defined Task 5, wired Task 5 (StorageConfig), used Task 5 (test)
- `MinioObjectStorage(props, s3, s3Async, transferManager, meterRegistry)` — defined Task 6, wired Task 6 (StorageConfig)
- `uploadExecutor: Executor` (virtual thread bean) — defined Task 4, used Task 5 (LocalFs ctor), Task 5 (StorageConfig)
- `WriteResult.recordCount` — wait, the spec uses `resultCount` (preserves existing field name). Plan step 9.1 uses `resultCount`. Consistent.

All consistent.

**Risks acknowledged in spec:**

- §10 Risk: `PipedInputStream` thread affinity (producer on IO, consumer on S3 SDK's netty). Mitigated by IO dispatcher for producer.
- §10 Risk: LocalFs temp file on crash. Not addressed in plan (JVM shutdown hook is out of scope).
- §10 Risk: CF composition overhead. Negligible.

**Open questions from spec:** None — caller migration resolved during brainstorming.
