# Issue #1104 @Transactional Hygiene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring 4 files in `module-infra` into compliance with `data-access.md` Transaction Scope rules: explicit `readOnly` + `transactionManager` on every `@Transactional`, `@Async`/`@Transactional` proxy split, CPU work (gzip/hash, JSON parse) moved out of transaction, class-level `@Transactional` defaulting to `readOnly = true`.

**Architecture:** Mechanical hygiene refactor. No behavior change. Method-name heuristic drives readOnly classification: `find*/get*/list*/count*/exists*/check*` → `readOnly = true`; everything else → `readOnly = false`. Self-injected proxy (`@Lazy` self) splits `@Async` from `@Transactional`. Caller pre-computes CPU work before invoking `@Transactional` method.

**Tech Stack:** Kotlin 1.x, Spring `@Transactional`/`@Async`, Jackson `ObjectMapper`, JPA, PGMQ.

---

## File Structure

### Modified files (4)

| File | Responsibility for this PR |
|------|---------------------------|
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt` | Add `value = "transactionManager"` and `readOnly = …` to all 16 `@Transactional` annotations. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt` | Split `@Async` from `@Transactional` via `@Lazy` self-injection. Move existing `@Transactional` to private `*InTx` methods. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt` | Move `gzipCompress` + `sha256Hex` out of TX in `startAndCompleteCalculation` + `completeCalculationWithResult`. Add `value`/`readOnly` to all 6 `@Transactional`. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationRepositoryImpl.kt` | Class-level `@Transactional("transactionManager", readOnly = true)`. Method-level override on `save`, `deleteByUserIgn`, `deleteAll`, `upsertByVersion`. |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt` | Hoist `objectMapper.readValue()` out of `upsertFromCalculation` TX. Caller passes parsed `presets: List<PresetView>?`. Keep `value`/`readOnly` consistent. |

### New files (0)

### Test files (0)

No unit tests added. Existing test suite (`./gradlew test`) must pass unchanged — this is a refactor with no observable behavior change.

---

## Read/Write Classification Heuristic

Apply uniformly to `CalculationJobService`:

| Prefix | readOnly | Examples in `CalculationJobService` |
|--------|---------:|--------------------------------------|
| `find*`, `get*`, `list*`, `count*`, `exists*`, `check*` | `true` | none in this file |
| All other names | `false` | all 16 methods |

Every method in `CalculationJobService` writes (port call mutates state, snapshot save, queue send, status transition). So all 16 → `readOnly = false`. Document this in the diff comment.

---

## Task 1: CalculationJobService — explicit readOnly + transactionManager

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt:38, 45, 56, 72, 87, 97, 112, 128, 146, 155, 164, 180, 183, 193, 211, 217`

- [ ] **Step 1: Replace all 16 bare `@Transactional` annotations**

For each of the 16 method declarations (`createJob`, `createOrFindActiveJob`, `requestOcidResolve`, `resolveOcidAndEnqueueApiData`, `saveSnapshotAndMarkReady`, `markSnapshotReady`, `handleApiFailure`, `handleOcidFailure`, `retryOcidResolvingJob`, `retryApiRequestedJob`, `dispatchToExternalApi`, `resolveOcidInPlace`, `saveInputSnapshotAndMarkReady`, `saveInputSnapshotAndDispatchCalculation`, `dispatchCalculationCompleted`, `retryExternalApiJob`), replace:

```kotlin
    @Transactional
    fun <methodName>(...)
```

with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    fun <methodName>(...)
```

All 16 methods are state-mutating (port writes, snapshot saves, queue sends, status transitions). Every `readOnly = false`.

- [ ] **Step 2: Verify count is now 16 lines containing `value = "transactionManager"`**

Run: `grep -c 'value = "transactionManager"' module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`
Expected: `16`

- [ ] **Step 3: Verify no bare `@Transactional` lines remain**

Run: `grep -n '^    @Transactional$\|^    @Transactional *$' module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`
Expected: no output.

- [ ] **Step 4: Compile**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL` or only unrelated warnings.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt
git commit -m "fix(infra): add readOnly + transactionManager to CalculationJobService @Transactional"
```

---

## Task 2: EquipmentDbWorker — split @Async from @Transactional

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt:33-148`

