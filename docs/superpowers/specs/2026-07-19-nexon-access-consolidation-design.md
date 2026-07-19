# Nexon Access Consolidation

- **Status**: Approved
- **Priority**: P1
- **Date**: 2026-07-19
- **Program**: [ETL module-infra Deepening Program](2026-07-19-etl-infra-deepening-program-design.md)
- **Integrates with**: [Kafka Delivery Outcome](2026-07-19-kafka-delivery-outcome-design.md)
- **Review**: grill-me findings incorporated

---

## 1. Scope

`module-nexon-client`가 Nexon Open API의 transport construction, URI encoding, timeout/body limit, credential header handling, failure classification, redaction, low-cardinality metrics를 소유한다. system-key bulk snapshot과 user-provided BYOK authentication은 policy를 공유하지만 별도 connection pool과 client instance를 사용한다.

`NexonExternalApiClientAdapter`, `MaplestoryApiConfig`, `RealNexonAuthClient`의 중복 transport/error rules가 대상이다. app/web 전용 Nexon persistence, PGMQ workers, caching, fan-out orchestration은 이동하지 않는다.

## 2. Non-goals

- Nexon endpoint/path, query parameter, response DTO 변경
- system key와 BYOK pool 공유
- rate limit 수치 tuning
- app/web PGMQ, cache, resilience orchestration 재설계
- HTTP client library 교체
- raw response를 공통 domain model 하나로 통합

## 3. Problem

external-api의 bulk adapter는 bounded Reactor Netty pool, 2 MiB body cap, VALUES_ONLY encoding을 직접 구성한다. module-infra의 `MaplestoryApiConfig`는 별도 unpooled client를 만들고 BYOK `RealNexonAuthClient`는 모든 4xx를 empty로 만들며 상위 `LogicExecutor`가 transient error까지 `Optional.empty`로 축소할 수 있다.

그 결과 timeout/429/5xx가 invalid API key와 구별되지 않고, auth response consumer가 error response publish 완료 전에 ACK할 수 있다. builder, status handling, response logging 정책도 drift한다.

## 4. Decision

### 4.1 Module boundary

새 Gradle module은 `module-nexon-client`, package root는 `maple.nexon.client`다.

```text
module-nexon-client
  ├─ config     NexonClientProperties, NexonClientProfile
  ├─ transport  NexonTransportFactory, NexonTransport
  ├─ failure    NexonFailure, NexonFailureClassifier
  ├─ system     SystemKeyNexonClient
  ├─ byok       ByokNexonClient, NexonCharacterList
  └─ metrics    NexonClientMetrics

module-external-api
  ├─ ExternalApiClientPort adapter → SystemKeyNexonClient
  └─ AuthCharacterFetch handler   → ByokNexonClient

module-infra
  └─ legacy NexonApiClient/NexonAuthClient adapters
       delegate to module-nexon-client for app/web callers
```

새 module은 module-external-api의 outbound port나 workload domain을 import하지 않는다. external-api가 thin adapter로 endpoint/key types를 transport request로 변환한다. BYOK decode 결과는 새 module의 neutral `NexonCharacterList`이며 external-api는 이를 auth response event로, module-infra facade는 기존 `CharacterListResponse`로 mapping한다. 새 module이 legacy infra DTO를 import하지 않는다.

### 4.2 Shared transport policy

`NexonTransportFactory`는 profile을 받아 독립 `ConnectionProvider`, `HttpClient`, `WebClient`를 만든다. 공통 규칙은 다음과 같다.

- base URL: `https://open.api.nexon.com`
- `DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY`
- `Accept: application/json`
- Reactor Netty connect/response timeout
- per-call timeout ceiling
- bounded pending acquire
- response body cap
- compression setting
- URI metric normalization
- `x-nxopen-api-key` injection at request boundary
- response/error redaction and failure classifier

base URL, builder, timeout application, body-limit application, error classification은 이 module에 한 구현만 존재한다.

### 4.3 Isolated profiles

| Profile | Purpose | Pool |
| --- | --- | --- |
| `SYSTEM_BULK` | scheduler/urgent raw snapshot using service key | current `nexon.http-client` defaults: max 250, pending 1,000, acquire 5s |
| `USER_BYOK` | character-list authentication using per-message key | separate bounded pool, max 32, pending 128, acquire 2s |

