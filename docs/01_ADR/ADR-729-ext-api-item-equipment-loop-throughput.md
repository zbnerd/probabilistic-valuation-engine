# ADR-729: ext-api ITEM_EQUIPMENT loop throughput — producer-side serialize + OCID read-through

- Status: Proposed
- Date: 2026-06-20
- Owner: external-api

---

## 1. Background / Problem

### Background

ITEM_EQUIPMENT pipeline loop throughput on `module-external-api` was measured at ~98 files/s against the reference 150-160 files/s. Two hotspots were identified by reading the hot path:

1. `ChunkedSnapshotSink.runWriterLoop` is a single platform thread that owns Jackson `writeValueAsBytes` + GZIP + disk write. Per-record JSON serialization of a ~250KB `SnapshotChunkRecord` runs serially on this thread, capping writer throughput.
2. `OcidCacheProvider.loadFromRun(runId)` is invoked by `runItemEquipmentPhase` at the start of every loop iteration. Each call materializes the full ~600K-record JSONL into a `List<Map>` via `runBlocking { ... .toList() }`, then rebuilds the HashMap and `cacheRef.set(map)`. The `cacheRef` is never consulted to short-circuit a repeat load.

### Problem

A single iteration of the ITEM_EQUIPMENT loop pays:
- A non-trivial setup cost (OCID cache reload, ~seconds) that does not vary with iteration content.
- A writer-side CPU ceiling (single-thread Jackson serialize) that scales with the iteration's record count.

Throughput variance and the gap to reference suggest both effects compound.

### Goal

Raise the steady-state ITEM_EQUIPMENT loop throughput to ≥150 files/s on a single ext-api instance with `-Xmx2g`, and eliminate per-iteration OCID cache reload cost from iteration 2 onward.

---

## 2. Decision

> Two independent moves, in one PR.

1. **Producer-side serialization.** `BatchFetchSupport` serializes `SnapshotChunkRecord.Success` to JSON bytes on the calling virtual thread using a shared `ObjectMapper`. A new `SnapshotChunkRecord.PreSerialized` variant carries the pre-serialized bytes plus the uncompressed byte count. The sink writer thread only does `GZIPOutputStream.write(bytes)` + disk; no Jackson call.
2. **Read-through OCID cache.** `OcidCacheProvider` tracks the last successfully loaded `key` in an `AtomicReference<String?>`. `loadFromKey(key)` short-circuits with `cacheRef.get()` when `key == loadedKey.get()`, avoiding reload + repopulate.

```text
[Before]
producer VT ─ submit(record) ─► sink writer thread
                                  └─ Jackson serialize (250KB)
                                  └─ GZIP write
                                  └─ disk
[OCID cache: per-iteration full reload]

[After]
producer VT ─ submit(PreSerialized) ─► sink writer thread
                                       └─ GZIP write (bytes only)
                                       └─ disk
[OCID cache: read-through on key, hit returns cacheRef]
```

---

## 3. Trade-offs

### Sensitivity

* Per-record body size: larger bodies amplify the producer-side serialize win.
* Iteration count: read-through win scales with `iterations - 1`.
* In-flight fan-out: producer-side serialize uses the VT pool; cap at current `maxInFlight` to avoid heap pressure from held byte buffers.

### Trade-off

| Choice | Gain | Cost |
| -- | -- | -- |
| Producer-side Jackson | Writer thread unblocked; CPU moves to VT pool (already sized for fetch fan-out) | 100 VTs × 250KB byte buffer held in flight briefly; +1 ObjectMapper shared instance (thread-safe by Jackson contract) |
| Read-through OCID cache | Zero reload cost for iterations 2..N | `AtomicReference<String?>` is write-once per upstream; if `upstreamRunId` changes mid-loop, the new key triggers a reload — same cost as before |

### Risk

* **Heap pressure from held byte buffers.** Mitigated by `maxInFlight` cap (current 100) and the fact that buffers release on `gzipped.write` returning. Watch RSS for 2 minutes after deploy.
* **ObjectMapper contention.** Jackson `ObjectMapper` is documented thread-safe for read operations including `writeValueAsBytes`. If contention shows up, switch to per-VT `ObjectWriter` cached in a `ThreadLocal`.
* **SnapshotChunkRecord.PreSerialized adds a variant.** Any new caller of `ChunkedSnapshotSink.submit` must pick `Success` (legacy path, sink serializes) or `PreSerialized` (producer serialized). Tests must cover both.

### Non-Risk

* `cacheRef` concurrency: still an `AtomicReference` snapshot. Read path (`current()`) is unchanged. Read-through only affects the `loadFromKey` write path.
* GZIP stream state: sink thread still owns the active `GZIPOutputStream` per chunk; only the serialize step moves out.
* `ObjectMapper` lifecycle: Spring-managed singleton, no new bean.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After (target) | Notes |
| ------ | -----: | -------------: | ----- |
| ITEM_EQUIPMENT files/s | 98 | ≥150 | Single ext-api `-Xmx2g` |
| OCID cache setup per iter | ~few s | 0 (iter ≥ 2) | `loadFromRun` returns cached `cacheRef` |
| ext-api RSS during loop | 1067 MB | ≤1300 MB | Brief byte buffer hold; expect 5-10% rise |
| Writer thread CPU | saturated | idle headroom | Sink writer no longer serializes |

### Observed Result

* TBD — fill in after deploy + re-run of pipeline test.

---

## 5. Summary

> Move Jackson serialization to the producer VT pool and short-circuit OCID cache reload on key match; one PR, two files per move, single seam touched per concern.
