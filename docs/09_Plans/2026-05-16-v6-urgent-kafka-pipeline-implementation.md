# V6 Urgent Kafka Pipeline 구현 기록

> 작성일: 2026-05-16
> 관련 PR: #830 (urgent pipeline), #831 (batch-urgent dedup), #832 (merge to master)
> ADR: docs/01_ADR/ADR-026_v6-urgent-kafka-pipeline.md

---

## 1. 배경 및 목표

### 문제 상황
V6 read endpoint (module-rest-controller)에서 character IGN으로 조회 시 Redis cache, DB read model, OCID lookup 어디에도 데이터가 없는 경우:
- 기존: DeferredResult timeout → 202 Accepted 반환 후 사용자가 재시도해야 함
- 데이터가 아예 없는 캐릭터는 스케줄러 배치 파이프라인이 돌아야만 데이터가 생김

### 목표
DB와 Redis에 모두 없는 캐릭터에 대해:
1. Nexon API로 OCID lookup 수행
2. Character Basic + Item Equipment 데이터 fetch
3. Kafka 메시지로 각 모듈 파이프라인에 작업 분배
4. **Urgent 우선순위**로 처리 (배치 파이프라인보다 빠르게)
5. 완료 후 OCID lookup 테이블에도 추가 → 이후 스케줄러 파이프라인에 포함

### 넥슨 API 특이사항
- 존재하지 않는 캐릭터: 응답코드 400 반환
- OCID lookup에 포함되지 않은 캐릭터는 external-api의 배치 스케줄러에 의해 처리될 때까지 데이터 없음

---

## 2. 아키텍처 설계

### Kafka Topic 구성

| Topic | Publisher | Consumer | 용도 |
|-------|-----------|----------|------|
| `urgent-character-request` | rest-controller | external-api | urgent 작업 요청 |
| `urgent-character-not-found` | external-api | rest-controller | 존재하지 않는 캐릭터 negative cache |
| `external-api.urgent.snapshot.chunk-ready` | external-api | calculator (urgent group), synchronizer/basic (urgent group) | urgent chunk 처리 |
| `calculator.result.chunk-ready` (기존) | calculator | synchronizer/result (기존, shared) | 계산 결과 |

### 데이터 흐름

```
[rest-controller]
  BatchReadScheduler → cache miss → urgent trigger
    ↓ Kafka: urgent-character-request
[external-api]
  UrgentCharacterRequestConsumer
    → OCID lookup (Nexon API)
    → Character Basic + Item Equipment 병렬 fetch
    → GzipJsonlChunkWriter로 chunk 생성
    ↓ Kafka: external-api.urgent.snapshot.chunk-ready
[calculator] (urgent consumer group)
  KafkaSnapshotChunkReadyConsumer.consumeUrgent()
    → CalculatorChunkProcessingCoordinator.handle()
    ↓ Kafka: calculator.result.chunk-ready (shared)
[synchronizer] (urgent consumer group)
  BasicSnapshotChunkConsumer.consumeUrgentBasic()
    → bulkUpsert character_basic_read_model
    → upsertOcidFromBasicRecords() ← OCID lookup 테이블에 추가
    ↓ DB: character_basic_read_model, game_character
[rest-controller]
  UrgentCharacterNotFoundConsumer
    → negative cache 설정 (Redis SET)
```

### Feature Flag
```yaml
expectation:
  v6:
    urgent:
      enabled: false  # 환경별 활성화
```

---

## 3. 모듈별 구현 상세

### 3.1 module-rest-controller

#### UrgentCharacterRequest (DTO)
```kotlin
data class UrgentCharacterRequest(
    val eventId: String = UUID.randomUUID().toString(),
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: Instant = Instant.now()
)
```

#### UrgentTriggerPublisher
- `KafkaTemplate<String, String>` 사용
- fire-and-forget: `whenComplete`로 로깅만, `join()/get()` 없음
- ObjectMapper로 JSON 직렬화
- `@ConditionalOnProperty(name = ["expectation.v6.urgent.enabled"], havingValue = "true")` 로 bean 등록

