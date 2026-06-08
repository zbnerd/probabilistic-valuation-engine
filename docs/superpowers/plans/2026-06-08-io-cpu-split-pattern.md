# IO/CPU Split Pattern Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ADR-723 + Guide §23을 작성하여 IO/CPU 분리 패턴을 확립하고, 후속 4개 이슈(#1128-#1131)가 참조할 수 있는 reference 문서를 제공한다.

**Architecture:** 2개 산출물 (a) `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` 5섹션 결정 기록, (b) `docs/03_Technical_Guides/async-concurrency.md` §23 패턴 매뉴얼 추가. 코드 변경 없음. PR은 develop base.

**Tech Stack:** Markdown, Git, GitHub CLI (`gh`)

**Reference:**
- Spec: `docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md`
- ADR format: `.claude/rules/adr-conventions.md`
- Guide base: `docs/03_Technical_Guides/async-concurrency.md` (existing §21-§22)

---

## File Structure

이 plan은 다음 파일을 생성/수정한다:

| 파일 | 작업 | 책임 |
|---|---|---|
| `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` | Create | 5섹션 결정 기록 (Background/Decision/Trade-offs/Result/Summary) |
| `docs/03_Technical_Guides/async-concurrency.md` | Modify | §23 섹션 추가 (원칙, 분류, wrap 방식, 사례, 안티, review checklist) |

**두 파일은 독립적으로 commit 가능.** §23은 ADR을 cross-reference하지만, ADR이 먼저 작성되어야 한다.

---

## Task 1: Setup feature branch

**Files:** None (git only)

- [ ] **Step 1: 현재 working tree 상태 확인**

Run:
```bash
git status --short
```

Expected: spec doc commit 이후의 uncommitted changes만 보임 (D 파일들 — 이전 작업 잔재). 본 plan과 무관.

- [ ] **Step 2: develop에서 최신 base 확인**

Run:
```bash
git log --oneline -3
```

Expected: `27543e0f1 docs(spec): IO/CPU split pattern design for #1125` 가 가장 위에 있어야 함 (develop HEAD).

- [ ] **Step 3: feature branch 생성**

Run:
```bash
git checkout -b feature/1125-io-cpu-split-pattern
```

Expected: `Switched to a new branch 'feature/1125-io-cpu-split-pattern'`. Working tree의 D 파일은 이 branch에도 그대로 따라옴 (commit만 안 됨, OK).

- [ ] **Step 4: branch 확인**

Run:
```bash
git branch --show-current
```

Expected: `feature/1125-io-cpu-split-pattern`

---

## Task 2: Write ADR-723_io-cpu-split-pattern.md

**Files:**
- Create: `docs/01_ADR/ADR-723_io-cpu-split-pattern.md`

- [ ] **Step 1: 파일 작성**

`docs/01_ADR/ADR-723_io-cpu-split-pattern.md` 에 다음 내용 전체 작성:

