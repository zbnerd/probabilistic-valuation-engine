# 03. 파이프라인 데이터 정합성

> chunk key/body 불일치, 잘못된 upstream runId, result writer pipe race.
> "에러 없이 0건 처리" — silent data loss 패턴들이 만든 장애. 로그에 에러가 없어도 데이터가 안 흐르는 가장 교활한 버그 유형.

**영향(Impact):** chunk key/body schema 불일치·잘못된 upstreamRunId 로 calculator/synchronizer 가 모든 chunk silent drop → **valuation data 0건**. 에러 로그 없이 0건 처리(가장 교활한 장애 유형).

---

## 3-1. producer/consumer chunk-key layout 불일치 — `OcidLookupPhase` chunk 못 찾음

- **Session:** 20260611-054924-211160
- **문제/에러:** raw-path→MinIO 마이그레이션 Task 7 후, producer 가 `runs/$runId/ranking-overall/part-N.jsonl.gz` 에 write 하는데 consumer(`OcidLookupPhase`) 는 `runs/$runId/ranking-overall/chunks/...` read → phase 가 data 없이 silent 통과.
- **원인:** `ChunkFileManager`(producer) 가 구 local-FS layout 의 `/chunks/` subdir 누락.
- **해결:** producer key 에 `ranking-overall/chunks/` convention 복구(producer-consumer 합의). commit `2d9222680`.
- **왜 이 방법 / 대안:** consumer 기존 contract 에 producer 맞춤(`OcidLookupPhase`/reader 무변경). ADR-725 "single ObjectStorage interface" non-risk(hot-path layout 은 cutover 중 변경 금지)와 교차검증.

---

## 3-2. chunk body schema mismatch — calculator/synchronizer 가 record 100% silent drop

