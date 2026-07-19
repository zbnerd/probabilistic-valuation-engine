# Kafka Delivery Outcome

- **Status**: Approved
- **Priority**: P0
- **Date**: 2026-07-19
- **Program**: [ETL module-infra Deepening Program](2026-07-19-etl-infra-deepening-program-design.md)
- **Depends on**: [Pipeline Artifact Identity and Lifecycle](2026-07-19-pipeline-artifact-lifecycle-design.md) for cleanup durable inbox

---

## 1. Scope

`module-pipeline-messaging`가 Spring Kafka container 설정, record metadata, ACK, retry, backpressure, retry exhaustion, DLT publish를 소유한다. workload handler는 durable work 결과를 `DeliveryOutcome`으로 표현한다.

적용 대상은 calculator snapshot consumer, synchronizer basic/result/OCID consumers, external-api auth/urgent consumers, cleanup consumed-chunk inbox consumer다.

## 2. Non-goals

- Kafka topic, consumer group, partition 수, event JSON 변경
- Kafka exactly-once 또는 storage/DB와 Kafka의 distributed transaction 도입
- synchronizer DB lease/`nextRetryAt` state machine 제거
- workload concurrency pool을 하나로 합침
- module-app/web의 PGMQ 또는 Kafka 경로 이동
- 모든 event를 하나의 generic envelope로 재작성

## 3. Problem

현재 listener가 parsing, workload 실행, retry sleep, semaphore 판단, async publish, ACK를 동시에 수행한다. module별로 성공의 의미가 다르고, 일부 경로는 async send가 끝나기 전에 ACK하거나 capacity 부족을 ACK/drop으로 처리할 가능성이 있다. calculator 내부 retry와 Spring Kafka retry가 겹치며 cleanup inbox는 ACK 후 메모리에만 남는다.

## 4. Decision

### 4.1 Module boundary

새 Gradle module은 `module-pipeline-messaging`, package root는 `maple.pipeline.messaging`다.

```text
module-pipeline-messaging
  ├─ contract   DeliveryContext, DeliveryOutcome
  ├─ policy     DeliveryRetryPolicy, DeliveryFailureClassifier
  ├─ adapter    KafkaDeliveryAdapter, KafkaDltPublisher
  ├─ config     PipelineKafkaConsumerConfiguration
  └─ metrics    DeliveryMetrics

workload module
  ├─ decoder
  ├─ handler / state machine
  └─ outbound ports
```

기존 `KafkaConsumerConfig`는 container-only configuration으로 축소해 새 모듈로 이동한다. workload bean, serializer, business retry 정책은 각 실행 모듈에 남는다.

### 4.2 Delivery contract

`DeliveryContext`는 topic, partition, offset, timestamp, key, delivery attempt를 제공한다. payload는 workload별 decoder가 type-safe event로 변환한다.

`DeliveryOutcome`은 다음 닫힌 결과만 허용한다.

| Outcome | Meaning | Adapter action |
| --- | --- | --- |
| `Success` | durable side effect와 필수 outbound publish 완료 | ACK/commit |
| `TerminalDrop(reason)` | stale run처럼 재처리할 필요가 없는 의도적 폐기 | reason metric 후 ACK |
| `InvalidMessage(reason)` | payload/header가 contract를 만족하지 않음 | 원본 record를 DLT에 publish한 뒤 ACK |
| `Retryable(cause)` | 일시적 storage/DB/HTTP/Kafka failure | ACK 없이 configured retry |
| `Backpressure(delay)` | bounded resource가 현재 포화 | partition pause 또는 nack, ACK 없음 |

`RetryExhausted`는 handler가 반환하지 않는다. adapter가 retry budget을 소진했을 때 생성하는 terminal delivery state이며 원본 record와 마지막 error를 DLT에 publish한 뒤에만 commit한다. DLT publish 실패 시 commit하지 않는다.

### 4.3 One retry owner

