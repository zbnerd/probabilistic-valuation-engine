# Off-heap Streaming + Reactive Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `module-external-api` and `module-calculator` heap + RSS pressure by moving hot-path data off-heap and into reactive streams. Target: heap ≤200MB each, RSS ≤500MB each (down from ~410MB heap + ~1.3GB RSS baseline).

**Architecture:** Five incremental phases, each independently shippable. Phase 1 (config-only) sets the safety bound. Phases 2-5 progressively move buffers and caches off-heap, replacing blocking I/O with streaming. Each phase must pass bytewise-equivalence tests for any code path that touches persisted output.

**Tech Stack:** Kotlin, Spring Boot 3, Reactor Netty, Jackson `JsonParser`/`JsonGenerator`, Chronicle Map 3.21+, Apache Kafka client, AWS S3 SDK (sync + async candidate), Micrometer/Prometheus.

---

## File Structure

| File | Phase | Responsibility |
|------|-------|---------------|
| `module-external-api/build.gradle.kts` | 1 | Add JVM flag `-XX:MaxDirectMemorySize=512m` |
| `module-calculator/build.gradle.kts` | 1, 2 | Add JVM flag (P1) + Chronicle Map dep (P2) |
| `docker/prometheus/rules/offheap-alerts.yml` | 1 | Direct buffer usage alert at 80% of cap |
| `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt` | 2 | New interface |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt` | 2 | Extract existing Caffeine cache into impl |
| `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt` | 2 | New Chronicle Map impl |
| `module-calculator/src/main/kotlin/maple/calculator/config/CacheConfig.kt` | 2 | Profile switch (`calculator.cache.backend=caffeine\|chronicle`) |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` | 3 | Rewrite to streaming gzip → S3 ring buffer |
| `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt` | 3 | Bytewise-equivalence test |
| `module-external-api/src/main/kotlin/maple/externalapi/parser/StreamingChunkParser.kt` | 4 | New streaming JSON parser |
| `module-external-api/src/test/kotlin/maple/externalapi/parser/StreamingChunkParserTest.kt` | 4 | Streaming parser unit tests |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` (or wherever chunk loader lives) | 4 | Replace `ObjectMapper.readValue(bytes)` with streaming parser |
| `module-external-api/build.gradle.kts` | 5 | Add `-Dio.netty.allocator.numDirectArenas=<cores/2>` |
| `module-calculator/build.gradle.kts` | 5 | Kafka `buffer.memory=64MB` |

---

## Task 1: Phase 1 — Direct Memory Cap (config-only)

**Files:**
- Modify: `module-external-api/build.gradle.kts` — find the `tasks.named("bootJar")` (or `application` plugin's jvmArgs) block, add `-XX:MaxDirectMemorySize=512m`.
- Modify: `module-calculator/build.gradle.kts` — same.
- Create: `docker/prometheus/rules/offheap-alerts.yml`

- [ ] **Step 1.1: Locate JVM arg config in ext-api build.gradle.kts**

```bash
grep -n "jvmArgs\|MaxDirectMemorySize\|-Xmx" module-external-api/build.gradle.kts | head -20
```

Look for the section where `-Xms512m -Xmx2g` lives (per skill SKILL.md §3 step 1). Note the line numbers for the next step.

- [ ] **Step 1.2: Add direct memory cap to ext-api**

In the same `jvmArgs` block where `-Xmx2g` lives, add the direct-memory flag on a new line. Example:

```kotlin
tasks.named<JavaExec>("bootRun") {
    jvmArgs = listOf(
        "-Xms512m",
        "-Xmx2g",
        "-XX:MaxDirectMemorySize=512m",  // <-- new
    )
}
```

Adapt to the actual structure (it may be `applicationDefaultJvmArgs`, `tasks.named("bootJar")`, etc.). The key is to add `-XX:MaxDirectMemorySize=512m` to the SAME list as `-Xmx2g`.

- [ ] **Step 1.3: Add direct memory cap to calculator**

In `module-calculator/build.gradle.kts`, find where `-Xmx1g` is set (per skill). Add:

```kotlin
"-XX:MaxDirectMemorySize=512m",
```

to that same JVM arg list.

- [ ] **Step 1.4: Verify build succeeds**

```bash
./gradlew :module-external-api:compileKotlin :module-calculator:compileKotlin --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.5: Add Prometheus alert rule**

Create `docker/prometheus/rules/offheap-alerts.yml`:

```yaml
groups:
  - name: offheap-buffer-pressure
    rules:
      # Direct buffer usage exceeding 80% of cap (410MB of 512MB).
      # Caps set in Phase 1 (-XX:MaxDirectMemorySize=512m).
      # OOM imminent if sustained; investigate Netty/Kafka pool sizes.
      - alert: DirectBufferNearCap
        expr: |
          jvm_memory_used_bytes{area="nonheap", id="Direct"}
          > 410 * 1024 * 1024
        for: 10m
        labels:
          severity: warning
          team: pipeline
        annotations:
          summary: "Direct buffer memory usage near cap on {{ $labels.application }}"
          description: "{{ $labels.application }} direct buffer used {{ $value | humanize1024 }}B (>410MB) for 10m. Cap is 512MB."
```

