# ADR-743: Small-File Problem Resolution (Flush-Time Rollup)

- Status: Proposed
- Date: 2026-06-28
- Owner: Architecture Team

---

## 1. Background / Problem

### Background

The external-API + calculator pipeline produces snapshot chunks (raw external-API captures) and result chunks (calculator output) at the following per-run volume:

- Snapshot CHARACTER_BASIC: chunked at `max-records: 2000` (`module-external-api/src/main/resources/application.yml`)
- Snapshot ITEM_EQUIPMENT: chunked at `max-records: 500`
- Result (Calculator): one file per input snapshot chunk (`SnapshotChunkProcessor.kt:97` → `CalculationResultWriter.write`)
- OCID mapping: already rolled, ~1 file per run

For an active user base of ~595K, this yields ~1.2K snapshot files + ~1.2K result files per run. Across runs in a day/week, file count trends toward the high range cited in issue #1427.

Operational consequences:

- MinIO LIST API latency scales with file count
- Lifecycle rule evaluation cost scales with object count
- Replay scenarios must enumerate files across runs (linearly scaling)
- Future Iceberg/columnar adoption blocked at substrate level without consolidation

### Problem

Without write-path consolidation, operational cost grows linearly with data volume and downstream columnar-format adoption (Iceberg, Parquet) is blocked by the small-file substrate.

### Goal

Recommend a write-path consolidation approach that reduces file count 10-100× without changing chunking semantics or per-record processing. Implementation is out of scope for this ADR; the implementation issue will reference it.

---

## 2. Decision

> **Adopt flush-time rollup: roll N consecutive chunks into one file before publishing the manifest. Kafka event count = merged-file count.**

```text
Producer (write loop)
  → emit N chunks (each ≤ max-records: e.g. 2000 CHARACTER_BASIC, 500 ITEM_EQUIPMENT)
  → flush: gzip + concatenate into single merged file
  → upload merged file to MinIO at key {runKey}/chunks/merged-NNNNNN.jsonl.gz
  → publish 1 Kafka event referencing the merged file (manifest records chunk ID range)
  → continue with next N chunks
```

`N` (rollup-size) is configurable, default 10, externalized to YAML under `external-api.snapshot.chunk.rollup-size`.

---

## 3. Trade-offs

### Sensitivity

- **File count**: ~1.2K/run → ~24-240/run (10-100× reduction depending on rollup-size)
- **Kafka event granularity**: per-chunk → per-merged-file (consumers see fewer, larger events)
- **Writer memory**: N × max-records records held in memory until flush (e.g. 10 × 2000 = 20K records, ~20 MB at 1 KB/record; ~5 MB at 250 B/record)
- **Fetch throughput**: unchanged (chunk boundary preserved within merged file; fetch waves still sized to chunk count, not file count)
- **Failure blast radius**: merged file size ~N× larger; one writer crash before flush loses N× more in-flight data

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Flush-time rollup (chosen) | 10-100× file reduction; no extra I/O; in-band; matches natural write boundary | Lower Kafka event granularity; slightly higher writer memory; larger failure blast radius per file |
| Increase `max-records` (500/2000 → 5000/20000) | Simple config change | Affects fetch throughput + backpressure balance; larger failure blast radius per chunk; changes chunking semantics |
| Nightly compaction | Decoupled from write path | New operational surface area (job failure recovery, atomic swap, idempotency on partial compaction) |
| Close-time merge task | Async, doesn't block write | Ordering complexity; new worker to operate and monitor |

### Risk

- **Blast radius per failure:** merged file size ~N× larger; one writer crash before flush loses ~N× more in-flight data than today's per-chunk files. Mitigation: writer memory bounded by N × max-records; can re-emit from fetch wave.
- **Kafka consumer ordering:** consumers that assumed per-chunk granularity may need to update. Mitigation: per-merged-file Kafka event carries the chunk ID range in the manifest, so consumers can re-derive per-chunk boundaries if needed.
- **Downstream replay:** replay tooling may need to handle merged-file format. Mitigation: existing reader code already handles arbitrary file size (JSONL parsing is line-oriented, file size is irrelevant); only file *count* per replay changes.

### Non-Risk

- **JSONL format inside merged file:** unchanged. Each line is still one record. Reader unaffected.
- **Per-record processing:** downstream consumers process record-by-record within the merged file — no behavior change.
- **Storage cost:** unchanged (merged file = same total bytes as N constituent chunks; gzip compression ratio unaffected because chunk boundaries are arbitrary inside the stream).
- **Manifest schema:** additive change (add merged-file ranges) — does not break existing manifest consumers.

---

## 4. Result / Evidence

### Metrics (from investigation report `docs/02_Investigations/2026-06-28-small-file-measurement.md`)

| Metric | Current | After flush-time rollup (estimated, N=10) | Notes |
| -- | --: | --: | -- |
| Files/day per run (snapshot) | ~1.2K | ~120 | 10× reduction at N=10 |
| Files/day per run (result) | ~1.2K | ~120 | 10× reduction at N=10 |
| Kafka events/day per run | ~2.4K | ~240 | Same ratio |
| MinIO LIST cost (single-run replay) | <10 s | <1 s | Linear scaling |
| Lifecycle evaluation cost per run | low single-digit seconds | fraction of a second | Linear scaling |
| Writer in-memory buffer (records) | ≤2,000 (CHARACTER_BASIC) / ≤500 (ITEM_EQUIPMENT) | ≤20,000 / ≤5,000 | N=10 buffer |

### Observed Result

Investigation complete. Recommendation: flush-time rollup with N=10 default, YAML-configurable.

Implementation is out of scope — a separate implementation issue will reference this ADR.

---

## 5. Summary

> **Flush-time rollup (merge N chunks into one file before manifest publish) reduces per-run file count 10-100× with minimal write-path complexity and no per-record processing change. Implementation tracked separately.**