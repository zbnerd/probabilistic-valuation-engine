# ADR-906: Port Abstraction Cleanup (Leaky Abstraction Removal)

- Status: Accepted
- Date: 2026-06-05
- Owner: zbnerd
- Issue: #906

> **Note (2026-06-06):** 6 dead Like ports (LikeAtomicFetchStrategy, CompensationCommand, LikeRelationBufferStrategy, LikeRelationSyncPort, LikeSyncPort, LikeEventPublisher) were deleted in PR TBD. See [2026-06-06-like-port-merge-design.md](2026-06-06-like-port-merge-design.md) for the actual deletion rationale.

---

## 1. Background / Problem

### Background

module-core의 포트 인터페이스에 인프라 기술명(PGMQ, Redis, Kafka, MySQL, Spring, Micrometer)이 인터페이스 이름, 메서드 이름, Javadoc에 노출되어 있다. 결과적으로 구현체를 교체할 때 모듈-core의 인터페이스까지 수정해야 한다 — Hexagonal Architecture의 의존성 역전 원칙(DIP) 위반이다.

### Problem

| 포트 인터페이스 | 노출된 기술명 |
|----------------|------|
| `PgmqPort` | 인터페이스 이름에 PGMQ |
| `QueueNames` | PGMQ 큐 이름 상수가 core에 위치 |
| `CacheManagerPort` | `getCache()`, `getMeterRegistry()`가 `Any?` 반환 → Spring/Micrometer 의존 유출 |
| `LikeSyncPort` | `flushLocalToRedis()`, `syncRedisToDatabase()` 메서드명 |
| `LikeRelationSyncPort` | 동일 |
| `LikeRelationBufferStrategy` | Javadoc에 `RedisLikeRelationBuffer` 참조 |
| `PersistenceTrackerStrategy` | Javadoc에 `RedisEquipmentPersistenceTracker` 참조 |
| `FanOutQueuePort` | Javadoc에 `FanOutQueueProducer` 참조 |
| `EventPublisher` | Javadoc에 `PgmqStreamPublisher`, `KafkaEventPublisher` 참조 |
| `MessageTopic` | Javadoc에 "Redis topics, Kafka topics" |

영향 범위: ~50 파일 (module-core 10, module-infra 25, module-app 10, 기타 5).

### Goal

- module-core의 포트 인터페이스에서 모든 인프라 기술명 제거
- 메서드명은 캐시 계층(L1/L2/영구 저장소) 같은 **도메인 중립 용어**로 통일
- 캐시/메트릭 인터페이스의 `Any?` 반환을 generic typed contract로 교체
- Javadoc의 구현체 클래스명 참조 제거
- 빌드/테스트/런타임 검증 통과

---

## 2. Decision

> 우리는 module-core의 포트 인터페이스를 기술 중립적으로 정리하고, 캐시/메트릭 abstraction을 module-common으로 끌어올린다.

```text
module-common
  ├─ DomainCache           (NEW — get<T>, put, invalidate)
  ├─ MetricsRegistry       (NEW — counter, timer)
  └─ existing errors/utils

module-core (port interfaces only)
  ├─ port/out/MessageQueuePort           (RENAMED from PgmqPort)
  ├─ port/out/MessageTopic<T>            (Javadoc: "message topics" 만)
  ├─ port/out/EventPublisher             (Javadoc impl refs 제거)
  ├─ port/out/FanOutQueuePort            (Javadoc impl refs 제거)
  ├─ port/out/LikeSyncPort               (메서드명 L1→L2, L2→영구)
  ├─ port/out/LikeRelationSyncPort       (동일)
  ├─ port/out/LikeRelationBufferStrategy (Javadoc impl refs 제거)
  ├─ port/out/PersistenceTrackerStrategy (Javadoc impl refs 제거)
  └─ port/inbound/CacheManagerPort       (DomainCache + MetricsRegistry 사용)

module-infra
  ├─ adapter/outgoing/MessageQueuePortAdapter  (RENAMED)
  ├─ infrastructure/queue/QueueNames           (MOVED from core)
  ├─ infrastructure/cache/DomainCacheAdapter   (NEW — Spring Cache 래핑)
  └─ infrastructure/metrics/MetricsRegistryAdapter (NEW — Micrometer 래핑)
```

