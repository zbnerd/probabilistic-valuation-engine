# StepTrace 부하테스트 리포트

날짜: 2026-05-02
브랜치: `chore/pgmq-only-result-projection`
조건: COUNT=10000, CONCURRENCY=50
StepTrace 임계값: 500ms (기본값)

---

## 1. ExternalApiWorker:PureCalculate (n=408)

평균 1,427ms. CPU 계산 + 결과 저장 파이프라인.
코드: `ExternalApiWorker.kt` → `runCalculationAndComplete()`

### loadInput — avg 50ms / p99 779ms

```kotlin
// ExternalApiWorker.kt:274
val input = stage("LoadInput", jobId.toString()) {
    calculationInputPort.findByJobId(jobId) ?: error("Calculation input missing: $jobId")
}
```

DB에서 `CalculationInput` 조회 (job_id 기반). p99 779ms는 DB 경합.

### pureCalculate — avg 1,054ms / p99 3,292ms (병목)

```kotlin
// ExternalApiWorker.kt:279
val calcResult = stage("PureCalculate", payload.userIgn) {
    runBlocking(cpuDispatcher) {
        withContext(cpuDispatcher) {
            pureCalculationPort.calculate(input)
        }
    }
}
```

순수 CPU 연산. `PureCalculationPort.calculate()`에서 확률 기댓값 계산 수행.
코루틴 `Dispatchers.Default` 위에서 실행. 캐릭터 장비 복잡도에 따라 편차 큼 (p50 1s, max 3.7s).
**최적화 여지 적음** — 알고리즘 자체가 무거움.

### serializeResult — avg 5ms / p99 77ms

```kotlin
// ExternalApiWorker.kt:288
val resultBytes = stage("SerializeResult", payload.userIgn) {
    objectMapper.writeValueAsString(calcResult).toByteArray()
}
```

Jackson으로 계산 결과 객체를 JSON 문자열로 직렬화.

### gzipResult — avg 2ms / p99 5ms

```kotlin
// ExternalApiWorker.kt:291
val gzipData = stage("GzipResult", payload.userIgn) {
    gzipCompress(resultBytes)
}
```

`GZIPOutputStream`으로 압축. 결과 JSON이 보통 수십 KB라 빠름.

### hashResult — avg 4ms / p99 15ms

```kotlin
// ExternalApiWorker.kt:294
val hash = stage("HashResult", payload.userIgn) {
    sha256Hex(resultBytes)
}
```

`MessageDigest.getInstance("SHA-256")`으로 무결성 해시 생성.

### completeCalculation — avg 305ms / p99 1,198ms

```kotlin
// ExternalApiWorker.kt:299
stage("CompleteCalculation", jobId.toString()) {
    executionService.completeCalculation(
        jobId = jobId,
        gzipData = gzipData,
        hash = hash,
        originalSize = resultBytes.size,
        compressedSize = gzipData.size,
        characterClass = characterClass,
        presetNo = payload.presetNo,
        characterId = characterId,
    )
}
```

`@Transactional` 내부에서:
1. `jobPort.completeFromSnapshotReady(jobId)` — CAS 전환 (`SNAPSHOT_READY → COMPLETED`)
2. `resultPort.saveIfAbsent(CalculationResultData)` — `INSERT ON CONFLICT DO NOTHING`
3. `pgmqClient.send(RESULT_READY, ...)` — PGMQ에 결과 준비 메시지 발행
4. TX 커밋 대기

p99 1.2초는 DB 쓰기 경합 + TX 커밋 대기.

---

## 2. ExternalApiWorker:ProcessMessage (n=8,798)

평균 797ms. 전체 파이프라인 (API 호출 → 스냅샷 → 계산).
코드: `ExternalApiWorker.kt` → `processPipeline()`

### findJob — avg 20ms / p99 181ms

```kotlin
// ExternalApiWorker.kt:147
val existingJob = jobPort.findJobById(jobId)
```

`calculation_jobs`에서 job 상태 조회. p99 181ms는 DB 경합.

### resolveAndFetch — avg 431ms / p99 1,390ms (병목)

```kotlin
// ExternalApiWorker.kt:179
val (ocid, equipmentResponse) = stage("ResolveAndFetch", payload.userIgn) {
    resolveOcidAndFetchEquipment(jobId, payload.userIgn, existingJob?.ocid)
}
```

```kotlin
// resolveOcidAndFetchEquipment() 내부:
val cached = jobOcid ?: ocidPort.resolveOcid(userIgn)   // OCID 캐시/DB 조회
// 캐시 미스 시:
val ocid = nexonApiClient.getOcid(userIgn)               // Nexon OCID API
val equipment = equipmentFetchProvider.fetch(ocid)       // Nexon 장비 API
```

