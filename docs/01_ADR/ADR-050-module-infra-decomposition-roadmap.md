# ADR-050: module-infra 분해 로드맵 (444-file God Module)

- Status: Accepted
- Date: 2026-06-05
- Owner: arch
- Related: #907

---

## 1. Background / Problem

### Background

`module-infra` = 444 파일 / 48,620줄 / 전체 51%. 단일 모듈에 domain model, application service, port adapter, infra impl, cross-cutting executor, 51개 config bean 혼재. 모든 모듈(`module-external-api`, `module-calculator`, `module-synchronizer`, `module-rest-controller`, `module-auth`, `module-app`)이 `module-infra`에 직접 의존 → 변경 blast radius 최대, 컴파일 단위 거대, 테스트 격리 불가.

### Problem

- **Fan-in 폭발**: `module-infra` 변경 시 6개 다운스트림 모듈 동시 컴파일 + 회귀 검증 필요
- **결합도**: 51개 `@Configuration` 클래스가 28개 `infrastructure/*` 패키지를 wiring → 1건 변경이 모든 wiring에 영향
- **테스트 속도**: 단일 `module-infra:test` 가 수 분 소요, infra 일부 격리 검증 불가
- **Acyclic 의존성 깨짐 위험**: `application/service/` 가 도메인 + infra 동시 의존 → 신규 모듈 분리 시 순환 위험

### Goal

1. `module-executor` 추출 (모든 추출의 차단요소 해소)
2. 8개 후보 서브모듈의 파일/포트/의존성/마이그레이션 전략 문서화
3. `module-infra` fan-in 0까지 단계적 축소

---

## 2. Decision

> **순방향 fan-out 추출**: `module-executor` 선행 → 다른 7개 모듈은 executor 의존성만으로 추출. `module-infra`는 호환 facade로 잔류 후 단계적 축소.

### 후보 서브모듈 (현실 검증된 라인 수)

| # | 모듈 | 파일 | 줄 | 의존 | 비고 |
|---|------|------|-----|------|------|
| 1 | `module-executor` | 25 + 9 policy/strategy | 2,332 | module-common | 모든 추출 선행 |
| 2 | `module-persistence` | 49 | 4,263 | executor, jdbc | JPA/QueryDSL adapter |
| 3 | `module-monitoring` | 48 | 4,088 | executor, metrics | Prometheus + alert |
| 4 | `module-cache` | 29 | 3,643 | executor, caffeine, l2 | TieredCache |
| 5 | `module-pgmq` (+messaging+queue+event) | 33 | 3,392 | executor, jdbc | PGMQ wrapper |
| 6 | `module-aop` (+concurrency+lock) | 35 | 3,528 | executor | retry/lock/singleflight |
| 7 | `module-external-client` | 20 | 2,145 | executor, webclient | Nexon API |
| 8 | `module-security` | 8 + 1 auth | 1,034 | module-web | SecurityConfig 이전 |

> **조정 사항**: 이슈 본문 vs 실측 차이 — `calculation-engine`/`calculation` 패키지 부재(0 file). 계산 로직은 `application/service/`(4 파일) + `infrastructure/calculation/`(없음) + `domain/`(13 파일) 에 분산. **별도 `calculation-engine` 모듈 분리 대신 `module-core` 흡수** 가 더 자연스러움. ADR-039와 정합.

### 모듈 그래프 (목표)

```
module-common
  ├── module-executor            (LogicExecutor, TaskContext, policies)
  ├── module-pgmq                (PGMQ client, queue/event/messaging 흡수)
  ├── module-aop                 (retry, lock, singleflight)
  ├── module-security            (SecurityFilterChain 만)
  ├── module-monitoring          (Prometheus, alert)
  ├── module-cache               (L1+L2, SingleFlightLoader)
  ├── module-persistence         (JPA + QueryDSL adapter)
  ├── module-external-client     (Nexon WebClient)
  ├── module-core                (도메인 + port + application service)
  │      ↑
  ├── module-infra               (호환 facade: re-export만, 점진적 축소)
  ├── module-web                 (Controller, DTO)
  ├── module-rest-controller, module-calculator, module-synchronizer,
      module-external-api, module-auth, module-app
```

### module-executor 추출 상세 (1순위)

**포함 파일 (34개)**

