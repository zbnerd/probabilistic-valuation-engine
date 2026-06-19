# Issue #1312 — Streaming Calculator Writer + CF-Chain SnapshotChunkProcessor

- Date: 2026-06-19
- Status: Draft (pending user review)
- Owner: maple-pipeline
- Parent: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 3
- Issue: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1312

---

## 1. Background / Problem

### Background

Per the parent off-heap streaming spec, `CalculationResultWriter.write()` buffers the entire chunk in `ByteArrayOutputStream` before gzipping and uploading. With `CHUNK_PROCESS_PERMITS=4`, peak heap = 4 × chunk-size (~10MB each, ~40MB total). This is one of three load-bearing hot-path heap consumers identified in the 2026-06-19 diagnose run.

The parent spec §4 Phase 3 proposed a streaming rewrite using a `PipedInputStream` bridge and the existing sync `ObjectStorage.putStream` — but a follow-up code review found that `MinioObjectStorage.putStream` itself calls `input.readBytes()` (full ByteArray drain, see `MinioObjectStorage.kt:99`), so a pipe gives zero heap reduction.

The 2026-06-19 plan rewrite (`522ef07ba docs(plan): rewrite Task 3 to use S3AsyncClient chunked transfer + CF chain`) proposed `S3AsyncClient.putObject` with `AsyncRequestBody.fromInputStream(input, -1L)` (chunked transfer encoding, async-only) and converting `write()` to return `CompletableFuture<WriteResult>`.

The only caller of `write()` is `SnapshotChunkProcessor.process()` (line 91-93), which uses `async { write() }.await()` inside `coroutineScope`. The caller migration to CF was identified as the highest-risk design decision.

### Problem

Replace the legacy `ByteArrayOutputStream`-based gzip+upload with streaming pipe → S3 chunked transfer. Eliminate the 4×chunk-size heap peak. Migrate the caller to a CF-based pipeline that preserves the `suspend fun` contract of `process()`.

### Goal

- Heap reduction: calculator heap < 200MB sustained (from ~414MB baseline)
- Bytewise equivalence: gzipped JSONL output identical to current implementation
- No `join()`/`get()`/`runBlocking()` in production code
- Caller migration: single `.await()` at the coroutine→CF boundary (acceptable per project convention for return-value bridges)

---

## 2. Decision

> **Streaming gzip → S3 chunked transfer via CF chain, with Flow-internal + CF-at-boundary caller migration.**

### Architecture

**Writer (CF chain, no suspend):**

```
Flow<CalculationResult>
  → producerScope.future { collect }              (IO dispatcher)
  → JsonGenerator → CountingOutputStream → GZIPOutputStream
  → PipedOutputStream → 8MB PipedInputStream      (backpressure)
  → ObjectStorage.putStreamMultipart              (S3AsyncClient chunked | LocalFs temp-file)
  → thenCombine { _, putResult → WriteResult }
  → whenComplete { pipe cleanup }
  → exceptionally { log + re-throw }
```

**Caller (Flow internally, CF at boundary):**

```kotlin
suspend fun process(event, key): ChunkResult = coroutineScope {
    launch { readLines(parseDispatcher) }   // unchanged
    launch { parseLines(parseDispatcher) } // unchanged
    launch { processItems(calcDispatcher) } // unchanged
    resultChannel.close()                    // unchanged

    val writeResult = resultWriter.write(key, channelAsFlow(resultChannel)).await()
    //                                                 ^ single .await() at boundary
    ChunkResult(..., writeResult.objectKey, writeResult.resultCount, ...)
}
```

### Public interface changes

| File | Change |
|------|--------|
| `ObjectStorage.kt` | Add `putStreamMultipart(key, input): CompletableFuture<PutResult>`. Additive; `putStream` retained for legacy callers. |
| `CalculationResultWriter.kt` | `write()` returns `CompletableFuture<WriteResult>` (was `suspend fun` + sync `WriteResult`). `WriteResult` gains `etag: String?`. |
| `SnapshotChunkProcessor.kt` | Caller migrated to single `.await()` at boundary. No signature change. |

