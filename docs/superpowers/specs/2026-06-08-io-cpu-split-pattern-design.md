# IO/CPU Split Pattern: Architectural Convention for Dispatcher Routing

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1125
- Related ADR: ADR-720
- Blocks: #1128, #1129, #1130, #1131

## Goal

활성 모듈 4개(external-api, synchronizer, calculator, rest-controller)의
VT executor가 IO/CPU 작업을 혼재 실행하는 문제를 해결한다.
CPU-bound 작업을 `Dispatchers.Default`로 offload하는 컨벤션을 확립하고,
4개 후속 이슈(#1128-#1131)가 동일 기준으로 적용할 수 있는 reference를 제공한다.

## Background

### 문제

- 모든 VT executor가 IO-bound(HTTP, DB, Redis, file R/W)와
  CPU-bound(JSON parse/serialize, GZIP, SHA-256, large collection, 확률 계산)를
  동일 carrier thread에서 직렬 실행.
- CPU-heavy 구간이 carrier를 장시간 점유 → 같은 executor의 다른 IO-bound VT가
  carrier 획득을 위해 대기 → throughput 저하.
- `Dispatchers.Default` 또는 `Dispatchers.IO`로 dispatcher를 분리한 모듈이 거의 없음.

### 선례

`ItemCalculationExecutorConfig.kt` Phase 2.5 시도 (이미 코드 주석에 기록):

```
Phase 2.5: Virtual Thread + Semaphore(64) → CPU-bound에서 3.5× 회귀
```

→ 동일 원칙(IO/CPU 분리)이 이미 실측 근거와 함께 코드베이스에 존재.

### 올바른 사례 (이미 코드베이스에 존재)

- `module-infra/.../ResultReadyProjectionWorker.kt:123-130`
  — `runBlocking(Dispatchers.Default) { async(Dispatchers.Default) { ... } }`
  (VT-only 모듈의 bridge 패턴)
- `module-calculator/.../SnapshotChunkProcessor.kt:60-75`
  — `launch(Dispatchers.Default) { ... }` (Coroutine 모듈)

### 안티 사례 (수정 대상)

- `module-infra/.../PgmqWorker.kt:377`
  — `runBlocking(Dispatchers.IO) { items.map { calculateOnly(it) } }`
  (CPU-bound를 IO dispatcher에 dispatch) — #1131에서 수정
  (※ module-infra는 위 grep 체크리스트 대상이 아니므로 #1131 PR review에서 별도 확인)
- `module-external-api/.../OcidLookupPhase.kt:149-185` 외 다수
  — VT carrier에서 JSON parse + GZIP inline 실행 — #1128에서 수정

## Architecture

### Dispatcher 라우팅 원칙

| 작업 종류 | 예시 | 실행 컨텍스트 |
|---|---|---|
| **IO-bound** | DB query/update, HTTP call, Redis op, file R/W | VT executor (현재) |
| **CPU-bound** | JSON parse/serialize, GZIP, SHA-256, large collection, 확률 계산 | `Dispatchers.Default` (coroutine) |

### CPU-bound 분류 (Algorithm-based)

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

### 모듈별 wrap 방식

| 모듈 | 호출 컨텍스트 | 권장 패턴 |
|---|---|---|
| `module-external-api` | `CoroutineScope(dispatcher).future { ... }` 안 | `withContext(Dispatchers.Default) { cpuWork() }` |
| `module-calculator` | `launch(Dispatchers.Default) { ... }` 안 (coroutine) | `withContext(Dispatchers.Default) { cpuWork() }` |
| `module-synchronizer` | VT-only, no CoroutineScope | `runBlocking(Dispatchers.Default) { cpuWork() }` |
| `module-rest-controller` | `CompletableFuture.supplyAsync(..., executor)` | 컨텍스트 따라 위 1·2·3행 중 선택 |

**선택 기준:**

- 이미 `CoroutineScope` 안 → `withContext`
- VT-only → `runBlocking` bridge (이 경우만)
- `runBlocking`은 VT carrier를 *block*하지 않음.
  Dispatchers.Default의 ForkJoinPool worker로 점프 후 작업 완료 시 VT로 복귀.

### 단일 dispatcher 결정

`Dispatchers.Default` (JVM-wide ForkJoinPool, CPU core count - 1 bound) 단일 사용.

- 모듈별 dedicated executor 분리 안 함. (관리 비용, 1125 scope 폭증 회피)
- 한 모듈이 core 점유 시 다른 모듈 영향 → 후속 4개 이슈 적용 후 부하테스트로 측정.
  임계치 초과 시 #1126 또는 후속 ADR에서 dedicated executor 분리 검토.

## 산출 파일

| 파일 | 형식 | 위치 |
|---|---|---|
| `ADR-720_io-cpu-split-pattern.md` | adr-conventions.md 5섹션 | `docs/01_ADR/` |
| `async-concurrency.md` patch | §23 섹션 추가 | `docs/03_Technical_Guides/` |
| (이 spec doc) | brainstorming 산출물 | `docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md` |

## Acceptance Criteria 매핑

| #1125 AC | 충족 위치 |
|---|---|
| IO/CPU 분리 패턴이 코드 컨벤션 또는 유틸리티로 정의됨 | Guide §23.1, §23.3 |
| 기존 `Dispatchers.Default` 올바른 사용 사례를 참고 패턴으로 문서화 | Guide §23.4 |
| 각 모듈에 적용 가능한 래핑 방식(withContext vs runBlocking)이 명확히 구분됨 | Guide §23.3 표 |
| 3.5x 회귀 선례 근거 | ADR §1 (Background) |
| CPU-bound 분류 기준 | Guide §23.2 |
| 안티 사례 | Guide §23.5 |

## Testing / Verification

- **단위 테스트 없음** — 이 이슈는 문서/ADR만. 코드 변경 없음.
- **검증 절차:**
  1. `git log -- docs/01_ADR/ADR-720_io-cpu-split-pattern.md` — 파일 존재
  2. `git log -- docs/03_Technical_Guides/async-concurrency.md` — §23 섹션 추가
  3. 후속 이슈(1128-1131) PR 본문에 "References #1125, applies §23" 명시
  4. 부하테스트는 4개 머지 후 cold-miss 시나리오로 비교
     (RESET_VIEWS=1 + RESET_ACTIVE_JOBS=1, baseline 대비 throughput/latency)

### PR Review Checklist

```bash
# Anti-pattern A: CPU-bound를 IO dispatcher에 dispatch
grep -rn "runBlocking(Dispatchers\.IO)" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller

# Anti-pattern B: VT/IO에서 JSON parse/serialize inline
grep -rn "objectMapper\.readTree\|objectMapper\.writeValueAs" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller \
    | grep -v "Dispatchers.Default"
```

CPU-bound 코드 발췌 발견 시 → reviewer가 §23.2 표에 따라 offload 권고.

## Migration / Rollout

- **Phase 1:** #1125 머지 → 4개 후속 이슈 동시 진행 가능 (이미 #1125가 blocker 해소)
- **Phase 2:** #1128, #1129, #1130, #1131 머지 → cold-miss 부하테스트
- **Phase 3:** baseline 대비 throughput/latency 비교, ADR §4 "Result/Evidence" 갱신
- **Rollback:** #1125 자체는 문서 변경 → 롤백 의미 없음.
  후속 이슈가 독립 머지/롤백 가능.

## 영향 범위 (Out of Scope)

- ❌ 기존 코드 변경 (ItemCalculationExecutorConfig 강화 등) — §23.4 모범 사례 인용만
- ❌ lint rule / 자동 검증 — PR review 절차로 강제 (§23.6 grep commands)
- ❌ module-core/module-common utility — pure convention
- ❌ Dispatchers.Default saturation 자동 감지 — #1126 결과 후 별도 검토

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| 후속 4개 이슈가 일관성 없이 적용 | Medium | Medium | §23.3 모듈별 wrap 방식 명시, PR review checklist |
| Dispatchers.Default saturation | Low | Medium | 부하테스트로 측정, 임계치 초과 시 dedicated executor 분리 ADR |
| ADR 번호 충돌 | Low | Low | 머지 전 `ls docs/01_ADR/` 로 확인 (현재 latest 719) |
| 가이드 §23이 기존 §21/§22와 충돌 | Low | Low | §23.1 cross-reference 명시 |

## Out-of-Scope 후속 작업

1. **#1126** — infra executor bean naming 명확화 (1125의 dispatcher 결정 보완)
2. **#1128-#1131** — 4개 모듈에 §23 적용
3. **#1127** — calculator parse/calc worker count 독립화 (1125와 디커플)
4. (후속 ADR 후보) Dispatchers.Default saturation 측정 후 dedicated executor 분리 검토