The current pattern (`@Async` + `@Transactional` on same method) does not work via Spring AOP proxy — only the outer annotation is honored. Fix: keep `@Async` on the public method, route the work through `@Lazy`-injected self proxy to a private `@Transactional` method.

- [ ] **Step 1: Add `@Lazy` self-injection to constructor**

Replace constructor (lines 33-39):

```kotlin
@Component
class EquipmentDbWorker(
    private val repository: CharacterEquipmentRepository,
    private val objectMapper: ObjectMapper,
    private val persistenceTracker: PersistenceTrackerStrategy,
    private val executor: LogicExecutor,
) {
```

with:

```kotlin
@Component
class EquipmentDbWorker(
    private val repository: CharacterEquipmentRepository,
    private val objectMapper: ObjectMapper,
    private val persistenceTracker: PersistenceTrackerStrategy,
    private val executor: LogicExecutor,
    @Lazy private val self: EquipmentDbWorker,
) {
```

Add the import:

```kotlin
import org.springframework.context.annotation.Lazy
```

- [ ] **Step 2: Refactor `persist` (lines 44-67) — keep `@Async`, move TX into private method**

Replace the entire `persist` method with:

```kotlin
    @Async
    fun persist(ocid: String, response: EquipmentResponse): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val context = TaskContext.of("EquipmentWorker", "AsyncPersist", ocid)

        persistenceTracker.trackOperation(ocid, future)

        return executor.executeOrCatch(
            {
                self.persistInTx(ocid, response, context)
                log.debug("💾 [Async DB Save Success] ocid: {}", ocid)
                future.complete(null)
                future
            },
            { e ->
                log.error("❌ [Async DB Save Error] ocid: {} | 사유: {}", ocid, e.message)
                future.completeExceptionally(e)
                future
            },
            context,
        )
    }

    @Transactional(value = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun persistInTx(ocid: String, response: EquipmentResponse, context: TaskContext) {
        performSave(ocid, response, context)
    }
```

Note: `persistInTx` is `fun` (not `private`) so the `@Lazy` self proxy call goes through Spring AOP.

- [ ] **Step 3: Refactor `findValidJson` (line 97) — add `value` and confirm `readOnly`**

Replace:

```kotlin
    @Transactional("transactionManager", readOnly = true)
    fun findValidJson(ocid: String): Optional<String> {
```