- **Session:** 20260611-054924-211160
- **문제/에러:** Calculator `SnapshotChunkProcessor.parseLines` "460 records read, 0 success, 0 items, 0 results" — 모든 item-equipment chunk silent drop. Sync `DefaultChunkFileReader.parseBasicLine` 도 char-basic chunk 100% "chunk processing failed" filter.
- **원인:** reader 가 `node.path("status").asText()=="SUCCESS"` + `node.path("body")` 확인, but 신규 chunk format 은 base64 `bodyBytes` + `httpStatus` 운반; 구 field 둘 다 `MissingNode`.
- **해결:** 양 module 에 `extractBody(node)` helper 추가(inline `body` JSON 또는 base64 `bodyBytes`→JSON); `status=="SUCCESS"` **또는** `httpStatus==200` 일 때 SUCCESS 처리. commit `254441e65` (#1233).
- **왜 이 방법 / 대안:** dual-shape reader(breaking format migration 회피) — 기존 artifact 계속 parse. inline comment 로 dual shape 문서화(향후 refactor 가 한쪽 branch drop 방지). **기각:** 단일 format 강제(cutover 중 in-flight MinIO object 무효화).

---

## 3-3. ITEM_EQUIPMENT 가 매 run silent skip — 잘못된 `upstreamRunId` source

- **Session:** 20260621-041954-715366
- **문제/에러:** ITEM_EQUIPMENT 가 ~1초 만에 0 record exit. `ocid-mapping-{runId}.jsonl.gz` S3 lookup `NoSuchKey`, cache 공란. 0 record 진단 중 ext-api `[PROBE-ext]` 로그로 발견. chain 도입 후 모든 `run-on-startup` trigger 에서 silent broken.
- **원인:** `triggerDailyRefresh` 가 `cbRunId`(CHARACTER_BASIC) 를 ITEM_EQUIPMENT 의 `upstreamRunId` 로 전달. `runItemEquipmentPhase` → `ocidCacheProvider.loadFromRun(upstreamRunId)` 가 `ocid-mapping-{cbRunId}.jsonl.gz` 탐색 — but OCID mapping 은 **OCID_LOOKUP** 이 write.
- **해결:** ITEM_EQUIPMENT 에 OCID_LOOKUP runId 전달(`loadFromRun` 이 올바른 mapping file 해석). commit `e4dc0dcdd`. ADR-729(OCID read-through cache 설계, write-key contract 준수).
- **왜 이 방법 / 대안:** 최소 정정 — upstream-runId 를 artifact 소유 phase 에 정렬. **기각:** phase 간 ocid-mapping join(phase semantics 결합, per-run isolation 파손).

---

## 3-4. Calculator result writer 빈 gzip 파일 — pipe race ("Read end dead")

- **Session:** 20260622-060636-122097
- **문제/에러:** `CalculationResultWriter` 가 모든 ITEM_EQUIPMENT chunk 에 0-row `result-part-*.jsonl.gz` 생성. Sync `results=0`; DAG `wait_for_item_equipment_cycle` 미충족 → **valuation data 0건**. 로그: `IOException: Read end dead` + `NullPointerException: Deflater has been closed` @ `CalculationResultWriter.kt:140`; `calculator_chunks_processed_total=0`, run 당 ~14,921 ERROR. 검증: 깨진 파일 0 bytes, fix 후 674,986 bytes/30,507 rows.
- **원인:** `PipedInputStream`/`PipedOutputStream` + `putStreamMultipart` 경로가 AWS SDK async reader 와 race. producer 코루틴 exit 후 `PipedInputStream` "Read end dead"(`readSide.isAlive()` check) throw + `Deflater` 이미 close → truncated/empty gzip. `composed.whenComplete { pipeInput.close() }` 도 SDK drain mid-read 중 발생 가능.
- **해결:** pipe 경로를 temp-file drain + `putFileAsync` 로 교체(OcidLookupPhase 검증패턴 `c7b20f4c3` 미러). producer 코루틴이 `Flow<CalculationResult>` → `GZIPOutputStream` → `Files.createTempFile` drain, close 후 `putFileAsync`(MinIO S3TransferManager multipart | LocalFs atomic move) upload. temp file `whenComplete` 안전망 삭제. `CompletableFuture<WriteResult>` 반환 불변. **ADR-730**.
- **왜 이 방법 / 대안:** 대안 "pipe + putStreamMultipart 유지" = disk write 0 but data-loss race 잔존. temp-file approach 가 correctness + 실제 byte count 를 chunk 당 1회 disk write 와 교환(chunk ≤500 record/128MB). memory 는 구 pipe 보다 엄격히 우수(temp file = disk, heap 아님) — OOM risk 무도입; LocalFs `putFile` atomic rename.

---

## 3-5. OCID_LOOKUP upload 회귀 — streaming-gzip 변경 후 `contentLength must not be negative`

- **Session:** 20260620-053408-1772825 (+ 20260621-041954)
- **문제/에러:** OCID_LOOKUP 즉시 `contentLength must not be negative` 실패(PR #1318 streaming writer 회귀). 3 중 bug: (a) `MinioObjectStorage.putStreamMultipart: b.contentLength(-1L)` → `IllegalArgumentException`(SDK `Validate.isNotNegativeOrNull`); (b) executor 누락 → NPE; (c) 3-4 와 동일 pipe race.
- **원인:** `putStreamMultipart` 가 `contentLength(-1)`(unknown length 에 invalid signal — `null` 이어야) + executor 없이 호출. SDK 가 background thread 에서 `InputStream` read 시 non-null `Executor` 필요; pipe pattern 이 async reader 와 추가 race.
- **해결:** `MinioObjectStorage`: `contentLength(-1L)` 제거(null 이 unknown-length 정확 신호), 공유 virtual-thread `streamReadExecutor` 추가. `OcidLookupPhase`: pipe+`putStreamMultipart` → temp file+`putFileAsync`; writer 가 `Files.createTempFile` drain, S3TransferManager multipart upload, `finally` cleanup. 검증: 594,928 mapping upload(18 MiB gz), 400 files/s. 이 fix 가 later calc(3-4) template.
- **왜 이 방법 / 대안:** pure streaming 유지는 pipe race 하 불가; temp-file+multipart 가 검증된 ext-api 패턴. **기각:** per-record content-length probing(어차피 전 stream buffer 필요).

---

## 3-6. Phase DAG upstream sensor 교착 — daily chain 완료 후 standalone trigger timeout

- **Session:** 20260623-042836-3612963 (+ 20260623-081233, -085126, -100348)
- **문제/에러:** `item_equipment_pipeline` run 2개가 daily chain success 후 `wait_upstream_terminal_character_basic` 에 4h stuck. ext-api 가 run 완료 시 `current=null` 반환 → sensor strict-progression gate(`current_idx > target_idx`) 불충족 → timeout 까지 poll. 선행 break: PR #1329 `daily_full_pipeline` wrapper 가 `TriggerDagRunOperator` 로 즉시 발화 → ext-api `400 MISSING_UPSTREAM` 거절(phase in-flight).
- **원인:** phase DAG 내부 wait sensor 가 `trigger_once` *이후* 실행 → trigger 가 wait 보다 선키; but gate 는 `current.phase` strict advance 만 고려. run 완료 시 `current` null → gate 통과 불가.
- **해결:** 2-part. (a) commit `3e81c8f73`: `phase_pipeline_factory` 에 `make_wait_phase_terminal_sensor(phase)`; `make_phase_dag` 가 `upstream_phase` param 획득(3 branch 전부 trigger 전 upstream terminal 대기). phase-progression gating(xcom/runId correlation 불필요). test 12건. (b) commit `f203688b4`: 2차 통과 조건 — `current==null` **and** `lastCompletedByPhase[phase].terminal=true` 시 upstream terminal 처리. **ADR-734**.
- **왜 이 방법 / 대안:** ADR-734 trade-off 기각: operator-JSON scope parsing(mode-on-conf 제거), `stop_loop_pipeline` 즉시정지(30min drain latency). 선택 설계가 ext-api 를 유일 loop-state owner 로 유지 + 기존 `PhaseLoopController` 재사용; 제약: loop 가 ext-api restart 시 사망(#1291 §13 pre-existing, → 사례 05-1 참조).

---

## 3-7. item-equipment loop 가 daily runId 덮어씀 — Airflow sensor runId 불일치 FAILED

- **Session:** 20260613-053448-2564568 (+ 20260612-013142 "current 가 old run 가리키는 문제")
- **문제/에러:** `daily_collection_pipeline` Airflow sensor 2h 재시도 후 FAILED(06-12→06-13 연속 3회 실패), `ALREADY_RUNNING`/runId mismatch — daily run healthy 임에도.
- **원인:** PR #1278 이 loop 에 `startItemEquipmentCycle` 추가, 매 item-equipment cycle 마다 `currentRun` 무조건 덮어씀; pipeline 중간에 daily run runId clobber → sensor expected runId 불일치.
- **해결:** `currentRun` 이 null 또는 이미 terminal 일 때만 `startItemEquipmentCycle` 호출; per-cycle runId 는 log-correlation handle 로 강등, `/run-status` 는 daily runId 유지. commit `7a3841168`. 관련 `cbff899cc`: loop `startRun` hardcode `RANKING_FETCH` → `startItemEquipmentCycle(runId)` 로 initial phase `ITEM_EQUIPMENT` 설정.
- **왜 이 방법 / 대안:** **기각:** run-status 를 두 concurrent run(daily+loop cycle) 추적으로 재설계 — daily run 을 single source of truth 유지, loop runId 를 correlation 전용 강등(Airflow sensor 계약 = daily trigger 당 1 runId).