```
infrastructure/executor/TaskContext.kt
infrastructure/executor/LogicExecutor.kt (+ DefaultLogicExecutor, BasicExecutor, SafeExecutor, ResilientExecutor, DefaultCheckedLogicExecutor, CheckedLogicExecutor)
infrastructure/executor/StepTimer.kt
infrastructure/executor/policy/{ExecutionPolicy, ExecutionPipeline, ExecutionOutcome, FailureMode, FinallyPolicy, LoggingPolicy, PolicyOrder, TaskLogSupport, TaskLogTags}.kt
infrastructure/executor/function/{CheckedRunnable, CheckedSupplier, ThrowingRunnable}.kt
infrastructure/executor/strategy/ExceptionTranslator.kt
infrastructure/executor/classifier/{ExceptionClassifier, DefaultExceptionClassifier, CircuitBreakerClassification}.kt
```

**의존성**: module-common, kotlin-stdlib, jackson (TaskContext payload용), spring-core, spring-context (AOP), kotlin-coroutines, reactor-core, micrometer-core (메트릭 tagging용 — 옵션).

**공개 API**

```kotlin
interface LogicExecutor {
    fun <T> execute(task: () -> T, ctx: TaskContext): T
    fun executeVoid(task: () -> Unit, ctx: TaskContext)
    fun <T> executeOrDefault(task: () -> T, default: T, ctx: TaskContext): T
    // ... 7개 시그니처 (code-rules.md §1)
}

data class TaskContext(
    val taskName: String,
    val tags: Map<String, String> = emptyMap(),
    val step: StepTimer = StepTimer.disabled(),
)
```

**port/interface 분리**

```kotlin
// module-core/.../core/port/outbound/ExecutorPort.kt
interface ExecutorPort {
    fun <T> execute(task: () -> T, ctx: TaskContext): T
    // ...
}
```

`LogicExecutor` 직접 의존 → `ExecutorPort` 의존으로 다운스트림 전환. `module-infra`에 adapter (`ExecutorPortAdapter : ExecutorPort` delegating to `LogicExecutor`) 제공. 전환 후 `module-infra` 의존 제거.

**마이그레이션 단계**

1. `module-executor` 신규 생성, `LogicExecutor` 등 34 파일 이동 + package 변경 (`maple.expectation.executor.*`)
2. `module-infra/build.gradle` 에 `implementation project(':module-executor')` 추가 (re-export)
3. 다운스트림 6 모듈은 `module-infra` 경유로 무변경 사용 가능
4. `module-executor` 안정화 확인 후 다운스트림을 `module-executor` 직접 의존으로 전환
5. `module-infra` 에서 executor 패키지 삭제

**검증**

- `module-executor:test` 단독 실행
- `module-calculator`, `module-external-api` bootRun 회귀 없음 확인 (workflow-rules.md §10)
- `verifyNoSpringDependency` task 통과

### 의존성 그래프 (현재 vs 목표)

**현재** (acyclic 깨짐 직전):

```
core ← infra ← {external-api, calculator, synchronizer, rest-controller, app}
            ↑
            └── web ← rest-controller
            └── auth ← rest-controller
```

**목표**:

```
core ← executor ← {pgmq, aop, security, monitoring, cache, persistence, external-client, infra}
                                          ↑
                                          └── infra (facade) ← {web, ...}
```

---

## 3. Trade-offs

### Sensitivity

* **`LogicExecutor` 호출 빈도**: 모든 hot path (Item calc, Character calc, equipment processing)에서 사용. 변경 시 회귀 폭발. (이슈 본문: Ca=149)
* **`TaskContext` 의 step timer**: 마이크로초 단위 호출. 직렬화 비용 변동 시 throughput 영향.
* **`module-infra` 컴파일 시간**: 48K 줄 단일 컴파일 ≈ 90초. 8분할 시 합계 60-70초로 추정 (Gradle 병렬 빌드 효과).
* **AOP 의존성**: `kotlin-spring-allopen` + `@Transactional` + `@Async` 가 executor + aop 양쪽에 걸림. 분리 시 어노테이션 처리 순서 회귀 위험.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| `module-executor` 1순위 추출 | 다른 7개 모듈의 차단 해소, 가장 작은 fan-in (LogicExecutor는 다운스트림 6곳에서 사용) | 1단계 ROI 낮음 (즉시 다운스트림 분해는 2단계에서야 효과) |
| 8개 분리 vs 점진적 2-3개만 | 명확한 책임 경계, 모듈별 독립 빌드 | 8개 마이그레이션 PR 부담, 설정 중복 위험 |
| `module-infra` facade 잔류 | 다운스트림 무중단 전환, 점진적 검증 | 단일 facade 클래스가 God Class화 위험 (모니터링 필요) |
| `calculation-engine` 별도 모듈 안 함 | application/service 4 파일만 이동, PR 1건 | 8개 모듈로 못 나눔 (5개 시나리오 한 곳에) |

### Risk

