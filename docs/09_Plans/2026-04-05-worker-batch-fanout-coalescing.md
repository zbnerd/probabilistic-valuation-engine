# Plan: Worker Batch Fan-out + Coalescing (ADR-700)

## Context

PR #699에서 Worker RPS 2.75→98.5 달성. 이후 병목: **배치 내 동일 OCID에 대한 중복 API 호출**.

현재 배치 91개 메시지가 각각 독립적으로 OCID 해석 + 장비 fetch → 동일 OCID가 N번 fetch됨.
V4에 이미 구축된 `AdaptiveMicroBatchUserService` + `NexonEquipmentMicroBatchAdapter` + `CharacterOcidPort.resolveOcids()` 재활용.

**목표:** 배치 내 OCID 중복 제거 → 장비 캐시 pre-warm → 기존 `process()`는 캐시 hit → API 호출 감소
**RPS 타겟:** 검증 필요. 현재 98.5 RPS에서 API 호출 감소로 유의미한 개선 예상.

---

## 수정 파일

### 1. `PgmqWorker.kt` — pre-warm hook 추가
**경로:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`

`processMessages()`에서 병렬 처리 **직전**에 `preWarmBatch(messages)` 훅 호출. 기본 구현은 no-op.

```kotlin
// 추가: pre-warm hook (하위 클래스에서 override)
protected open fun preWarmBatch(messages: List<PgmqMessage<T>>) {
    // no-op by default
}

// processMessages() 수정 위치 (line ~102):
// val messages = pgmqClient.read(...)
// if (messages.isEmpty()) return
//
// preWarmBatch(messages)  // ← 추가: OCID dedup + equipment cache pre-warm
//
// // 이후 기존 병렬 처리 로직 그대로 (수정 없음)
// val futures = messages.map { ... }
```

**장점:** `workerPool` 노출 불필요, 병렬 처리 로직은 한 곳에 집중, 하위 클래스는 pre-warm만 담당.

### 2. `ExpectationCalcWorker.kt` — preWarmBatch override
**경로:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExpectationCalcWorker.kt`

추가 주입: `CharacterOcidPort`, `EquipmentFanOutPort`

```kotlin
override fun preWarmBatch(messages: List<PgmqMessage<ExpectationCalcMessage>>) {
    val context = TaskContext.of("ExpectationCalcWorker", "PreWarm", queueName)

    executor.executeVoid({
        // 1. 배치 내 unique IGN 추출
        val igns = messages.map { it.payload.userIgn }.toSet()

        // 2. Batch OCID resolve (재사용: CharacterOcidPort.resolveOcids)
        val ignToOcid = characterOcidPort.resolveOcids(igns)

        if (ignToOcid.isEmpty()) return@executeVoid

        // 3. Equipment cache pre-warm — CONCURRENT submission 필수
        //    순차 호출 시 AdaptiveMicroBatchUserService가 Fast Lane으로 처리 → coalescing 미발생
        //    Virtual Thread에서 동시 submit → semaphore(10) 초과 시 Batch Lane으로 routing
        //    → NexonFanOutBatchLoader.load()가 병렬 batch fetch → L1/L2 캐시 적재
        val warmupFutures = ignToOcid.values.map { ocid ->
            CompletableFuture.supplyAsync {
                equipmentFanOutPort.preFetchByOcid(ocid)
            }
        }
        CompletableFuture.allOf(*warmupFutures.toTypedArray())
            .orTimeout(15, TimeUnit.SECONDS)
            .handle { _, _ -> }  // best-effort: 실패해도 진행

        log.info("[ExpectationCalcWorker] Pre-warm: {} igns → {} ocids", igns.size, ignToOcid.size)
    }, context)
}
```

**Critical: 왜 동시 submit인가?**
- `AdaptiveMicroBatchUserService.getByKey()`는 `semaphore.tryAcquire()`로 Fast/Batch Lane 분기
- `semaphorePermits=10` → 10개까지 Fast Lane (개별 fetch)
- 초과분은 Channel → 10ms 대기 → `batchLoader(batch)` → `NexonFanOutBatchLoader.load()` batch fetch
- **순차 호출 시**: 매 호출이 blocking → semaphore 항상 available → 전부 Fast Lane → coalescing 없음
- **동시 submit 시**: 50개가 동시에 들어오면 10개 Fast + 40개 Batch → coalescing 발생 → API 호출 최소화

