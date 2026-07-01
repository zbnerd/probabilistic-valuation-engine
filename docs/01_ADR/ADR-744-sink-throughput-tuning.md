# ADR-744: Sink Throughput Tuning — Kafka Batching + MinIO Multipart + Time-Based Chunk Flush

- Status: Accepted
- Date: 2026-07-02
- Owner: pipeline-sre

## 1. Background / Problem

### Background

Per the endurance test report (`docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md`), 80h observation confirmed a sustained throughput ceiling of 100–150 users/s for the ITEM_EQUIPMENT phase. Three bottlenecks were identified, all in the ext-api snapshot drain path:

1. **Kafka producer** uses defaults (`linger.ms=0`, `batch.size=16KB`, `compression.type=none`, `enable.idempotence=false`). The `chunk-ready` event is fire-and-forget per chunk.
2. **MinIO upload** runs through `S3TransferManager` with default 5 MB part size and unbounded concurrency. Single-node MinIO plus a 50-thread part-upload pool creates noisy contention on the writer thread's await.
3. **Chunk rotation** is purely size-based (`recordCount >= maxRecords || uncompressedBytes >= maxUncompressedBytes`). During low-rate periods the current chunk sits open indefinitely, delaying the chunk-ready publish and downstream consumption.

Multi-writer was considered and rejected: writer-thread context switching on the hot path is empirically a net loss for our load profile.

### Problem

Sink-side latency (sink_submit p99 = 2.17 s during burst) is gating fetcher throughput. Fetcher is otherwise healthy (failed=0, fetchJoinMs 1.16 s p50).

### Goal

Reduce sink_submit latency variance and burst drain rate without changing fetcher concurrency.

## 2. Decision

> **Tune Kafka producer batching + MinIO multipart transfer + introduce a 1 s idle-time chunk flush.**

```text
[ChunkedSnapshotSink.runWriterLoop]
   ↓ queue.poll(1000ms)  ← new: idle tick
   ├─ record arrived → append + size-based rotate check
   └─ timeout (no record) → rotateChunk + publishWhenUploaded   ← new

[Kafka producer (auto-configured from spring.kafka.producer)]
   ↓ linger.ms=50, batch.size=128 KB, compression.type=lz4, idempotence=on

[S3TransferManager]
   ↓ partSize=8 MB, multiPartConcurrency=10
```

## 3. Trade-offs

### Sensitivity

- Single-node MinIO backend → high part-concurrency stalls on disk IO.
- Chunk flush interval (1 s) → trade chunk count vs chunk-rotation latency.
- Kafka linger.ms (50 ms) → adds 50 ms p50 latency to chunk-ready events; offsets by halving broker request count.

### Trade-off

| Choice | Get | Give |
| --- | --- | --- |
| Kafka batching (linger=50ms) | -50% broker requests, lz4 wire-compression | +50ms p50 chunk-ready publish latency |
| MinIO partSize=8MB / concurrency=10 | Parallel part uploads bounded; CPU friendly | Larger part overhead per failed PUT (retry 8MB not 5MB) |
| 1 s idle-tick chunk flush | Chunk-ready publish at low-rate intervals | +1 PUT/chunk at low traffic (idle) |
| `enable.idempotence=true` | No duplicate chunk-ready events on retry | `acks=all` hard requirement (already met) |

### Risk

- **lz4 codec** must be on classpath. If absent, fallback to `snappy` or `gzip`.
- **Time-based rotation** writes an extra chunk per idle period — under burst load it is never triggered, so net write count is unchanged.
- **Poll-based writer loop** uses `poll(1000ms)` instead of `take()`. Poll returns immediately when records arrive — no latency added under load.

### Non-Risk

- **F-side unchanged**: semaphore, HTTP pool, rate-limit caps untouched. Fetcher ceiling preserved.
- **DB-side unchanged**: synchronizer / calculator path unaffected.
- **Manifest / cleanup path unchanged**: chunk rotation triggers the existing `closeCurrentChunk()` + `publishWhenUploaded()` + `awaitAllUploadsAsync()` flow.

## 4. Result / Evidence

### Metrics

| Metric | Before | Target |
| --- | --- | --- |
| `external_api_snapshot_sink_submit_seconds` p99 | 2174 ms | <500 ms |
| `external_api_snapshot_sink_queue_depth_max` | 3000 (cap) | <1500 typical |
| ext-api users/s sustained | 100–150 | 150–250 |
| MinIO PUTs per chunk | 1 sequential | 1 with parallel parts |
| Kafka producer request rate | baseline | -50% |

### Observed Result

To be measured via load test (`/pipeline-test`) after deploy. Compare with the endurance report baseline.

## 5. Summary

> Sink drain latency gates the fetcher ceiling. Tune three independent layers (Kafka batching, MinIO multipart, time-based chunk flush) without touching fetcher concurrency or worker thread count.

## Implementation Plan

1. **Kafka producer (yaml-only):** add `linger.ms=50`, `batch.size=131072`, `compression.type=lz4`, `enable.idempotence=true`, `max.in.flight=5` to `spring.kafka.producer.properties.*`.
2. **MinIO multipart (yaml + 1 bean edit):** add `part-size-bytes`, `multi-part-concurrency` to `MinioProperties`; tune `S3TransferManager.builder()` in `StorageConfig.kt:107-115`.
3. **Chunk time flush (code + yaml):** add `maxChunkAgeMs: Long = 1000L` to `EndpointChunkConfig`; switch `runWriterLoop()` from `queue.take()` to `queue.poll(1000ms)`; on `null` (idle), call `fileManager.rotateChunk()` if writer non-empty.
4. **.env append** (no overwrite) per `.claude/rules/critical-rules.md`.

## References

- `docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md`
- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt`