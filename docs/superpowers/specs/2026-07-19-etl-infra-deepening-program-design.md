# ETL module-infra Deepening Program

- **Status**: Approved
- **Date**: 2026-07-19
- **Scope**: module-external-api, module-calculator, module-synchronizer, module-cleanup
- **Excluded consumers**: module-app, module-web 전용 module-infra 기능

---

## 1. Problem

네 개의 ETL 실행 모듈은 독립 배포 단위지만 저장소, Kafka 전달 제어, 계산 엔진, Nexon HTTP 접근을 `module-infra`에서 직접 가져온다. 그 결과 module-infra의 넓은 runtime classpath와 Spring 설정이 모든 워커로 전파되고, artifact key·ACK 시점·재시도·외부 API 오류 의미가 workload 코드에 흩어진다.

이번 프로그램은 파일을 단순 이동하지 않는다. 변경 이유가 같은 코드가 함께 바뀌도록 네 개의 응집된 경계를 만들고, 활성 ETL 모듈의 `module-infra` 직접 의존을 제거한다.

## 2. Decision

세 가지 접근을 비교했다.

| 접근 | 장점 | 거절 또는 선택 이유 |
| --- | --- | --- |
| Gradle/package 분리 우선 | 빠르게 모듈 수를 늘릴 수 있음 | 얕은 경계를 그대로 고정하므로 거절 |
| 범용 ETL runtime 하나 | 공통 실행 표면을 한곳에 모음 | 서로 다른 실패·일관성 모델을 거대한 프레임워크로 결합하므로 거절 |
| **계약 우선 수직 심화** | 저장, 전달, 계산, 외부 접근의 변경 축을 독립화 | **선택** |

프로그램은 다음 네 하위 프로젝트로 분리한다.

1. P0: [Pipeline Artifact Identity and Lifecycle](2026-07-19-pipeline-artifact-lifecycle-design.md)
2. P0: [Kafka Delivery Outcome](2026-07-19-kafka-delivery-outcome-design.md)
3. P1: [Valuation Calculation Kernel Extraction](2026-07-19-valuation-kernel-extraction-design.md)
4. P1: [Nexon Access Consolidation](2026-07-19-nexon-access-consolidation-design.md)

각 하위 프로젝트는 별도 구현 계획, 테스트 가능한 완료 조건, 독립 rollback 지점을 가진다.

## 3. Goals

- 활성 ETL 네 모듈의 `module-infra` 직접 Gradle 의존을 0으로 만든다.
- artifact identity와 run lifecycle을 한 경계에서 소유한다.
- workload handler에서 Kafka ACK·retry·DLT 기계적 제어를 제거한다.
- 계산 핵심을 Spring, 캐시, CSV, 직렬화로부터 분리한다.
- Nexon transport 정책과 오류 분류를 한 모듈에서 정의하되 system-key와 BYOK 자원 풀은 격리한다.
- 기존 LocalFS/MinIO, Kafka event JSON, topic 이름, artifact object key를 호환한다.
- module-app/module-web가 요구하는 기존 API는 `module-infra` compatibility facade로 유지한다.

## 4. Non-goals

- module-app/module-web만 사용하는 JPA persistence, PGMQ, cache, AOP, security, monitoring의 이동
- Kafka topic 재설계, event schema 버전 상승, exactly-once 도입
- object key rename 또는 기존 artifact 대량 migration
- 계산 공식 변경, Nexon 요청 의미 변경, retention 정책 값 변경
- 모든 비동기 실행을 하나의 generic pipeline abstraction으로 통합

## 5. Target Architecture

