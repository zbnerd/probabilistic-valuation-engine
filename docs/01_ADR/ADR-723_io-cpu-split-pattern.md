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
