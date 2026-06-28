# 02. 메모리·OOM·스트리밍 I/O

> 대용량 IGN(~600K) 처리 중 heap 압박·OOM·스트리밍 writer race.
> "buffer 전체를 heap 에 올린다" 와 "동기 put 으로 writer thread 를 막는다" 패턴이 만든 장애.

---

## 2-1. OCID 매핑 writer OOM — 120MB buffered string list (600K 엔트리)

- **Session:** 20260611-054924-211160, 20260611-152120-705286 (`/diagnose ext-api oom`)
- **문제/에러:** OCID run 25분 지점 `OutOfMemoryError`, 2.5h run 사망. `results: MutableList<String>` 증가로 heap plateau. 각 `{"userIgn","ocid"}` JSON(~200B)을 단일 in-memory list(~120MB/600K) 적재 후 run 끝에 1회 `objectStorage.put(key, ByteArray)` flush. 1GB heap ceiling(in-flight batch state + item-equipment loop 공유).
- **원인:** 전체 매핑을 heap list 적재 후 일괄 flush.
- **해결:** 매핑을 `Channel` 로 stream → 단일 writer 코루틴이 gzip + `ObjectStorage.putStream` write. heap = pipe buffer(~64KB) + GZIP 내부 buffer; 120MB list 제거. commit `4fa308f19` (#1234).
- **왜 이 방법 / 대안:** `OcidCacheProvider` reader 불변 — `listByPrefix + maxByOrNull(lastModified)` 로 latest object 취하므로 동일 key streaming write 가 동일 artifact 생성. **기각:** 출력을 multi-key chunking(reader 의 latest-object contract 복잡화).

---

## 2-2. `GzipJsonlChunkWriter` OOM — chunk 당 in-memory `ByteArrayOutputStream`

- **Session:** 20260611-054924-211160, 20260611-152120-705286
- **문제/에러:** `GzipJsonlChunkWriter.close` line 55 에서 `OutOfMemoryError`, char-basic 사망(~10:27/12:10/12:44). 128MB uncompressed chunk 당 ~256MB peak heap.
- **원인:** chunk 를 `ByteArrayOutputStream` 적재 후 `ObjectStorage.put`(buffer + `toByteArray()` copy + deflater state) → writer-thread heap 1GB 초과.
- **해결:** `GZIPOutputStream` 으로 temp file write, close 시 `ObjectStorage.putStream` stream. heap = deflater window(~32KB), chunk 크기 무관. 회귀 test(160K record/~40MB round-trip OOM 없음). commit `810b3ca41`.
- **왜 이 방법 / 대안:** trade-off: chunk rotation 시 brief disk 이중 사용. 후속 `3dbc18ce4` 가 `putFile(key, path)` 추가(double spool 제거 — `putStream` 이 두 backend 모두 2차 temp spool → item-equipment 143→50 files/s 저하). 추가 `94cdd5685` 가 upload 를 `S3TransferManager` fire-and-forget(sinkSubmitMs 917→near-0).

---

## 2-3. `ChunkedSnapshotSink` 가 OOM/`Error` 삼킴 — `catch (Exception)` 구멍

- **Session:** 20260611-152120-705286
- **문제/에러:** `daily_collection` DAG 1h19m 후 `IllegalStateException("sink writer thread is not alive")` FAILED — 원본 `OutOfMemoryError` 소실. heap 25분간 928MB/1GB plateau.
- **원인:** `ChunkedSnapshotSink.runWriterLoop` 의 `catch (ex: Exception)` — `OutOfMemoryError`/`StackOverflowError`/`VirtualMachineError` 는 `Error`라 catch 미발동, thread silent 사망, 다음 `submit()` 은 `writerFuture.isDone` 만 관측.
- **해결:** catch 를 `Throwable` 로 확장 — writer 장애 시 `writerError` 기록 + `accepting=false` 전환; 다음 `submit()` 이 `"sink closed due to writer error: <원본 message>"` throw(cause chain). `ChunkedSnapshotSinkTest` 로 contract pin. commit `d0747c10b` (#1235).
- **왜 이 방법 / 대안:** `Throwable` catch 는 일반적이지 않으나 여기서는 의도적 — sink writer 단일 코루틴의 silent 사망은 Error 노출보다 엄격히 더 나쁨. test 가 fix 없이 fail(OOM 소실)되어 진단 contract lock.

---

## 2-4. 동기 `s3.putObject` 가 writer thread 5-10s/chunk block → fetcher 기아

- **Session:** 20260614-020255-3566982 (+ 20260614-075928, 20260614-150517)
- **문제/에러:** item-equipment ~50 files/s 로 저하(baseline 150 의 1/3). sinkQueue 3000 cap 포화, `sinkSubmitMs` avg 917ms(max 2270ms). fetcher 가상스레드가 fetch 보다 queue 공간 대기에 시간 소비.
- **원인:** `GzipJsonlChunkWriter.close()` 가 ~128MB chunk 동기 `s3.putObject`; ~50 files/s 시 writer thread chunk 당 5-10s block → upstream fetcher 굶주림.
- **해결:** `ObjectStorage.putFileAsync(key, path)` → `CompletableFuture<PutResult>`. MinIO 구현 `S3TransferManager.upload()` (5MB multipart part, 50-thread pool, 128MB 에서 5-10x 빠름). `ChunkFileManager` 가 in-flight future 추적, `awaitAllUploads(timeoutMs)` 를 manifest write 전 호출(manifest 가 누락 chunk 참조 방지); timeout/실패 시 loud fail + `cleanupOnFailure` + `publishRunFailed`. commit `94cdd5685` (#1283). ADR-730.
- **왜 이 방법 / 대안:** LocalFs backend 는 `completedFuture(putFile(...))` 반환(writer API backend 간 대칭). **기각:** 동기 put 유지 + sink queue 확장(벽 이동만). ADR-717 이 "pool 압력 떨어지면 `.join()`/batch wait 가 다음 병목" 이라 예고 — 본 사례가 그 병목 해소.

---

## 2-5. item-equipment throughput 3중 병목 — 102 files/s (150 baseline)

- **Session:** 20260616-094101-1480103 (+ 20260616-005215, 20260616-135046)
- **문제/에러:** item-equipment 102 files/s 고착(baseline 150). (1) chunk-ready publish race: writer 가 `future.join()` block 으로 "PUT 후 publish" 정렬 → 90s 당 1133 chunk 실패. (2) in-flight cap 100 이 250-permit rate limiter throttle(per-batch wave 2.5s vs 1.6s). (3) `MinioObjectStorage` temp-file double spool — chunk 당 4 disk 왕복 → `/tmp` I/O 경합. (4) Calculator `CurrentRunIdHolder` in-memory `ConcurrentHashMap`(restart 유실 + multi-instance drift).
- **원인:** 동기 ordering primitive, concurrency knob 불일치, 동기 `S3Client.putObject` chunked-stream 불가로 redundant disk spool, stateful in-memory run registry.
- **해결:** (1) `future.whenComplete` 비동기 ordering — 0 race error, writer ~50ms 복귀. (2) `application.yml` in-flight 100→250. (3) stream→`ByteArray` drain + `RequestBody.fromByteArray`(1 왕복, disk 무). (4) `CurrentRunIdHolder` ext-api `/run-status` poll → stale-chunk skip reason `calculator_chunks_skipped_total{reason="stale_run"}` 이관. commit `ecee74549` (#1294). heap `-Xmx` 1g→2g(`a76ff88dd`, #1293) — GC 압박이 실제 effective gate 였음.
- **왜 이 방법 / 대안:** in-memory `ByteArray` 가 동기 경로 유일 옵션(동기 `S3Client.putObject` 는 length-1 chunked-stream API 무). 완전 async(S3TransferManager, ADR-730) 는 연기. **기각:** in-flight 정렬 없이 pool 인상(pending connection 압력, ADR-717). 검증: ext-api 102→150 files/s, calc 186→362 users/s. **heap 이 effective gate** 였음 — "throughput 영향 없었음, Heap이 effective gate".

---

## 2-6. Calculator off-heap OCID cache + Netty/Kafka direct-buffer 튜닝

- **Session:** 20260619-174026-4166158 (18,587 tool calls, 설계 doc 5건), 20260622-174152-1947951, 20260623-005012-3020198
- **문제/에러:** hot-path OCID cache 가 on-heap(300K char 부하 시 heap 압박); Netty/Kafka direct-buffer pool undertune → `DirectBuffer` allocation stall. spec 작업 중 producer-only 인 `consumer buffer.memory` config 와 Chronicle Map version pin(초기 3.23.5→3.26.8 정정) 적발.
- **원인:** 기본 heap cache + 미구성 pool sizing, 고처리량 볼륨.
- **해결:** `chronicle-map 3.26.8` dep(`cca3dfba9`/`54021fca2`), `OffHeapSerializedBackend` 도입(`c0a163928`), Netty/Kafka direct buffer pool 튜닝(producer-only Kafka, `30c601724`/`4be816e99`), Prometheus `offheap-alerts.yml`+`cache-backend-alerts.yml`. metric naming `calculator_cache_*` 정렬.
- **왜 이 방법 / 대안:** off-heap cache 가 GC 압박 회피를 allocation overhead 와 교환. **기각:** heap 확장(300K char 부하 시 heap 압박이 GC 지배). `-P` override 용 lazy `providers.gradleProperty`.