---

## 3. Components

### 3.1 New code (module-common)

`ObjectStorage.putStreamMultipart(key, input): CompletableFuture<PutResult>` — additive, throws if not implemented.

### 3.2 New / modified code (module-infra)

**`MinioObjectStorage`:**
- Ctor adds `s3Async: S3AsyncClient` param
- `putStreamMultipart` impl:
  ```kotlin
  s3Async.putObject(req, AsyncRequestBody.fromInputStream(input, -1L))
      .handleAsync { resp, err ->
          if (err != null) throw RuntimeException("putStreamMultipart failed key=$key", err)
          PutResult(key, -1L, resp.eTag())  // size unknown with chunked encoding
      }
  ```
- S3 SDK RetryPolicy.defaultRetryPolicy (3 retries) — inherited from existing client config

**`LocalFsObjectStorage`:**
- Ctor adds `uploadExecutor: Executor` (virtual-thread, wired from `ConcurrencyConfiguration`)
- `putStreamMultipart` impl:
  ```kotlin
  val temp = Files.createTempFile("objstore-", ".tmp")
  CompletableFuture.supplyAsync({
      Files.copy(input, temp, REPLACE_EXISTING)
      putFile(key, temp)
  }, uploadExecutor).whenComplete { _, _ ->
      Files.deleteIfExists(temp)  // single cleanup, runs on success + failure
  }
  ```

**`StorageConfig.minioObjectStorage` bean:** inject existing `s3AsyncClient` bean (already wired at line 64).

### 3.3 New / modified code (module-calculator)

**`CountingOutputStream.kt`** (new, public) — promoted from private nested class in writer. Uses `AtomicLong counter` for thread-safety. Replaces buggy nested impl (`CalculationResultWriter.kt:79-103`).

**`WriteCounters.kt`** (new) — `AtomicLong records / uncompressedBytes / compressedBytes`. Thread-safe.

**`CalculationResultWriter.kt`** (rewrite):
- Ctor: `(objectStorage, objectMapper, producerScope = CoroutineScope(Dispatchers.IO + SupervisorJob()))`
- `write(key, flow): CompletableFuture<WriteResult>`
- Producer: `producerScope.future { Flow.collect → JsonGen → CountingOS → GZIPOS → pipe }`
- Consumer: `objectStorage.putStreamMultipart(key, pipeIn)`
- Compose: `producerFuture.thenCombine(uploadFuture) { _, putResult → WriteResult(...) }`
- Cleanup: `whenComplete { runCatching { pipeIn.close() } }`
- Error: `exceptionally { log + throw new RuntimeException("streaming write failed key=$key", it) }`
- `WriteResult` field rename: keep `resultCount`, add `etag: String?` from `putResult.checksum`

**`SnapshotChunkProcessor.kt`** (modify caller):
- `val writeFuture = resultWriter.write(key, channelAsFlow(resultChannel))` — called BEFORE `coroutineScope` block, so the write starts draining `resultChannel` concurrently with the parse+calc workers (preserves the overlap the original `async { write() }` had)
- `coroutineScope { launch { readLines } + launch { parseLines } + launch { processItems } }` — workers fill `resultChannel`; `processItems` closes it after draining `itemChannel` (unchanged)
- `val writeResult = writeFuture.await()` — single `.await()` at the coroutine→CF boundary
- No structural change to parallel pipeline

### 3.4 New test code (module-calculator)

- `CountingOutputStreamTest.kt` — unit test for the promoted public class
- `WriteCountersTest.kt` — concurrency test for the counters
- `CalculationResultWriterTest.kt` — bytewise equivalence + error path
- `StubObjectStorage.kt` — `open class` test fixture, default-then-override
- `SnapshotChunkProcessorTest.kt` — end-to-end with stub ObjectStorage + stub parser

