# Issue #1126: Executor Bean Rename + expectationComputeExecutor IO/CPU Split

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1126
- Label: ready-for-human
- Blocks: ADR-723 §4 saturation follow-up (indirect)

## Goal

`expectationComputeExecutor`의 IO/CPU 혼재를 해소하고, `CoreExecutorConfig.taskExecutor` + `RestControllerExecutorConfig.taskExecutor` 의 bean 이름 충돌을 명시적 naming 으로 해결한다. ADR-723 의 `Dispatchers.Default` 단일 dispatcher 결정과 양립하는 legacy 한정 dedicated CPU executor.

## Background

### 문제

1. **`InfraExecutorConfig.expectationComputeExecutor` (lines 98-120)**
   - Bean 이름은 "compute" 이지만 docstring 은 "parsing/calculation/external calls" 로 혼합 명시.
   - Pool sizing (core=4, max=8) 은 IO-bound bulkhead 기준. CPU-bound 작업 시 thread 부족.
   - `EquipmentDataResolver.kt:42`, `EquipmentFetchProvider.kt:38 (docstring)` 등에서 사용.

2. **`CoreExecutorConfig.taskExecutor` + `RestControllerExecutorConfig.taskExecutor` (충돌)**
   - 둘 다 `@Bean(name = ["taskExecutor"])` 로 동일 bean 이름 정의.
   - `CoreExecutorConfig` 는 모든 모듈 공통 (`async-` prefix).
   - `RestControllerExecutorConfig` 는 module-rest-controller 한정 (`rest-api-` prefix) — **현재 develop HEAD 에 미존재, #1126 PR 에서 신규 생성**.
   - module-app 이 둘 다 import 시 `IllegalStateException` (동일 이름 bean 충돌).
   - `@ConditionalOnMissingBean` 또는 loading order 로 예측 불가.

### #1125 (ADR-723) 와의 관계

ADR-723 §3 Trade-off: "단일 dispatcher (`Dispatchers.Default`) 결정, 모듈별 dedicated executor 분리 안 함".

이 결정은 **새로운 coroutine 기반 코드** 경로 (withContext Dispatchers.Default) 에 적용. legacy `expectationComputeExecutor` 경로는 별도:
- EquipmentDataResolver, EquipmentFetchProvider 는 `@Qualifier("expectationComputeExecutor") Executor` 주입 (coroutine 미사용)
- 이 경로의 IO/CPU 분리는 ADR-723 과 **양립**

