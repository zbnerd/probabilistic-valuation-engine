# StepTrace & Slow Task 내부 로직 추적 보고서

> 측정일: 2025-05-02 | 부하테스트 10,000 IGN, cold-miss, 50 concurrency

## 1. Slow Task Top 10 내부 로직

### 1.1 TimeoutScanner — avg 2,662ms, max 14,701ms

```
CalculationJobTimeoutScanner.scanStaleJobs() [module-infra/.../job/CalculationJobTimeoutScanner.kt:22]
  @Scheduled(fixedDelay=30000ms)
  TaskContext.of("TimeoutScanner", "Scan", "stale_jobs")
  │
  ├── scanOcidResolving()          [120초 타임아웃]
  │   ├── jobPort.findStaleJobs("OCID_RESOLVING", cutoff)
  │   │   └── SQL: SELECT ... FROM calculation_jobs
  │   │         WHERE status='OCID_RESOLVING' AND updated_at < :cutoff
  │   ├── jobPort.findJobsByIds(candidateIds)
  │   │   └── SQL: SELECT ... FROM calculation_jobs WHERE job_id = ANY(?)
  │   └── [per job] markFailed() 또는 retryOcidResolvingJob()
  │       ├── SQL: UPDATE calculation_jobs SET status='FAILED' ...
  │       └── SQL: SELECT pgmq.send('external_api', ...::jsonb)
  │
  ├── scanApiRequested()           [300초 타임아웃]
  │   └── (동일 패턴: findStaleJobs → findJobsByIds → markFailed/retry)
  │
  └── scanRetrying()               [180초 타임아웃]
      └── (동일 패턴)
```

**Leaf**: 3개 상태 스캔 × (findStaleJobs + findJobsByIds + N×(markFailed/retry)) = 최대 60+ DB 라운드트립. maxBatchSize=20이므로 최악의 경우 14.7초.

---

### 1.2 PgmqWorker — avg 809ms, 20,117 calls

```
PgmqWorker<T>.processMessages() [module-infra/.../pgmq/PgmqWorker.kt:159]
  @Scheduled(fixedDelay=300ms)
  TaskContext.of("PgmqWorker", "ProcessBatch", queueName)
  │
  ├── pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeoutSec)
  │   └── SQL: SELECT msg_id, read_ct, enqueued_at, vt, message
  │         FROM pgmq.read(?, ?, ?)   -- SKIP LOCKED 기반 메시지 소비
  │
  ├── [Branch A] processBatchSinglePhase(messages)
  │   └── [per msg] CompletableFuture.supplyAsync({ processSingleMessage(msg) }, workerPool)
  │       └── SUBCLASS process() -- 실제 비즈니스 로직
  │           (ExternalApiWorker.process(), ResultReadyProjectionWorker.process() 등)
  │
  ├── [Branch B] processBatchPipelined(messages) -- 2-phase pipeline
  │   ├── Phase 1: calculateOnly() → pipelineBuffer.offer()
  │   └── Phase 2: drainBuffer() @Scheduled(fixedDelay=100ms)
  │
  └── archiveBatch(queueName, archiveIds)
      └── SQL: DELETE FROM pgmq.q_$queue WHERE msg_id IN (...)
            RETURNING ... INSERT INTO pgmq.a_$queue ...
```

**Leaf**: PGMQ read(메시지 소비) + 서브클래스 process(비즈니스 로직) + archive(아카이브). 809ms는 큐 오버헤드 + 디스패치 비용.

---

### 1.3 ExternalApiWorker — avg 637ms, 21,533 calls

