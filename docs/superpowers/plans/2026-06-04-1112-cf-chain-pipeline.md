# #1112 ExternalApiWorker CF Chaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert ExternalApiWorker.processPipeline() from synchronous blocking to CompletableFuture chaining, removing `.join()` calls and `runBlocking`, reducing worker thread occupancy from ~15s to ~2s.

**Architecture:** Keep PgmqWorker.process() Boolean signature. Move findJobById into CF chain (fixes timer leak). Convert `resolveOcidAndFetchEquipment()` to return CF. Chain all pipeline steps via `thenCompose`/`thenApply`. Wrap `@Cacheable` calls in `supplyAsync` on dedicated VT executors. Overlap snapshot write with calculation input building. Single `.join()` in process() with CompletionException unwrapping for ACK/NACK routing.

**Tech Stack:** Kotlin, CompletableFuture, Virtual Thread executors, Spring @Cacheable, PGMQ

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `module-infra/.../worker/ExternalApiWorker.kt` | Modify | Main pipeline refactor |
| `docs/01_ADR/ADR-XXX_external-api-worker-cf-chaining.md` | Create | Architecture decision |

### ExternalApiWorker.kt method changes

| Method | Before | After |
|--------|--------|-------|
| `process()` | calls `processPipeline()` sync | calls `pipelineAsync().join()` + CompletionException unwrapping |
| `processPipeline()` | sync, 3x `.join()` + `runBlocking` | → renamed to `pipelineAsync()`, returns `CF<Unit>` |
| `resolveOcidAndFetchEquipment()` | returns `Pair<>`, ends with `.join()` | → `resolveOcidAndFetchEquipmentAsync()`, returns `CF<Pair<>>`, no `.join()` |
| `convertItems()` | `runBlocking(Dispatchers.Default)` | `CompletableFuture.supplyAsync` + `allOf` for large batches |
| `runCalculationAndComplete()` | unchanged | unchanged (own StepTimer, own finally) |

### Grill-me design fixes applied

| # | Issue | Fix |
|---|-------|-----|
| Q1 | Timer leak when findJobById throws | findJobById moved into CF chain |
| Q2 | Double CompletionException wrapping | try-catch after .join() unwraps CompletionException |
| Q3 | CF\<Void\> vs CF\<Void?\> type mismatch | Return `CompletableFuture<Unit>`, use `completedFuture(Unit)` |
| Q4 | Timer double close | `whenComplete { _, _ -> timer.close(log) }` only, remove individual closes |

---

## Task 1: ADR Document

**Files:**
- Create: `docs/01_ADR/ADR-XXX_external-api-worker-cf-chaining.md`

- [ ] **Step 1: Write ADR**

```markdown
# ADR-XXX: ExternalApiWorker CF Chaining — 15초+ 동기 블로킹 해소

- Status: Proposed
- Date: 2026-06-04
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- ExternalApiWorker.processPipeline()이 OCID resolve → Equipment fetch → Snapshot write → CPU 계산 → Result 저장을 단일 PGMQ worker thread에서 동기 실행
- 3개 `.join()` + 1개 `runBlocking` = 최대 15초+ worker thread 점유
- PGMQ worker pool(cores×2)의 모든 thread가 점유되면 throughput 병목

### Problem

- 단일 메시지 처리에 15초+ 소요 → worker pool 포화 → 전체 파이프라인 throughput 제한

### Goal

- Worker thread 점유 시간 5초 이하
- `.join()` 3회 → 1회 (최종 ACK/NACK만)
- `runBlocking` 제거

---

## 2. Decision

> CompletableFuture 체이닝으로 파이프라인 전체를 비동기화. process() 내부에서 단일 `.join()`만 유지.

```text
pipelineAsync() {
  findJobById (supplyAsync) → CF
    └→ 상태 체크 (terminal/snapshot_ready/not_processable)
         └→ resolveOcidAndFetchEquipmentAsync() → CF (apiCallExec VT)
              └→ thenCompose: 병렬 시작
                   ├─ snapshotPut (snapshotExec VT)
                   ├─ convertItems + buildInput + saveIfAbsent
                   └→ thenCompose: snapshotPut 완료 후
                        └→ saveSnapshotMetadata [TX] → runCalculationAndComplete [CPU+TX]
}.whenComplete { timer.close }