```markdown
# ADR-723: IO/CPU 분리 패턴 확립

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Related: #1125 (blocks #1128, #1129, #1130, #1131)

---

## 1. Background / Problem

### Background

- 4개 활성 모듈 (module-external-api, module-synchronizer, module-calculator, module-rest-controller) 의 VT executor 가 IO-bound 와 CPU-bound 작업을 동일 carrier thread 에서 직렬 실행.
- `Dispatchers.Default` 또는 `Dispatchers.IO` 로 dispatcher 를 분리한 사례가 거의 없음.
- CPU-heavy 구간 (JSON parse/serialize, GZIP, SHA-256, large collection 변환, 확률 계산) 이 carrier 를 장시간 점유 → 같은 executor 의 다른 IO-bound VT 가 carrier 획득을 위해 대기 → throughput 저하.

### Problem

- VT executor 의 IO/CPU 혼재가 throughput 의 systematic 저하 요인.
- 후속 4개 이슈 (#1128 external-api, #1129 synchronizer, #1130 rest-controller, #1131 infra worker) 의 일관된 적용을 위한 reference 컨벤션 부재.

### Goal

- IO-bound 와 CPU-bound 의 dispatcher 분리 패턴을 확립.
- Algorithm-based CPU-bound 분류 기준 명시.
- 모듈별 wrap 방식 (withContext vs runBlocking) 명확화.
- 후속 4개 이슈의 단일 reference 제공.

### 선례 (실측 근거)

`module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ItemCalculationExecutorConfig.kt` Phase 2.5 시도 (코드 주석):

```
Phase 2.5: Virtual Thread + Semaphore(64) → CPU-bound에서 3.5× 회귀
```

→ IO/CPU 분리 원칙이 이미 실측 회귀 데이터와 함께 코드베이스에 존재.

---

## 2. Decision

> **IO-bound 작업은 VT executor 를 유지하고, CPU-bound 작업은 `Dispatchers.Default` (coroutine) 로 offload 한다.**

```text
┌─────────────────────────────────────────────────┐
│ VT Executor (IO-bound)                          │
│   ├─ HTTP / Redis / DB IO (VT blocking OK)      │
│   ├─ File read/write (asynchronous)             │
│   └─ [CPU offload]                              │
│        └─→ runBlocking(Dispatchers.Default) { } │  (VT-only module)
│            └─ JSON / GZIP / SHA-256 / Calc      │
│   └─ DB write (back to VT)                      │
└─────────────────────────────────────────────────┘
```

### 핵심 결정 사항

1. **단일 dispatcher:** `Dispatchers.Default` (JVM-wide ForkJoinPool, CPU core count - 1 bound) 만 사용. 모듈별 dedicated executor 분리 안 함.
2. **Algorithm-based 분류:** O(n²) 이상 또는 n=10K+ 의 collection op 은 무조건 offload. 단순 O(n) 의 소규모 op 은 inline OK.
3. **모듈별 wrap 방식:**
   - Coroutine 모듈 (external-api, calculator): `withContext(Dispatchers.Default) { ... }`
   - VT-only 모듈 (synchronizer): `runBlocking(Dispatchers.Default) { ... }` (carrier block 아님 — ForkJoinPool worker 로 점프)

---

## 3. Trade-offs

### Sensitivity

* Dispatchers.Default saturation — 한 모듈이 core 를 점유 시 다른 모듈에 cross-module 영향.
* 후속 4개 이슈의 적용 깊이 — 모두 적용 시 효과 측정 가능, 일부만 적용 시 효과 불완전.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Dispatchers.Default 단일 사용 | 단순성, 일관성, 0 코드 변경 | 모듈 간 격리 부재 |
| (rejected) 모듈별 dedicated CPU executor | 격리, 모니터링 가능 | 관리 비용, 1125 scope 폭증, 1126 이슈 영역 침범 |

### Risk

* **Dispatchers.Default saturation 시 cross-module 영향** — 후속 4개 이슈 적용 후 부하테스트로 측정. 임계치 초과 시 #1126 또는 후속 ADR 에서 dedicated executor 분리 검토.
* **후속 4개 이슈의 일관성 없는 적용** — Guide §23.3 모듈별 wrap 방식 명시 + PR review checklist 로 강제.

### Non-Risk

* **CPU 분류 모호성** — Algorithm-based threshold 표로 해결.
* **이미 올바른 사례 부재** — `ResultReadyProjectionWorker.kt:123-130`, `SnapshotChunkProcessor.kt:60-75` 가 reference 로 존재.

---

## 4. Result / Evidence

### Metrics (예측)

| Metric | Baseline (예상) | Target (예상) | Notes |
| ------ | ----: | ----: | ----- |
| IO-bound VT 응답성 (p99 latency) | carrier-pin 영향 | carrier-pin 제거 후 안정화 | cold-miss 시나리오 |
| CPU-bound 처리 throughput | VT 1 thread = 1 work | Dispatchers.Default = N core × N work | 후속 이슈 적용 후 측정 |
| Cross-module 영향 | (측정 불가) | (측정 예정) | 부하테스트로 empirical 확인 |
| **Saturation trigger** | (metric 없음) | `ForkJoinPool.commonPool().activeThreadCount > coreCount * 2` 지속 시 dedicated executor 분리 검토 | Prometheus metric follow-up issue 필요 |

### Observed Result (Phase 2: 머지 후 측정 예정)

* #1128, #1129, #1130, #1131 머지 후 cold-miss 부하테스트 (RESET_VIEWS=1 + RESET_ACTIVE_JOBS=1) 로 baseline 대비 throughput/latency 비교.
* 본 ADR 의 "Result" 섹션은 측정 후 갱신.

### 검증 절차

* [ ] `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` 파일 존재
* [ ] 5섹션 구조 (Background, Decision, Trade-offs, Result, Summary) 준수
* [ ] `docs/03_Technical_Guides/async-concurrency.md` §23 섹션 추가
* [ ] 후속 4개 이슈 PR 본문에 "References #1125, applies §23" 명시
* [ ] Saturation metric (Prometheus `forkjoinpool_active_threads` 등) follow-up issue 생성 (실제 expose는 본 PR scope 외)

---

## 5. Summary

> **CPU-bound 작업은 `Dispatchers.Default` (coroutine) 로 offload 하고, IO-bound 작업은 VT executor 에서 직접 실행한다. Algorithm-based 분류 + 모듈별 wrap 방식 (withContext / runBlocking) 으로 일관성 보장.**
```