```text
module-common                         module-core
  ├─ event/storage contracts           └─ pure valuation kernel
  └─ shared value types

module-pipeline-artifact
  ├─ artifact identity/layout
  ├─ writer and run lifecycle
  ├─ retention and durable cleanup inbox
  └─ ObjectStorage adapters: LocalFS, MinIO

module-pipeline-messaging
  ├─ DeliveryOutcome
  ├─ retry/backpressure/DLT policy
  └─ Spring Kafka delivery adapter

module-nexon-client
  ├─ common transport policy
  ├─ isolated system-key client/pool
  ├─ isolated BYOK client/pool
  └─ failure classifier and metrics

module-external-api ── nexon-client → pipeline-artifact → pipeline-messaging
module-calculator   ── pipeline-messaging → module-core → pipeline-artifact
module-synchronizer ─ pipeline-messaging → DB → pipeline-messaging
module-cleanup      ── pipeline-messaging → durable inbox → pipeline-artifact

module-infra
  └─ compatibility facade → extracted modules
       ↑
  module-app / module-web
```

최종 Gradle dependency 방향은 다음과 같다.

| Consumer | Direct project dependencies relevant to this program |
| --- | --- |
| module-external-api | module-common, module-core, module-pipeline-artifact, module-pipeline-messaging, module-nexon-client |
| module-calculator | module-common, module-core, module-pipeline-artifact, module-pipeline-messaging |
| module-synchronizer | module-common, module-core, module-pipeline-artifact, module-pipeline-messaging |
| module-cleanup | module-common, module-pipeline-artifact, module-pipeline-messaging |
| module-infra | module-common, module-core, extracted modules |

`module-pipeline-artifact`, `module-pipeline-messaging`, `module-nexon-client`는 서로 순환 의존하지 않는다. cleanup의 durable inbox 저장은 messaging handler가 artifact 포트를 호출하는 workload 조합이며 messaging 모듈이 artifact 모듈에 의존하지 않는다.

## 6. Global Invariants

1. 첫 migration 동안 기존 Kafka topic, consumer group, event JSON, source/result object key 문자열은 바꾸지 않는다.
2. 명시적 terminal drop을 제외한 처리는 at-least-once이다.
3. durable side effect와 필수 outbound publish가 완료되기 전에는 offset을 commit하지 않는다.
4. replay되는 작업의 side effect는 deterministic key, DB upsert/lease, durable inbox identity로 멱등이어야 한다.
5. LocalFS와 MinIO는 같은 `ObjectStorage` contract suite를 통과해야 한다.
6. 새 모듈은 workload 정책을 가져가지 않는다. 공통 mechanism과 명시적 contract만 소유한다.
7. 기존 caller를 먼저 characterization test로 고정하고, 최소 contract를 추출한 뒤 caller를 한 개씩 전환한다.
8. 각 전환은 별도 commit/PR로 되돌릴 수 있어야 한다.
9. module-infra facade는 app/web 호환을 위한 임시 경계이며 새 ETL 코드는 facade를 참조할 수 없다.
10. 기존 공개 wire contract를 바꿔야 하는 발견은 이 프로그램에 흡수하지 않고 별도 ADR 승인을 요구한다.

## 7. Delivery Order

| Order | Deliverable | Exit condition |
| ---: | --- | --- |
| 1 | Characterization baseline | key, ACK, calculation, Nexon error behavior가 테스트와 지표로 기록됨 |
| 2 | Artifact identity/backend contract | key 생성과 LocalFS/MinIO contract가 새 모듈에서 통과 |
| 3 | Calculator delivery outcome | calculator listener가 outcome만 반환하고 delivery adapter가 ACK/retry/DLT 소유 |
| 4 | Synchronizer delivery outcome | DB lease/nextRetryAt는 유지되고 Kafka 제어가 adapter로 이동 |
| 5 | External API delivery outcome | auth/urgent/OCID 비동기 완료와 backpressure가 outcome으로 표현 |
| 6 | Cleanup durable inbox | Kafka offset 전에 inbox object가 durable하게 기록됨 |
| 7 | Valuation kernel | golden master를 유지한 pure kernel과 adapter가 동작 |
| 8 | Nexon consolidation | transport/error policy가 단일 모듈에서 동작하고 두 pool은 격리 |
| 9 | Gradle extraction completion | 활성 네 모듈의 module-infra 직접 의존 0 |
| 10 | Dependency guard | 금지 dependency/import가 architecture test에서 차단 |