process() {
  pipelineAsync().join()  // CompletionException 언래핑
}
```

---

## 3. Trade-offs

### Sensitivity

* 외부 API 응답 시간 (Nexon API latency)
* Equipment 캐시 적중률 (@Cacheable hit/miss)
* VT executor 스레드 수

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| CF 체이닝 (in-process) | 코드 변경 최소, process() 시그니처 유지 | 멀티 큐 수준의 독립 스케일링 불가 |
| supplyAsync로 @Cacheable 래핑 | 캐시 동작 유지 + 비동기 실행 | 스레드 경계 1회 추가 |
| 단일 .join() (process) | ACK/NACK Boolean 반환 유지 | worker thread 1회 park |

### Risk

* CF 체이닝 디버깅 복잡도 증가 (스레드 경계 다수)
* 예외 전파가 CompletionException으로 래핑되어 언래핑 필요

### Non-Risk

* @Cacheable 동작 변경 없음 (supplyAsync 내부에서 동일 메서드 호출)
* 기존 PgmqWorker ACK/NACK 메커니즘 변경 없음
* StepTimer lifecycle: whenComplete로 단일 책임 보장

---

## 4. Result / Evidence

### Metrics

| Metric | Before | Target |
| ------ | -----: | ------ |
| .join() count | 3 | 1 |
| runBlocking count | 1 | 0 |
| Worker thread occupancy | ~15s | <5s |

### Observed Result

* (부하테스트 후 업데이트)

---

## 5. Summary

> ExternalApiWorker 파이프라인을 CF 체이닝으로 전환하여 .join() 3회→1회, runBlocking 제거, worker thread 점유 시간 5초 이하 달성.
```

- [ ] **Step 2: Create branch and commit ADR**

```bash
git checkout -b refactor/1112-cf-chain-pipeline
git add docs/01_ADR/ADR-XXX_external-api-worker-cf-chaining.md
git commit -m "docs: ADR for ExternalApiWorker CF chaining (#1112)"
```

---

## Task 2: Convert `resolveOcidAndFetchEquipment` to async

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt:359-393`

This is the biggest blocking point — `.join()` at line 392 with `.orTimeout(15, SECONDS)`.

- [ ] **Step 1: Replace `resolveOcidAndFetchEquipment` with async version**

Delete the method at lines 359-393 and replace with:

```kotlin
    /**
     * Resolve OCID and fetch equipment data — async.
     *
     * OCID cache hit: dispatches equipment fetch to apiCallExec (fetchWithCache may block on cache miss).
     * OCID cache miss: chains OCID API → equipment API via thenCompose.
     *
     * No .join() — returns CompletableFuture for chaining.
     */
    private fun resolveOcidAndFetchEquipmentAsync(
        jobId: UUID,
        userIgn: String,
        jobOcid: String?,
    ): CompletableFuture<Pair<String, EquipmentResponse>> {
        val cached = jobOcid ?: ocidPort.resolveOcid(userIgn)
        if (cached != null) {
            jobService.resolveOcidInPlace(jobId, cached)
            return CompletableFuture.supplyAsync({
                Pair(cached, equipmentFetchProvider.fetchWithCache(cached))
            }, apiCallExec.executor)
        }

        return nexonApiClient.getOcidByCharacterName(userIgn)
            .handle { result, ex ->
                if (ex != null) {
                    log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                    throw ExceptionUtils.unwrapAs(ex, CharacterNotFoundException::class.java) ?: ex
                }
                result
            }
            .thenApply { response ->
                if (response == null || response.ocid.isBlank()) {
                    throw CharacterNotFoundException(userIgn)
                }
                response.ocid
            }
            .thenApply { ocid ->
                jobService.resolveOcidInPlace(jobId, ocid)
                ocid
            }
            .thenCompose { ocid ->
                CompletableFuture.supplyAsync({
                    log.debug("[VT] API call on virtual thread: isVirtual={}", Thread.currentThread().isVirtual)
                    Pair(ocid, equipmentFetchProvider.fetchWithCache(ocid))
                }, apiCallExec.executor)
            }
            .orTimeout(15, TimeUnit.SECONDS)
    }