- [ ] **Step 2: 파일 작성 확인**

Run:
```bash
wc -l docs/01_ADR/ADR-723_io-cpu-split-pattern.md
```

Expected: 100 lines 이상.

- [ ] **Step 3: 5섹션 구조 확인**

Run:
```bash
grep -E "^## [0-9]\." docs/01_ADR/ADR-723_io-cpu-split-pattern.md
```

Expected: 정확히 5개 매치 (1. Background / Problem, 2. Decision, 3. Trade-offs, 4. Result / Evidence, 5. Summary).

- [ ] **Step 4: 5섹션 trade-off 세부 구조 확인**

Run:
```bash
grep -E "^### (Sensitivity|Trade-off|Risk|Non-Risk|Metrics|Observed Result)$" docs/01_ADR/ADR-723_io-cpu-split-pattern.md
```

Expected: 6개 매치.

- [ ] **Step 5: Commit ADR**

Run:
```bash
git add docs/01_ADR/ADR-723_io-cpu-split-pattern.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-723 IO/CPU split pattern (#1125)

5섹션 결정 기록:
- Background: 4개 모듈의 VT executor IO/CPU 혼재 문제
- Decision: CPU-bound는 Dispatchers.Default, IO-bound는 VT
- Trade-offs: 단일 dispatcher 단순성 vs 격리 부재
- Result: 후속 4개 이슈 적용 후 부하테스트로 측정
- Summary: Algorithm-based 분류 + withContext/runBlocking 모듈별 wrap

Reference:
- Spec: docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md
- Blocks: #1128, #1129, #1130, #1131

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

Expected: `[feature/1125-io-cpu-split-pattern <hash>] docs(adr): ADR-723 IO/CPU split pattern (#1125)`

---

## Task 3: Patch async-concurrency.md with §23

**Files:**
- Modify: `docs/03_Technical_Guides/async-concurrency.md` (append §23 before "## Technical Validity Check" or at end of file)

- [ ] **Step 1: 현재 파일 끝부분 확인**

Run:
```bash
tail -20 docs/03_Technical_Guides/async-concurrency.md
```

Expected: 마지막에 `## Technical Validity Check` 또는 `### Related Evidence` 섹션. §23 은 `## Technical Validity Check` 직전에 삽입.

- [ ] **Step 2: §23 섹션 위치 결정**

`grep -n "^## Technical Validity Check" docs/03_Technical_Guides/async-concurrency.md` 로 라인 번호 확인. 예: `320:## Technical Validity Check` → §23 은 319 라인 끝 (또는 320 직전) 에 삽입.

- [ ] **Step 3: §23 섹션 본문 추가**

`docs/03_Technical_Guides/async-concurrency.md` 의 `## Technical Validity Check` 직전 라인에 다음을 삽입 (정확한 위치는 Step 2 의 grep 결과에 따라):

```markdown

---

## 23. IO/CPU Split Pattern (Issue #1125)

> **Related:** [ADR-723](../01_ADR/ADR-723_io-cpu-split-pattern.md), Issue #1125
>
> **Last Updated:** 2026-06-08
>
> **Production Status:** Pattern established; per-module adoption tracked in #1128, #1129, #1130, #1131.

CPU-bound 작업이 VT executor 의 carrier thread 를 pinning 하여 IO-bound VT 의 응답성을 저하시키는 문제를 해결하기 위한 dispatcher 라우팅 패턴.

### §23.1 원칙

| 작업 종류 | 예시 | 실행 컨텍스트 |
|---|---|---|
| **IO-bound** | DB query/update, HTTP call, Redis op, file R/W | VT executor (현재 유지) |
| **CPU-bound** | JSON parse/serialize, GZIP, SHA-256, large collection, 확률 계산 | `Dispatchers.Default` (coroutine) |

### §23.2 CPU-bound 분류 (Algorithm-based)

| 복잡도 \\ 입력 크기 | n < 10K | 10K ≤ n < 100K | n ≥ 100K |
|---|---|---|---|
| O(n) (단순 map/filter) | inline OK | inline OK | **offload** |
| O(n log n) (sort) | inline OK | **offload** | **offload** |
| O(n²), O(n³+) (Markov, DP conv) | **offload** | **offload** | **offload** |

**무조건 offload (카테고리 무관):**

- `ObjectMapper.readTree / writeValueAsBytes / writeValueAsString`
- `GzipUtils.compress / decompress`
- `sha256Hex / Hashing.sha256`
- 확률 계산 (Markov chain, DP convolution) — 모든 크기

### §23.3 모듈별 wrap 방식

| 모듈 | 호출 컨텍스트 | 권장 패턴 |
|---|---|---|
| `module-external-api` | `CoroutineScope(dispatcher).future { ... }` 안 | `withContext(Dispatchers.Default) { cpuWork() }` |
| `module-calculator` | `launch(Dispatchers.Default) { ... }` 안 (coroutine) | `withContext(Dispatchers.Default) { cpuWork() }` |
| `module-synchronizer` | VT-only, no CoroutineScope | `runBlocking(Dispatchers.Default) { cpuWork() }` |
| `module-rest-controller` | `CompletableFuture.supplyAsync(..., executor)` | 컨텍스트 따라 위 1·2·3행 중 선택 |

**선택 기준:**

- 이미 `CoroutineScope` 안 → `withContext`
- VT-only → `runBlocking` bridge (이 경우만, **PGMQ batch worker 같은 single-threaded consumer 한정**)
- `runBlocking` 은 calling thread 를 block 한다. 따라서 **single-threaded batch worker (e.g. PgmqWorker) 에서만** 안전. multi-threaded consumer (e.g. async Kafka consumer) 에서는 message poll loop starvate 위험 → `withContext` + structured concurrency 로 리팩토링 필요.

### §23.4 올바른 사례 (Reference)

#### Case A: VT-only 모듈 (synchronizer 패턴, PGMQ single-thread batch)

**파일:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt:120-135`

```kotlin
// GOOD: runBlocking(Dispatchers.Default) bridge in single-threaded PGMQ batch worker
): List<PgmqProjectionOutcome> = runBlocking(Dispatchers.Default) {
    items.map { item ->
        // CPU-bound: JSON parse, grouping, transform
        transformOutcome(item)
    }
}
```

#### Case B: Coroutine 모듈 (single-item CPU offload)

**파일:** `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:60-80`

```kotlin
// GOOD: withContext inside launch (single-item CPU offload)
launch(Dispatchers.Default) {
    val parsed = withContext(Dispatchers.Default) { parseLines(batch) }
    val calculated = withContext(Dispatchers.Default) { processItems(parsed) }
    // IO-bound 부분은 별도 dispatcher
    withContext(ioDispatcher) { writeResult(calculated) }
}
```

#### Case C: Coroutine 모듈 (multi-item parallel CPU offload)

**용도:** 동일 coroutine scope 안에서 multiple items 를 병렬 처리. `async` + `awaitAll` 사용.

```kotlin
// GOOD: async + awaitAll for multi-item parallel CPU offload
coroutineScope {
    val deferreds = items.map { item ->
        async(Dispatchers.Default) {
            // CPU-bound per item
            processItem(item)
        }
    }
    val results = deferreds.awaitAll()
    // results 사용
}
```

### §23.5 안티 사례 (Anti-pattern)

#### Anti-A: CPU 작업을 IO dispatcher 에 dispatch

**파일:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt:377` (참고용, #1131 에서 수정)

```kotlin
// BAD: Dispatchers.IO 에 CPU 작업 dispatch (IO 는 64 thread 한정)
val results: List<CalculationResult> = runBlocking(Dispatchers.IO) {
    items.map { calculateOnly(it) }  // CPU-bound!
}
```

**Fix:** `Dispatchers.IO` → `Dispatchers.Default`

#### Anti-B: VT 에서 inline CPU-heavy

**파일:** `module-external-api/src/main/kotlin/maple/externalapi/phase/OcidLookupPhase.kt:149-185` (참고용, #1128 에서 수정)

```kotlin
// BAD: VT carrier 에서 JSON parse + GZIP 직접 실행
fun fetchOcid(data: ByteArray): Ocid = executor.execute {
    val tree = objectMapper.readTree(data)   // CPU pinning
    val bytes = objectMapper.writeValueAsBytes(tree)  // CPU pinning
    Ocid.from(tree)
}
```

**Fix:** `withContext(Dispatchers.Default) { ... }` 로 wrap

### §23.6 PR Review Checklist

PR review 시 다음 grep 으로 안티 사례 잔존 여부 확인:

```bash
# Anti-A: CPU-bound 를 IO dispatcher 에 dispatch
grep -rn "runBlocking(Dispatchers\.IO)" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller

# Anti-B: VT/IO 에서 JSON parse/serialize inline
grep -rn "objectMapper\.readTree\|objectMapper\.writeValueAs" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller \
    | grep -v "Dispatchers.Default"
```

**Note:** `module-infra` 의 `PgmqWorker.kt:377` 은 위 grep 으로 잡히지 않음 — #1131 PR review 에서 별도 확인.

CPU-bound 코드 발췌 발견 시 → reviewer 가 §23.2 표에 따라 offload 권고.

### §23.7 Cross-reference

- §21 (Async Non-Blocking Pipeline Pattern) — `join()` 금지, 체이닝 유지. §23 과 조화: CPU offload 후에도 IO 단계는 VT 체이닝.
- §22 (Thread Pool Backpressure Best Practice) — `AbortPolicy` + `CallerRunsPolicy` 구분. §23 의 dispatcher 결정과 무관.

### §23.8 Related

- ADR-723: `docs/01_ADR/ADR-723_io-cpu-split-pattern.md`
- Spec: `docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md`
- 후속: #1128 (external-api), #1129 (synchronizer), #1130 (rest-controller), #1131 (infra worker)

```

- [ ] **Step 4: §23 섹션이 파일 끝 또는 §22 와 Technical Validity Check 사이에 정확히 삽입되었는지 확인**

Run:
```bash
grep -n "^## 23\." docs/03_Technical_Guides/async-concurrency.md
```

Expected: 1개 매치 (예: `320:## 23. IO/CPU Split Pattern (Issue #1125)`).

- [ ] **Step 5: §23 의 8개 subsection (§23.1 ~ §23.8) 확인**

Run:
```bash
grep -E "^### §23\.[0-9]" docs/03_Technical_Guides/async-concurrency.md
```

Expected: 8개 매치 (§23.1 원칙, §23.2 분류, §23.3 wrap 방식, §23.4 사례, §23.5 안티, §23.6 checklist, §23.7 cross-reference, §23.8 related).

- [ ] **Step 6: 기존 §21/§22 가 그대로 보존되는지 확인**

Run:
```bash
grep -E "^## 2[12]\." docs/03_Technical_Guides/async-concurrency.md
```

Expected: 2개 매치 (기존 §21, §22 손상 없음).

- [ ] **Step 7: Commit guide update**

Run:
```bash
git add docs/03_Technical_Guides/async-concurrency.md
git commit -m "$(cat <<'EOF'
docs(guide): §23 IO/CPU split pattern in async-concurrency.md (#1125)

- §23.1 원칙 (IO vs CPU 라우팅)
- §23.2 Algorithm-based CPU 분류 (O(n²)+, n=10K+)
- §23.3 모듈별 wrap 방식 (withContext vs runBlocking)
- §23.4 올바른 사례 (ResultReadyProjectionWorker, SnapshotChunkProcessor)
- §23.5 안티 사례 (PgmqWorker.kt:377, OcidLookupPhase.kt:149-185)
- §23.6 PR review checklist (grep commands)
- §23.7 §21/§22 cross-reference
- §23.8 관련 ADR-723, spec, 후속 이슈 링크

Reference:
- ADR-723
- Spec: docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md
- Blocks: #1128, #1129, #1130, #1131

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

Expected: `[feature/1125-io-cpu-split-pattern <hash>] docs(guide): §23 IO/CPU split pattern in async-concurrency.md (#1125)`

---

## Task 4: Final verification

**Files:** None (read-only verification)

- [ ] **Step 1: 두 파일 존재 확인**

Run:
```bash
ls -la docs/01_ADR/ADR-723_io-cpu-split-pattern.md docs/03_Technical_Guides/async-concurrency.md
```

Expected: 두 파일 모두 존재, 0 byte 아님 (ADR ≥ 100 lines, async-concurrency.md 가 약 13KB → 18KB+ 로 증가).

- [ ] **Step 2: 커밋 2개 존재 확인**

Run:
```bash
git log --oneline -3
```

Expected: 상위 2개 커밋이 본 plan 의 ADR / guide 커밋. develop HEAD (`27543e0f1 docs(spec): ...`) 가 3번째.

- [ ] **Step 3: §23 의 grep checklist 가 실제 코드베이스에 매치되는지 sanity check**

Run:
```bash
grep -rn "runBlocking(Dispatchers\.IO)" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller
```

Expected: 0 매치 (4개 모듈에는 안티 사례 없음). PgmqWorker.kt:377 은 module-infra 라 안 잡힘 (의도된).

```bash
grep -rn "objectMapper\.readTree\|objectMapper\.writeValueAs" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller \
    | grep -v "Dispatchers.Default" | head -10
```

Expected: 매치 있음 (현재 #1128 작업 전 상태이므로 잔존). 이 결과는 §23.6 의 checklist 가 실제로 동작함을 확인. 후속 #1128-#1131 작업 후 이 grep 결과가 비어져야 함.

- [ ] **Step 4: ADR → Guide cross-reference 검증**

Run:
```bash
grep -E "ADR-723|§23" docs/03_Technical_Guides/async-concurrency.md | head -5
echo "---"
grep -E "async-concurrency|§23" docs/01_ADR/ADR-723_io-cpu-split-pattern.md | head -5
```

Expected: 양방향 cross-reference 매치. ADR-723 본문은 spec 만 참조 (가이드 §23 은 ADR 의 결정이 guide 로 구체화되는 구조이므로, ADR 본문에 §23 링크는 필수가 아닐 수 있음 — spec 에 명시).

- [ ] **Step 5: spec → ADR-723 / Guide §23 양방향 참조 확인**

Run:
```bash
grep -E "ADR-723|§23" docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md | head -5
```

Expected: spec doc 본문에 ADR-723 과 §23 양쪽 참조 명시 (Task 2 Step 1, Task 3 Step 3 에서 작성한 내용).

- [ ] **Step 6: Create follow-up issue for saturation metric**

ADR §4 Result/Evidence 에 명시된 saturation metric (ForkJoinPool.commonPool().activeThreadCount Prometheus expose) 은 본 PR scope 외. follow-up issue 로 등록.

Run:
```bash
gh issue create --label ready-for-agent --title "infra: expose ForkJoinPool.commonPool().activeThreadCount as Prometheus metric" --body "$(cat <<'EOF'
## Background

ADR-723 (IO/CPU split pattern) 의 §4 Result/Evidence 에서 cross-module saturation detection 을 위해 `ForkJoinPool.commonPool().activeThreadCount` 를 Prometheus 로 노출할 필요성 명시.

본 metric 은 4개 모듈이 Dispatchers.Default 를 공유할 때 saturation 여부를 empirical 판단하기 위한 trigger:

> `activeThreadCount > coreCount * 2` 지속 시 dedicated executor 분리 검토

## Scope

- module-infra 에 Micrometer metric 등록 (이름: `forkjoinpool_active_threads`, type: Gauge)
- `ForkJoinPool.commonPool()` 의 `activeThreadCount` 를 reflect
- `forkjoinpool_queued_tasks`, `forkjoinpool_steal_count` 등 보너스 metric 도 가능 시 추가

## Acceptance

- [ ] `curl http://localhost:8081/actuator/prometheus | grep forkjoinpool` 매치 확인
- [ ] 부하테스트 중 saturation 발생 시 metric > coreCount * 2 확인 가능
- [ ] ADR-723 §4 Result/Evidence 갱신 (saturation threshold 실측치 반영)

## Related

- ADR-723
- #1125 (blocks this)
- 후속 saturation trigger: dedicated executor 분리 ADR (TBD)
EOF
)"
```

Expected: GitHub issue URL 출력. PR 본문 "Related" 섹션에 추가.

---

---

## Task 5: Push and create PR

**Files:** None

- [ ] **Step 1: branch push**

Run:
```bash
git push -u origin feature/1125-io-cpu-split-pattern
```

Expected: `* [new branch] feature/1125-io-cpu-split-pattern -> feature/1125-io-cpu-split-pattern`. 원격에 새 branch 생성.

- [ ] **Step 2: PR 생성 (develop base)**

Run:
```bash
gh pr create --base develop --head feature/1125-io-cpu-split-pattern \
    --title "docs(adr): ADR-723 + Guide §23 IO/CPU split pattern (#1125)" \
    --body "$(cat <<'EOF'
## Summary

- ADR-723: IO/CPU 분리 패턴 5섹션 결정 기록
- Guide §23: Algorithm-based CPU 분류 + 모듈별 wrap 방식 (withContext/runBlocking)

## 산출물

- `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` (new, 5섹션)
- `docs/03_Technical_Guides/async-concurrency.md` (modify, §23 추가)

## 핵심 결정

1. **단일 dispatcher:** `Dispatchers.Default` (JVM-wide ForkJoinPool)
2. **분류:** Algorithm-based — O(n²)+ 또는 n=10K+ 무조건 offload
3. **Wrap 방식:** coroutine 모듈은 `withContext`, VT-only 모듈은 `runBlocking` bridge
4. **No code change:** 컨벤션 + reference 만. 모듈별 dedicated executor 분리 보류.

## Acceptance Criteria 매핑

| #1125 AC | 충족 위치 |
|---|---|
| IO/CPU 분리 패턴이 코드 컨벤션 또는 유틸리티로 정의됨 | Guide §23.1, §23.3 |
| 기존 `Dispatchers.Default` 올바른 사용 사례를 참고 패턴으로 문서화 | Guide §23.4 |
| 각 모듈에 적용 가능한 래핑 방식(withContext vs runBlocking)이 명확히 구분됨 | Guide §23.3 표 |

## 검증

- [x] `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` 파일 존재 (5섹션 구조)
- [x] `docs/03_Technical_Guides/async-concurrency.md` §23 섹션 추가 (8개 subsection)
- [x] 기존 §21/§22 손상 없음
- [x] Spec doc → ADR-723/§23 cross-reference

## Out of Scope

- 코드 변경 없음 (lint rule, utility class 등 모두 보류)
- 부하테스트는 후속 #1128-#1131 머지 후 별도 진행
- Saturation metric Prometheus expose — follow-up issue 로 별도 등록 (Task 4 Step 6)
- Dispatchers.Default saturation 자동 감지 + dedicated executor 분리 — follow-up ADR (TBD)

## Related

- Spec: docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md
- Blocks: #1128, #1129, #1130, #1131
- Follow-up: saturation metric issue (Task 4 Step 6 에서 생성)
- 선례: ItemCalculationExecutorConfig Phase 2.5 (3.5x latency regression)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL 출력. 예: `https://github.com/zbnerd/probabilistic-valuation-engine/pull/1198`

- [ ] **Step 3: PR URL 사용자 보고**

PR URL 을 사용자에게 보고. PR 본문 검증 (GitHub UI) 또는:

Run:
```bash
gh pr view --json url,title,state,baseRefName,headRefName
```

Expected: `state: OPEN`, `baseRefName: develop`, `headRefName: feature/1125-io-cpu-split-pattern`.

---

## Self-Review Checklist (post-write)

- [x] **Spec coverage:** 모든 #1125 acceptance criteria 가 ADR §1 (3.5x 선례) / Guide §23.1-§23.3 (분류 + wrap 방식) / Guide §23.4 (올바른 사례) 에 매핑됨.
- [x] **No placeholders:** Task 본문에 "TBD", "TODO", "implement later" 없음. 모든 코드/마크다운이 완전함.
- [x] **Type/symbol consistency:** §23.3 표의 4개 모듈명, §23.4-§23.5 의 파일 경로와 라인 번호가 spec doc 과 일치.
- [x] **Frequent commits:** Task 1, Task 2 Step 5, Task 3 Step 7, Task 5 Step 1 — 모두 의미 있는 commit 단위로 분리.

## Execution Notes

- 이 plan 은 **코드 변경 없음**. TDD red-green-refactor cycle 이 없음 (테스트 대상이 산출물 자체).
- 각 commit 후 `./gradlew compileKotlin compileJava --continue` 불필요 (문서만).
- Working tree 의 D 파일 (이전 작업 잔재) 은 본 plan 과 무관. commit 시 `git add <specific file>` 로 명시적 staging.
- 후속 4개 이슈 (#1128-#1131) 가 이 PR 머지 후 동시 진행 가능. PR 본문에 "Blocks" 명시.
