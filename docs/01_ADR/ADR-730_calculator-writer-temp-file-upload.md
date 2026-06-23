# ADR-730: CalculationResultWriter — drain to temp file, upload via putFileAsync

- Status: Accepted
- Date: 2026-06-22
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- `CalculationResultWriter.write` streams `Flow<CalculationResult>` through `GZIPOutputStream` → `PipedOutputStream`/`PipedInputStream` → `ObjectStorage.putStreamMultipart` (AWS SDK `AsyncRequestBody.fromInputStream`).
- The same pipe pattern was already used and **abandoned** in `OcidLookupPhase` (commit `c7b20f4c3`, "fix(ext-api): OCID_LOOKUP pipe → temp file").

### Problem

- Item-equipment runs produce **0-row** `result-part-*.jsonl.gz` files. Synchronizer logs `results=0` for every `calculator.result.chunk-ready`. DAG `wait_for_item_equipment_cycle` never satisfies → no valuation data.
- Log signature: `IOException: Read end dead` + `NullPointerException: Deflater has been closed` at `CalculationResultWriter.kt:140`. `calculator_chunks_processed_total=0`, ~15k ERRORs/run.
- Root cause (identical to the documented ext-api bug #3): the SDK reads the `PipedInputStream` on a background thread that races the writer coroutine. After the producer coroutine exits, `PipedInputStream` throws "Read end dead" (`readSide.isAlive()` check) → truncated/empty gzip. The `composed.whenComplete { pipeInput.close() }` can also fire while the SDK drain is still mid-read.

### Goal

- Calculator must persist every `result-part` file with the real row count. No "Read end dead", no empty gzip files.

---

## 2. Decision

> Replace the `PipedInputStream`/`PipedOutputStream` + `putStreamMultipart` path with a temp-file drain + `putFileAsync` upload, mirroring the proven `OcidLookupPhase` fix.

```text
producer coroutine: Flow.collect → GZIPOutputStream(BufferedOutputStream(FileOutputStream(tempFile)))
                    ↓ on producer complete (file flushed + closed)
putFileAsync(objectKey, tempFile)   (S3TransferManager multipart | LocalFs atomic move)
                    ↓ whenComplete (success or failure)
Files.deleteIfExists(tempFile)      (safety net; LocalFs putFile already moved it)
```

The method still returns `CompletableFuture<WriteResult>` (caller `SnapshotChunkProcessor` bridges via `.await()`); the CF chain stays fully async, no `.join()`/`.get()`.

---

## 3. Trade-offs

### Sensitivity

* Temp-file disk I/O per chunk (chunk = up to 500 records / 128MB uncompressed). Disk throughput on the calculator host bounds the write path.
* Temp dir capacity under sustained item-equipment load (thousands of chunks).
* `putFileAsync` MinIO path uses S3TransferManager multipart with its own 50-thread executor — sized in `StorageConfig`.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Temp file + putFileAsync | Correctness (no pipe race); real byte counts; reuses the proven ext-api pattern | Extra disk write per chunk (gzip → temp file, then upload reads it); ~1 RTT of disk I/O vs pure streaming |
| Keep pipe + putStreamMultipart | Zero intermediate disk write | "Read end dead" race → data loss (the bug we are fixing) |

### Risk

* Temp-file disk pressure if calculator host disk is small under sustained load. Mitigated: chunk size bounded (500 records / 128MB), temp files deleted in `whenComplete`, OS temp dir rotation.

### Non-Risk

* Memory: temp file is on disk, not heap — strictly better than the prior pipe which still needed the SDK to buffer. No OOM risk introduced.
* Latency: LocalFs `putFile` is an atomic rename (no copy); MinIO multipart reads the file sequentially. Neither reintroduces the heap pressure the streaming design was built to avoid.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| current-run result rows (before fix) | 0 | `result-part-000068.jsonl.gz` gunzip = 0 lines |
| prior-run result rows (baseline) | 30,436 | same writer path, pre-regression run |
| `calculator_chunks_processed_total` (before fix) | 0 | all writes empty |

### Observed Result

Verified end-to-end against MinIO (item-equipment phase triggered via `POST /api/internal/trigger/phase/ITEM_EQUIPMENT`, runId `verify-writer-fix-1`):

* `calculator_chunks_processed_total` = 64 (was 0 before fix).
* `calculator_chunks_failed_total` = 0.
* `calculator_items_calculated_total` = 1,948,957.
* `Read end dead` errors in calculator log = 0 (was ~14,921 over 9 min before fix).
* `result-part-000001.jsonl.gz` (verify run): **674,986 bytes, 30,507 rows** of valid JSONL.
* `result-part-000068.jsonl.gz` (prior broken run, same code path pre-fix): **0 bytes** ("unexpected end of file").

The `mc cat | gunzip | wc -l` pipe returns 0 due to a pipe-buffering artifact between `mc cat` and `gunzip`; the disk-downloaded file (`mc cp` then `gunzip -c`) shows the real 30,507 rows.

---

## 5. Summary

> Drain calculator results to a temp file and upload via `putFileAsync`, dropping the `PipedInputStream`/`putStreamMultipart` path that races the SDK reader and produces empty gzip files.