- calculator의 listener/processor 내부 sleep-and-retry를 제거한다.
- Spring Kafka delivery adapter만 record redelivery budget과 backoff를 소유한다.
- synchronizer의 DB lease, attempt, `nextRetryAt`은 business state이므로 유지한다. state machine은 결과를 반환하고 adapter가 Kafka action을 수행한다.
- HTTP client의 connection-level retry가 존재하면 idempotent request와 짧은 transport retry로 한정하고 delivery retry count와 metric을 분리한다.
- current configuration의 retry 횟수/backoff는 첫 migration에서 그대로 옮긴다. tuning은 before/after 지표를 갖는 별도 변경이다.

### 4.4 Completion boundary

ACK 가능한 완료점은 workload별로 고정한다.

| Workload | Success boundary |
| --- | --- |
| calculator | result artifact upload와 result-ready Kafka send completion |
| synchronizer | DB transaction/lease transition과 chunk-consumed Kafka send completion |
| external auth | BYOK Nexon response event Kafka send completion |
| external urgent | source artifact upload와 downstream event send completion |
| OCID lookup | 모든 required async stage와 artifact/event completion |
| cleanup inbox | durable inbox object put completion |

Kafka producer의 send 호출 반환만으로 성공하지 않는다. returned future/stage가 성공해야 한다. timeout, broker failure, cancellation은 `Retryable`이다.

### 4.5 Workload-specific decisions

#### Calculator

- deterministic result object key에 overwrite하므로 replay가 안전하다.
- artifact upload 후 publish 사이 crash는 replay 시 같은 object를 다시 쓰고 duplicate event를 낼 수 있다.
- downstream synchronizer는 DB upsert/lease를 통해 duplicate를 흡수한다.
- parse 불가능 payload는 `InvalidMessage`, 계산/storage/publish 일시 실패는 `Retryable`이다.

#### Synchronizer

- DB write는 기존 transaction, idempotent upsert, lease semantics를 유지한다.
- lease를 다른 worker가 보유하거나 `nextRetryAt`이 미래면 delay를 계산해 `Backpressure` 또는 `Retryable`로 반환한다.
- stale run은 ADR-727에 따라 `TerminalDrop(STALE_RUN)`이며 metric과 structured log를 남긴다.
- DB commit 후 outbound publish 실패 시 replay가 발생한다. DB write와 consumed event 모두 duplicate-safe여야 한다.

#### External API

- `RealNexonAuthClient`의 timeout/429/5xx와 async send failure는 `Retryable`이다.
- invalid BYOK credential만 user-visible terminal response이며 response publish 완료 후 `Success`다.
- `OcidLookupPhase` 내부 future의 exceptional completion은 listener까지 `Retryable`로 전달한다.
- urgent semaphore가 없으면 message를 ACK/drop하지 않고 `Backpressure`를 반환한다.

#### Cleanup

- record identity는 `topic/partition/offset`이다.
- artifact spec의 `cleanup/inbox/{topic}/{partition}/{offset}.json` put이 완료된 후 ACK한다.
- process restart는 storage listing으로 pending inbox를 복구한다.
- in-memory queue overflow/drop counter는 제거하고 durable backlog age/count alert로 교체한다.

### 4.6 DLT contract

- 현재 topic별 DLT naming/recoverer convention을 유지한다.
- 원본 topic, partition, offset, key, timestamp, exception class, normalized reason, delivery attempt를 header에 기록한다.
- 원본 payload를 보존한다. secret-bearing header와 BYOK 값은 복사하지 않는다.
- `InvalidMessage`와 `RetryExhausted`를 구분하는 reason을 기록한다.
- DLT publish completion 전 source offset commit을 금지한다.

### 4.7 Backpressure

`Backpressure`는 오류 log flood를 만들지 않는 정상 제어 상태다.

- 해당 partition만 pause/nack하고 다른 partition은 진행할 수 있게 한다.
- delay는 listener poll interval과 `max.poll.interval.ms`보다 안전하게 작아야 한다.
- adapter가 pause duration, resumes, repeated backpressure를 계측한다.
- semaphore/thread-pool queue capacity는 workload module이 결정하고 adapter는 제시된 delay를 실행한다.

## 5. Failure and Replay Matrix

