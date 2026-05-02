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

`CalculationInputRepository.findByJobId()` — JPA로 `calculation_inputs` 테이블에서 `job_id` 기반 조회. p99 779ms는 DB 경합.

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

**호출 체인**:

① `PureCalculationAdapter.calculate()` → ② `PureExpectationCalculator.calculate()` → ③ `PresetCalculationHelper.calculatePreset()`

```kotlin
// PureCalculationAdapter.kt:13 — module-app
override fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 =
    calculator.calculate(input)
```

```kotlin
// PureExpectationCalculator.kt:13-32 — module-app
fun calculate(input: CalculationInput): EquipmentExpectationResponseV4 {
    val cubeInputs = input.items.map { EquipmentItemConverter.toCubeInput(it) }

    val preset = presetHelper.calculatePreset(
        cubeInputs,
        input.presetNo,
        input.characterClass,
    )

    return EquipmentExpectationResponseV4(
        userIgn = input.userIgn,
        calculatedAt = LocalDateTime.now(),
        fromCache = false,
        totalExpectedCost = preset.totalExpectedCost,
        totalCostText = preset.totalCostText,
        totalCostBreakdown = preset.costBreakdown,
        maxPresetNo = input.presetNo,
        presets = listOf(preset),
    )
}
```

**핵심 병목** — `PresetCalculationHelper.calculatePreset()` (`PresetCalculationHelper.java:89-117`):

```java
// 장비별 순차 계산 루프 — 캐릭터당 장비 수(13~22개)만큼 반복
for (var cubeInput : cubeInputs) {
    EquipmentCalculationInput input = buildInput(cubeInput, presetNo);
    item = calculateSingleItem(input, cubeInput, characterClass);  // ← 여기가 무거움
    costAcc.add(item.getExpectedCost());
}
```

**`calculateSingleItem` 내부** (`PresetCalculationHelper.java:240-248`):

```java
private ItemExpectationV4 calculateSingleItem(...) {
    // Decorator 패턴으로 구성된 계산기 체인
    EquipmentExpectationCalculator calculator = calculatorFactory.createFullCalculator(input);
    double itemCost = calculator.calculateCost();       // ← 무거운 CPU 연산
    var costBreakdown = calculator.getDetailedCosts();
    return buildItemResult(input, cubeInput, itemCost, costBreakdown,
        calculator.getEnhancePath(), characterClass);
}
```

**`EquipmentExpectationCalculatorFactory.createFullCalculator()`** (`EquipmentExpectationCalculatorFactory.java:48-78`):

```java
// Decorator 체인 구성 (장비 특성에 따라 선택적 연결):
// BaseEquipmentItem → BlackCubeDecoratorV4 → AdditionalCubeDecoratorV4 → StarforceDecoratorV4
public EquipmentExpectationCalculator createFullCalculator(EquipmentCalculationInput input) {
    EquipmentExpectationCalculator calculator =
        new BaseEquipmentItem(input.getItemName(), input.getItemLevel(), input.getCurrentStar());

    if (input.hasPotential()) {
        calculator = new BlackCubeDecoratorV4(calculator, trialsProvider, costPolicy, potentialInput);
    }
    if (input.hasAdditionalPotential()) {
        calculator = new AdditionalCubeDecoratorV4(calculator, trialsProvider, costPolicy, additionalInput);
    }
    if (input.hasStarforce()) {
        calculator = new StarforceDecoratorV4(calculator, starforceLookupPort,
            input.getCurrentStar(), input.getTargetStar(), input.getItemLevel());
    }
    return calculator;
}
```

**`AbstractCubeDecoratorV4.calculateCost()`** (`AbstractCubeDecoratorV4.java:161-178`) — Decorator 체인 비용 누적:

```java
public double calculateCost() {
    double previousCost = super.calculateCost();            // 이전 단계 누적 비용
    double expectedTrials = delegate.calculateTrials();     // 기하분포 기대 시도 횟수 계산
    long roundedTrials = Math.round(expectedTrials);        // 정수 반올림
    double costPerTrial = delegate.getLongCostPerTrial();   // 큐브 단가 (레벨×등급)
    double cubeCost = roundedTrials * costPerTrial;         // 큐브 총 비용
    return previousCost + cubeCost;                         // 누적
}
```

`calculateTrials()` 내부에서 기하분포 확률 계산 수행. 장비 등급, 잠재옵션 조합에 따라 시도 횟수가 기하급수적으로 증가. `Dispatchers.Default` 코루틴 위에서 실행. **최적화 여지 적음** — 알고리즘 자체가 무거움 (장비 13~22개 × Decorator 체인 × 확률 계산).

### serializeResult — avg 5ms / p99 77ms