| Domain | Pattern |
|---|---|
| New coroutine code (#1128-#1131) | `withContext(Dispatchers.Default) { ... }` (ADR-723) |
| Legacy `expectationComputeExecutor` path | `expectationComputeIoExecutor` + `expectationComputeCpuExecutor` (이 spec) |

## Architecture

### Bean 정의 (총 4개)

```text
module-infra config/
├─ CoreExecutorConfig.kt
│   └─ @Bean defaultAsyncExecutor (prefix: async-)
│       replace: taskExecutor
├─ InfraExecutorConfig.kt
│   ├─ @Bean expectationComputeIoExecutor (IO-bound, prefix: expectation-io-)
│   │   replace: expectationComputeExecutor (legacy, IO-only)
│   └─ @Bean expectationComputeCpuExecutor (CPU-bound, prefix: expectation-cpu-)
│       NEW (CPU 작업용 dedicated)
└─ RestControllerExecutorConfig.kt (NEW)
    └─ @Bean restApiControllerExecutor (prefix: rest-api-)
        replace: taskExecutor (이전에는 충돌 가능성, 이제 명시)
```

### Sizing

| Bean | core | max | queue | 근거 |
|---|---|---|---|---|
| `defaultAsyncExecutor` | 4 | 8 | 200 | `@Async` fire-and-forget (기존 `async` 설정) |
| `restApiControllerExecutor` | 4 | 8 | 200 | controller dispatch (default async 동일) |
| `expectationComputeIoExecutor` | 4 | 8 | 5000 | IO-bound bulkhead (기존 `expectation` 설정) |
| `expectationComputeCpuExecutor` | `availableProcessors` (default 4) | `availableProcessors` * 2 (default 8) | 1000 | CPU-bound (ItemCalculationExecutorConfig sizing 동일) |

### Application YAML 변경

```yaml
executor:
  expectation:
    compute-io:
      core-pool-size: 4
      max-pool-size: 8
      queue-capacity: 5000
    compute-cpu:
      core-pool-size: ${availableProcessors:4}    # t3.small 2 vCPU → 4 fallback
      max-pool-size: ${availableProcessors:8}     # 1:2 ratio with core
      queue-capacity: 1000
  # (기존 async, alert, operational, backfill 변경 없음)
```

### Injection Sites (hard rename)

| 파일 | 라인 | 변경 |
|---|---|---|
| `messaging/PgmqEventPublisherAdapter.kt` | 35 | `@Qualifier("taskExecutor")` → `@Qualifier("defaultAsyncExecutor")` |
| `messaging/KafkaEventPublisher.kt` | 26 | `@Qualifier("taskExecutor")` → `@Qualifier("defaultAsyncExecutor")` |
| `provider/EquipmentFetchProvider.kt` | 51 | `@Qualifier("taskExecutor")` → `@Qualifier("defaultAsyncExecutor")` |
| `cache/equipment/EquipmentDataResolver.kt` | 42 | `@Qualifier("expectationComputeExecutor")` → `@Qualifier("expectationComputeIoExecutor")` |

(CPU executor 사용자 site 는 현재 없음. CPU 작업 site 는 ADR-723 §23.3 의 `withContext(Dispatchers.Default)` 패턴으로 처리하거나, 후속에서 `@Qualifier("expectationComputeCpuExecutor")` 추가)

## 산출 파일

| 파일 | 작업 | 핵심 |
|---|---|---|
| `module-infra/.../config/CoreExecutorConfig.kt` | Modify | `@Bean` 이름 `taskExecutor` → `defaultAsyncExecutor` |
| `module-infra/.../config/InfraExecutorConfig.kt` | Modify | `expectationComputeExecutor` → `expectationComputeIoExecutor` + `expectationComputeCpuExecutor` |
| `module-infra/.../config/RestControllerExecutorConfig.kt` | **Create** | `restApiControllerExecutor` |
| `module-infra/.../config/ExecutorProperties.kt` | Modify | `expectation.compute-io`, `expectation.compute-cpu` 섹션 |
| `module-infra/.../config/ExecutorConfig.kt` | Modify | `@Import(RestControllerExecutorConfig::class)` 추가 |
| `module-infra/.../messaging/PgmqEventPublisherAdapter.kt` | Modify | `@Qualifier("defaultAsyncExecutor")` |
| `module-infra/.../messaging/KafkaEventPublisher.kt` | Modify | `@Qualifier("defaultAsyncExecutor")` |
| `module-infra/.../provider/EquipmentFetchProvider.kt` | Modify | `@Qualifier("defaultAsyncExecutor")` |
| `module-infra/.../cache/equipment/EquipmentDataResolver.kt` | Modify | `@Qualifier("expectationComputeIoExecutor")` |
| `src/main/resources/application.yml` | Modify | `executor.expectation.compute-io/cpu` 키 추가 |

## Acceptance Criteria 매핑

| #1126 AC | 충족 위치 |
|---|---|
| expectationComputeExecutor가 IO/CPU 역할로 분리됨 | `expectationComputeIoExecutor` + `expectationComputeCpuExecutor` |
| taskExecutor bean 이름 충돌 해결 | `defaultAsyncExecutor` + `restApiControllerExecutor` 명시적 분리 |
| 기존 bean을 참조하는 모든 injection point에 @Qualifier 업데이트 | 4 file hard rename |
| `./gradlew compileKotlin compileJava --continue` 통과 | rename 일관성 + compile 검증 |

## Testing / Verification

### Unit 테스트

기존 `ExecutorConfigTest.java` 가 `InfraExecutorConfig` 직접 사용. 새 `RestControllerExecutorConfig` 가 추가되어도 `@Import` 패턴 동일 → 기존 테스트 그대로 통과 예상.

### 검증 절차 (PR 머지 전)

```bash
# Step 1: Hard rename 누락 검증 (각 old name 이 0 hit)
grep -rn '"taskExecutor"' --include='*.kt' --include='*.java' \
    module-infra module-external-api module-synchronizer module-calculator module-rest-controller 2>/dev/null \
    | grep -v '/build/' | grep -v '\.worktrees/'
# Expected: 0 hit

grep -rn '"expectationComputeExecutor"' --include='*.kt' --include='*.java' \
    module-infra module-external-api module-synchronizer module-calculator module-rest-controller 2>/dev/null \
    | grep -v '/build/' | grep -v '\.worktrees/'
# Expected: 0 hit

# Step 2: New names 정확성 검증
grep -rn '@Qualifier("(defaultAsyncExecutor|restApiControllerExecutor|expectationComputeIoExecutor|expectationComputeCpuExecutor)")' --include='*.kt' --include='*.java' \
    module-infra module-external-api module-synchronizer module-calculator module-rest-controller 2>/dev/null \
    | grep -v '/build/' | grep -v '\.worktrees/'
# Expected: 4+ hits (EquipmentDataResolver Io, PgmqEventPublisherAdapter + KafkaEventPublisher + EquipmentFetchProvider default)

# Step 3: compile 검증
./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue
# Expected: BUILD SUCCESSFUL

# Step 4: (선택) 부하테스트 — runtime 검증. ADR-723 Phase 2 와 동시 진행.
```

## Migration / Rollout

- **단일 PR:** refactor only. 한 PR 에서 모든 rename + split + 검증.
- **Rollback:** rename 만 했으므로 git revert 1 commit 으로 복구 가능. runtime 부하테스트 결과 후 별도 후속 PR 없음 (legacy + ADR-723 의 이원화 의도된 것).
- **Hot-deploy:** Spring DI 변경이지만 startup-time 검증 가능. `IllegalStateException` (bean 충돌) 시 startup fail-fast.

## 영향 범위 (Out of Scope)

- ❌ #1125 ADR-723 의 `Dispatchers.Default` 결정 변경 안 함
- ❌ 다른 executor (alertTaskExecutor, aiTaskExecutor, operationalExecutor, backfillExecutor) 이름 유지
- ❌ runtime 부하테스트는 #1125 머지 후 cold-miss 시나리오로 별도 측정
- ❌ `ExecutorProperties.validateAll()` 의 validation 로직 변경 없음
- ❌ 새 ADR 불필요 (refactor 만, ADR-723 §3 의 trade-off 결정에 양립)
- ❌ CPU executor 의 현 사용자 site 없음 (후속 작업에서 추가)

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Hard rename 시 injection site 누락 | Medium | High (compile fail) | grep 검증 + compileKotlin --continue |
| CPU executor oversizing (t3.small 2 vCPU) | Low | Low | YAML 기본값 conservative (core=4, max=4) |
| module-app 에서 두 config 동시 import 시 startup fail | Low | High | `CoreExecutorConfig.taskExecutor` `@Bean` 제거 + `RestControllerExecutorConfig` 의 명시적 bean 이름 (`restApiControllerExecutor`) 으로 충돌 원천 차단 |
| `Dispatchers.Default` 와 `expectationComputeCpuExecutor` 이원화 (혼란) | Low | Low | ADR-723 §23.3 cross-reference (후속 task) |
| `RestControllerExecutorConfig` 가 develop HEAD 에 미존재 (현재 working tree 깨짐) | High | Medium | 작업 전 `git checkout HEAD -- <files>` 로 복원 (이전 세션 잔재) |

## Self-Review Check (spec 작성 후)

- [x] Placeholder: 없음 (TBD/TODO 0건)
- [x] Internal consistency: 4 bean 이름 일관, naming convention 일관
- [x] Scope: 단일 PR 로 bounded, 후속 의존 없음
- [x] Ambiguity: sizing 기본값 명시, dispatchers.Default 와 차이 명시
- [x] Cross-reference: ADR-723 §3 (trade-off) 와 §23.3 (wrap 방식) 모두 참조

## Related

- Spec: 이 파일
- ADR-723: docs/01_ADR/ADR-723_io-cpu-split-pattern.md (이원화 의도된 trade-off)
- Plan (후속): docs/superpowers/plans/2026-06-08-1126-executor-rename-split.md (writing-plans 산출)
- Working tree 상태: develop HEAD 의 InfraExecutorConfig / CoreExecutorConfig 가 working tree 에서 missing. PR 작업 전 `git checkout HEAD -- <files>` 필요.