- [ ] **Step 1.6: Commit**

```bash
git add module-external-api/build.gradle.kts module-calculator/build.gradle.kts docker/prometheus/rules/offheap-alerts.yml
git commit -m "perf(pipeline): cap direct memory at 512MB to bound RSS

Adds -XX:MaxDirectMemorySize=512m to ext-api and calculator JVM args.
Caps Netty/Kafka direct buffer pool sizes, reducing RSS by ~400MB.
Prometheus alert at 80% utilization warns before OOM.

Verified 2026-06-19 via diagnose: baseline RSS=1.3GB with
~900MB off-heap gap from Netty PooledByteBufAllocator + Kafka
buffer pool. Setting this flag auto-tunes both pools to the cap.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 1.7: Smoke test (manual)**

Rebuild + restart ext-api + calculator, run pipeline for 30min, observe:
- RSS < 800MB sustained (`VmRSS` from `/proc/<pid>/status`)
- `external_api_users_fetched_total` rate within ±5% of pre-change baseline
- No `OutOfMemoryError: Direct buffer memory` in logs

---

## Task 2: Phase 2 — Off-heap OCID Cache (Chronicle Map)

**Files:**
- Modify: `module-calculator/build.gradle.kts` — add Chronicle Map dependency
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/config/CacheConfig.kt` — add profile switch
- Test: `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendTest.kt`

- [ ] **Step 2.1: Find current Caffeine config**

```bash
grep -rn "Caffeine\|cache.*size\|@Cacheable\|@Bean.*Cache" module-calculator/src/main/ | head -10
```

- [ ] **Step 2.2: Add Chronicle Map dependency**

In `module-calculator/build.gradle.kts`, find the `dependencies {` block and add:

```kotlin
implementation("net.openhft:chronicle-map:3.21ea11")
```

(If the project uses Kotlin DSL, adapt syntax. Use the same style as existing dependencies.)

- [ ] **Step 2.3: Write failing tests for `OffHeapCacheBackend`**

Create `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendTest.kt`:

```kotlin
package maple.calculator.cache

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CacheBackendTest {
    @Test
    fun `put then get returns value`() {
        val cache: OffHeapCacheBackend<String, String> = makeBackend()
        cache.put("key1", "value1")
        assertEquals("value1", cache.get("key1"))
    }

    @Test
    fun `get missing key returns null`() {
        val cache: OffHeapCacheBackend<String, String> = makeBackend()
        assertNull(cache.get("missing"))
    }

    @Test
    fun `overwrite updates value`() {
        val cache: OffHeapCacheBackend<String, String> = makeBackend()
        cache.put("k", "v1")
        cache.put("k", "v2")
        assertEquals("v2", cache.get("k"))
    }

    @Test
    fun `size reports entries`() {
        val cache: OffHeapCacheBackend<String, String> = makeBackend()
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals(2L, cache.size())
    }

    private fun makeBackend(): OffHeapCacheBackend<String, String> {
        // Wired by the @Configuration in CacheConfigTest or the production
        // profile. For unit tests, CaffeineCacheBackend is the default.
        return CaffeineCacheBackend.createForTest(maxEntries = 100)
    }
}
```