```
ExternalApiWorker.process(message) [module-infra/.../worker/ExternalApiWorker.kt:110]
  TaskContext.of("ExternalApiWorker", "ProcessMessage", userIgn)
  StepTimer("ExternalApiWorker:ProcessMessage")
  │
  ├── findJob
  │   └── SQL: SELECT ... FROM calculation_jobs WHERE job_id=?
  │
  ├── resolveAndFetch                 ★ 주 병목 (avg 491ms)
  │   ├── [OCID 캐시 있음]
  │   │   └── equipmentFetchProvider.fetchWithCache(ocid)
  │   │       └── nexonApiClient.getItemDataByOcid(ocid).orTimeout(10s).join()
  │   │           └── WebClient GET /maplestory/v1/character/item-equipment?ocid=...
  │   │               (Resilience4j: CircuitBreaker + Bulkhead + TimeLimiter + Retry)
  │   │
  │   └── [OCID 없음]
  │       ├── nexonApiClient.getOcidByCharacterName(userIgn)
  │       │   └── WebClient GET /maplestory/v1/id?character_name=...
  │       └── equipmentFetchProvider.fetchWithCache(ocid)
  │           └── (위와 동일)
  │
  ├── serializeSnapshot
  │   └── objectMapper.writeValueAsBytes(equipmentResponse)
  │
  ├── snapshotStore.put(snapshot, data)   -- 비동기 (snapshotWriter Executor)
  │   └── LocalSnapshotObjectStore.put() → GZIP 압축 + 파일 쓰기
  │
  ├── buildCalculationInput
  │   └── convertItems(equipmentResponse) -- 아이템 변환 (CPU)
  │
  ├── saveCalculationInput
  │   └── SQL: INSERT INTO calculation_snapshot_inputs ON CONFLICT DO NOTHING
  │
  ├── awaitSnapshotPut → snapshotFuture.join()
  │
  └── runCalculationAndComplete         ★ CPU 병목 (avg 1,440ms)
      StepTimer("ExternalApiWorker:PureCalculate")
      │
      ├── loadInput
      │   └── SQL: SELECT ... FROM calculation_snapshot_inputs WHERE job_id=?
      │
      ├── pureCalculate                  ★★ 최대 병목 (avg 1,216ms)
      │   └── runBlocking(cpuDispatcher) { pureCalculationPort.calculate(input) }
      │       └── CPU-bound 기대값 계산 (13-22개 장비 × 확률 시뮬레이션)
      │
      ├── serializeResult → objectMapper.writeValueAsBytes
      ├── gzipResult → GZIPOutputStream 압축
      ├── hashResult → SHA-256 해시
      │
      └── completeCalculation
          ├── SQL: UPDATE calculation_jobs SET status='COMPLETED' WHERE job_id=?
          ├── SQL: INSERT INTO calculation_results ON CONFLICT DO NOTHING
          └── SQL: SELECT pgmq.send('result_ready', ...::jsonb)
```

**Leaf**: Nexon API 호출(resolveAndFetch avg 491ms) + CPU 계산(pureCalculate avg 1,216ms) + 다중 DB 라운드트립.

---

### 1.4 EquipmentWorker — avg 584ms, 105 calls

```
EquipmentDbWorker.persist(ocid, response) [module-infra/.../worker/EquipmentDbWorker.kt:44]
  @Async @Transactional(REQUIRES_NEW)
  TaskContext.of("EquipmentWorker", "AsyncPersist", ocid)
  │
  ├── objectMapper.writeValueAsString(response)
  │   └── ACTUAL CPU: 장비 응답 JSON 직렬화 (큰 페이로드)
  │
  ├── repository.findById(CharacterId.of(ocid))
  │   └── SQL: SELECT ... FROM character_equipment WHERE ocid=?
  │
  └── repository.save(updated)
      └── SQL: INSERT/UPDATE character_equipment
```

**Leaf**: Jackson 직렬화 + DB 조회 + DB 저장. @Async + REQUIRES_NEW로 각 호출이 독립 트랜잭션.

---

### 1.5 SnapshotStore — avg 888ms, 9 calls

```
LocalSnapshotObjectStore.put(snapshot, data) [module-infra/.../external/snapshot/LocalSnapshotObjectStore.kt:31]
  TaskContext.of("SnapshotStore", "Put", objectKey)
  Semaphore(10) 획득
  │
  ├── gzipCompress(data)
  │   └── GZIPOutputStream 압축 (큰 스냅샷 바이트)
  │
  ├── sha256(compressed)
  │   └── MessageDigest SHA-256 해시
  │
  └── 파일 시스템 쓰기
      └── FileOutputStream(tempFile) → Files.move(tempFile, fullPath, ATOMIC_MOVE)
```