핵심 결정:
1. **`PgmqPort` → `MessageQueuePort`**: 가장 일반적. PGMQ/Kafka/RabbitMQ/SQS 모두 같은 contract.
2. **`QueueNames` → module-infra로 이관**: core는 큐 이름 문자열을 모름. 어댑터가 PGMQ 큐 이름을 알고 send 호출.
3. **`Any?` → generic typed contract**: `DomainCache<T>` 와 `MetricsRegistry` 인터페이스를 module-common에 정의. 어댑터 내부에서 Spring `Cache` / Micrometer `MeterRegistry`로 cast.
4. **메서드명 L-계층화**: `flushLocalToL2()`, `syncL2ToPersistence()`. 저장소 종류는 어댑터 책임.
5. **Javadoc 정리**: 구현체 클래스명 참조 일체 제거. `@see` 태그에 어댑터 클래스명 금지.

---

## 3. Trade-offs

### Sensitivity

- 영향 파일 수: ~50 (module-core 10, module-infra 25, module-app 10, 기타 5)
- 빌드 검증: `compileKotlin compileJava --continue` + `test`
- 런타임 검증: `module-app:bootRun` 후 V5 API 호출 → `Calculation completed with result saved` 로그 확인
- V6(V6 Redis like feature) 의존성: module-rest-controller가 MessageQueuePort/LikeSyncPort 사용 시 컴파일 영향

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 모든 변경을 단일 PR로 묶음 | 일관성, ADR-906 수용 기준 동시 충족, V6 기초 작업 선행 | PR 리뷰 부하 증가, 롤백 단위가 큼 |
| `MessageQueuePort` (generic) 사용 | 미래 PGMQ 외 기술 도입 시 인터페이스 변경 불요 | 도메인 힌트 손실 (`CalculationQueuePort` 대비) |
| `DomainCache`/`MetricsRegistry`를 module-common에 정의 | core의 `Any?` 의존성 영구 제거, 다른 모듈도 재사용 가능 | 새 인터페이스 테스트 4건 추가, 어댑터 2개 신규 |
| `QueueNames` 모듈 이동 | core가 PGMQ 큐 이름 문자열을 모름 (완전한 추상화) | 호출처가 `QueueNames` 심볼을 import하는 경로 전부 갱신 필요 |

### Risk

- `DomainCache.get<T>` 호출 시 caller가 정확한 `Class<T>` 전달 안 하면 ClassCastException 가능. Javadoc + 테스트로 방어.
- `MessageQueuePort` 이름이 너무 일반적이어서 향후 메시지 큐가 아닌 곳에서 잘못 주입될 위험. 패키지(`port.out`)와 Javadoc으로 차단.
- 캐시 어댑터에서 Spring `Cache` 캐스팅 실패 시 NPE 가능. adapter 내부 `requireNotNull` 로 fail-fast.
- 50+ 파일 diff는 리뷰 비용 증가. PR 분할하지 않음 — 어댑터 신규는 코어 rename과 동시에 같은 PR에서 진행 (빌드 의존성 때문에 분할 불가). 리뷰어는 ① 코어 인터페이스 변경, ② 어댑터 신규, ③ 호출처 임포트 갱신 세 영역을 순차 확인.

### Non-Risk

- 동작 변화 없음. 메서드 시그니처(L-계층 이름)와 반환 타입 generic만 변경.
- 기존 테스트가 깨지지 않도록 시그니처 보존. 테스트는 adapter test만 갱신.
- 어댑터 빈 와이어링은 Spring DI가 자동 처리.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After (target) | Notes |
| ------ | ----: | ----: | ----- |
| Core 포트 파일 중 tech name 노출 | 10/10 | 0/10 | grep `Pgmq\|Redis\|Kafka\|MySQL` |
| `Any?` 반환 메서드 in CacheManagerPort | 3 | 0 | typed contract로 교체 |
| Javadoc `@see`에 infra 클래스 참조 | 7 | 0 | 어댑터 인터페이스의 `@see maple.expectation.infrastructure.*` 전부 제거 |
| 영향 파일 수 | n/a | ~50 | rename + adapter 신규 |
| `compileKotlin compileJava` | pass | pass | `--continue` 필수 |
| `test` | pass | pass | 전략 인터페이스는 Javadoc만 변경, 테스트 불요 |

### Observed Result

- PR 머지 후 module-core에 `Pgmq`, `Redis`, `Kafka`, `MySQL` 문자열이 grep으로 0건
- `module-app:bootRun` 후 `/api/v5/characters/진격캐넌/expectation` 호출 → `Calculation completed with result saved` 로그 확인
- `./gradlew test` 전 모듈 통과

---

## 5. Summary

> module-core의 포트 인터페이스에서 인프라 기술명을 일소하고, 캐시/메트릭 abstraction을 module-common의 typed contract로 끌어올려 완전한 Hexagonal 경계를 복원한다.
