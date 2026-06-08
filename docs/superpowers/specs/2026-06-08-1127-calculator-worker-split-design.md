# Issue #1127: Calculator Pipeline parse/calc Worker Count 독립 설정

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1127
- Label: ready-for-agent
- Blocks: #1198 saturation follow-up (indirect)

## Goal

`SnapshotChunkProcessor` 의 `parseLines`(JSON parse) 와 `processItems`(확률 계산) worker count 를 독립 설정 가능하게 분리한다. 기존 `workerCount` 기본값은 그대로 유지하여 backward-compat 보장. Dispatcher override 옵션 포함 (custom String→CoroutineDispatcher converter).

## Background

### 현재 상태 (develop HEAD)

`module-calculator/.../SnapshotChunkProcessor.kt:37-39`:
```kotlin
private val workerCount: Int = requireNotNull(properties.workerCount.takeIf { it > 0 }) {
    "calculator.pipeline.worker-count must be positive: ${properties.workerCount}"
}
```

`SnapshotChunkProcessor.kt:60-79`:
```kotlin
launch {
    coroutineScope {
        repeat(workerCount) {
            launch(Dispatchers.Default) {
                parseLines(lineChannel, itemChannel, recordCount, successCount, totalItems)
            }
        }
    }
    itemChannel.close()
}

launch {
    coroutineScope {
        repeat(workerCount) {
            launch(Dispatchers.Default) {
                processItems(itemChannel, resultChannel, calculatedCount, errorCount)
            }
        }
    }
    resultChannel.close()
}
```

### 문제

`parseLines` (JSON parse + EquipmentItem 변환) 와 `processItems` (확률 계산) 가 같은 `Dispatchers.Default` 의 동일 worker pool 공유. 대규모 chunk 에서:
- JSON parse 가 CPU core 점유 시 계산 지연
- 반대로 계산이 길어지면 parse 도 대기

`parseWorkers` 와 `calcWorkers` 독립 설정으로 해결.

## Architecture

### 변경 파일 (3개)

| 파일 | 변경 |
|---|---|
| `module-calculator/.../config/PipelineProperties.kt` | 4 field 추가 (parseWorkers, calcWorkers, parseDispatcher, calcDispatcher) |
| `module-calculator/.../config/CoroutineDispatcherConverter.kt` (NEW) | `Converter<String, CoroutineDispatcher>` — `default`/`io`/`unconfined` short name 매핑 |
| `module-calculator/.../processor/SnapshotChunkProcessor.kt` | 2 worker field 추가 + 2 launch site dispatcher 변경 |

### 새 YAML 키

```yaml
calculator:
  pipeline:
    worker-count: 4                # legacy (parse/calc 공통)
    channel-capacity: 500
    parse-workers: 4               # NEW, default = 4
    calc-workers: 4                # NEW, default = 4
    parse-dispatcher: DEFAULT      # NEW, default = Dispatchers.Default
    calc-dispatcher: DEFAULT       # NEW, default = Dispatchers.Default
```

### Sizing 결정

| Field | Type | Default | Sizing 근거 |
|---|---|---|---|
| `parseWorkers` | Int | 4 | 기존 `workerCount` 와 동일 (backward compat) |
| `calcWorkers` | Int | 4 | 기존 `workerCount` 와 동일 (backward compat) |
| `parseDispatcher` | CoroutineDispatcher (YAML String) | `Dispatchers.Default` | ADR-723 §23.2 Algorithm-based 분류: O(n) JSON parse, n=10K+ 무조건 offload |
| `calcDispatcher` | CoroutineDispatcher (YAML String) | `Dispatchers.Default` | ADR-723 §23.2: 확률 계산은 무조건 offload (모든 크기) |

### CoroutineDispatcher YAML binding

`@ConfigurationProperties` 는 Kotlin `CoroutineDispatcher` 를 직접 bind 하지 못함. `Converter<String, CoroutineDispatcher>` bean 추가:

```kotlin
@Component
class CoroutineDispatcherConverter : Converter<String, CoroutineDispatcher> {
    override fun convert(source: String): CoroutineDispatcher = when (source.lowercase()) {
        "default" -> Dispatchers.Default
        "io" -> Dispatchers.IO
        "unconfined" -> Dispatchers.Unconfined
        else -> throw IllegalArgumentException(
            "Unknown dispatcher: $source. Supported: default, io, unconfined"
        )
    }
}
```

YAML usage:
```yaml
calculator:
  pipeline:
    parse-dispatcher: io        # → Dispatchers.IO
    calc-dispatcher: default    # → Dispatchers.Default
```

미명시 시 Kotlin default value (`Dispatchers.Default`) 자동 적용. Converter 는 override 시에만 호출.

### 기존 `workerCount` 처리

**유지 + deprecate 없음 + fallback 없음.** `workerCount` 는 legacy field 로 그대로 동작. parseWorkers/calcWorkers 는 독립 field, YAML 에 명시 안 하면 default 4.

## 산출 파일

| 파일 | 작업 | 라인 변경 예상 |
|---|---|---|
| `module-calculator/.../config/PipelineProperties.kt` | Modify | 4 field 추가 (lines 7-8 부근) |
| `module-calculator/.../config/CoroutineDispatcherConverter.kt` | **Create** | ~20 lines (Converter<String, CoroutineDispatcher>) |
| `module-calculator/.../processor/SnapshotChunkProcessor.kt` | Modify | 2 field 추가 (lines 37-39 부근) + 2 launch site (lines 61, 72) |
| `docs/03_Technical_Guides/async-concurrency.md` | Modify | §23.3 module-calculator 행 dispatcher override 한 줄 추가 (cross-ref) |