---

## 4. Data flow

### Producer (writer hot path)

1. `write(key, flow)` invoked by caller
2. Allocate `PipedInputStream(8MB)` + `PipedOutputStream(pipe)`, `WriteCounters`
3. `producerScope.future { ... }` starts coroutine on `Dispatchers.IO`
4. Coroutine body: `GZIPOutputStream(pipeOut).use { gz → CountingOutputStream(gz, compressedBytes).use { cgz → JsonGenerator(cgz).use { gen → flow.collect { gen.writeObject(it); gen.writeRaw('\n') } } } }`
5. `pipeOut.close()` in `finally` → EOF for consumer
6. Producer returns `Unit` → `producerFuture` completes

### Consumer (Minio)

1. `s3Async.putObject(req, AsyncRequestBody.fromInputStream(pipeIn, -1L))`
2. `SdkChunkedEncodingInputStream` wraps `pipeIn` → 5MB chunks → multipart upload (auto)
3. `handleAsync { resp, err → if (err != null) throw ...; PutResult(key, -1L, resp.eTag()) }`

### Consumer (LocalFs)

1. `CompletableFuture.supplyAsync({ Files.copy(pipeIn, temp, REPLACE_EXISTING); putFile(key, temp) }, uploadExecutor)`
2. `.whenComplete { _, _ → Files.deleteIfExists(temp) }` — single cleanup

### Compose

1. `producerFuture.thenCombine(uploadFuture) { _, putResult → WriteResult(records.get(), uncompressedBytes.get(), compressedBytes.get(), putResult.checksum) }`
2. `.whenComplete { _, _ → runCatching { pipeIn.close() } }`
3. `.exceptionally { log + throw RuntimeException("streaming write failed key=$key", it) }`

### Caller

1. `val writeFuture = resultWriter.write(key, channelAsFlow(resultChannel))` — starts CF immediately, returns `CompletableFuture<WriteResult>`. Producer coroutine begins draining `resultChannel` in the background.
2. `coroutineScope { launch { readLines } + launch { parseLines } + launch { processItems } }` — workers fill `resultChannel`; `processItems` closes it after draining `itemChannel` (unchanged).
3. `val writeResult = writeFuture.await()` — single `.await()` at the coroutine→CF boundary. By this point, writeFuture is likely already complete (write drains channel as it fills, so it finishes shortly after the last calcWorker exits).
4. Build `ChunkResult(... writeResult.objectKey/resultCount/uncompressedBytes/compressedBytes/etag)` and return

> **Critical:** `write()` must be called BEFORE entering `coroutineScope` block, not after. This preserves the overlap with parse+calc workers — the same overlap the original `async { write() }` had.

### Backpressure

When pipe fills (8MB):
- `pipeOut.write()` blocks
- `GZIPOutputStream` blocks
- `JsonGenerator` blocks
- `Flow.collect` suspends
- Producer's `Dispatchers.IO` thread parks
- Consumer (S3) drains at its own rate
- When S3 stalls, pipe stays full, producer parks — no unbounded heap growth

---

## 5. Error handling

| Failure | Behavior |
|---------|----------|
| Flow.collect throws (serialization error, cancelled scope) | Coroutine completes exceptionally → `producerFuture` propagates. `pipeOut.close()` runs in `finally` → EOF for consumer. |
| GZIPOutputStream fails | Same as above. |
| Pipe write blocks (S3 stalled) | Producer parks on `pipeOut.write()`. S3 backpressure. Heap bounded by pipe (8MB) + 1 in-flight gz buffer (4KB). |
| S3 putObject fails | `handleAsync` throws `RuntimeException("putStreamMultipart failed key=$key", err)`. `uploadFuture` completes exceptionally. `whenComplete` pipe cleanup runs. |
| S3 mid-upload abort | S3 SDK RetryPolicy.defaultRetryPolicy (3 retries). 3 fails → `RuntimeException` propagates. |
| LocalFs putFile fails (disk full, permission) | `supplyAsync` completes exceptionally. `whenComplete` cleanup runs (temp deleted). |
| Producer ok + upload fails | `thenCombine` propagates upload exception. `whenComplete` pipe cleanup runs. `exceptionally` logs + re-throws. |
| Caller `await()` throws in `SnapshotChunkProcessor` | `process()` propagates. Kafka message NACK (existing path). |
| S3AsyncClient bean not wired (test profile) | Spring boot fails to start `MinioObjectStorage` ctor — only if `storage.backend=minio`. Local profile unaffected. |