OCID resolve + Nexon 외부 API 호출. **외부 I/O 병목** — 제어 불가.

### serializeSnapshot — avg 8ms / p99 29ms

```kotlin
// ExternalApiWorker.kt:184
val snapshotData = stage("SerializeSnapshot", payload.userIgn) {
    objectMapper.writeValueAsBytes(equipmentResponse)
}
```

Nexon API 응답을 바이트 배열로 직렬화.

### buildAndSaveInput — avg 59ms / p99 543ms

```kotlin
// ExternalApiWorker.kt:205-218
val inputItems = stage("BuildCalculationInput", payload.userIgn) {
    convertItems(equipmentResponse)  // 장비 아이템 변환 (병렬 async 가능)
}
val calcInput = CalculationInput(jobId=..., items=inputItems, ...)
stage("SaveCalculationInput", jobId.toString()) {
    calculationInputPort.saveIfAbsent(calcInput)  // INSERT ON CONFLICT DO NOTHING
}
```

장비 데이터 변환 + 계산 입력 DB 저장.

### awaitSnapshotPut — avg 0ms / p99 4ms

```kotlin
// ExternalApiWorker.kt:221
val putResult = stage("AwaitSnapshotPut", jobId.toString()) {
    snapshotFuture.join()  // 비동기 스냅샷 저장 완료 대기
}
```

`CompletableFuture.join()`. buildAndSaveInput과 병렬 실행되어 보통 이미 완료됨.

### saveSnapshotAndMarkReady — avg 48ms / p99 470ms

```kotlin
// ExternalApiWorker.kt:240
stage("SaveSnapshotAndMarkReady", jobId.toString()) {
    jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)
}
```

`@Transactional` 내부에서:
1. snapshot 엔티티 저장
2. job 상태 `API_REQUESTED → SNAPSHOT_READY` CAS 전환

### runCalculationAndComplete — avg 229ms / p99 1,940ms

```kotlin
// ExternalApiWorker.kt:245
runCalculationAndComplete(jobId, payload, ocid, characterClass)
```

위 **PureCalculate** 전체 포함 (loadInput → pureCalculate → serialize → gzip → hash → completeCalculation).

---

## 3. ResultProjection:ProjectBatch (n=86)

평균 1,301ms. PGMQ 메시지 읽기 → 뷰 프로젝션.
코드: `ResultReadyProjectionWorker.kt` → `projectPgmqBatch()`

### parseMessages — avg 0ms / p99 5ms

```kotlin
// ResultReadyProjectionWorker.kt:67-68
val archiveIds = mutableListOf<Long>()
val parsed = messages.mapNotNull { parsePgmqMessage(it, archiveIds) }
```

PGMQ 메시지에서 `jobId` 추출. 파싱 실패 시 archiveIds에 추가.

### loadCalculationResults — avg 315ms / p99 1,605ms

```kotlin
// ResultReadyProjectionWorker.kt:75-77
val jobIds = parsed.map { it.jobId }.distinct()
val jobsById = jobPort.findJobsByIds(jobIds).associateBy { it.jobId }
val resultsByJobId = resultPort.findByJobIds(jobIds).associateBy { it.jobId }
```

2건의 batch DB 조회:
1. `findJobsByIds()` — `calculation_jobs`에서 job 정보 (userIgn, ocid 등)
2. `findByJobIds()` — `calculation_results`에서 gzip 결과 데이터

p99 1.6s는 대량 batch + DB 경합.

### buildViewRows — avg 75ms / p99 1,009ms

```kotlin
// ResultReadyProjectionWorker.kt:79
val outcomes = buildPgmqProjectionCommands(parsed, jobsById, resultsByJobId)
```

```kotlin
// buildPgmqProjectionCommands() 내부 (병렬 async):
parsed.map { message ->
    async(Dispatchers.Default) {
        val resultJson = decompress(resultData.responseBody)  // GZIP 해제
        val tree = objectMapper.readTree(resultJson)           // JSON 파싱
        totalExpectedCost = tree.get("totalExpectedCost")?.asLong()
        presetsJson = objectMapper.writeValueAsString(presetsNode)
        CharacterViewProjectionCommand(userIgn=..., totalExpectedCost=..., presetsJson=...)
    }
}.awaitAll()
```

GZIP 해제 + JSON 파싱 + projection command 생성. 병렬 `Dispatchers.Default`.

### batchUpsertViews — avg 829ms / p99 3,093ms (병목)

```kotlin
// ResultReadyProjectionWorker.kt:85
if (commands.isNotEmpty()) {
    viewQueryPort.batchUpsertFromCalculations(commands)
}
```

