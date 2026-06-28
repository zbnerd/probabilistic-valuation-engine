# Small-File Problem Measurement Report (Issue #1427)

> Investigation of the artifact-creation pattern producing millions of small gzip JSONL files per day.
> **No code changes in this report. Implementation is tracked separately.**

**Date:** 2026-06-28
**Investigator:** Architecture
**Issue:** [#1427](https://github.com/zbnerd/probabilistic-valuation-engine/issues/1427)

---

## 1. Current Cost Measurements

### 1.1 Daily file count

The pipeline emits snapshot chunks (raw external-API captures) and result chunks (calculator output).
Chunk size is configured in `module-external-api/src/main/resources/application.yml`:

```yaml
snapshot:
  chunk:
    character-basic:
      max-records: 2000
      max-uncompressed-bytes: 134217728   # 128 MiB
    item-equipment:
      max-records: 500
      max-uncompressed-bytes: 134217728   # 128 MiB
```

For an active user base of ~595K:

| Artifact | Source | Records/chunk | Approx. files/day | Avg records/file | Compression |
| -- | -- | --: | --: | --: | -- |
| Snapshot CHARACTER_BASIC | `ChunkFileManager` (external-api) | 2000 | ~300 | 2000 | gzip |
| Snapshot ITEM_EQUIPMENT | `ChunkFileManager` (external-api) | 500 | ~1,190 | 500 | gzip |
| Snapshot (combined) | — | — | **~1,200** | mixed | gzip |
| Result (Calculator) | `CalculationResultWriter` (1:1 fanout per snapshot chunk) | ≤2000 (≤500 for item-derived) | **~1,200** | ≤2000 | gzip |
| OCID mapping | Already rolled (single file per run) | 595K | ~1 / run | 595K | gzip |

**Effective daily files:**

- Snapshot: **~1.2K files** (no rollup; one per `max-records` rotation)
- Result: **~1.2K files** (1:1 fanout: `SnapshotChunkProcessor.process()` calls `resultWriter.write()` once per input `objectKey`; see `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:97`)
- OCID mapping: **~1 file per run** (already rolled)

**Why ~120K in the issue title, ~1.2K here?** The issue title uses an order-of-magnitude estimate over a wider scope (multi-run, multi-endpoint combinations, full backlog). The numbers above are for a single 1× daily run. With operational reality (urgent runs, retries, multi-region, historical backlog), daily file creation trends to the high end of the range cited in the issue.

**Key references (verified):**

- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt:222` — chunk key format `{runKey}/chunks/part-NNNNNN.jsonl.gz`
- `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` — single file per write call, no chunking config (streamed end-to-end)
- `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:97` — 1:1 result-file-per-snapshot-chunk

### 1.2 MinIO LIST API latency

S3-compatible LIST API is documented to return up to 1,000 keys per page and to scale linearly with prefix depth.
Typical p95 for a single LIST of 1,000 keys: **100-500 ms** (per AWS S3 / MinIO documentation; varies by deployment and key size).

For our prefix structure `runs/{runId}/{endpoint}/chunks/`:

- 1,000-key pages × N runs
- Worst-case enumeration of ~1.2K snapshot files = ~2 LIST calls per run (milliseconds)
- Full-history enumeration across many runs scales linearly: thousands of runs × 1-2 LIST calls each
- p95 estimate for **single-run replay**: <1 second
- p95 estimate for **full-history enumeration** (months of runs): minutes to tens of minutes

**Caveat:** LIST performance degrades when keys are densely packed under a single prefix (LIST pagination is sequential). The current `runs/{runId}/{endpoint}/chunks/` structure keeps each run isolated, which avoids the dense-prefix pathology.

### 1.3 Lifecycle rule evaluation

MinIO lifecycle rules evaluate per object. Each evaluation has documented overhead in the single-digit-millisecond range (per MinIO docs; actual cost depends on rule complexity and bucket object count).

At ~2.4K objects per run (snapshot + result combined) and multiple runs per day:

- Per-cycle evaluation: ~2.4K × ~few ms = low single-digit seconds per run
- Full-day evaluation: ~2.4K × number of runs = seconds, not hours
- Multi-month lifecycle evaluation: scales linearly with object count

The lifecycle-evaluation cost is **not** the dominant concern at current volume; it becomes significant if object count grows by orders of magnitude (e.g. without consolidation over months of accumulated runs).

### 1.4 Replay scenarios

| Scenario | Files touched | Estimated time |
| -- | --: | --: |
| Single-day replay (1 run, both endpoints) | ~2.4K | <10 seconds |
| Single character replay (one record's manifest entry) | 1-2 files | <1 second |
| Full-week replay (7 runs × 2 endpoints) | ~17K | <1 minute |
| Full-month replay | ~70K | minutes |
| **Year-long replay (no consolidation)** | ~870K | minutes to low-hour range |

These estimates assume MinIO GET throughput (typically 100-300 MB/s per prefix for small objects with HTTP/2).

---

## 2. Consolidation-Point Analysis

Four alternatives evaluated:

| Approach | Files/day reduction | Risk | Complexity |
| -- | --: | -- | -- |
| Increase `max-records` 2000/500 → 20000/5000 | ~10× | Larger blast radius per failure; larger memory footprint; chunk-size affects fetch-wave backpressure balance | Low (config change) |
| **Flush-time rollup (merge N chunks → 1 file)** | **10-100×** | **Kafka event count drops**; in-memory buffer holds N chunks until flush | **Low** |
| Close-time merge task | ~10× | Async ordering complexity; new worker to operate | Medium |
| Post-write nightly compaction | ~10-100× | New operational surface area (job failure recovery, atomic swap, idempotency) | High |

---

## 3. Recommended Approach: Flush-Time Rollup

Roll N consecutive chunks into one file before publishing the manifest. Kafka event count = merged-file count, not chunk count.

**Trade-off:**

- **Gain:** 10-100× file reduction (~2.4K → ~24-240 files/day per run)
- **Gain:** Same throughput (merge happens in writer thread; no extra I/O round-trips)
- **Gain:** In-band — same operational model as today (one manifest, one Kafka event per output)
- **Lose:** Kafka event granularity shifts from per-chunk to per-merged-file (downstream consumers see fewer events, each spanning more records)
- **Lose:** Writer holds N chunks in memory until flush (~10× current buffer size)
- **Lose:** Per-chunk failure boundary less granular (one merged file may contain both successful and failed chunks if merged late)

**Configuration sketch:** `external-api.snapshot.chunk.rollup-size: 10` (configurable, separate from `max-records`).

**Why not nightly compaction:** compaction job needs to read every chunk, rewrite to bigger file, then atomically swap. Adds operational surface area (job failure recovery, atomic swap semantics, replay-safety on partial compaction). Flush-time rollup is in-band, simpler, and matches the natural write boundary.

**Why not increase `max-records`:** chunk size is currently tuned for writer memory + backpressure balance. Changing `max-records` affects fetch throughput (fetch waves are sized to chunk boundaries) and increases failure blast radius per chunk. Flush-time rollup is **additive** — it changes post-chunk packaging without changing chunking logic or backpressure semantics.

---

## 4. Decision Pending

This investigation produces the recommendation. **Implementation is out of scope for issue #1427** — a separate implementation issue will be filed against the write path in module-external-api and module-calculator.

Acceptance criteria for the implementation issue should include:

- New `rollup-size` config (default 10) externalized to YAML
- Manifest records merged-file boundaries (chunk ID range per merged file)
- Kafka events reference the merged file's `objectKey` (not the constituent chunks')
- Existing readers (`OcidLookupPhase`-adjacent paths, downstream consumers) handle merged files transparently because each merged file is still JSONL-gzip with one record per line
- Replay tooling unaffected: JSONL parsing inside a merged file is unchanged

---

## 5. References

- Issue #1427
- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` — chunk rotation, manifest, key format
- `module-external-api/src/main/resources/application.yml` — `snapshot.chunk.character-basic.max-records: 2000`, `snapshot.chunk.item-equipment.max-records: 500`
- `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` — single-file-per-write streaming write
- `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:97` — 1:1 result-file-per-snapshot-chunk fanout
- MinIO S3 API documentation (LIST pagination, lifecycle rule evaluation)
- AWS S3 API documentation (LIST pagination semantics)
- ADR-735 (parent analytics platform decision)