### Cleanup invariants

- **Pipe:** `whenComplete { runCatching { pipeIn.close() } }` — runs on success + failure
- **Producer:** `try/finally { runCatching { pipeOut.close() } }` — EOF guaranteed before uploadCF depends on it
- **Temp file (LocalFs):** single `whenComplete` cleanup, no race
- **Semaphore:** N/A (pipe provides backpressure)

### Logging

- One log per chunk (existing convention): `[Writer] wrote calculator result chunk: objectKey={} results={} uncompressedBytes={} compressedBytes={}`
- On error: `[CalculationResultWriter] write failed for key={}` with stack trace

---

## 6. Testing

### Unit tests

1. **`CountingOutputStreamTest`** (module-calculator):
   - `write(Int) increments counter`
   - `write(ByteArray, off, len) increments by len`
   - `count is thread-safe under concurrent writes` (AtomicLong)

2. **`WriteCountersTest`** (module-calculator):
   - `concurrent increment from N threads yields sum`

3. **`CalculationResultWriterTest`** (module-calculator):
   - `streaming gzip output decompresses to expected JSONL` — bytewise compare with reference
   - `streaming write with empty flow produces gzip header (1f 8b)` — handles empty
   - `write with failing ObjectStorage propagates exception via exceptionally` — error path
   - `write counters track records/uncompressed/compressed correctly` — 100 records, verify counts

4. **`SnapshotChunkProcessorTest`** (module-calculator):
   - `process() returns ChunkResult with writeResult populated` — verify CF chain
   - `process() propagates write failure` — error path

5. **`StubObjectStorage`** (test fixture): `open class` with `NotImplementedError` defaults, captures InputStream for assertion

### Test infrastructure

- `ObjectMapper` injected via Spring test config (`@TestConfiguration`) — no direct `JacksonConfig().objectMapper()` (fixes plan bug)
- All async assertions use `cf.thenAccept { ... }.get()` in tests only (acceptable per code-style.md)

### Live smoke (after deploy)

```bash
# Capture reference run BEFORE deploy
mc cp local/maple-expectation/calculator/runs/<runId>/<chunk>.jsonl.gz /tmp/reference.jsonl.gz

# Deploy + run pipeline
# Capture new run
mc cp local/maple-expectation/calculator/runs/<runId>/<chunk>.jsonl.gz /tmp/new.jsonl.gz

# Bytewise diff
diff <(gunzip -c /tmp/new.jsonl.gz | head -1000) <(gunzip -c /tmp/reference.jsonl.gz | head -1000)
# Expected: no diff

# Heap check
curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{application="calculator"}' | jq
# Expected: heap < 200MB sustained
```

### Verification commands (per workflow-rules.md §10)

```bash
./gradlew :module-calculator:test :module-infra:test --continue
# Boot + live API
./gradlew :module-calculator:bootRun
curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
grep "Calculation completed" module-calculator/logs/app.log | tail -5
grep "ERROR" module-calculator/logs/app.log | tail -10  # must be empty
```

---

## 7. Critical files

| File | Change |
|------|--------|
| `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` | Add `putStreamMultipart` |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt` | Add `s3Async` ctor param + `putStreamMultipart` impl |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt` | Add `uploadExecutor` ctor param + `putStreamMultipart` impl |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt` | Wire `s3AsyncClient` into `minioObjectStorage` bean |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt` | Add `uploadExecutor` bean (virtual thread, `Executors.newVirtualThreadPerTaskExecutor()`) if not present |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` | Rewrite to CF chain |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt` | New public class |
| `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt` | New public class |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` | Caller migration (single `.await()`) |
| `module-calculator/build.gradle.kts` | Verify `kotlinx-coroutines-jdk8` dep present (required for `CoroutineScope.future {}`) |
| `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt` | New test |
| `module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt` | New test fixture |
| `module-calculator/src/test/kotlin/maple/calculator/processor/SnapshotChunkProcessorTest.kt` | New test |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt` | New test for `putStreamMultipart` |
| `docs/superpowers/specs/2026-06-19-issue-1312-streaming-writer-cf-chain-design.md` | This file |

---

## 8. Open questions

None — caller migration and parallelism model resolved during brainstorming (CF-based + Flow internally).

## 9. Non-risks

- **Heap: same 8MB bound** as parent spec §4 Phase 3 target. No change.
- **S3 chunked transfer:** proven async-only per existing `MinioObjectStorage.putStream` comment (lines 76-110). `S3AsyncClient` bean already wired (`StorageConfig.kt:64`).
- **Caller signature:** `process()` remains `suspend fun ChunkResult`. No API break for downstream callers.
- **Backwards compatibility:** `ObjectStorage.putStream` retained. `CalculationResultWriter.write` API change is internal (single caller).

## 10. Trade-offs

### Sensitivity

- **Pipe buffer size (8MB):** sensitive to chunk size (1.4-10MB target). 8MB ≥ largest expected chunk; smaller risks producer stalls, larger risks heap overshoot.
- **S3 chunked transfer retry policy:** 3 retries inherited from existing `RetryPolicy.defaultRetryPolicy`. Sensitive to MinIO availability.
- **LocalFs `uploadExecutor`:** virtual-thread executor from `ConcurrencyConfiguration`. Sensitive to concurrency tuning if many concurrent chunks.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| CF chain (not suspend) | Pure async, composable, no thread handoffs | Caller migration complexity |
| `PipedInputStream` 8MB | Natural backpressure, no explicit semaphore | Single producer/consumer thread; not parallelizable |
| Single `.await()` at caller boundary | Caller stays `suspend fun` | One thread park per chunk (acceptable; not a hot path) |

### Risks

- **`PipedInputStream` thread affinity:** `pipeOut.write()` and `pipeIn.read()` must run on different threads. If both run on `Dispatchers.IO` and the IO pool is exhausted, dead-lock. Mitigation: producer uses `Dispatchers.IO`, consumer runs on S3 SDK's internal netty threads (separate pool).
- **LocalFs temp file on crash:** if JVM crashes mid-upload, temp file orphaned. Mitigation: JVM shutdown hook deletes `/tmp/objstore-*.tmp`. Optional: use a known directory with periodic cleanup.
- **CF composition overhead:** each `.thenCombine` allocates a small object. For 4 concurrent chunks × 5 stages, ~20 small allocations. Negligible.

### Non-risks

- **S3 SDK version:** existing `S3AsyncClient` bean uses the same SDK version as `S3Client`. No dependency change.
- **Object storage backend selection:** both Minio and LocalFs paths updated. Profile switch (`storage.backend=local|minio`) unchanged.
- **Existing test suite:** only writer + processor tests affected. All other modules unchanged.

---

## 11. Summary

> Replace `CalculationResultWriter.write()`'s `ByteArrayOutputStream` buffering with a CF chain: Flow → gzip → 8MB pipe → `ObjectStorage.putStreamMultipart` (S3AsyncClient chunked transfer | LocalFs temp-file). Migrate the single caller to single-`.await()` at the coroutine→CF boundary. Heap reduction: 4×chunk-size peak eliminated (~40MB).