* **Facade God Class화**: `module-infra`에 re-export 위임 클래스가 누적되면 새 God Module 출현
* **Config 분할 시 wiring 누락**: 51 `@Configuration` 중 1개라도 잘못 import하면 boot 실패 — 1단계 executor에서 wiring 0개라 risk 낮음
* **Test fixture 의존**: 통합 테스트가 `module-infra` 의 mock 객체 직접 참조 시 → port interface 추출 시 함께 분리 필요
* **package 경로 변경 비용**: 34 파일 × 호출지 N (N≫100) 일괄 import 변경 필요 — IDE refactor로 안전 처리
* **Virtual thread / coroutine 혼재**: executor는 `Dispatchers.Default` + `ReentrantLock` 가정. 분리 시 의존성 누락 시 carrier pinning 회귀

### Non-Risk

* **Acyclic 의존성 깨짐**: 현재 1-stage 추출로 해결 가능
* **빌드 파이프라인 영향**: Gradle multi-module 패턴 이미 정착 (9 모듈)
* **테스트 컨벤션 회귀**: `module-infra:test` 단위만 줄어들 뿐 패턴 동일

---

## 4. Result / Evidence

### Metrics (목표)

| Metric | 현재 | 목표 | 측정 시점 |
| ------ | ---: | ---: | ------- |
| `module-infra` 파일 수 | 444 | < 100 (facade만) | 8모듈 분리 완료 후 |
| `module-infra` LOC | 48,620 | < 10,000 | 동일 |
| `module-infra` 직접 의존 모듈 수 | 6 | 1 (module-web) | facade 단계 종료 후 |
| `module-infra:test` 시간 | ~180s | < 60s | 분할 후 |
| `compileKotlin --continue` wall time | baseline | -30% | 4모듈 분리 후 |
| `module-executor:test` 커버리지 | (infra 일부) | > 85% | 1단계 종료 시 |

### Observed Result (1단계 후 예상)

* 6 다운스트림 모듈 무변경 — Gradle `implementation project(':module-infra')` 가 `module-executor` re-export
* `LogicExecutor` 호출 site 100% 동일 API
* bootRun 회귀 0

---

## 5. Summary

> **`module-executor` 1순위 추출 + 8개 서브모듈 점진 분해. `module-infra`는 호환 facade로 잔류 후 축소, fan-in 6→1 목표.**

### 다음 액션 (별도 이슈)

1. `module-executor` 추출 이슈 (PR 1, ~34 파일 이동 + 빌드 설정)
2. `ExecutorPort` 정의 + `module-infra` adapter (port/interface 분리)
3. 7개 후속 모듈별 별도 이슈 (각 PR 1-3건)
4. `module-infra` 축소 트리거: fan-in 0 도달 시 facade 삭제

---

## Post-ADR Updates (2026-06-05)

### 선행 작업 완료

| 작업 | PR/이슈 | ADR-050 영향 |
|------|---------|-------------|
| ExecutorConfig → CoreExecutorConfig + InfraExecutorConfig 분리 | #1119 | `module-executor` 추출의 설정 분리 선제 완료 |
| VT ExecutorManager inline → @Bean injection | #1120 | executor bean 관리 체계 정비 |
| LogicExecutor/TaskContext core 승격 | #904 (closed — 위험 과대) | **선행 불필요로 판단** — #1119 분리로 충분 |
| domain/v2 → infrastructure/persistence 이관 | #896 (#1148) | `module-persistence` 후보 파일 정리 완료 |
| 동시성 어댑터 6종 도입 | #1157 | `module-aop` 후보에 concurrency 패키지 포함 |
| 포트 인터페이스 기술명 제거 | #906 (#1146) | 모듈 분해 시 port 이름이 기술 중립적 |

### 우선순위 재조정

| 후보 | 기존 순위 | 변경 순위 | 이유 |
|------|----------|----------|------|
| `module-executor` | 1 | **2** | #1119 분리로 긴급도 하락. core 승격(#904) close |
| `module-persistence` | 4 | **1** | #896 이관 완료, 파일 정리 상태 가장 좋음 |
| `module-aop` (+concurrency) | 6 | **3** | #1157 어댑터 포함 필요 |
| `module-cache` | 5 | **4** | 변동 없음 |

### `module-executor` 추출 재평가

#904(LogicExecutor core 승격) close로 인해 `module-executor` 독립 모듈 필요성 감소. 대신:
- #1119 CoreExecutorConfig가 이미 `module-core` 인접 설정 담당
- #1157 ConcurrencyConfiguration이 동시성 어댑터 wiring 담당
- **권장**: `module-executor` 추출 보류, `module-persistence`를 1순위로 진행