with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = true)
    fun findValidJson(ocid: String): Optional<String> {
```

(No structural change — alignment only. Keep behavior identical.)

- [ ] **Step 4: Refactor `persistRawJson` (lines 126-148) — same pattern as `persist`**

Replace the `persistRawJson` method body with:

```kotlin
    @Async
    fun persistRawJson(ocid: String, json: String): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val context = TaskContext.of("EquipmentDb", "PersistRaw", ocid)

        persistenceTracker.trackOperation(ocid, future)

        return executor.executeOrCatch(
            {
                self.persistRawJsonInTx(ocid, json)
                log.debug("💾 [DB Save] Raw JSON saved: ocid: {}", StringMaskingUtils.maskOcid(ocid))
                future.complete(null)
                future
            },
            { e ->
                log.error("❌ [DB Save Error] ocid: {} | err={}", StringMaskingUtils.maskOcid(ocid), e.message)
                future.completeExceptionally(e)
                future
            },
            context,
        )
    }

    @Transactional(value = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun persistRawJsonInTx(ocid: String, json: String) {
        performRawSave(ocid, json)
    }
```

- [ ] **Step 5: Verify all 4 `@Transactional` lines use `value = "transactionManager"`**

Run: `grep -n '@Transactional' module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt`
Expected: 4 lines, each containing `value = "transactionManager"`. `persist` and `persistRawJson` retain only `@Async`.

- [ ] **Step 6: Compile**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt
git commit -m "fix(infra): split @Async from @Transactional in EquipmentDbWorker via self proxy"
```

---

## Task 3: CalculationExecutionService — move gzip/hash out of TX, explicit readOnly

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt:28, 42, 98, 150, 202, 231`

Two methods contain CPU work inside `@Transactional`: `startAndCompleteCalculation` (line 150) and `completeCalculationWithResult` (line 231). Each computes `gzipData` + `hash` after `transitionStatus(COMPLETED)` but before `resultPort.save`. Hoist that work above the `@Transactional` boundary by extracting a public non-TX wrapper that pre-computes and delegates to a `*InTx` method.

- [ ] **Step 1: Add `value` + `readOnly` to `startCalculation` (line 28)**

Replace:

```kotlin
    @Transactional
    fun startCalculation(jobId: UUID, workerId: String): Boolean {
```

with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    fun startCalculation(jobId: UUID, workerId: String): Boolean {
```

- [ ] **Step 2: Add `value` + `readOnly` to `completeCalculation` (line 42)**

Replace:

```kotlin
    @Transactional
    fun completeCalculation(
```

with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    fun completeCalculation(
```

- [ ] **Step 3: Add `value` + `readOnly` to `completeCalculatedResult` (line 98)**

Replace:

```kotlin
    @Transactional
    fun completeCalculatedResult(
```

with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    fun completeCalculatedResult(
```

- [ ] **Step 4: Add `value` + `readOnly` to `handleCalculationFailure` (line 202)**

Replace:

```kotlin
    @Transactional
    fun handleCalculationFailure(jobId: UUID, errorCode: String, errorMessage: String) {
```

with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    fun handleCalculationFailure(jobId: UUID, errorCode: String, errorMessage: String) {
```

- [ ] **Step 5: Refactor `startAndCompleteCalculation` (line 150) — hoist gzip/hash out of TX**

Replace the entire method (lines 150-200) with:

```kotlin
    fun startAndCompleteCalculation(
        jobId: UUID,
        workerId: String,
        resultJson: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
    ): Boolean {
        // CPU work outside TX boundary (gzip + SHA-256)
        val rawBytes = resultJson.toByteArray()
        val gzipData = gzipCompress(rawBytes)
        val hash = sha256Hex(rawBytes)
        return startAndCompleteCalculationInTx(
            jobId = jobId,
            workerId = workerId,
            characterClass = characterClass,
            presetNo = presetNo,
            characterId = characterId,
            gzipData = gzipData,
            hash = hash,
            originalSize = rawBytes.size,
            compressedSize = gzipData.size,
        )
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun startAndCompleteCalculationInTx(
        jobId: UUID,
        workerId: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
        gzipData: ByteArray,
        hash: String,
        originalSize: Int,
        compressedSize: Int,
    ): Boolean {
        val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
        if (!locked) return false
        jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)

        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (!completed) return false

        resultPort.save(
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

        jobPort.unlock(jobId)
        log.info("[jobId={}] Calculation completed with result saved", jobId)
        return true
    }
```

- [ ] **Step 6: Refactor `completeCalculationWithResult` (line 231) — same hoist**

Replace the entire method (lines 231-276) with:

```kotlin
    fun completeCalculationWithResult(
        jobId: UUID,
        resultJson: String,
        characterClass: String,
        presetNo: Int,
        characterId: String,
    ): Boolean {
        // CPU work outside TX boundary (gzip + SHA-256)
        val rawBytes = resultJson.toByteArray()
        val gzipData = gzipCompress(rawBytes)
        val hash = sha256Hex(rawBytes)
        return completeCalculationWithResultInTx(
            jobId = jobId,
            characterClass = characterClass,
            presetNo = presetNo,
            characterId = characterId,
            gzipData = gzipData,
            hash = hash,
            originalSize = rawBytes.size,
            compressedSize = gzipData.size,
        )
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun completeCalculationWithResultInTx(
        jobId: UUID,
        characterClass: String,
        presetNo: Int,
        characterId: String,
        gzipData: ByteArray,
        hash: String,
        originalSize: Int,
        compressedSize: Int,
    ): Boolean {
        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (!completed) return false

        resultPort.save(
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

        jobPort.unlock(jobId)
        log.info("[jobId={}] Calculation completed with result saved", jobId)
        return true
    }
```

- [ ] **Step 7: Verify all 6 `@Transactional` annotations are explicit**

Run: `grep -n '@Transactional' module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt`
Expected: 6 lines, all containing `value = "transactionManager"` and `readOnly = false`. No bare `@Transactional`.

- [ ] **Step 8: Compile**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt
git commit -m "fix(infra): move gzip+hash out of TX, add readOnly + transactionManager in CalculationExecutionService"
```

---

## Task 4: CharacterValuationRepositoryImpl — class-level readOnly default

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationRepositoryImpl.kt:32, 77, 89, 98, 157`

- [ ] **Step 1: Change class-level annotation to `readOnly = true`**

Replace line 32:

```kotlin
@Transactional("transactionManager")
```

with:

```kotlin
@Transactional(value = "transactionManager", readOnly = true)
```

- [ ] **Step 2: Add method-level override on `save` (line 77)**

Insert one annotation line above `fun save` (currently line 77):

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    open fun save(entity: CharacterValuationEntity): CharacterValuationEntity {
```

- [ ] **Step 3: Add method-level override on `deleteByUserIgn` (line 89)**

Insert one annotation line above `fun deleteByUserIgn`:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    open fun deleteByUserIgn(userIgn: String?) {
```

- [ ] **Step 4: Add method-level override on `deleteAll` (line 98)**

Insert one annotation line above `fun deleteAll`:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    open fun deleteAll() {
```

- [ ] **Step 5: Add method-level override on `upsertByVersion` (line 157)**

Insert one annotation line above `fun upsertByVersion`:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    open fun upsertByVersion(entity: CharacterValuationEntity): Boolean {
```

- [ ] **Step 6: Verify 5 method-level overrides + 1 class-level annotation**

Run: `grep -n '@Transactional' module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationRepositoryImpl.kt`
Expected: 5 lines, all `value = "transactionManager", readOnly = false`. 1 class-level line with `readOnly = true`.

- [ ] **Step 7: Compile**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationRepositoryImpl.kt
git commit -m "fix(infra): class-level readOnly=true in CharacterValuationRepositoryImpl with write overrides"
```

---

## Task 5: CharacterViewQueryServicePostgres — JSON parse out of TX

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt:55-95`

The `objectMapper.readValue()` call on line 71 runs inside the `@Transactional` block. Hoist by parsing in a non-TX wrapper that delegates to a `*InTx` method receiving the parsed `presets` list.

- [ ] **Step 1: Add `value` + `readOnly` annotation alignment to `findByUserIgn` (line 52)**

(No change required — line is already explicit. Skip if already aligned.)

- [ ] **Step 2: Refactor `upsertFromCalculation` (line 55) — hoist JSON parse**

Replace the entire method (lines 55-95) with:

```kotlin
    @Transactional(value = "transactionManager", readOnly = false)
    fun upsertFromCalculationInTx(
        userIgn: String,
        messageId: String?,
        characterOcid: String?,
        characterClass: String?,
        characterLevel: Int?,
        totalExpectedCost: Long,
        maxPresetNo: Int,
        presetNo: Int,
        presets: List<CharacterValuationViewEntity.PresetView>?,
    ) {
        val context = TaskContext.of("PostgresQuery", "UpsertFromCalculation", userIgn)
        executor.executeVoid({
            val entity = CharacterValuationViewEntity(
                userIgn = userIgn,
                messageId = messageId,
                characterOcid = characterOcid,
                characterClass = characterClass,
                characterLevel = characterLevel,
                totalExpectedCost = totalExpectedCost,
                maxPresetNo = maxPresetNo,
                presetNo = presetNo,
                presets = presets,
                calculatedAt = java.time.Instant.now(),
                fromCache = false,
                version = System.currentTimeMillis(),
            )
            upsert(entity)
        }, context)
    }

    fun upsertFromCalculation(
        userIgn: String,
        messageId: String?,
        characterOcid: String?,
        characterClass: String?,
        characterLevel: Int?,
        totalExpectedCost: Long,
        maxPresetNo: Int,
        presetNo: Int,
        presetsJson: String,
    ) {
        // CPU-bound JSON parse outside transaction boundary
        val context = TaskContext.of("PostgresQuery", "ParsePresets", userIgn)
        val presets: List<CharacterValuationViewEntity.PresetView>? = executor.executeOrDefault(
            {
                objectMapper.readValue(
                    presetsJson,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, CharacterValuationViewEntity.PresetView::class.java),
                )
            },
            null,
            context,
        )
        upsertFromCalculationInTx(
            userIgn = userIgn,
            messageId = messageId,
            characterOcid = characterOcid,
            characterClass = characterClass,
            characterLevel = characterLevel,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
            presetNo = presetNo,
            presets = presets,
        )
    }
```

Note: the public `upsertFromCalculation(presetsJson: String)` is the same signature as the original. Internal callers (e.g. a projection worker) need to be updated to call `upsertFromCalculationInTx` if they already have parsed presets, or the public `upsertFromCalculation(presetsJson)` if they have JSON. Search for callers and decide.

- [ ] **Step 3: Find all callers of `upsertFromCalculation`**

Run: `grep -rn 'upsertFromCalculation' module-infra/src module-app/src module-calculator/src module-rest-controller/src module-synchronizer/src module-web/src 2>/dev/null`
Expected: caller list. For each caller, decide:
- Caller has raw JSON string → call public `upsertFromCalculation(presetsJson)`.
- Caller already parsed the list → switch to `upsertFromCalculationInTx(...)`.

Document the decision in this step before continuing.

- [ ] **Step 4: Compile + tests pass**

Run: `./gradlew :module-infra:compileKotlin :module-infra:test --continue 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`, no test failures attributable to this change.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt
git commit -m "fix(infra): hoist JSON parse out of TX in CharacterViewQueryServicePostgres"
```

---

## Task 6: Full validation

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`. No new failures vs baseline.

- [ ] **Step 3: Verify no bare `@Transactional` annotations remain in target files**

Run:
```bash
grep -rn '^[[:space:]]*@Transactional[[:space:]]*$' \
  module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt \
  module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt \
  module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt \
  module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationRepositoryImpl.kt \
  module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt
```
Expected: no output.

- [ ] **Step 4: Verify no `@Async` + `@Transactional` on same method**

Run:
```bash
grep -rn -B1 '@Async' module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt | grep -A1 '@Async'
```
Expected: lines containing `@Async` are not immediately followed by `@Transactional` on the next line.

- [ ] **Step 5: Commit final tidy (if any)**

If Steps 1-4 produced no further edits, skip. Otherwise:

```bash
git add -A
git commit -m "chore(infra): tx-hygiene verification tidy"
```

---

## Self-Review

**1. Spec coverage:**
- `CalculationJobService` 16 → Task 1 ✓
- `EquipmentDbWorker` @Async + @Transactional split → Task 2 ✓
- `CalculationExecutionService` gzip/hash out of TX → Task 3 ✓
- `CharacterValuationRepositoryImpl` class-level readOnly → Task 4 ✓
- `CharacterViewQueryServicePostgres` JSON out of TX → Task 5 ✓
- compile + test → Task 6 ✓

**2. Placeholder scan:** No "TBD" / "fill in" / "similar to". Task 5 Step 3 requires caller-discovery before continuing — flagged in step.

**3. Type consistency:** All `*InTx` parameter lists match the `*OutOfTx` wrapper call sites. `gzipData: ByteArray` / `hash: String` / `originalSize: Int` / `compressedSize: Int` consistent across tasks 3.

**4. Caveat:** `CharacterViewQueryServicePostgres.upsertFromCalculation` is currently consumed by external callers. Task 5 Step 3 grep must run before Step 4 compile. If no callers exist, the public `upsertFromCalculation(presetsJson)` is the only entry point and no caller updates are needed.