```

Key changes from original:
- Return type: `Pair<>` → `CompletableFuture<Pair<>>`
- Removed `.join()` at the end
- OCID cached path: wrapped in `CompletableFuture.supplyAsync({...}, apiCallExec.executor)` to avoid blocking worker thread on equipment fetch
- Non-cached path: identical CF chain, just no `.join()` at end

- [ ] **Step 2: Commit (compile will fail — expected, Task 4 fixes caller)**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt
git commit -m "refactor: convert resolveOcidAndFetchEquipment to async CF (#1112)"
```

---

## Task 3: Convert `convertItems` to remove `runBlocking`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt:332-350`

- [ ] **Step 1: Replace `convertItems` method**

Replace lines 332-350 with:

```kotlin
    private fun convertItems(equipmentResponse: EquipmentResponse): List<EquipmentItem> {
        val items = equipmentResponse.itemEquipment ?: return emptyList()
        if (items.size < PARALLEL_ITEM_CONVERSION_THRESHOLD) {
            return items.map { convertItem(it) }
        }

        val futures = items.map { item ->
            CompletableFuture.supplyAsync { convertItem(item) }
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        return futures.map { it.join() }
    }
```

Key changes:
- `runBlocking(Dispatchers.Default) { items.map { async { ... } }.awaitAll() }` → `CompletableFuture.supplyAsync` + `allOf().join()`
- The `.join()` here runs within a CF callback on VT executor (not on PgmqWorker thread)

- [ ] **Step 2: Remove unused coroutine imports**

Remove these imports from the file header:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
```

Remove `@OptIn(ExperimentalCoroutinesApi::class)` from the class annotation (line 65).

- [ ] **Step 3: Commit (compile will fail — expected, Task 4 fixes caller)**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt
git commit -m "refactor: replace runBlocking with CF parallel in convertItems (#1112)"
```

---

## Task 4: Restructure `processPipeline` as CF chain

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt:103-274`

This is the core refactor. Convert `processPipeline()` to `pipelineAsync()` returning `CompletableFuture<Unit>`, and update `process()` to call it with CompletionException unwrapping.

### Design decisions from grill-me:
- `findJobById` moved INTO CF chain (fixes timer leak on throw)
- `process()` wraps `.join()` in try-catch for CompletionException unwrapping (fixes double wrapping)
- Return type `CompletableFuture<Unit>` (fixes CF\<Void\> type mismatch)
- `whenComplete { _, _ -> timer.close(log) }` only (fixes timer double close)

- [ ] **Step 1: Replace `process()` method (lines 103-126)**

```kotlin
    override fun process(message: PgmqMessage<ExternalApiJobPayload>): Boolean {
        val payload = message.payload
        val jobId = UUID.fromString(payload.jobId)
        val context = TaskContext.of("ExternalApiWorker", "ProcessMessage", payload.userIgn)

        return executor.executeOrCatch(
            {
                try {
                    pipelineAsync(payload).join()
                } catch (ex: CompletionException) {
                    throw ex.cause ?: ex
                }
                true
            },
            { e ->
                if (isCharacterNotFound(e)) {
                    val errorMsg = (ExceptionUtils.unwrapAsyncException(e)?.message ?: "Character not found").take(200)
                    jobPort.markFailed(jobId, "CHARACTER_NOT_FOUND", errorMsg)
                    log.warn("[jobId={}] Character not found, skipping retry: {}", jobId, errorMsg)
                    true
                } else {
                    log.error("[jobId={}] External API stage failed: {}", jobId, e.message)
                    handleFailure(jobId, e)
                }
            },
            context,
        )
    }