#### UrgentCharacterNotFoundConsumer
- Kafka listener on `urgent-character-not-found`
- `@ConditionalOnProperty` 로 gated
- Null-safe parsing: `objectMapper.readTree(message).get("userIgn")?.asText()`
- malformed message → acknowledge to skip (poison message 처리)
- `cacheService.setNegativeCache(userIgn, negativeCacheTtlSeconds)` 로 Redis에 NOT_FOUND 캐싱

#### ReadModelCacheService 변경
- `NEGATIVE_KEY_PREFIX = "v6:not-found"` — 존재하지 않는 캐릭터 negative cache
- `URGENT_PENDING_PREFIX = "v6:urgent-pending"` — urgent 중복 트리거 방지 dedup
- 추가 메서드:
  - `getNegativeCache(userIgn)` — Redis key 존재 여부 확인
  - `setNegativeCache(userIgn, ttlSeconds)` — NOT_FOUND 캐싱 (기본 1시간)
  - `tryMarkUrgentPending(userIgn)` — Redis SETNX로 urgent dedup (TTL: YAML 설정, 기본 30초)
  - `isUrgentPending(userIgn)` — urgent 처리 중인지 확인
  - `clearUrgentPending(userIgn)` — urgent 완료 시 키 삭제
- `multiPut()`에서 cache 저장 시 `clearUrgentPending()` 호출 → urgent 완료 즉시 키 정리

#### BatchReadScheduler 변경 (resolveBatch 흐름)
```
1. Redis cache lookup → hits / misses 분리
2. Cache hits → 즉시 200 반환
3. Cache misses 중 urgent-pending 캐릭터 → DB 조회 스킵 (자원 절약)
4. DB batch query (urgent-pending 제외한 나머지만)
5. DB results → Redis cache 저장 (multiPut, 이때 urgent-pending 키도 삭제)
6. DB hit → 200 반환
7. DB miss + negative cache → 404 (X-Error-Reason: character-not-found)
8. DB miss + urgent 가능 → tryMarkUrgentPending → 성공 시 urgent publish, 실패 시 이미 처리 중
9. 나머지 → DeferredResult timeout → 202 Accepted
```

#### V6ReadConfig 변경
- `urgentTriggerPublisher` bean: `@ConditionalOnProperty` 로 urgent 활성화 시만 생성
- `batchReadScheduler` bean: `ObjectProvider<UrgentTriggerPublisher>` 로 nullable injection
  - urgent 비활성화 환경에서는 publisher = null → urgent 로직 무시

#### V6ReadProperties 변경
- `urgentPendingTtlSeconds: Long = 30` 추가 (YAML: `pending-ttl-seconds`)

#### V6ReadMetrics 변경
- `urgentTriggerTotal: Counter` 추가 (metric name: `v6_urgent_trigger_total`)

#### application.yml 변경
```yaml
expectation:
  v6:
    urgent:
      enabled: false
      request-topic: urgent-character-request
      not-found-topic: urgent-character-not-found
      negative-cache-ttl-seconds: 3600
      pending-ttl-seconds: 30
```

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
    consumer:
      group-id: rest-controller-urgent-feedback
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: manual_immediate
```

#### build.gradle 변경
```groovy
implementation(libs.spring.kafka)
```

### 3.2 module-external-api

#### UrgentCharacterRequestConsumer
- OCID lookup: `clientPort.fetch()` → null-safe ocid extraction
- 존재하지 않는 캐릭터 (400 응답) → not-found topic에 publish
- 존재하는 캐릭터: CHARACTER_BASIC + ITEM_EQUIPMENT 병렬 fetch
  - `CompletableFuture.allOf(basicFuture, itemFuture).join()` (consumer 내부에서는 허용)
- GzipJsonlChunkWriter로 단일 레코드 chunk 생성
- SnapshotChunkReadyEvent를 urgent chunk-ready topic에 publish
- runId 포맷: `urgent-{UUID}` (배치 파이프라인의 타임스탬프와 구분)

#### application.yml 변경
```yaml
external-api:
  urgent:
    enabled: false
    request-topic: urgent-character-request
    not-found-topic: urgent-character-not-found
    chunk-ready-topic: external-api.urgent.snapshot.chunk-ready
    consumer-group-id: external-api-urgent-character-request