**Best-effort 정책:**
- `resolveOcids`에서 누락된 IGN (신규 캐릭터) → `process()`에서 정상 처리 (기존 경로)
- pre-warm 타임아웃/실패 → `process()`에서 캐시 miss → API 호출 fallback
- pre-warm은 최적화일 뿐, 실패해도 Worker 동작에 영향 없음

이후 기존 `process()` → `calculateExpectationAsync()` 호출 시 장비 fetch가 **캐시 hit** → API 호출 생략.

### 3. `ExpectationCalcLowWorker.kt` — 동일 패턴 적용
**경로:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExpectationCalcLowWorker.kt`

`ExpectationCalcWorker`와 동일한 `preWarmBatch()` override. `CharacterOcidPort`, `EquipmentFanOutPort` 주입 추가.

### 4. `application-vultr.yml` — 설정 변경 없음
기존 `AdaptiveMicroBatchProperties` 기본값 사용:
- `semaphorePermits=10`, `batchMaxWaitMs=10`, `batchMaxSize=50`

---

## 재사용 인프라 (변경 없음)

| 컴포넌트 | 역할 | 경로 |
|----------|------|------|
| `CharacterOcidPort.resolveOcids(Set)` | Batch IGN→OCID (IN clause) | `module-core/.../port/out/CharacterOcidPort.kt` |
| `EquipmentFanOutPort.preFetchByOcid(ocid)` | Cache pre-fetch + coalescing 트리거 | `module-core/.../port/out/EquipmentFanOutPort.kt` |
| `NexonEquipmentMicroBatchAdapter` | preFetchByOcid 구현체 | `module-infra/.../batch/NexonEquipmentMicroBatchAdapter.kt` |
| `AdaptiveMicroBatchUserService` | Core coalescing engine (semaphore routing) | `module-infra/.../batch/AdaptiveMicroBatchUserService.kt` |
| `NexonFanOutBatchLoader.load(ocids)` | Batch Lane 병렬 fetch | `module-infra/.../fanout/NexonFanOutBatchLoader.kt` |

---

## 처리 흐름 (Before → After)

**Before:**
```
91 messages → 91x OCID resolve → 91x equipment API fetch → 91x calculate → 91x save
= 273 API calls (Nexon API bottleneck)
```

**After:**
```
91 messages
→ preWarmBatch:
    1) batch OCID resolve: 91 IGNs → ~50 unique OCIDs (1 DB query)
    2) concurrent preFetchByOcid(50 OCIDs):
       - 10 Fast Lane (semaphore=10, 개별 fetch+cache)
       - 40 Batch Lane (coalescing → NexonFanOutBatchLoader.load() → 병렬 batch fetch)
    = ~50 API calls (unique OCIDs only)
→ 91x process() → calculateExpectationAsync():
    - equipment fetch = 캐시 hit (0 API calls)
    - calculate + save (CPU + DB)
= ~50 API calls (기존 273에서 82% 감소)
```

---

## Verification

1. `./gradlew compileKotlin compileJava --continue` — 컴파일 확인
2. `./gradlew test` — 기존 테스트 통과
3. 서버 기동 후 PGMQ에 테스트 메시지 enqueue → Worker 로그에서:
   - `Pre-warm: X igns → Y ocids` 로그 확인
   - 기존 `process()` 로그 정상 출력
4. Micrometer: `pgmq.worker.processed{status=success}` 카운터 증가 확인
5. Nexon API 호출 수 감소 확인 (`nexon_api_calls_total` 비교)

---

## DoD

- [ ] ADR-700 문서 작성 (`docs/01_ADR/`)
- [ ] 컴파일 통과
- [ ] 단위 테스트 통과
- [ ] CLAUDE.md 원칙 준수 (Zero try-catch, LogicExecutor 위임)
- [ ] 중복 구현 없음 (V4 micro-batch 100% 재활용)
- [ ] Best-effort pre-warm (실패해도 Worker 동작 보장)
- [ ] `develop` 브랜치 PR