```

Key change: `try { pipelineAsync(payload).join() } catch (ex: CompletionException) { throw ex.cause ?: ex }` — unwraps CompletionException so recovery function sees the real exception.

- [ ] **Step 2: Replace `processPipeline()` with `pipelineAsync()` (lines 139-274)**

Delete the entire `processPipeline` method and replace with:

```kotlin
    private fun pipelineAsync(payload: ExternalApiJobPayload): CompletableFuture<Unit> {
        val jobId = UUID.fromString(payload.jobId)
        val timer = StepTimer("ExternalApiWorker:ProcessMessage", stepTraceThresholdMs, tags = mapOf("jobId" to payload.jobId))

        return CompletableFuture.supplyAsync({
            stage("FindJob", jobId.toString()) {
                jobPort.findJobById(jobId)
            }
        }, apiCallExec.executor)
            .thenApply { existingJob ->
                timer.mark("findJob")
                existingJob
            }
            .thenCompose { existingJob ->
                // Terminal states: skip entirely
                if (existingJob != null && (existingJob.status == CalculationJobStatus.COMPLETED || existingJob.status == CalculationJobStatus.FAILED)) {
                    log.debug("[jobId={}] Skipping — terminal state {}", jobId, existingJob.status)
                    return@thenCompose CompletableFuture.completedFuture(Unit)
                }

                // Consolidated retry: SNAPSHOT_READY means API+snapshot already done
                if (consolidatedEnabled && existingJob != null && existingJob.status == CalculationJobStatus.SNAPSHOT_READY) {
                    val characterId = existingJob.ocid
                    if (characterId == null) {
                        log.warn("[jobId={}] No OCID in SNAPSHOT_READY state, cannot retry calculation", jobId)
                        return@thenCompose CompletableFuture.completedFuture(Unit)
                    }
                    val characterClass = stage("LoadCharacterClass", jobId.toString()) {
                        calculationInputPort.findByJobId(jobId)?.characterClass ?: ""
                    }
                    timer.mark("loadCharacterClass")
                    log.info("[jobId={}] Resuming from calculation (SNAPSHOT_READY)", jobId)
                    return@thenCompose CompletableFuture.supplyAsync({
                        runCalculationAndComplete(jobId, payload, characterId, characterClass)
                        timer.mark("runCalculationAndComplete")
                        Unit
                    }, apiCallExec.executor)
                }

                // Not processable
                if (existingJob != null && !existingJob.status.isExternalApiProcessable()) {
                    log.debug("[jobId={}] Skipping — state {}", jobId, existingJob.status)
                    return@thenCompose CompletableFuture.completedFuture(Unit)
                }

                // === Full pipeline: API fetch → snapshot → calculation → result write ===
                resolveOcidAndFetchEquipmentAsync(jobId, payload.userIgn, existingJob?.ocid)
                    .thenApply { equipmentResult ->
                        timer.mark("resolveAndFetch")
                        equipmentResult
                    }
                    .thenCompose { (ocid, equipmentResponse) ->
                        // Step 3: Serialize snapshot (CPU, fast)
                        val snapshotData = stage("SerializeSnapshot", payload.userIgn) {
                            objectMapper.writeValueAsBytes(equipmentResponse)
                        }
                        timer.mark("serializeSnapshot")

                        val objectKey = generateObjectKey(jobId)
                        val snapshotId = UUID.randomUUID()
                        val snapshot = CalculationSnapshot(
                            snapshotId = snapshotId,
                            jobId = jobId,
                            objectKey = objectKey,
                            storageType = "LOCAL",
                            characterId = ocid,
                            presetNo = payload.presetNo,
                            expiresAt = Instant.now().plusSeconds(86400),
                        )

                        // Step 3.5: Snapshot put — async on snapshotExec (overlaps with input building)
                        val snapshotPutFuture = CompletableFuture.supplyAsync({
                            stage("SnapshotPut", jobId.toString()) {
                                snapshotStore.put(snapshot, snapshotData)
                            }
                        }, snapshotExec.executor)

                        // Step 4: Build input + save (overlaps with snapshot put)
                        val inputItems = convertItems(equipmentResponse)
                        val characterClass = equipmentResponse.characterClass ?: ""
                        val calcInput = CalculationInput(
                            jobId = jobId.toString(),
                            userIgn = payload.userIgn,
                            characterClass = characterClass,
                            presetNo = payload.presetNo,
                            items = inputItems,
                        )
                        stage("SaveCalculationInput", jobId.toString()) {
                            calculationInputPort.saveIfAbsent(calcInput)
                        }
                        timer.mark("buildAndSaveInput")

                        // Step 5+6: Wait for snapshot put → save metadata → (calculation or dispatch)
                        snapshotPutFuture.thenCompose { putResult ->
                            timer.mark("awaitSnapshotPut")

                            val snapshotEntity = CalculationSnapshotEntity(
                                snapshotId = snapshotId,
                                jobId = jobId,
                                objectKey = objectKey,
                                storageType = "LOCAL",
                                characterId = ocid,
                                presetNo = payload.presetNo,
                                compressedSize = putResult.compressedSize,
                                originalSize = snapshotData.size.toLong(),
                                hash = putResult.hash,
                                expiresAt = snapshot.expiresAt,
                            )

                            if (consolidatedEnabled) {
                                stage("SaveSnapshotAndMarkReady", jobId.toString()) {
                                    jobService.saveInputSnapshotAndMarkReady(snapshotEntity, jobId, snapshotId)
                                }
                                timer.mark("saveSnapshotAndMarkReady")

                                // Step 7-10: Inline calculation + result write
                                CompletableFuture.supplyAsync({
                                    runCalculationAndComplete(jobId, payload, ocid, characterClass)
                                    timer.mark("runCalculationAndComplete")
                                    Unit
                                }, apiCallExec.executor)
                            } else {
                                // Legacy: dispatch to calculation_requested_queue
                                stage("DispatchCalculation", jobId.toString()) {
                                    jobService.saveInputSnapshotAndDispatchCalculation(
                                        snapshotEntity = snapshotEntity,
                                        jobId = jobId,
                                        snapshotId = snapshotId,
                                        payload = CalculationRequestedPayload(
                                            jobId = jobId.toString(),
                                            userIgn = payload.userIgn,
                                            presetNo = payload.presetNo,
                                            characterId = ocid,
                                            characterClass = characterClass,
                                        ),
                                    )
                                }
                                timer.mark("dispatchCalculation")
                                CompletableFuture.completedFuture(Unit)
                            }
                        }
                    }
            }
            .whenComplete { _, _ -> timer.close(log) }
            .thenApply { Unit }
    }