```kotlin
// ExternalApiWorker.kt:288
val resultBytes = stage("SerializeResult", payload.userIgn) {
    objectMapper.writeValueAsString(calcResult).toByteArray()
}
```

Jackson `ObjectMapper.writeValueAsString()`으로 계산 결과 객체 → JSON 문자열 → `ByteArray` 변환.

### gzipResult — avg 2ms / p99 5ms

```kotlin
// ExternalApiWorker.kt:291
val gzipData = stage("GzipResult", payload.userIgn) {
    gzipCompress(resultBytes)
}

// ExternalApiWorker.kt:417
private fun gzipCompress(data: ByteArray): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
    return bos.toByteArray()
}
```

`GZIPOutputStream`으로 압축. 결과 JSON이 보통 수십 KB라 빠름.

### hashResult — avg 4ms / p99 15ms

```kotlin
// ExternalApiWorker.kt:294
val hash = stage("HashResult", payload.userIgn) {
    sha256Hex(resultBytes)
}

// ExternalApiWorker.kt:422
private fun sha256Hex(data: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(data).joinToString("") { "%02x".format(it) }
}
```

`MessageDigest("SHA-256")`으로 무결성 해시 생성.

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

**내부 구현** (`CalculationExecutionService.kt:43-86`):

```kotlin
fun completeCalculation(
    jobId: UUID, gzipData: ByteArray, hash: String,
    originalSize: Int, compressedSize: Int,
    characterClass: String, presetNo: Int, characterId: String,
): Boolean {
    // 1. CAS 전환: SNAPSHOT_READY → COMPLETED
    val completed = jobPort.completeFromSnapshotReady(jobId)
    if (!completed) return false

    // 2. 결과 저장: INSERT ON CONFLICT DO NOTHING
    resultPort.saveIfAbsent(
        CalculationResultData(
            resultId = UUID.randomUUID(),
            jobId = jobId,
            characterClass = characterClass,
            presetNo = presetNo,
            schemaVersion = 1,
            contentType = "application/json",
            contentEncoding = "gzip",
            responseBody = gzipData,
            originalSize = originalSize,
            compressedSize = compressedSize,
            hash = hash,
            status = "SUCCESS",
        ),
    )

    // 3. PGMQ 메시지 발행
    pgmqClient.send(
        QueueNames.RESULT_READY,
        mapOf(
            "jobId" to jobId.toString(),
            "characterId" to characterId,
            "presetNo" to presetNo,
            "contentEncoding" to "gzip",
            "schemaVersion" to 1,
        ),
    )

    log.info("[jobId={}] Calculation completed (optimized single-TX)", jobId)
    return true
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

**내부 구현** (`ExternalApiWorker.kt:340-374`):

```kotlin
private fun resolveOcidAndFetchEquipment(
    jobId: UUID, userIgn: String, jobOcid: String?
): Pair<String, EquipmentResponse> {
    // 빠른 경로: OCID 캐시 히트
    val cached = jobOcid ?: ocidPort.resolveOcid(userIgn)
    if (cached != null) {
        jobService.resolveOcidInPlace(jobId, cached)
        return Pair(cached, equipmentFetchProvider.fetchWithCache(cached))
    }

    // 느린 경로: Nexon API 호출 체인
    return nexonApiClient.getOcidByCharacterName(userIgn)  // ① Nexon OCID API
        .handle { result, ex ->                            // ② 에러 핸들링
            if (ex != null) {
                log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                throw ExceptionUtils.unwrapAs(ex, CharacterNotFoundException::class.java) ?: ex
            } else { result }
        }
        .thenApply { response ->                           // ③ OCID 검증
            if (response == null || response.ocid.isBlank()) {
                throw CharacterNotFoundException(userIgn)
            }
            response.ocid
        }
        .thenApply { ocid ->                               // ④ OCID DB 저장
            jobService.resolveOcidInPlace(jobId, ocid)
            ocid
        }
        .thenCompose { ocid ->                             // ⑤ 장비 API 호출
            CompletableFuture.supplyAsync({
                Pair(ocid, equipmentFetchProvider.fetchWithCache(ocid))
            }, apiCallPool)                                // virtual thread pool
        }
        .orTimeout(15, TimeUnit.SECONDS)                   // 15초 타임아웃
        .join()
}
```

OCID resolve + Nexon 외부 API 호출. 캐시 히트 시 동기 빠른 경로, 미스 시 `getOcidByCharacterName` → `resolveOcidInPlace` → `fetchWithCache` CF 체인 후 `.join()`. **외부 I/O 병목** — 제어 불가.

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
    convertItems(equipmentResponse)
}
val calcInput = CalculationInput(jobId=..., items=inputItems, ...)
stage("SaveCalculationInput", jobId.toString()) {
    calculationInputPort.saveIfAbsent(calcInput)
}
```

