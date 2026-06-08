# Issue #1131: ExternalApiWorker + PgmqWorker CPU/IO 분리

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1131
- Label: ready-for-agent
- Blocked by: #1125 (MERGED in PR #1199)
- Blocks (indirect): #1198 saturation follow-up

## Goal

`ExternalApiWorker.runCalculationAndComplete` (module-external-api) 와 `PgmqWorker.processSequentialBatch` (module-infra) 의 CPU/IO 분리. `ItemCalculationExecutorConfig` 의 "VT rejected due to 3.5x latency regression on CPU-bound work" 원칙 적용.

## Background

### 문제

1. **ExternalApiWorker.runCalculationAndComplete**: PgmqWorker 의 virtual thread 에서 `pureCalculationPort.calculate()` + `objectMapper.writeValueAsString()` + `compress()` + `sha256Hex()` 전부 inline 실행. CPU-heavy 가 VT carrier 장시간 pinning.

2. **PgmqWorker.processSequentialBatch**: `calculateOnly()` 를 `Dispatchers.IO` 에 dispatch. IO dispatcher 64 thread 한정. CPU 작업이 IO thread 점유 → 실제 IO starvation.

## Architecture

### File 1: ExternalApiWorker.runCalculationAndComplete (module-external-api)

```kotlin
// Issue #1131: CPU offload (calculate + serialize + gzip + SHA-256) on Dispatchers.Default.
// DB read (calculationInputPort.findByJobId) + DB write (executionService.completeCalculation) on VT.
suspend fun runCalculationAndComplete(...) {
    val input = calculationInputPort.findByJobId(jobId)  // IO (VT)

    val (result, json) = runBlocking(Dispatchers.Default) {
        val r = pureCalculationPort.calculate(input)  // CPU: Markov, DP
        val j = objectMapper.writeValueAsString(r) + compress(r) + sha256Hex(r)  // CPU
        r to j
    }  // CPU offloaded

    executionService.completeCalculation(result, json)  // IO (VT)
}
```

(Or via `runBlocking(Dispatchers.Default)` directly in current sync method — caller unchanged, see plan.)

### File 2: PgmqWorker.processSequentialBatch (module-infra)

```kotlin
// 기존: runBlocking(Dispatchers.IO) { calculateOnly() }
// 변경: runBlocking(Dispatchers.Default) { calculateOnly() }  (CPU → Default not IO)
```

PGMQ worker caller 가 `runBlocking(Dispatchers.Default)` 호출. ADR-723 §23.3 PGMQ single-threaded batch → runBlocking safe.

## 산출 파일 (2 file modify, 2 module)

| File | Module | 작업 |
|---|---|---|
| `module-external-api/.../worker/ExternalApiWorker.kt` | external-api | Modify `runCalculationAndComplete` |
| `module-infra/.../worker/PgmqWorker.kt` | infra | Modify `processSequentialBatch` |

## Acceptance Criteria 매핑

| #1131 AC | 충족 |
|---|---|
| ExternalApiWorker의 계산+직렬화+GZIP+SHA-256이 Dispatchers.Default에서 실행 | File 1 |
| PgmqWorker.processSequentialBatch의 calculateOnly()가 Dispatchers.Default에서 실행 | File 2 |
| DB read/write는 기존 VT executor 유지 | File 1 (DB read/write not wrapped) |
| ./gradlew compileKotlin compileJava --continue 통과 | refactor only |

## Testing / Verification

```bash
# 1. compile
./gradlew :module-external-api:compileKotlin :module-infra:compileKotlin --continue 2>&1 | tail -10
# Expected: BUILD SUCCESSFUL (또는 pre-existing module-infra 에러만)

# 2. grep 검증
grep -rn "runBlocking(Dispatchers\.Default)" --include='*.kt' module-external-api module-infra 2>/dev/null
# Expected: 2+ hits (ExternalApiWorker + PgmqWorker)
```

## Migration / Rollout

- 단일 PR. Risk 낮음.
- PgmqWorker 의 `Dispatchers.IO` → `Dispatchers.Default` 변경은 CPU 작업의 dispatcher affinity. 기존 IO 작업 (PGMQ read/write) 는 caller 에서 실행.

## 영향 범위 (Out of Scope)

- ❌ Module-synchronizer / module-rest-controller 동일 pattern — #1129 / #1130 별도.
- ❌ 새 ADR — ADR-723 §23.3 pattern 적용만.
- ❌ Runtime 부하테스트 — #1198 saturation metric 와 동시.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| ExternalApiWorker caller 가 suspend fun 미지원 | Low | Low | 현재 caller 가 PgmqWorker (sync). `runBlocking(Dispatchers.Default) { cpuWork() }` 패턴으로 호환. |
| PgmqWorker 가 single-threaded batch 아닌 경우 runBlocking risk | Low | Medium | ADR-723 §23.3 검증. PGMQ worker 는 본질적으로 single-threaded. |
| `Dispatchers.IO` → `Dispatchers.Default` 변경 후 IO 작업 (PGMQ read) 성능 영향 | Low | Low | IO 작업은 caller 의 caller 에서 실행 (PGMQ 의 executor). 본 변경은 `calculateOnly()` 만 영향. |

## Self-Review Check

- [x] Placeholder: 없음
- [x] Internal consistency: 2 file 의 wrap pattern 정합 (`runBlocking(Dispatchers.Default)`)
- [x] Scope: 단일 PR, bounded
- [x] Ambiguity: ExternalApiWorker 의 caller pattern 명확
- [x] AC coverage: 4 AC 모두 file 매핑

## Related

- Spec: 이 파일
- ADR-723: `docs/01_ADR/ADR-723_io-cpu-split-pattern.md`
- Plan (후속): `docs/superpowers/plans/2026-06-08-1131-infra-worker-dispatcher.md`
- Issue #1131: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1131
- Predecessor: #1125, #1128, #1129, #1130
- Sibling: #1198