두 profile은 connect 3s, response 5s의 current default를 유지한다. system profile은 current 10s call ceiling과 2 MiB body cap을 유지한다. BYOK profile은 10s call ceiling과 256 KiB body cap을 사용한다. 모든 값은 profile-specific configuration으로 override 가능하고 boot validation에서 양수/bounds를 확인한다.

system configuration은 기존 `nexon.http-client.*`와 `NEXON_HTTP_*` 환경 변수 이름 및 기존 `*-timeout-ms`/`response-timeout-seconds` suffix를 그대로 binding한다. BYOK는 `nexon.byok-http-client.*`를 사용하고 nullable legacy property bridge가 기존 `nexon.api.connect-timeout`/`response-timeout`을 mapping한다. legacy와 새 timeout key가 함께 명시되면 새 profile-specific key가 우선한다. 두 pool name이 같으면 boot를 실패시킨다.

pool name과 metrics tag에 profile만 사용한다. system bulk saturation이 BYOK acquire queue나 connections를 소비할 수 없다. module auto-configuration이 두 `ConnectionProvider`를 소유하고 shutdown 때 pending acquisition을 중단한 뒤 bounded timeout으로 dispose한다.

### 4.4 Client contracts

`SystemKeyNexonClient`는 endpoint path/query와 system credential을 받아 raw bytes를 비동기로 반환한다. 현재 `ExternalApiClientPort.fetch` adapter는 반환 `CompletionStage`를 그대로 전달한다.

`ByokNexonClient.getCharacterList(apiKey)`는 character list를 비동기로 반환한다. `Optional.empty`나 boolean `validateApiKey`를 제공하지 않는다. 성공적으로 받은 빈 account/character list는 valid empty response이며 invalid credential과 구별한다.

BYOK value는 request header를 만드는 lexical scope를 벗어나 field/cache/context에 저장하지 않는다. future completion 후 client object graph가 key를 참조하지 않아야 한다.

module-infra의 기존 `NexonAuthClient` FQN이 app/web compatibility에 필요하면 adapter가 새 client를 호출해 기존 return type으로 변환한다. unused `validateApiKey` method는 제거하고 caller compile test로 무사용을 증명한다.

### 4.5 Failure taxonomy

`NexonFailureClassifier`는 endpoint purpose, status, known Nexon error code, transport cause를 다음 sealed failure로 변환한다.

| Failure | Examples | Delivery meaning |
| --- | --- | --- |
| `InvalidCredential` | 401/403 또는 검증된 credential error code | BYOK terminal user error |
| `NotFound` | current fixture의 `OPENAPI00004 Data not found` 등 | workload-specific terminal absence; credential failure 아님 |
| `InvalidRequest` | 다른 non-credential 4xx | programming/data error; no transient masking |
| `RateLimited` | 429 | retryable, retry-after 존중 |
| `Timeout` | connect/response/call timeout | retryable |
| `UpstreamUnavailable` | 5xx/network unavailable | retryable |
| `ResponseTooLarge` | configured body cap 초과 | explicit failure; system alert |
| `DecodeFailure` | successful HTTP body가 expected DTO를 만족하지 않음 | explicit failure; retry/DLT policy에 전달 |

error code는 endpoint-aware fixture로 분류한다. 같은 HTTP status만으로 credential/not-found 의미를 추측하지 않는다. 분류되지 않은 4xx를 invalid credential로 취급하지 않는다. raw `WebClientResponseException`과 URI/body를 포함할 수 있는 transport/decode cause는 typed failure의 cause chain에도 보존하지 않고, typed failure에는 status, sanitized Nexon code, timeout kind, endpoint purpose/template만 포함한다.

### 4.6 Security and redaction

- BYOK와 system API key는 log, metric, exception message, tracing attribute, Kafka/DLT header에 절대 기록하지 않는다.
- response body 전체를 log하지 않는다. status, sanitized Nexon error code, bounded reason만 기록한다.
- character name, OCID, raw URI query는 metric tag로 쓰지 않는다.
- URI metric mapper는 endpoint template만 반환하고 raw request URI를 사용하지 않는다.
- request/response diagnostic body capture는 지원하지 않는다.

### 4.7 Delivery integration

`AuthCharacterFetchConsumer`의 workload handler는 다음 결과를 반환한다.