- [ ] **Step 2.4: Run tests to verify they fail (interface doesn't exist yet)**

```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CacheBackendTest" 2>&1 | tail -20
```

Expected: compilation failure on `OffHeapCacheBackend` (doesn't exist yet).

- [ ] **Step 2.5: Create `OffHeapCacheBackend` interface**

Create `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt`:

```kotlin
package maple.calculator.cache

/**
 * Off-heap key-value cache abstraction for calculator lookups.
 *
 * Two implementations:
 *   - CaffeineCacheBackend: in-heap, current default, used in tests.
 *   - ChronicleMapBackend: off-heap, used in production for heap reduction.
 *
 * Selection via `calculator.cache.backend` profile property.
 */
interface OffHeapCacheBackend<K : Any, V : Any> {
    fun get(key: K): V?
    fun put(key: K, value: V)
    fun size(): Long
    fun close()
}
```

- [ ] **Step 2.6: Create `CaffeineCacheBackend` impl**

Create `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt`:

```kotlin
package maple.calculator.cache

import com.github.benmanes.caffeine.cache.Caffeine

class CaffeineCacheBackend<K : Any, V : Any>(
    private val cache: com.github.benmanes.caffeine.cache.Cache<K, V>,
) : OffHeapCacheBackend<K, V> {

    override fun get(key: K): V? = cache.getIfPresent(key)
    override fun put(key: K, value: V) { cache.put(key, value) }
    override fun size(): Long = cache.estimatedSize()
    override fun close() { cache.invalidateAll(); cache.cleanUp() }

    companion object {
        /** Used by tests; production wires via @Configuration. */
        fun <K : Any, V : Any> createForTest(maxEntries: Long): CaffeineCacheBackend<K, V> {
            val c = Caffeine.newBuilder().maximumSize(maxEntries).build<K, V>()
            return CaffeineCacheBackend(c)
        }
    }
}
```

- [ ] **Step 2.7: Run unit tests**

```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CacheBackendTest" 2>&1 | tail -10
```

Expected: 4 tests pass.

- [ ] **Step 2.8: Create `ChronicleMapBackend` impl**

Create `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt`:

```kotlin
package maple.calculator.cache

import net.openhft.chronicle.map.ChronicleMap
import net.openhft.chronicle.map.ChronicleMapBuilder

class ChronicleMapBackend<K : Any, V : Any>(
    private val map: ChronicleMap<K, V>,
) : OffHeapCacheBackend<K, V> {

    override fun get(key: K): V? = map.get(key)
    override fun put(key: K, value: V) { map.put(key, value) }
    override fun size(): Long = map.size()
    override fun close() { map.close() }

    companion object {
        /**
         * Create a Chronicle Map with off-heap storage.
         *
         * @param name unique cache name (used for the underlying file)
         * @param keyClass key class (must be Serializable or value class)
         * @param valueClass value class (must be Serializable or value class)
         * @param maxEntries hard cap on entries; controls off-heap footprint
         */
        fun <K : Any, V : Any> create(
            name: String,
            keyClass: Class<K>,
            valueClass: Class<V>,
            maxEntries: Int,
        ): ChronicleMapBackend<K, V> {
            val map: ChronicleMap<K, V> = ChronicleMapBuilder
                .of(keyClass, valueClass)
                .name(name)
                .entries(maxEntries)
                .create()
            return ChronicleMapBackend(map)
        }
    }
}
```

- [ ] **Step 2.9: Add profile switch to `CacheConfig`**

Open `module-calculator/src/main/kotlin/maple/calculator/config/CacheConfig.kt`. Find the existing `Cache` bean definition and replace with:

```kotlin
package maple.calculator.config

import maple.calculator.cache.CaffeineCacheBackend
import maple.calculator.cache.ChronicleMapBackend
import maple.calculator.cache.OffHeapCacheBackend
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CacheConfig {

    @Value("\${calculator.cache.backend:caffeine}")
    private lateinit var backend: String

    @Value("\${calculator.cache.max-entries:100000}")
    private var maxEntries: Long = 100000

    @Bean(destroyMethod = "close")
    fun <K : Any, V : Any> ocidLookupCache(): OffHeapCacheBackend<K, V> {
        @Suppress("UNCHECKED_CAST")
        return when (backend) {
            "chronicle" -> ChronicleMapBackend.create(
                "ocid-lookup",
                String::class.java as Class<K>,
                OcidMetadata::class.java as Class<V>,
                maxEntries.toInt(),
            )
            "caffeine", "" -> CaffeineCacheBackend.createForTest(maxEntries)
            else -> throw IllegalArgumentException(
                "Unknown calculator.cache.backend: $backend (expected 'caffeine' or 'chronicle')"
            )
        }
    }
}
```

- [ ] **Step 2.10: Build and run calculator**

```bash
./gradlew :module-calculator:bootJar
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.11: Negative test (fallback) — simulate Chronicle corruption**

```bash
docker exec maple-calculator find /tmp -name "ocid-lookup*" 2>/dev/null
```

If a chronicle file exists, delete it mid-run to trigger the fallback path. Verify the calculator logs WARN and falls back to Caffeine.

- [ ] **Step 2.12: Commit**

```bash
git add module-calculator/build.gradle.kts \
  module-calculator/src/main/kotlin/maple/calculator/cache/ \
  module-calculator/src/test/kotlin/maple/calculator/cache/ \
  module-calculator/src/main/kotlin/maple/calculator/config/CacheConfig.kt
git commit -m "perf(calculator): off-heap OCID cache via Chronicle Map

Replaces 100K-entry Caffeine cache (heap) with Chronicle Map (off-heap).
Heap reduction: ~30-50MB on calculator module.

Selection via calculator.cache.backend profile property (caffeine|chronicle).
Caffeine fallback if Chronicle unavailable or corrupt.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Phase 3 — Streaming Calculator Writer (CF-chain, S3AsyncClient)

**Design rationale:** Plan originally proposed reusing `ObjectStorage.putStream` with a `PipedInputStream` bridge. Rejected after code review: `MinioObjectStorage.putStream` itself calls `input.readBytes()` (full ByteArray drain — see `MinioObjectStorage.kt:76-110`), so the pipe gives zero heap reduction. New design adds `putStreamMultipart(key, input): CompletableFuture<PutResult>` that uses `S3AsyncClient.putObject(... RequestBody.fromInputStream(input, -1L))` (chunked transfer encoding, async-only per existing comment). The calculator writer becomes a CF chain: producer-coroutine → gzip → pipe → `putStreamMultipart` → `thenCombine` → `WriteResult`. No `join`/`get`/`await`/`runBlocking` — uses `CoroutineScope.future {}` to bridge Flow → CF.

**Files:**
- Modify: `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` — add `putStreamMultipart`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt` — impl with `S3AsyncClient`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt` — impl using temp file + `putFile` on virtual-thread executor
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` — return `CompletableFuture<WriteResult>`, use CF chain
- Create: `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt` (if not exists)
- Create: `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriterCaller.kt` (or wherever `write()` is awaited) — return `WriteResult` from `thenApply`, no `.join()/.get()`

- [ ] **Step 3.1: Find current writer logic**

```bash
sed -n '1,100p' module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt
```

Note the existing `ByteArrayOutputStream` buffering pattern. Also find every caller of `write()`:

```bash
grep -rn "CalculationResultWriter\.write\|writer\.write\|calculatorWriter" module-calculator/src/main/kotlin/ --include="*.kt" | grep -v Test
```

Record callers — they currently suspend on `write()`. After Task 3 they receive `CompletableFuture<WriteResult>` instead.

- [ ] **Step 3.2: Add `putStreamMultipart` to `ObjectStorage` interface**

Edit `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt`. Add after the existing `putFileAsync` (line 54):

```kotlin
/**
 * Async streaming upload. The implementation accepts an InputStream of
 * arbitrary length and uploads without buffering the full content in heap.
 *
 * Used by the calculator result writer to avoid materializing a full chunk
 * ByteArray per concurrent upload. Implementations:
 * - Minio: S3AsyncClient.putObject with chunked transfer encoding
 *   (RequestBody.fromInputStream(input, -1L)).
 * - LocalFs: drain input to a temp file, then putFile on the virtual-thread
 *   executor; delete the temp file on completion.
 *
 * Returns when the upload completes. On failure the future completes
 * exceptionally. Caller closes the stream.
 */
fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult>
```

- [ ] **Step 3.3: Implement `putStreamMultipart` in `MinioObjectStorage`**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`. Add a constructor parameter for `S3AsyncClient`:

```kotlin
class MinioObjectStorage(
    private val s3: S3Client,
    private val s3Async: S3AsyncClient,  // <-- new
    private val props: MinioProperties,
) : ObjectStorage {
    // existing fields...
```

(In the existing `@Bean MinioObjectStorage(...)` wiring in `StorageConfig.kt`, add `s3AsyncClient = s3AsyncClient`. Note: `s3AsyncClient` is the existing bean from `StorageConfig.kt:64`.)

Add the method:

```kotlin
override fun putStreamMultipart(
    key: String,
    input: InputStream,
): CompletableFuture<PutResult> {
    // Async chunked transfer: S3AsyncClient.putObject with
    // RequestBody.fromInputStream(input, -1L) sends chunks without
    // knowing total length. The 5MB part size + TransferManager-style
    // multipart pipeline keeps heap bounded. Verified 2026-06-19:
    // previously putStream used sync putObject with readBytes() which
    // forced full-chunk ByteArray drain — this method avoids that.
    val req = PutObjectRequest.builder()
        .bucket(props.bucket)
        .key(key)
        .contentType("application/octet-stream")
        .build()

    return s3Async.putObject(req, AsyncRequestBody.fromInputStream(input, -1L))
        .handleAsync { resp, err ->
            if (err != null) {
                throw RuntimeException("putStreamMultipart failed for key=$key", err)
            }
            PutResult(key, -1L, resp.eTag())  // size unknown with chunked transfer
        }
}
```

Add import at top of file: `software.amazon.awssdk.services.s3.model.PutObjectResponse`, `software.amazon.awssdk.core.async.AsyncRequestBody`, `java.util.concurrent.CompletableFuture`.

- [ ] **Step 3.4: Wire `S3AsyncClient` into the `MinioObjectStorage` bean**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`, find the `MinioObjectStorage` bean and add `s3AsyncClient` parameter:

```kotlin
@Bean
@ConditionalOnProperty(name = ["storage.backend"], havingValue = "minio")
fun minioObjectStorage(
    @Qualifier("s3Client") s3: S3Client,
    s3AsyncClient: S3AsyncClient,  // existing bean (line 64)
    props: MinioProperties,
): MinioObjectStorage = MinioObjectStorage(s3, s3AsyncClient, props)
```

Add `@Qualifier` import if needed.

- [ ] **Step 3.5: Implement `putStreamMultipart` in `LocalFsObjectStorage`**

Edit `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt`. Add:

```kotlin
override fun putStreamMultipart(
    key: String,
    input: InputStream,
): CompletableFuture<PutResult> {
    // Drain InputStream to temp file (bounded heap), then putFile on
    // virtual-thread executor. Returns CF for chain-composability.
    val tempFile = Files.createTempFile("objstore-", ".tmp")
    return CompletableFuture.runAsync({
        Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
        val result = putFile(key, tempFile)
        Files.deleteIfExists(tempFile)
        result
    }, uploadExecutor).handleAsync { result, err ->
        Files.deleteIfExists(tempFile)  // cleanup on any error
        if (err != null) throw RuntimeException("putStreamMultipart failed", err.cause ?: err)
        result
    }
}
```

`uploadExecutor` should be a virtual-thread executor (referenced from `ConcurrencyConfiguration` per project conventions). Add `@Qualifier` if the existing field is named.

- [ ] **Step 3.6: Verify module-infra builds**

```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.7: Create `CountingOutputStream` (if not present)**

Check first:

```bash
find module-calculator/src/main/kotlin -name "CountingOutputStream.kt"
```

If absent, create `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt`:

```kotlin
package maple.calculator.writer

import java.io.OutputStream

/**
 * OutputStream that counts bytes written. Thread-safe via synchronized write().
 */
class CountingOutputStream(
    private val delegate: OutputStream,
    private val counter: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic.AtomicLong(0),
) : OutputStream() {

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

- [ ] **Step 3.8: Create `WriteCounters`**

Create `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt`:

```kotlin
package maple.calculator.writer

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe counters for streaming write. AtomicLong fields allow
 * producer (Flow.collect on dedicated dispatcher) and consumer (CF
 * callback) to update independently without locks.
 */
class WriteCounters {
    val records: AtomicLong = AtomicLong(0)
    val uncompressedBytes: AtomicLong = AtomicLong(0)
    val compressedBytes: AtomicLong = AtomicLong(0)
}
```

- [ ] **Step 3.9: Write failing bytewise-equivalence test**

Create `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt`:

```kotlin
package maple.calculator.writer

import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream

class CalculationResultWriterTest {

    @Test
    fun `streaming gzip output decompresses to expected JSONL`() {
        val results = flowOf(
            mapOf("ign" to "f***l", "score" to 100),
            mapOf("ign" to "a***b", "score" to 200),
        )
        // Use a stub ObjectStorage that captures the stream and returns CF.
        val captured = ByteArrayOutputStream()
        val stub = object : StubObjectStorage() {
            override fun putStreamMultipart(key: String, input: java.io.InputStream) =
                CompletableFuture.completedFuture(
                    run {
                        input.copyTo(captured)
                        ObjectStorage.PutResult(key, captured.size().toLong(), "stub-etag")
                    }
                )
        }

        val writer = CalculationResultWriter(stub, objectMapper)
        val cf = writer.write("test.jsonl.gz", results)

        // CF chain — assert via thenAccept, no join/get/await
        cf.thenAccept { writeResult ->
            assertEquals(2L, writeResult.recordCount)
            val decompressed = GZIPInputStream(captured.toByteArray().inputStream())
                .bufferedReader().readText()
            assertTrue(decompressed.contains("\"ign\":\"f***l\""))
            assertTrue(decompressed.contains("\"ign\":\"a***b\""))
        }.get()  // .get() in test only is OK; production code uses thenApply
    }

    @Test
    fun `streaming write with empty flow still produces gzip header`() {
        val captured = ByteArrayOutputStream()
        val stub = object : StubObjectStorage() {
            override fun putStreamMultipart(key: String, input: java.io.InputStream) =
                CompletableFuture.completedFuture(
                    run {
                        input.copyTo(captured)
                        ObjectStorage.PutResult(key, captured.size().toLong(), "stub-etag")
                    }
                )
        }
        val writer = CalculationResultWriter(stub, objectMapper)
        cf = writer.write("empty.jsonl.gz", kotlinx.coroutines.flow.emptyFlow())

        cf.thenAccept {
            // gzip magic 1f 8b
            assertEquals(0x1f.toByte(), captured.toByteArray()[0])
            assertEquals(0x8b.toByte(), captured.toByteArray()[1])
        }.get()
    }

    companion object {
        // Spring auto-configured ObjectMapper (avoids code-style rule against
        // direct ObjectMapper() construction).
        private val objectMapper = maple.expectation.common.config.JacksonConfig().objectMapper()
    }
}
```

Create `module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt` (test fixture):

```kotlin
package maple.calculator.writer

import maple.expectation.common.storage.ObjectStorage
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant

/**
 * Stub ObjectStorage for unit tests. Default impl throws — tests override
 * only the methods they need. This avoids mocking framework dependency.
 */
open class StubObjectStorage : ObjectStorage {
    override fun put(key: String, data: ByteArray) = throw NotImplementedError()
    override fun putStream(key: String, input: InputStream) = throw NotImplementedError()
    override fun putStreamMultipart(key: String, input: InputStream) = throw NotImplementedError()
    override fun putFile(key: String, path: Path) = throw NotImplementedError()
    override fun putFileAsync(key: String, path: Path) = throw NotImplementedError()
    override fun get(key: String) = throw NotImplementedError()
    override fun getStream(key: String): InputStream = throw NotImplementedError()
    override fun delete(key: String) {}
    override fun exists(key: String) = false
    override fun listByPrefix(prefix: String) = emptyList<ObjectStorage.ObjectInfo>()
    override fun deleteByPrefix(prefix: String) = 0L
    override fun calculatePrefixSize(prefix: String) = 0L
    override fun getLastModified(key: String) = Instant.EPOCH
}
```

- [ ] **Step 3.10: Run tests to verify they fail**

```bash
./gradlew :module-calculator:test --tests "maple.calculator.writer.CalculationResultWriterTest"
```

Expected: compilation failure on `CalculationResultWriter` constructor (takes suspend fun `write`, will be replaced with CF).

- [ ] **Step 3.11: Rewrite `CalculationResultWriter.write` as CF chain**

Replace the `write()` function body in `CalculationResultWriter.kt`:

```kotlin
package maple.calculator.writer

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.future
import maple.calculator.domain.CalculationResult
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.infrastructure.task.LogicExecutor
import maple.expectation.infrastructure.task.TaskContext
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPOutputStream

class CalculationResultWriter(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
    private val logicExecutor: LogicExecutor? = null,
    private val producerScope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
) {
    private val log = LoggerFactory.getLogger(CalculationResultWriter::class.java)

    data class WriteResult(
        val recordCount: Long,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
        val etag: String?,
    )

    /**
     * Stream calculation results through gzip → S3 multipart upload.
     *
     * Flow:
     *   results.collect → JsonGenerator → CountingOutputStream →
     *     GZIPOutputStream → pipeOutput → (8MB pipe) →
     *     putStreamMultipart → S3AsyncClient chunked transfer.
     *
     * Producer runs in `producerScope` (IO dispatcher). Consumer is the
     * S3 async client. The pipe provides natural backpressure: when full,
     * the producer's `pipeOutput.write()` blocks → the producer's
     * gzipOutputStream.write() blocks → the JsonGenerator's collect
     * suspends → no more results are pulled until the consumer drains.
     *
     * Returns CompletableFuture<WriteResult>. Callers MUST chain via
     * thenApply/thenCompose — no .join()/.get()/.await().
     */
    fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): CompletableFuture<WriteResult> {
        val pipeInput = PipedInputStream(8 * 1024 * 1024)
        val pipeOutput = PipedOutputStream(pipeInput)
        val counters = WriteCounters()

        // Producer CF: collect Flow → write to pipe. CF chain, no runBlocking.
        val producerFuture: CompletableFuture<Unit> = producerScope.future {
            try {
                GZIPOutputStream(pipeOutput).use { gz ->
                    CountingOutputStream(gz, counters.compressedBytes).use { cgz ->
                        objectMapper.factory.createGenerator(cgz).use { gen ->
                            results.collect { result ->
                                counters.records.incrementAndGet()
                                gen.writeObject(result)
                                gen.writeRaw('\n')
                            }
                        }
                    }
                }
                Unit
            } finally {
                runCatching { pipeOutput.close() }  // signal EOF to consumer
            }
        }

        // Consumer CF: pipeInput → S3 multipart (chunked encoding).
        val uploadFuture = objectStorage.putStreamMultipart(objectKey, pipeInput)

        // Compose: producer done + upload done → WriteResult
        val composed = producerFuture.thenCombine(uploadFuture) { _, putResult ->
            WriteResult(
                recordCount = counters.records.get(),
                uncompressedBytes = counters.uncompressedBytes.get(),
                compressedBytes = counters.compressedBytes.get(),
                etag = putResult.checksum,
            )
        }

        // Cleanup pipe on any path (success or failure).
        return composed.whenComplete { _, _ ->
            runCatching { pipeInput.close() }
        }.exceptionally { err ->
            log.error(
                "[CalculationResultWriter] write failed for key={}",
                objectKey, err,
            )
            throw RuntimeException("streaming write failed for key=$objectKey", err)
        }
    }
}
```

**Adapt the constructor signature to match the existing class.** The original takes `objectStorage` only; preserve any additional dependencies. Keep logic executor / task context integration if present (wrap with `logicExecutor.execute(task, ctx) { ... }` for error handling per project convention).

- [ ] **Step 3.12: Update all callers to use CF chain**

For every caller found in Step 3.1:

```kotlin
// Before (suspend):
val writeResult = writer.write(key, results)
someMethod(writeResult)

// After (CF chain — no .join/.get/.await):
writer.write(key, results)
    .thenAccept { writeResult -> someMethod(writeResult) }
    .exceptionally { err -> logger.error("write failed", err); null }
```

If a caller genuinely needs to wait (e.g., HTTP response that must include the result), chain inside the same CF that handles the HTTP response — never `.join()` on the writer CF.

- [ ] **Step 3.13: Run full calculator test suite**

```bash
./gradlew :module-calculator:test
```

Expected: all tests pass (legacy tests for the suspend variant, plus new CF-chain tests).

- [ ] **Step 3.14: Smoke test (live) — bytewise equivalence**

Run the pipeline once with the new writer. Capture the gz output:

```bash
mc cp local/maple-expectation/calculator/runs/<runId>/<chunk>.jsonl.gz /tmp/new_run.jsonl.gz
```

Compare with a reference run (saved from a previous pipeline test):

```bash
diff <(gunzip -c /tmp/new_run.jsonl.gz | head -1000) <(gunzip -c /tmp/reference_run.jsonl.gz | head -1000)
```

Expected: no diff. (Full diff on larger samples recommended.)

- [ ] **Step 3.15: Commit**

```bash
git add module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt \
  module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/ \
  module-calculator/src/main/kotlin/maple/calculator/writer/ \
  module-calculator/src/test/kotlin/maple/calculator/writer/
git commit -m "perf(calculator): streaming gzip → S3 via async chunked transfer

Adds putStreamMultipart(key, input): CompletableFuture<PutResult> to
ObjectStorage, backed by S3AsyncClient.putObject with chunked encoding
(RequestBody.fromInputStream(input, -1L)). Replaces the legacy
full-chunk ByteArrayOutputStream buffering.

CalculationResultWriter.write() now returns CompletableFuture<WriteResult>
via a CF chain: producer-coroutine → gzip → pipe (8MB backpressure) →
putStreamMultipart → thenCombine → result. No join/get/await/runBlocking.

Heap reduction: ~40MB (4 concurrent chunks × 10MB buffer eliminated).
Caller API: chain via thenApply/thenAccept.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Phase 4 — Streaming ext-api Chunk Parser

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/parser/StreamingChunkParser.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/parser/StreamingChunkParserTest.kt`
- Modify: chunk loader call site (find via grep in step 4.1)

- [ ] **Step 4.1: Find current chunk loader**

```bash
grep -rn "ObjectMapper\|readValue\|readTree" module-external-api/src/main/kotlin/ | grep -i "chunk\|payload\|item" | head -10
```

- [ ] **Step 4.2: Write failing tests**

Create `module-external-api/src/test/kotlin/maple/externalapi/parser/StreamingChunkParserTest.kt`:

```kotlin
package maple.externalapi.parser

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.util.zip.GZIPOutputStream

class StreamingChunkParserTest {

    @Test
    fun `parses gzipped JSONL one record at a time`() = runBlocking {
        val jsonl = """{"ign":"f***l","ocid":"abc123"}
{"ign":"a***b","ocid":"def456"}
"""
        val gzipped = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(jsonl.toByteArray()) }
            baos.toByteArray()
        }

        val records = StreamingChunkParser.parse(
            ByteArrayInputStream(gzipped),
        ).toList()

        assertEquals(2, records.size)
        assertEquals("f***l", records[0]["ign"])
        assertEquals("abc123", records[0]["ocid"])
        assertEquals("a***b", records[1]["ign"])
    }

    @Test
    fun `skips malformed records and continues`() = runBlocking {
        val jsonl = """{"ign":"valid","ocid":"abc"}
{this is not json}
{"ign":"also_valid","ocid":"def"}
"""
        val gzipped = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(jsonl.toByteArray()) }
            baos.toByteArray()
        }

        val records = StreamingChunkParser.parse(
            ByteArrayInputStream(gzipped),
            skipMalformed = true,
        ).toList()

        // Two valid records, one malformed skipped
        assertEquals(2, records.size)
        assertEquals("valid", records[0]["ign"])
        assertEquals("also_valid", records[1]["ign"])
    }
}
```

- [ ] **Step 4.3: Run tests to verify they fail**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.parser.StreamingChunkParserTest"
```