```

### 3.3 module-calculator

#### KafkaSnapshotChunkReadyConsumer 변경
- 기존 `consume()` 유지 (배치 chunk-ready topic)
- `consumeUrgent()` 추가 (urgent chunk-ready topic, 별도 consumer group)
- 두 메서드 모두 동일한 `coordinator.handle(event)` 호출
- 계산 결과는 normal result topic으로 publish (shared)

#### application.yml 변경
```yaml
calculator:
  kafka:
    urgent-snapshot-chunk-ready-topic: external-api.urgent.snapshot.chunk-ready
    urgent-consumer-group-id: calculator-urgent-chunk-processor
```

### 3.4 module-synchronizer

#### BasicSnapshotChunkConsumer 변경
- 생성자에 `NamedParameterJdbcTemplate` 추가
- `consumeUrgentBasic()` 추가:
  - idempotency: `isAlreadySuccess()`, `claimChunk()` 체크
  - 동시성 제어: `Semaphore(2)` (배치 consumer와 공유)
  - Virtual Thread에서 실행: `CompletableFuture.runAsync({}, vtExecutor)`
  - LogicExecutor로 예외 처리 위임
  - 성공 시 `upsertOcidFromBasicRecords()` 호출

- `upsertOcidFromBasicRecords(records)`:
  - 각 record에 대해 `INSERT INTO game_character (user_ign, ocid) ... ON CONFLICT (user_ign) DO UPDATE SET ocid = EXCLUDED.ocid`
  - OCID lookup 테이블에 추가 → 이후 스케줄러 파이프라인에서 자동 포함

#### application.yml 변경
```yaml
synchronizer:
  kafka:
    urgent-basic-chunk-ready-topic: external-api.urgent.snapshot.chunk-ready
    urgent-basic-consumer-group-id: synchronizer-urgent-basic-chunk-consumer