**Leaf**: GZIP 압축 + SHA-256 + 파일 시스템 atomic write. Semaphore(10) 대기 시간 포함 가능.

---

### 1.6 CubeService — avg 996ms, 7 calls

```
[현재 코드베이스에 "CubeService" TaskContext 없음]
추정: 큐브 확률 계산 경로
  │
  ├── CubeProbabilityRepositoryImpl — CSV 로드 인메모리 맵 조회
  │   key = "${CubeType}_${level}_${part}_${grade}_${slot}"
  │
  └── 큐브 기대값 계산 (CPU-bound 반복 확률 시뮬레이션)
```

---

### 1.7 PostgresQuery — avg 440ms, 173 calls

```
CharacterViewQueryServicePostgres [module-infra/.../persistence/CharacterViewQueryServicePostgres.kt]
  │
  ├── [Entry A] upsertFromCalculation()
  │   TaskContext.of("PostgresQuery", "UpsertFromCalculation", userIgn)
  │   ├── objectMapper.readValue(presetsJson, ...) -- JSON 역직렬화
  │   └── upsertNative(entity, presetsJson)
  │       └── SQL: INSERT INTO character_valuation_views (...) VALUES (...)
  │             ON CONFLICT (message_id) DO UPDATE SET ...
  │       └── asyncExecutor.submit { saveToReadModel(entity) }
  │
  ├── [Entry B] batchUpsertFromCalculations()   ★ 메인 경로
  │   TaskContext.of("PostgresQuery", "BatchUpsertFromCalculation", batchSize)
  │   StepTimer("PostgresQuery:BatchUpsertFromCalc")
  │   ├── [per cmd] parsePresets() → Jackson 역직렬화
  │   ├── timer.mark("prepareRows")
  │   ├── jdbc.batchUpdate(UPSERT_SQL, params)
  │   │   └── SQL: INSERT INTO character_valuation_views (...) VALUES (...)
  │   │         ON CONFLICT (message_id) DO UPDATE SET ...
  │   │         (batch 20-30건, CAST(:presets AS jsonb) 포함)
  │   ├── timer.mark("executeValuationViewUpsert")
  │   └── asyncExecutor.submit { saveToReadModelBatch(entities) }
  │
  └── [Entry C] findByUserIgnEntity()
      └── SQL: SELECT ... FROM character_valuation_views
            WHERE user_ign=? ORDER BY calculated_at DESC, id DESC LIMIT 1
```

**Leaf**: JDBC batch ON CONFLICT UPSERT + JSONB 파싱 + async read model fire-and-forget.

---

### 1.8 ResultProjection — avg 419ms, 378 calls

(아래 Section 2에서 상세 추적)

---

### 1.9 ReadModel — avg 361ms, 77 calls

```
CharacterViewQueryServicePostgres.saveToReadModel() / saveToReadModelBatch()
  [asyncExecutor.submit {} 에서 실행 — critical path 외부]
  │
  ├── serializeEntityToJson(entity)
  │   └── objectMapper.writeValueAsString(entity) -- 엔티티 전체 JSON 직렬화
  │
  ├── GzipUtils.compressUnchecked(json)
  │   └── GZIPOutputStream 압축 → BYTEA
  │
  └── repository.upsertNative() / jdbc.batchUpdate()
      └── SQL (단건): SELECT upsert_expectation_read_model(:userIgn, :payload, :calculatedAt)
          └── PL/pgSQL: INSERT INTO character_expectation_read_model
                ON CONFLICT (user_ign) DO UPDATE SET payload=..., calculated_at=...
                WHERE EXCLUDED.calculated_at >= character_expectation_read_model.calculated_at
      └── SQL (배치): INSERT INTO character_expectation_read_model (...) VALUES (...)
            ON CONFLICT (user_ign) DO UPDATE SET ...
            WHERE EXCLUDED.calculated_at >= ...
```

**Leaf**: JSON 직렬화 + GZIP 압축 + DB UPSERT. 비동기 fire-and-forget이지만 여전히 slow task로 기록됨.

---

### 1.10 CubeProbability — avg 2,004ms, 1 call