| Boundary failure | Offset | Replay invariant |
| --- | --- | --- |
| decode invalid | DLT success 후 commit | DLT record에 원본 보존 |
| storage put failure | 미commit | deterministic key |
| storage success, event publish failure | 미commit | overwrite + duplicate downstream event 허용 |
| DB transaction failure | 미commit | transaction rollback |
| DB commit, consumed publish failure | 미commit | idempotent upsert + durable cleanup identity |
| Nexon timeout/429/5xx | 미commit | request/event correlation 유지 |
| invalid BYOK | response publish 후 commit | terminal response 재생성 가능 |
| no urgent capacity | 미commit/pause | side effect 없음 |
| DLT publish failure | 미commit | DLT retry |
| stale run | commit | explicit terminal-drop metric |
| cleanup inbox put failure | 미commit | Kafka coordinate key |

## 6. Migration

1. listener별 ACK 시점, retry 수, DLT, async completion을 characterization test로 고정한다.
2. `DeliveryOutcome`과 adapter를 추가하되 기존 listener에는 연결하지 않는다.
3. calculator 내부 retry를 제거하고 단일 listener를 전환한다.
4. synchronizer consumer를 한 종류씩 전환한다.
5. external auth, urgent, OCID 경로를 순서대로 전환한다.
6. artifact durable inbox가 배포된 뒤 cleanup consumer를 전환한다.
7. 기존 공통 `KafkaConsumerConfig` 복제와 workload direct ACK를 제거한다.
8. architecture test로 workload package의 `Acknowledgment`, `acknowledge()`, `nack()` 사용을 금지한다.

한 번에 하나의 consumer group만 전환하며, 해당 단계 commit revert로 기존 listener semantics로 돌아갈 수 있게 한다.

## 7. Tests

### Component tests

- outcome별 ACK/nack/pause/DLT action
- DLT future 실패 시 no-commit
- async producer future가 끝나기 전 no-ACK
- retry budget exhaustion 후 DLT와 commit 순서
- backpressure 후 resume 및 다른 partition 진행
- calculator의 이중 retry 제거
- synchronizer lease/`nextRetryAt` 결과 mapping
- stale run terminal drop
- external auth invalid credential와 transient failure 구분
- urgent capacity exhaustion
- cleanup durable inbox duplicate/restart/put failure

### Real Kafka validation

repository rule Issue #207에 따라 새 Testcontainers integration test class는 추가하지 않는다. 기존 Docker/local Kafka 검증 흐름에서 실제 offset, redelivery, DLT record, restart recovery를 확인하고 결과를 evidence로 보존한다. 단위/component test는 fake acknowledgment와 controllable producer future로 모든 ordering branch를 결정적으로 검증한다.

## 8. Observability

- delivery outcomes by module/listener/outcome/reason
- retry attempts/exhausted/DLT publish failures
- ACK latency from record receipt to durable completion
- backpressure pause duration/count
- duplicate/replay counters where identity is observable
- consumer lag and DLT rate before/after

topic은 bounded tag로 허용하되 partition, offset, runId, key, exception message는 metric tag로 쓰지 않는다. exception class와 normalized reason만 사용한다.

## 9. Acceptance Criteria

- workload production 코드의 direct `Acknowledgment`, `acknowledge()`, `nack()` 사용이 0이다.
- 모든 필수 async send가 완료되기 전 offset commit이 발생하지 않는다.
- calculator record당 retry owner가 하나다.
- urgent capacity 부족과 cleanup storage 장애가 silent ACK/drop을 만들지 않는다.
- invalid/exhausted record는 DLT publish 성공 후에만 commit된다.
- stale run은 명시적 metric을 가진 terminal drop이다.
- cleanup inbox는 restart 후 pending record를 복구한다.
- 기존 topic, consumer group, event JSON이 바뀌지 않는다.
- 실제 Kafka 검증에서 offset, replay, DLT 순서가 contract와 일치한다.

## 10. ADR Alignment

- ADR-353의 좁은 dependency 방향을 따른다.
- ADR-391의 messaging seam을 독립 adapter로 만든다.
- ADR-727의 stale-run terminal handling을 보존한다.
- backend transaction/idempotency와 Kafka 경계를 명시해 repository backend 규칙을 만족한다.