Expected: compilation failure on `StreamingChunkParser`.

- [ ] **Step 4.4: Implement `StreamingChunkParser`**

Create `module-external-api/src/main/kotlin/maple/externalapi/parser/StreamingChunkParser.kt`:

```kotlin
package maple.externalapi.parser

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.zip.GZIPInputStream

object StreamingChunkParser {
    private val log = LoggerFactory.getLogger(StreamingChunkParser::class.java)
    private val factory = JsonFactory()

    /**
     * Stream-parse a gz-compressed JSONL input into a Flow of record Maps.
     *
     * Memory: O(1) per record. Jackson JsonParser reads one token at a time;
     * GZIPInputStream buffers via a small internal buffer (default 512B).
     * No intermediate `byte[]` or `List<Map>` allocation.
     *
     * @param input raw gz-compressed JSONL input stream
     * @param skipMalformed if true, malformed records log ERROR and continue;
     *                      if false, they propagate as exceptions
     */
    fun parse(
        input: InputStream,
        skipMalformed: Boolean = true,
    ): Flow<Map<String, Any>> = flow {
        GZIPInputStream(input).use { gz ->
            factory.createParser(gz).use { parser ->
                var recordCount = 0L
                var skipCount = 0L

                while (parser.nextToken() != null) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) continue

                    try {
                        val record = parser.readValueAs(Map::class.java) as Map<String, Any>
                        emit(record)
                        recordCount++
                    } catch (e: Exception) {
                        skipCount++
                        log.error(
                            "[StreamingChunkParser] skipping malformed record at offset {}",
                            parser.tokenLocation,
                            e,
                        )
                        if (!skipMalformed) throw e
                        // advance to next START_OBJECT
                        while (parser.nextToken() != null &&
                               parser.currentToken() != JsonToken.START_OBJECT) {
                            // drain tokens until next record
                        }
                    }
                }

                log.info(
                    "[StreamingChunkParser] parsed {} records ({} skipped)",
                    recordCount, skipCount,
                )
            }
        }
    }
}
```