P0 artifact가 cleanup inbox identity/storage를 제공하므로 cleanup의 Kafka 전환보다 먼저 완료한다. P1 둘은 P0 안정화 후 서로 독립적으로 진행할 수 있다. 물리적 Gradle 이동은 각 seam이 테스트로 고정된 뒤 수행해 package move와 behavior change가 한 commit에 섞이지 않게 한다.

## 8. Compatibility and Rollback

- 새 구현은 기존 bean 이름과 public type을 module-infra facade에서 위임해 app/web wiring을 보존한다.
- workload별 전환 동안 기존 adapter와 새 adapter를 동시에 등록하지 않는다. feature flag 대신 한 consumer씩 wiring을 교체하고 commit revert를 rollback 수단으로 쓴다.
- object key와 event payload는 byte-for-byte 또는 semantic-equality characterization으로 보호한다.
- calculator golden master가 다르면 새 kernel을 활성화하지 않는다.
- Kafka lag, DLT, duplicate count가 기준을 넘으면 해당 consumer 전환만 revert할 수 있어야 한다.
- storage backend 전환은 요구하지 않는다. 두 backend 모두 동일한 contract를 제공한다.

## 9. Verification and Metrics

구현 전후 같은 환경과 workload에서 다음을 기록한다.

| Measure | Target |
| --- | --- |
| 활성 ETL의 direct `project(':module-infra')` | 0 |
| workload 코드의 direct `Acknowledgment`/`acknowledge()` | 0 |
| artifact 모듈 밖의 production raw `runs/`, `calculator/runs/`, `_RUNNING`, `_SUCCESS` 조합 | 0 |
| LocalFS/MinIO contract failures | 0 |
| calculation golden-master regressions | 0 |
| Nexon transport builder/error-classifier duplicate implementations | 0 |
| Kafka lag/DLT | baseline 대비 유의한 증가 없음 |
| artifact write/calculation throughput | baseline 대비 회귀 없음 |

성능 작업 규칙에 따라 module별 `runtimeClasspath`, bootJar 크기, compile invalidation 범위, startup time, artifact throughput, calculation throughput의 before/after 값을 보존한다.

## 10. ADR Alignment

- ADR-050, ADR-352: pure calculation subset을 core로 이동하고 module-infra를 분해한다.
- ADR-350, ADR-351: cube 전체 wholesale migration은 보류하고 pure subset만 이동한다.
- ADR-353: dependency 방향을 core/common 및 좁은 adapter 모듈 쪽으로 제한한다.
- ADR-390: retention 안전장치와 active-run 보호를 유지한다.
- ADR-391: storage, messaging, external HTTP를 outbound seam으로 명시한다.
- ADR-719, ADR-725: ObjectStorage와 LocalFS/MinIO 호환을 유지한다.
- ADR-722: 새 package root는 Gradle module 책임과 일치시킨다.
- ADR-727: stale run은 오류 재시도가 아니라 관측 가능한 terminal drop이다.

## 11. Program Acceptance

프로그램은 다음 조건을 모두 만족할 때 완료된다.

- 네 하위 spec의 acceptance criteria가 모두 통과한다.
- 활성 ETL 네 모듈에서 module-infra Gradle/import 의존이 사라진다.
- app/web compatibility test가 기존 facade로 통과한다.
- dependency graph와 architecture tests가 역방향·순환 의존을 막는다.
- Kafka, storage, calculation, Nexon before/after 지표가 보존되고 허용되지 않은 회귀가 없다.
- topic, event JSON, 기존 object key가 변경되지 않았음을 contract tests가 증명한다.