```

Key design:
- **findJobById in CF chain**: `CompletableFuture.supplyAsync { stage("FindJob") }` — ensures timer leak can't happen
- **Single timer close**: `whenComplete { _, _ -> timer.close(log) }` covers ALL paths (success, error, skip)
- **No individual timer.close**: runCalculationAndComplete has its OWN StepTimer (PureCalculate), outer timer closed only by whenComplete
- **CF\<Unit\> return**: all branches return `CompletableFuture.completedFuture(Unit)` or `supplyAsync { ...; Unit }`
- **Snapshot overlaps input**: snapshotPut dispatched to snapshotExec while input builds inline
- **early return via return@thenCompose**: Kotlin labeled returns for skip/fast-path branches

- [ ] **Step 3: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "error|FAIL|BUILD" | head -20
```

Expected: BUILD SUCCESSFUL. If errors, fix before proceeding.

- [ ] **Step 4: Run tests**

```bash
./gradlew test 2>&1 | grep -E "error|FAIL|BUILD|tests completed" | tail -5
```

Expected: BUILD SUCCESSFUL. No new test failures.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt
git commit -m "refactor: convert ExternalApiWorker pipeline to CF chaining (#1112)"
```

---

## Task 5: Runtime verification

- [ ] **Step 1: Start relevant modules**

```bash
set -a && source .env && set +a
./gradlew :module-external-api:bootRun &
./gradlew :module-calculator:bootRun &
```

Wait for health check 200 on both ports (8081, 8082).

- [ ] **Step 2: Trigger expectation API**

```bash
curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