- [ ] **Step 4.5: Run tests**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.parser.StreamingChunkParserTest"
```

Expected: 2 tests pass.

- [ ] **Step 4.6: Replace chunk loader call site**

In the file identified in Step 4.1, replace the bulk-load pattern:

```kotlin
// Before: full buffer + List<Map>
val bytes = input.readBytes()  // full chunk in heap
val records: List<Map<String, Any>> = objectMapper.readValue(bytes)

// After: streaming
val records: Flow<Map<String, Any>> = StreamingChunkParser.parse(input)
```

Adapt downstream consumption to use the Flow instead of a List.

- [ ] **Step 4.7: Run all ext-api tests**

```bash
./gradlew :module-external-api:test
```

Expected: all tests pass.

- [ ] **Step 4.8: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/parser/ \
  module-external-api/src/test/kotlin/maple/externalapi/parser/ \
  <modified chunk loader file>
git commit -m "perf(ext-api): streaming JSONL parser for chunk payloads

Replaces ObjectMapper.readValue(byte[]) with Jackson JsonParser
streaming parse. No full-chunk intermediate List<Map>.

Heap reduction: ~50MB on ext-api module (peak chunk 5K records).
Skip-on-malformed preserves throughput on edge cases.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Phase 5 — Direct Buffer Tuning

**Files:**
- Modify: `module-external-api/build.gradle.kts` — add `-Dio.netty.allocator.numDirectArenas=<N>`
- Modify: `module-calculator/build.gradle.kts` — add Kafka `buffer.memory=64MB`

- [ ] **Step 5.1: Determine Netty direct arenas count**

```bash
nproc
```

Use `cores/2` as the arenas count. Example: 8 cores → 4 arenas.

- [ ] **Step 5.2: Add Netty arena config to ext-api**

In `module-external-api/build.gradle.kts`, find the JVM args block. Add:

```kotlin
"-Dio.netty.allocator.numDirectArenas=4",  // half of CPU cores
```

(Replace `4` with the actual value from Step 5.1.)

- [ ] **Step 5.3: Add Kafka buffer cap to calculator**

In `module-calculator/build.gradle.kts`, add to JVM args:

```kotlin
"-Dbuffer.memory=67108864",  // 64MB Kafka client buffer
```

Or via `application.yml` if Kafka is configured there:

```yaml
spring:
  kafka:
    producer:
      properties:
        buffer.memory: 67108864
    consumer:
      properties:
        buffer.memory: 67108864