```

---

## 4. 코드 리뷰에서 발견/수정된 이슈

### 4.1 UrgentCharacterNotFoundConsumer NPE risk
- **문제**: `objectMapper.readTree(message).get("userIgn").asText()` — malformed Kafka message에서 NPE
- **수정**: `objectMapper.readTree(message).get("userIgn")?.asText()` + null check + early return with ACK

### 4.2 IGN 로깅 마스킹 누락
- **문제**: UrgentCharacterNotFoundConsumer, ReadModelCacheService, BasicSnapshotChunkConsumer에서 raw userIgn 로깅
- **수정**: `maskIgn()` import 후 모든 log statement에 적용 (`f***l` 형식)

### 4.3 Malformed message 테스트 누락
- **문제**: userIgn이 없는 Kafka 메시지에 대한 테스트 케이스 없음
- **수정**: `UrgentCharacterNotFoundConsumerTest`에 missing userIgn 테스트 추가

---

## 5. Batch-Urgent 파이프라인 중복 처리 방지 (PR #831)

### 문제 분석
배치 파이프라인과 urgent 파이프라인이 같은 캐릭터를 동시에 처리할 때:

| 항목 | 개선 전 | 개선 후 |
|------|---------|---------|
| urgent-pending 키 정리 | TTL 30초 만료에만 의존 | `multiPut()` 시 즉시 삭제 |
| 배치의 DB 조회 | urgent 처리 중에도 DB 쿼리 실행 | `isUrgentPending()` 체크 후 스킵 |
| urgent TTL | 하드코딩 30초 | YAML `pending-ttl-seconds` 설정 |
| 중복 urgent 트리거 | TTL 만료 후 재트리거 가능 | 캐시 저장 시 즉시 키 삭제로 방지 |

### 안전망 (이미 존재)
- `claimChunk` idempotency: `(run_id, chunk_id)` 기반 — 다른 runId면 통과하지만
- `bulkUpsert` `ON CONFLICT (user_ign) DO UPDATE`: DB 수준에서 최종 데이터 보장
- 데이터 깨짐 없음, 다만 API 호출 및 계산 리소스 낭비 존재

---

## 6. 테스트

### Unit Tests
- `UrgentCharacterNotFoundConsumerTest`
  - `consume sets negative cache and acknowledges()` — 정상 케이스
  - `consume with missing userIgn acknowledges without setting negative cache()` — malformed message
- `UrgentTriggerPublisherTest`
  - `publish sends message with userIgn as key()` — Kafka producer 호출 검증
- `UrgentCharacterRequestConsumerTest` (module-external-api)
  - OCID lookup → data fetch → chunk publish 흐름
  - Character not found → not-found topic publish

### 컴파일/테스트 검증
```bash
./gradlew :module-rest-controller:compileKotlin  # PASS
./gradlew :module-rest-controller:test           # PASS (all tests)
./gradlew :module-external-api:compileKotlin     # PASS
./gradlew :module-calculator:compileKotlin       # PASS
./gradlew :module-synchronizer:compileKotlin     # PASS
```

### 런타임 검증 (PR #830)
- V6 cache hit → 200 OK
- V6 cache miss → 202 Accepted + Kafka 메시지 발행 확인
- Urgent dedup (SETNX) → 동일 캐릭터 재요청 시 중복 트리거 방지 확인
- 테스트 IGN: 류쫑 (CSV에서 랜덤 추출)

---

## 7. Claude Rules 업데이트

### workflow-rules.md
- Section 11 추가: 테스트용 userIGN 우선순위
  - 1순위: `진격캐넌`, `아델`, `강은호` (고정 캐릭터)
  - 2순위: `module-app/src/main/resources/data/userIgn_List.csv`에서 `shuf -n 1` 랜덤 추출
- Section 번호 재정렬 (11→12→13→14)

---

## 8. PR/Merge 이력

| # | PR | Base | 내용 |
|---|-----|------|------|
| 1 | #830 | develop | V6 urgent Kafka pipeline 전체 구현 |
| 2 | #831 | develop | Batch-urgent pipeline overlap 방지 (pending key management) |
| 3 | #832 | master | develop → master merge (충돌 해결: develop 버전 우선) |

---

## 9. 파일 변경 목록

### module-rest-controller (PR #830 + #831)
- `build.gradle` — spring-kafka 의존성 추가
- `application.yml` — Kafka producer/consumer config, urgent config
- `V6ReadConfig.kt` — urgent beans, ObjectProvider nullable injection
- `V6ReadProperties.kt` — urgentPendingTtlSeconds 추가
- `V6ReadMetrics.kt` — urgentTriggerTotal counter 추가
- `BatchReadScheduler.kt` — urgent trigger 로직, urgent-pending skip 로직
- `ReadModelCacheService.kt` — negative cache, urgent pending 관리 메서드
- `UrgentCharacterRequest.kt` — 신규 DTO
- `UrgentTriggerPublisher.kt` — 신규 Kafka producer
- `UrgentCharacterNotFoundConsumer.kt` — 신규 Kafka consumer
- `UrgentCharacterNotFoundConsumerTest.kt` — 신규 테스트
- `UrgentTriggerPublisherTest.kt` — 신규 테스트

### module-external-api (PR #830)
- `application.yml` — urgent config
- `UrgentCharacterRequestConsumer.kt` — 신규 Kafka consumer (OCID + data fetch)
- `UrgentCharacterRequestConsumerTest.kt` — 신규 테스트

### module-calculator (PR #830)
- `application.yml` — urgent topic config
- `KafkaSnapshotChunkReadyConsumer.kt` — consumeUrgent() 추가

### module-synchronizer (PR #830)
- `application.yml` — urgent topic config
- `BasicSnapshotChunkConsumer.kt` — consumeUrgentBasic(), upsertOcidFromBasicRecords() 추가

### 기타
- `docs/01_ADR/ADR-026_v6-urgent-kafka-pipeline.md` — ADR 문서
- `.claude/rules/workflow-rules.md` — 테스트 IGN 우선순위 규칙
- `gradle/libs.versions.toml` — spring-kafka version catalog entry