- character-list success: response event send completion 후 `Success`
- `InvalidCredential`: 기존 실패 response를 publish 완료한 뒤 `Success`
- character-list `NotFound`: credential error가 아닌 no-accessible-character 실패 response를 publish 완료한 뒤 `Success`
- `RateLimited`, `Timeout`, `UpstreamUnavailable`: `Retryable`
- response serialization/send failure: `Retryable`
- invalid source request: `InvalidMessage`

`OcidLookupPhase`와 bulk snapshot은 typed future failure를 잃지 않고 상위 delivery/scheduler boundary로 전달한다. 현재 사용되지 않는 `OcidLookupPhase.nexonAuthClient` injection과 future-use comment를 제거한다.

## 5. Migration

1. 두 기존 client의 URI, header, timeout, body cap, status behavior를 characterization tests로 기록한다.
2. typed failure classifier를 추가하고 current known Nexon codes를 fixture로 고정한다.
3. transport factory와 두 isolated profile/pool을 만든다.
4. system-key adapter를 thin wrapper로 바꾸고 bulk/urgent regression을 확인한다.
5. BYOK client를 전환하고 auth handler를 Kafka delivery outcome과 연결한다.
6. module-infra app/web adapters를 새 client delegate로 바꾼다.
7. unused `validateApiKey`와 `OcidLookupPhase` injection을 제거한다.
8. duplicated base URL/WebClient builder/status classifier를 제거하고 dependency guard를 추가한다.

system과 BYOK는 한 단계에서 동시에 전환하지 않는다. 각 client 전환은 독립 revert가 가능하다.

## 6. Tests

- VALUES_ONLY encoding과 endpoint/query golden tests, 특히 한글 character name
- system/BYOK credential header가 정확히 한 번 설정되고 log/exception에 노출되지 않음
- BYOK completion 후 client field/cache/context에 key reference가 남지 않음
- status + Nexon code failure taxonomy
- `OPENAPI00004` not-found가 invalid credential로 오분류되지 않음
- unknown 4xx가 invalid credential로 축소되지 않음
- connect, response, call timeout mapping
- 429 retry-after propagation
- system 2 MiB와 BYOK 256 KiB response cap
- pool name/capacity 격리와 한 profile saturation이 다른 profile acquisition을 막지 않음
- legacy property/env binding, distinct pool-name validation, provider shutdown
- URI metrics가 raw identifier를 tag로 만들지 않음
- auth invalid credential response publish completion 전 no-ACK
- auth timeout/429/5xx가 Kafka retry outcome으로 전파
- bulk raw bytes and endpoint mapping equivalence
- module-infra legacy caller compatibility

HTTP tests는 local stub server와 deterministic virtual/control clock을 사용한다. secret redaction은 captured log와 exception string 전체를 검사한다.

## 7. Observability

- request count/duration/body bytes/failure by profile, endpoint template, normalized failure
- pool active/idle/pending/acquire timeout by profile
- 429 and retry-after
- response-too-large/decode failures
- auth terminal invalid credential와 transient retry count

metric tag 집합은 profile, bounded endpoint name, status class, normalized failure로 제한한다.

## 8. Acceptance Criteria

- Nexon base URL, WebClient/HttpClient construction, timeout/body limit, error classification이 module-nexon-client에 한 번만 존재한다.
- system-key와 BYOK가 서로 다른 `ConnectionProvider`와 bounded queue를 사용한다.
- 기존 system property/env 이름은 유지되고 두 provider가 deterministic하게 dispose된다.
- timeout, 429, 5xx가 invalid credential/empty response로 축소되지 않는다.
- neutral character-list model과 legacy DTO mapping이 module 경계를 역전시키지 않는다.
- auth response Kafka send completion 전 ACK가 없다.
- API key와 raw response body가 log, metric, trace, exception, DLT에 노출되지 않는다.
- external-api가 `MaplestoryApiConfig`, `RealNexonAuthClient`, module-infra Nexon types를 import하지 않는다.
- unused `validateApiKey`와 `OcidLookupPhase` future-use injection이 제거된다.
- existing endpoint, query, response DTO, system-key behavior가 contract tests에서 호환된다.
- bulk/auth throughput과 pool saturation before/after evidence가 보존된다.

## 9. ADR Alignment

- ADR-050의 external client extraction 방향을 따른다.
- ADR-353의 dependency 방향을 지킨다.
- ADR-391의 external HTTP seam을 독립 adapter로 만든다.
- ADR-722에 따라 module/package naming을 일치시킨다.