**`convertItems` 구현** (`ExternalApiWorker.kt:313-331`):

```kotlin
private fun convertItems(equipmentResponse: EquipmentResponse): List<EquipmentItem> {
    val items = equipmentResponse.itemEquipment ?: return emptyList()
    if (items.size < PARALLEL_ITEM_CONVERSION_THRESHOLD) {
        return items.map { convertItem(it) }           // 임계값 미만: 순차
    }
    return runBlocking(Dispatchers.Default) {           // 임계값 이상: 병렬
        items.map { item ->
            async(Dispatchers.Default) { convertItem(item) }
        }.awaitAll()
    }
}

private fun convertItem(item: Any): EquipmentItem {
    val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>
    return converter.convertItem(itemMap)
}
```

장비 데이터 변환 + 계산 입력 DB 저장 (`INSERT ON CONFLICT DO NOTHING`).

### awaitSnapshotPut — avg 0ms / p99 4ms

```kotlin
// ExternalApiWorker.kt:221
val putResult = stage("AwaitSnapshotPut", jobId.toString()) {
    snapshotFuture.join()
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

**내부 구현** (`CalculationJobService.kt:184-191`):

```kotlin
fun saveInputSnapshotAndMarkReady(
    snapshotEntity: CalculationSnapshotEntity,
    jobId: UUID,
    snapshotId: UUID,
): Boolean {
    snapshotRepository.save(snapshotEntity)                          // ① 스냅샷 저장
    return jobPort.markSnapshotReady(jobId, snapshotId,              // ② CAS: API_REQUESTED → SNAPSHOT_READY
        CalculationJobStatus.API_REQUESTED)
}
```

`@Transactional` 내부에서 snapshot 엔티티 저장 + job 상태 CAS 전환.

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

**`parsePgmqMessage` 구현** (`ResultReadyProjectionWorker.kt:103-114`):

```kotlin
private fun parsePgmqMessage(
    message: PgmqMessage, archiveIds: MutableList<Long>
): PgmqProjectionMessage? {
    val payload = message.payload
    val jobIdStr = payload["jobId"]?.toString() ?: run {
        archiveIds += message.messageId          // 파싱 실패 → archive
        return null
    }
    val jobId = runCatching { UUID.fromString(jobIdStr) }.getOrNull() ?: run {
        archiveIds += message.messageId
        return null
    }
    return PgmqProjectionMessage(message.messageId, payload, jobId)
}
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

**`buildPgmqProjectionCommands` 구현** (`ResultReadyProjectionWorker.kt:83-102`):

```kotlin
private fun buildPgmqProjectionCommands(
    parsed: List<PgmqProjectionMessage>,
    jobsById: Map<UUID, CalculationJob>,
    resultsByJobId: Map<UUID, CalculationResultData>,
): List<PgmqProjectionOutcome> = runBlocking(Dispatchers.Default) {
    parsed.map { message ->
        async(Dispatchers.Default) {
            val job = jobsById[message.jobId]
            val resultData = resultsByJobId[message.jobId]
            when {
                job == null || resultData == null ->
                    PgmqProjectionOutcome(message.messageId, archive = true)
                else -> PgmqProjectionOutcome(
                    messageId = message.messageId,
                    command = toPgmqProjectionCommand(message, job, resultData),
                    archive = true,
                )
            }
        }
    }.awaitAll()
}
```

**`toPgmqProjectionCommand` 구현** (`ResultReadyProjectionWorker.kt:117-143`):

```kotlin
private fun toPgmqProjectionCommand(
    message: PgmqProjectionMessage,
    job: CalculationJob,
    resultData: CalculationResultData,
): CharacterViewProjectionCommand? {
    val resultJson = decompress(resultData.responseBody)     // GZIP 해제
    val tree = objectMapper.readTree(resultJson)              // JSON 파싱

    val totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: return null
    val maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: return null
    val presetsNode = tree.get("presets")
    val presetNo = (message.payload["presetNo"] as? Number)?.toInt() ?: 1
    val characterId = message.payload["characterId"]?.toString()
    val presetsJson = if (presetsNode != null) objectMapper.writeValueAsString(presetsNode) else "[]"

    return CharacterViewProjectionCommand(
        userIgn = job.userIgn,
        messageId = message.messageId.toString(),
        characterOcid = characterId,
        characterClass = resultData.characterClass,
        characterLevel = null,
        totalExpectedCost = totalExpectedCost,
        maxPresetNo = maxPresetNo,
        presetNo = presetNo,
        presetsJson = presetsJson,
    )
}
```

**`decompress` 구현** (`ResultReadyProjectionWorker.kt:151-153`):