```
CubeProbabilityRepositoryImpl.init() [module-infra/.../repository/CubeProbabilityRepositoryImpl.kt:35]
  @PostConstruct (애플리케이션 시작 시 1회만 실행)
  TaskContext.of("CubeProbability", "InitCsvLoad")
  │
  ├── ClassPathResource("data/cube_probability.csv").inputStream
  │   └── CSV 파일 읽기
  │
  ├── CsvMapper + CsvSchema.withHeader()
  │   └── [per row] CubeProbability 객체 생성
  │       key = "${CubeType.name}_${level}_${part}_${grade}_${slot}"
  │       probabilityCache.computeIfAbsent(key) { mutableListOf() }.add(p)
  │
  └── Validation: count == 0 → throw CubeDataInitializationException
```

**Leaf**: @PostConstruct 1회 실행. CSV 파싱 + 인메모리 Map 구축. 재현 성능 이슈 아님.

---

## 2. ResultProjection Pipeline 내부 로직

### 2.1 loadCalculationResults — avg 98ms

```
ResultReadyProjectionWorker.projectPgmqBatch() [ResultReadyProjectionWorker.kt:80]
  │
  ├── CompletableFuture.supplyAsync({ findJobsByIds }, asyncExecutor)
  │   └── CalculationJobPortAdapter.findJobsByIds()
  │       └── jobRepository.findAllById(ids)
  │           └── SQL: SELECT ... FROM calculation_jobs WHERE job_id = ANY(?)
  │                 PK 인덱스 스캔 (UUID)
  │
  ├── CompletableFuture.supplyAsync({ findByJobIds }, asyncExecutor)
  │   └── CalculationResultPortAdapter.findByJobIds()
  │       └── CalculationResultRepository.findByJobIdIn()
  │           └── SQL: SELECT ... FROM calculation_results WHERE job_id IN (...)
  │                 idx_calc_results_job 인덱스 스캔
  │                 ⚠️ response_body BYTEA (GZIP 압축 결과) 전송
  │
  └── jobsFuture.join() + resultsFuture.join()
      timer.mark("loadCalculationResults")
```

**병목 포인트**: `findByJobIds`에서 `response_body BYTEA`(GZIP 압축)를 전송. 배치 100건 × 압축 결과 크기 = DB 네트워크 I/O.

---

### 2.2 buildViewRows — avg 85ms

```
buildPgmqProjectionCommands(parsed, jobsById, resultsByJobId) [ResultReadyProjectionWorker.kt:107]
  runBlocking(Dispatchers.Default)
  │
  └── [per message] async(Dispatchers.Default) {
        ├── jobsById[message.jobId]        -- O(1) Map 조회
        ├── resultsByJobId[message.jobId]  -- O(1) Map 조회
        │
        └── toPgmqProjectionCommand(message, job, resultData)
            ├── decompress(resultData.responseBody)
            │   └── GZIPInputStream — BYTEA → JSON String (CPU-bound)
            ├── objectMapper.readTree(resultJson)
            │   └── Jackson JSON 트리 파싱 (CPU-bound)
            ├── tree.get("totalExpectedCost")?.asLong()
            ├── tree.get("maxPresetNo")?.asInt()
            └── objectMapper.writeValueAsString(presetsNode)
                └── presets subtree 재직렬화
      }
```

**병목 포인트**: DB 호출 없음. 순수 CPU: GZIP 해제 + JSON 파싱 + JSON 재직렬화. 배치 100건을 ForkJoinPool에서 병렬 처리.

---

### 2.3 batchUpsertViews — avg 573ms ★ 최대 병목