## Acceptance Criteria 매핑

| #1127 AC | 충족 |
|---|---|
| parseWorkers, calcWorkers가 YAML 설정으로 독립 조정 가능 | `calculator.pipeline.parse-workers`, `calc-workers` YAML 키 |
| 기본값 변경 없이 기존 동작 유지 | default 4 (기존 workerCount 값) + legacy workerCount 미변경 |
| ./gradlew :module-calculator:test 통과 | 변경 file 2개, API surface 미변경, 기존 test 영향 없음 |

## Testing / Verification

### Unit test

기존 `SnapshotChunkProcessorTest` (있는 경우) 는 `process()` 호출 시 default 동작 검증. Default 4 + Default dispatcher = 기존과 동일. **test 수정 불필요.**

### 검증 절차 (PR 머지 전)

```bash
# 1. compile
./gradlew :module-calculator:compileKotlin --continue
# Expected: BUILD SUCCESSFUL

# 2. test
./gradlew :module-calculator:test
# Expected: 기존 test 모두 통과 (default 동작 변경 없음)

# 3. YAML binding sanity (default 동작)
./gradlew :module-calculator:bootRun &
sleep 10
curl -s http://localhost:8082/actuator/health
# Expected: {"status":"UP"}
kill %1

# 4. YAML override 동작 (선택)
# application-local.yml 에:
#   calculator:
#     pipeline:
#       parse-workers: 8
#       calc-workers: 2
# → 부팅 후 부하테스트로 throughput 비교
```

## Migration / Rollout

- **단일 PR:** config + 1 file modify. Risk 낮음 (backward compat).
- **Rollback:** `git revert` 1 commit. runtime 부하테스트 없이 revert 가능.
- **Hot-deploy:** Spring bean 의 property 변경만. startup-time 검증.

## 영향 범위 (Out of Scope)

- ❌ `workerCount` legacy field 제거 → **follow-up issue 자동 생성** (#1127 PR 의 verification 단계에서 `gh issue create` 로 등록)
- ❌ channel capacity 분리 (per-stage `channel-capacity-parse`, `channel-capacity-calc`, 별도 issue) — Q2 결정: YAGNI
- ❌ parseDispatcher/calcDispatcher 별도 bean 등록 (현재 YAML String override, Converter 경유) — 후속 issue
- ❌ 새 ADR 불필요 (refactor only, ADR-723 §23.3 의 coroutine 패턴 적용)
- ❌ runtime 부하테스트 (후속 #1198 saturation metric 작업과 동시 진행)
- ❌ module-external-api 동일 패턴 (#1128 별도 brainstorming)

## Follow-up Issues (PR verification 단계에서 자동 생성)

1. **#TBD: deprecate legacy `workerCount` field** — `calculator.pipeline.worker-count` 를 `@Deprecated` 로 표시. 기본값 fallback 또는 auto-removal 결정 후 별도 PR.
2. **#TBD: register `parseDispatcher`/`calcDispatcher` as named Spring beans** — Converter + YAML String 대신 bean reference 로 override. 구성력 ↑, type safety ↑.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `Dispatchers.Default` 가 `data class` default value 로 사용 시 `equals/hashCode` 영향 | Low | Low | `Dispatchers.Default` 는 singleton object → reference equality OK |
| YAML binding 시 `CoroutineDispatcher` 직렬화 실패 | Low | Medium | `@ConfigurationProperties` 가 Kotlin `CoroutineDispatcher` 를 어떻게 처리하는지 검증 필요. 만약 실패 시 `String` + 별도 bean lookup 으로 변경 |
| Parse 가 calc 보다 빨라서 itemChannel backpressure 발현 | Low | Low | 기존 `channelCapacity=500` 유지. 발현 시 별도 issue |
| Boot 시 dispatcher override YAML 잘못된 값 (e.g., `parse-dispatcher: WRONG`) | Low | Low | `CoroutineDispatcher` 는 type binding 시 ClassCastException → 부팅 fail-fast. OK |

## Self-Review Check (spec 작성 후)

- [x] Placeholder: 없음 (TBD/TODO 0건, "Follow-up Issues" 섹션의 `#TBD` 는 GitHub issue 번호 placeholder — PR 시 자동 할당)
- [x] Internal consistency: 4 결정 (default, dispatcher converter, legacy, follow-up) 정합
- [x] Scope: 단일 PR, bounded
- [x] Ambiguity: dispatcher override type 명확 (String → Converter → CoroutineDispatcher)
- [x] Backward compat: 기존 `workerCount` 유지 명시
- [x] Guide cross-ref: async-concurrency.md §23.3 module-calculator 행 dispatcher override 링크

## Related

- Spec: 이 파일
- ADR-723: docs/01_ADR/ADR-723_io-cpu-split-pattern.md (Algorithm-based 분류, `withContext` 패턴)
- Plan (후속): docs/superpowers/plans/2026-06-08-1127-calculator-worker-split.md (writing-plans 산출)
- Issue #1127: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1127
- Predecessor: #1125 (IO/CPU split pattern, MERGED in PR #1199)