→ 내부적으로 `PostgresQuery:BatchUpsertFromCalc` 호출 (아래 4번 참조).

### archiveMessages — avg 79ms / p99 985ms

```kotlin
// ResultReadyProjectionWorker.kt:89
archiveIfNeeded(archiveIds)
// 내부:
pgmqClient.archiveBatch(QueueNames.RESULT_READY, messageIds)
```

PGMQ 메시지 아카이브 (`SELECT pgmq.archive(_queue, msgId)`).

---

## 4. PostgresQuery:BatchUpsertFromCalc (n=49)

평균 1,149ms. JDBC batch `ON CONFLICT` 쓰기.
코드: `CharacterViewQueryServicePostgres.kt` → `performBatchUpsert()`

### prepareRows — avg 64ms / p99 960ms

```kotlin
// CharacterViewQueryServicePostgres.kt:210-245
val rows = commands.mapIndexed { index, command ->
    val version = versionBase + index
    val presets = parsePresets(command)  // JSON 파싱
    val entity = CharacterValuationViewEntity(
        userIgn = command.userIgn,
        messageId = command.messageId,
        totalExpectedCost = command.totalExpectedCost,
        presets = presets,
        // ...
    )
    entity to MapSqlParameterSource()
        .addValue("userIgn", entity.userIgn)
        .addValue("messageId", entity.messageId)
        .addValue("presets", command.presetsJson)  // JSONB
        // ... 14개 파라미터 바인딩
}
```

엔티티 생성 + JDBC 파라미터 바인딩. p99 960ms는 30개 batch + JSON 파싱.

### executeValuationViewUpsert — avg 713ms / p99 1,981ms (병목)

```kotlin
// CharacterViewQueryServicePostgres.kt:248-277
val counts = jdbc.batchUpdate("""
    INSERT INTO character_valuation_views (
        user_ign, message_id, ..., presets, from_cache
    ) VALUES (
        :userIgn, :messageId, ..., CAST(:presets AS jsonb), :fromCache
    )
    ON CONFLICT (message_id) DO UPDATE SET
        user_ign = EXCLUDED.user_ign,
        version = character_valuation_views.version + 1,
        last_applied_version = EXCLUDED.last_applied_version,
        total_expected_cost = EXCLUDED.total_expected_cost,
        presets = EXCLUDED.presets,
        ...
    WHERE character_valuation_views.last_applied_version < EXCLUDED.last_applied_version
""", rows.map { it.second }.toTypedArray())
```

JDBC batch `ON CONFLICT (message_id) DO UPDATE` + 버전 체크 `WHERE version <`.
배치 사이즈 30. 쓰기 경합이 주요 병목.

### executeReadModelUpsert — avg 370ms / p99 1,415ms

```kotlin
// CharacterViewQueryServicePostgres.kt:280
saveToReadModelBatch(rows.map { it.first })
```

`character_expectation_read_model` 테이블에 batch upsert.
`ON CONFLICT (game_character_id, preset_no) DO UPDATE SET`.

---

## 기존 Slow Task 건수 (LoggingPolicy)

| 건수 | Operation |
|---:|---|
| 21,014 | PgmqWorker:ProcessMessage |
| 10,501 | ExternalApiWorker:ProcessMessage |
| 9,994 | ExternalApiWorker:ResolveAndFetch |
| 690 | ExternalApiWorker:PureCalculate |
| 379 | ExternalApiWorker:CompleteCalculation |
| 172 | ResultProjection:ProjectBatch:30 |
| 116 | PostgresQuery:BatchUpsertFromCalculation:30 |

---

## 결론

| 병목 조각 | 원인 | 코드 | 최적화 방향 |
|---|---|---|---|
| `pureCalculate` | 순수 CPU 연산 (확률 기댓값) | `pureCalculationPort.calculate(input)` | 알고리즘 개선 또는 캐릭터별 캐싱 |
| `executeValuationViewUpsert` | JDBC `ON CONFLICT` 쓰기 경합 | `jdbc.batchUpdate(INSERT ... ON CONFLICT ... WHERE version <)` | batch size 조정, connection pool 튜닝 |
| `resolveAndFetch` | Nexon 외부 API I/O | `ocidPort.resolveOcid()` + `equipmentFetchProvider.fetch()` | 외부 제어 불가, 재시도/타임아웃 조정만 가능 |
| `completeCalculation` | TX 커밋 대기 | CAS 전환 + `saveIfAbsent` + `pgmqClient.send()` | DB 경합, HikariCP pool sizing |
| `loadCalculationResults` | batch DB 조회 | `findJobsByIds()` + `findByJobIds()` | batch size 대비 인덱스 확인 |