```
viewQueryPort.batchUpsertFromCalculations(commands) [ResultReadyProjectionWorker.kt:96]
  └── CharacterViewQueryPortAdapter.batchUpsertFromCalculations()
      └── CharacterViewQueryServicePostgres.batchUpsertFromCalculations()
          TaskContext.of("PostgresQuery", "BatchUpsertFromCalculation", batchSize)
          │
          └── performBatchUpsert(commands)
              StepTimer("PostgresQuery:BatchUpsertFromCalc")
              │
              ├── [Phase 1: prepareRows] (CPU)
              │   └── [per cmd] parsePresets() → Jackson 역직렬화
              │       + MapSqlParameterSource 14개 파라미터 바인딩
              │
              ├── [Phase 2: executeValuationViewUpsert] (DB)
              │   └── jdbc.batchUpdate(SQL, params)
              │       └── SQL:
              │         INSERT INTO character_valuation_views (
              │           user_ign, message_id, jpa_version, character_ocid,
              │           character_class, character_level, calculated_at,
              │           last_api_sync_at, version, last_applied_version,
              │           total_expected_cost, max_preset_no, preset_no,
              │           presets, from_cache
              │         ) VALUES (
              │           :userIgn, :messageId, 0, :characterOcid,
              │           :characterClass, :characterLevel, :calculatedAt,
              │           :lastApiSyncAt, :version + 1, :lastAppliedVersion,
              │           :totalExpectedCost, :maxPresetNo, :presetNo,
              │           CAST(:presets AS jsonb), :fromCache
              │         )
              │         ON CONFLICT (message_id) DO UPDATE SET
              │           user_ign = EXCLUDED.user_ign,
              │           jpa_version = character_valuation_views.jpa_version + 1,
              │           ... (13 columns)
              │           presets = EXCLUDED.presets,
              │           from_cache = EXCLUDED.from_cache
              │
              │       [DB 동작]
              │       - Conflict target: message_id (unique index)
              │       - INSERT 시: CAST(:presets AS jsonb) → PostgreSQL JSONB 파싱
              │       - UPDATE 시: COALESCE 3개 + 버전 증가 + JSONB 업데이트
              │       - @Transactional → 배치 전체가 단일 트랜잭션 → 커밋 시 WAL fsync 1회
              │
              └── [Phase 3: async read model] (non-blocking)
                  └── asyncExecutor.submit { saveToReadModelBatch(entities) }
                      → GZIP 압축 + INSERT INTO character_expectation_read_model
```

**병목 포인트**:
- 배치 20-30건 × 15컬럼 + JSONB (`CAST(:presets AS jsonb)`) — PostgreSQL JSONB 파싱 오버헤드
- `ON CONFLICT` 시 unique index 체크 + COALESCE 3개 평가
- 단일 `@Transactional` → 커밋 시 WAL fsync

---

### 2.4 archiveMessages — avg 59ms

```
archiveIfNeeded(archiveIds) [ResultReadyProjectionWorker.kt:100]
  └── pgmqClient.archiveBatch("result_ready_queue", messageIds)
      └── PgmqClient.performArchiveBatch() [PgmqClient.kt:340]
          └── SQL:
            WITH deleted AS (
              DELETE FROM pgmq.q_result_ready_queue
              WHERE msg_id IN (?, ?, ...)         -- PK 인덱스 스캔
              RETURNING msg_id, read_ct, enqueued_at, vt, message
            )
            INSERT INTO pgmq.a_result_ready_queue (msg_id, read_ct, enqueued_at, vt, message)
              SELECT msg_id, read_ct, enqueued_at, vt, message FROM deleted
```

**DB 동작**: 단일 CTE로 원자적 DELETE + INSERT. `msg_id` PK 인덱스 스캔 (빠름). `message` JSONB 페이로드를 archive 테이블로 복사. 배치 ~100건의 전체 JSONB 페이로드 전송이 59ms의 주 원인.

---

## 3. 종합 병목 지도

```
[ExternalApiWorker]
  resolveAndFetch (491ms) ── Nexon API HTTP 호출
  pureCalculate (1,216ms) ── CPU 계산 ★
  completeCalculation (150ms) ── DB 3회 라운드트립
         │
         ▼ pgmq.send('result_ready')
[ResultReadyProjectionWorker]
  loadCalculationResults (98ms) ── 병렬 DB 2회 조회 ★ 최적화 완료
  buildViewRows (85ms) ── GZIP 해제 + JSON 파싱 (CPU)
  batchUpsertViews (573ms) ── JDBC batch UPSERT ★★ 현재 최대 병목
  archiveMessages (59ms) ── PGMQ CTE DELETE+INSERT
         │
         ▼ asyncExecutor (fire-and-forget)
[ReadModel]
  JSON 직렬화 + GZIP 압축 + DB UPSERT (361ms) ── 비동기, critical path 외부
```