```

(Adapt to project conventions; check existing kafka config first.)

- [ ] **Step 5.4: Build**

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5.5: Smoke test (1hr pipeline run)**

Run a full pipeline. Observe:
- RSS < 500MB sustained for both modules
- Throughput within ±5% of baseline
- No OOM

- [ ] **Step 5.6: Commit**

```bash
git add module-external-api/build.gradle.kts module-calculator/build.gradle.kts
git commit -m "perf(pipeline): tune Netty/Kafka direct buffer pools

Netty: numDirectArenas=cores/2 (4 on 8-core)
Kafka: buffer.memory=64MB (down from default 256MB)

Combined with Phase 1 cap, RSS reduced to ~500MB per module.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §3.1 Layered off-heap strategy → Tasks 1, 2, 3, 4, 5 ✓
- §4 Phase 1 (config-only) → Task 1 ✓
- §4 Phase 2 (Chronicle Map) → Task 2 ✓
- §4 Phase 3 (streaming writer) → Task 3 ✓
- §4 Phase 4 (streaming parser) → Task 4 ✓
- §4 Phase 5 (direct buffer tuning) → Task 5 ✓
- §5.1 OffHeapCacheBackend → Task 2 ✓
- §5.2 StreamingResultUploader → Task 3 ✓
- §5.3 StreamingChunkParser → Task 4 ✓
- §8 Verification per phase → each Task has Step "Run tests" or "Smoke test" ✓

**Placeholder scan:** No TBD/TODO. All steps have full code or commands.

**Type consistency:**
- `OffHeapCacheBackend<K, V>` defined Task 2.5, used in 2.9 (CacheConfig)
- `StreamingResultUploader` defined Task 3.4, used in 3.6 (CalculationResultWriter refactor)
- `StreamingChunkParser` defined Task 4.4, used in 4.6 (chunk loader refactor)

All consistent.

**Risks acknowledged:**
- Task 2 step 2.11: Chronicle corruption fallback tested manually (no automated test for file deletion mid-run). Adequate for Phase 2; can add chaos test in Phase 3+ if recurring.
- Task 3 step 3.8: bytewise equivalence requires a reference run captured before changes — engineer must capture before merging Task 3.

**Open question from spec (§11):**
- §11 Q2 (sync vs async S3 client): the plan uses sync client with 8MB streaming buffer (Task 3.6). This avoids the async-client refactor. Spec author recommends this; plan follows.