```kotlin
private fun decompress(data: ByteArray): String {
    GZIPInputStream(data.inputStream()).use { return String(it.readAllBytes()) }
}
```

병렬 `Dispatchers.Default` 위에서 각 메시지별로 GZIP 해제 + JSON 파싱 + projection command 생성.

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
```

**`archiveIfNeeded` 구현** (`ResultReadyProjectionWorker.kt:145-149`):

```kotlin
private fun archiveIfNeeded(messageIds: List<Long>) {
    if (messageIds.isNotEmpty()) {
        pgmqClient.archiveBatch(QueueNames.RESULT_READY, messageIds)
    }
}
```

PGMQ 메시지 아카이브 (`SELECT pgmq.archive(_queue, msgId)`).

---

## 4. PostgresQuery:BatchUpsertFromCalc (n=49)

평균 1,149ms. JDBC batch `ON CONFLICT` 쓰기.
코드: `CharacterViewQueryServicePostgres.kt`

### 진입점

```kotlin
// CharacterViewQueryServicePostgres.kt:91-95
fun batchUpsertFromCalculations(commands: List<CharacterViewProjectionCommand>): Int {
    if (commands.isEmpty()) return 0
    val context = TaskContext.of("PostgresQuery", "BatchUpsertFromCalculation", commands.size.toString())
    return executor.executeOrDefault({ performBatchUpsert(commands) }, 0, context)
}
```

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
        user_ign, message_id, jpa_version, character_ocid, character_class, character_level,
        calculated_at, last_api_sync_at, version, last_applied_version,
        total_expected_cost, max_preset_no, preset_no, presets, from_cache
    ) VALUES (
        :userIgn, :messageId, 0, :characterOcid, :characterClass, :characterLevel,
        :calculatedAt, :lastApiSyncAt, :version + 1, :lastAppliedVersion,
        :totalExpectedCost, :maxPresetNo, :presetNo, CAST(:presets AS jsonb), :fromCache
    )
    ON CONFLICT (message_id) DO UPDATE SET
        user_ign = EXCLUDED.user_ign,
        jpa_version = character_valuation_views.jpa_version + 1,
        character_ocid = COALESCE(EXCLUDED.character_ocid, character_valuation_views.character_ocid),
        character_class = COALESCE(EXCLUDED.character_class, character_valuation_views.character_class),
        character_level = COALESCE(EXCLUDED.character_level, character_valuation_views.character_level),
        calculated_at = EXCLUDED.calculated_at,
        last_api_sync_at = COALESCE(EXCLUDED.last_api_sync_at, character_valuation_views.last_api_sync_at),
        version = character_valuation_views.version + 1,
        last_applied_version = EXCLUDED.last_applied_version,
        total_expected_cost = EXCLUDED.total_expected_cost,
        max_preset_no = EXCLUDED.max_preset_no,
        preset_no = EXCLUDED.preset_no,
        presets = EXCLUDED.presets,
        from_cache = EXCLUDED.from_cache
    WHERE character_valuation_views.last_applied_version < EXCLUDED.last_applied_version
""", rows.map { it.second }.toTypedArray())
```

JDBC batch `ON CONFLICT (message_id) DO UPDATE` + 버전 체크 `WHERE last_applied_version <`.
배치 사이즈 30. `COALESCE`로 nullable 필드 보존. 쓰기 경합이 주요 병목.

### executeReadModelUpsert — avg 370ms / p99 1,415ms

```kotlin
// CharacterViewQueryServicePostgres.kt:280
saveToReadModelBatch(rows.map { it.first })
```

**`saveToReadModelBatch` 구현** (`CharacterViewQueryServicePostgres.kt:414-432`):

```kotlin
private fun saveToReadModelBatch(entities: List<CharacterValuationViewEntity>) {
    val commands = entities.map { entity ->
        val calculatedAt = entity.calculatedAt
            ?: throw IllegalStateException("calculatedAt must be set before writing to read model: userIgn=${entity.userIgn}")
        ReadModelWriteCommand(
            userIgn = entity.userIgn,
            json = serializeEntityToJson(entity),       // ObjectMapper로 직렬화
            calculatedAt = calculatedAt,
        )
    }
    executor.executeOrCatch(
        { readModelWriteService.writeToReadModelRawBatch(commands) },
        { e ->
            log.warn("[ReadModel] Non-fatal batch write failure (will retry on next calculation): rows={}", entities.size, e)
            0
        },
        TaskContext.of("ReadModel", "BestEffortBatchWrite", entities.size.toString()),
    )
}
```

`character_expectation_read_model` 테이블에 batch upsert.
`ON CONFLICT (game_character_id, preset_no) DO UPDATE SET`.
실패해도 non-fatal (다음 계산 주기에서 자가 복구).

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