Expected: HTTP 202.

- [ ] **Step 3: Check logs for pipeline completion**

```bash
grep "Calculation completed" module-calculator/logs/app.log | tail -5
grep "Pipeline stage completed" module-infra/logs/app.log 2>/dev/null | tail -5
```

Expected: "Calculation completed with result saved" + "Pipeline stage completed".

- [ ] **Step 4: Check StepTrace timing**

```bash
grep "StepTrace\|ExternalApiWorker:ProcessMessage\|ExternalApiWorker:PureCalculate" module-infra/logs/app.log 2>/dev/null | tail -20
```

Verify:
- `resolveAndFetch` runs on apiCallExec (not worker thread)
- `awaitSnapshotPut` minimal wait (overlaps with input build)
- Total pipeline time reduced

- [ ] **Step 5: Stop servers, create PR**

```bash
git push origin refactor/1112-cf-chain-pipeline
gh pr create --base develop --title "refactor: ExternalApiWorker CF chaining (#1112)" --body "Convert ExternalApiWorker pipeline from synchronous blocking to CompletableFuture chaining.

## Changes
- resolveOcidAndFetchEquipment() → resolveOcidAndFetchEquipmentAsync() returning CF
- convertItems() runBlocking → CompletableFuture.supplyAsync + allOf
- processPipeline() → pipelineAsync() with CF chain
- findJobById moved into CF chain (timer leak fix)
- Single .join() in process() with CompletionException unwrapping
- Snapshot put overlaps with input building
- whenComplete for timer lifecycle

## Verification
- compileKotlin compileJava --continue ✅
- ./gradlew test ✅
- Runtime API verification ✅

Closes #1112"
```

---

## Self-Review

**1. Spec coverage:**
- ✅ resolveOcidAndFetchEquipment .join() removed → Task 2
- ✅ snapshotFuture.join() removed → Task 4 (overlapped with input build)
- ✅ convertItems runBlocking removed → Task 3
- ✅ process() single .join() → Task 4
- ✅ ADR → Task 1
- ✅ Runtime verification → Task 5
- ✅ Timer leak fix (grill-me Q1) → Task 4
- ✅ CompletionException unwrapping (grill-me Q2) → Task 4
- ✅ CF\<Unit\> type (grill-me Q3) → Task 4
- ✅ Timer single close (grill-me Q4) → Task 4

**2. Placeholder scan:** No TBD/TODO. All code steps contain actual code.

**3. Type consistency:**
- `resolveOcidAndFetchEquipmentAsync` → `CompletableFuture<Pair<String, EquipmentResponse>>` → consumed via `.thenCompose { (ocid, equipmentResponse) -> ... }`
- `pipelineAsync` → `CompletableFuture<Unit>` → consumed in `process()` via `.join()` returning `Unit`
- `snapshotPutFuture` → `CompletableFuture<SnapshotStore.PutResult>` (inferred from `snapshotStore.put()`) → consumed via `.thenCompose { putResult -> ... }`
- `CalculationSnapshotEntity` constructor params match original
- All `return@thenCompose` branches return `CompletableFuture<Unit>` (completedFuture or supplyAsync)
