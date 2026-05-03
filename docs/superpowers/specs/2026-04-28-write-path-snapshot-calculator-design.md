# Write Path — Snapshot Calculator Design

**Date**: 2026-04-28
**Status**: Proposed
**Parent ADR**: ADR-three-path-independence-mq-boundary, ADR-write-path-snapshot-calculator

---

## Overview

Write Path를 "Nexon 응답 처리기"가 아니라 **"Snapshot 계산기"**로 재정의한다.
External API DTO를 완전히 차단하고, typed contract model(CalculationInput)만 소비해서
계산 결과를 응답 가능한 artifact로 영속화한다.

---

## Architecture: Four-Boundary Pipeline

```
┌──────────────┐                ┌──────────────────┐               ┌──────────────┐               ┌──────────────────┐
│ External API │  snapshot_     │   Write Path     │  result_      │  Read Path   │  response_    │ Request/Response │
│    JVM       │  ready         │    JVM           │  ready        │    JVM       │  ready        │      JVM         │
│              │ ──────────────▶│                  │ ─────────────▶│              │ ─────────────▶│                  │
│ API 호출     │                │ CalculationInput │               │ cache warm   │               │ HTTP 요청/응답   │
│ OCID resolve │                │ 계산 (pure fn)   │               │ read model   │               │ jobId 조회       │
│ Snapshot 생성│                │ result 저장      │               │              │               │ gzip 응답        │
│ EquipResp    │                │ gzip 압축        │               │              │               │                  │
│ → CalcInput  │                │ outbox 이벤트    │               │              │               │ Client polling   │
│ 변환 + 저장  │                │                  │               │              │               │ 또는 SSE/push    │
└──────────────┘                └──────────────────┘               └──────────────┘               └──────────────────┘
```

**데이터 흐름**:

```
External API:
  1. Nexon API → EquipmentResponse 수집
  2. EquipmentResponse → CalculationInput 변환
  3. CalculationInput DB 저장 (calculation_snapshot_inputs)
  4. snapshot_ready 발행 (jobId + inputRef만)

Write Path:
  5. snapshot_ready 소비
  6. DB에서 CalculationInput 1번 조회
  7. calculate(input: CalculationInput): CalculationResult (pure)
  8. result JSON 생성 → gzip 압축 → calculation_results 저장
  9. job COMPLETED 전이 + outbox_events insert (atomic)
  10. Outbox Relay → result_ready 발행

Read Path:
  11. result_ready 소비
  12. cache/read model 반영
  13. response_ready 발행

Request/Response:
  14. Client HTTP 요청 처리
  15. jobId 기준 결과 조회 → gzip 응답
```

---

## Gap 1: CalculationInput Contract Model

### 원칙

- CalculationInput은 External API DTO가 아니라 **내부 계산 계약 모델**이다
- 모든 필드는 **계산 재현에 필요한 값**으로만 구성된다
- Write Path는 CalculationInput 외의 데이터를 조회하지 않는다
- ID/reference 없음. **완전한 값 객체**
- schemaVersion으로 과거 input과 호환성 관리

### 변환 책임

변환은 **External API Path**에서 수행. Write Path는 변환에 관여하지 않는다.

```
External API Path:
  EquipmentResponse → CalculationInput 변환 → DB 저장 → snapshot_ready 발행

Write Path:
  CalculationInput 조회 → 계산 → 결과 저장
```

### CalculationInput 스키마

```kotlin
data class CalculationInput(
    val schemaVersion: Int = 1,
    val jobId: String,
    val userIgn: String,
    val characterClass: CharacterClass,
    val presetNo: Int,
    val items: List<EquipmentItem>
)

data class EquipmentItem(
    val part: EquipmentSlot,
    val equipmentPart: EquipmentPart,
    val itemName: String,
    val level: Int,

    val potential: PotentialLines?,
    val additionalPotential: PotentialLines?,

    val starforce: Int,
    val starforceScrollFlag: StarforceScrollFlag?,

    val addOption: AddOption,
    val baseAttackPower: Int,
    val baseMagicPower: Int
)

data class PotentialLines(
    val grade: PotentialGrade,
    val line1: PotentialOption?,
    val line2: PotentialOption?,
    val line3: PotentialOption?
)

data class AddOption(
    val str: Int, val dex: Int, val int: Int, val luk: Int,
    val maxHp: Int, val allStat: Int,
    val attackPower: Int, val magicPower: Int,
    val bossDamage: Int, val damage: Int
)
```

### Nullable 규칙

- `potential == null` → cube 계산 전체 skip
- `potential != null` → grade + 3 lines 필수 구조
- `additionalPotential` 동일
- `starforceScrollFlag == null` → 일반 스타포스

### Typed Enum/Value Classes

String 대신 typed contract로 강화:

| 필드 | 타입 | 설명 |
|------|------|------|
| characterClass | CharacterClass | 직업 분류 (flame job-weight) |
| part | EquipmentSlot | 장비 슬롯 (cube 확률 테이블) |
| equipmentPart | EquipmentPart | 보조무기 분류 |
| grade | PotentialGrade | 잠재 등급 |
| starforceScrollFlag | StarforceScrollFlag | noljang 여부 |

### Schema Evolution

```kotlin
interface CalculationEngine {
    fun supports(version: Int): Boolean
    fun calculate(input: CalculationInput): CalculationResult
}
```

- Additive only: 필드 추가만, 제거 금지
- 과거 schemaVersion의 input은 해당 버전 계산 로직으로 처리
- Consumer가 모르는 필드는 무시

---

## Gap 2: Write Path Retry & Error Handling

### 상태 전이

```
SNAPSHOT_READY → CALCULATING → COMPLETED
                              → FAILED (after max_retries)
                              → RETRYING → CALCULATING (backoff)
```

### 처리 흐름

```
1. message consume
2. idempotency check (terminal status → skip)
3. 상태 = CALCULATING (conditional update)
4. CalculationInput 조회 (1회, 추가 조회 없음)
5. calculate(input) (pure function)
6. 성공 → result 저장 + COMPLETED + outbox insert (atomic)
7. 실패 → retry_count++ + backoff or FAILED + DLQ
```

### Retry 정책

| 시도 | Backoff |
|------|---------|
| 1st retry | 1s |
| 2nd retry | 5s |
| 3rd retry | 30s |
| 4th retry | 2m |

- Exponential backoff + jitter
- 구현: `next_retry_at` 컬럼 기반 스케줄링
- `retry_count >= max_retries` → FAILED + DLQ

### Idempotency 보장

| 시나리오 | 방어 |
|---------|------|
| 같은 response 메시지 두 번 처리 | terminal status check (COMPLETED/FAILED → ack) |
| 같은 job 두 번 계산 | CAS: `WHERE status = 'CALCULATING'` |
| 같은 job 결과 두 번 저장 | `job_id UNIQUE` on calculation_results |
| stuck-in-CALCULATING | redelivery 시 감지 → FAILED 전이 |
| 크래시 후 재시작 | Timeout Scanner가 locked_until 만료 후 복구 |
| retry 중 두 worker 경쟁 | advisory lock / SELECT FOR UPDATE |

### DLQ

DLQ는 "버리는 곳"이 아니라 **"사람이 개입하는 큐"**.

- 진입 조건: max retry 초과, schema mismatch, irrecoverable error
- payload: jobId, error, retryCount, originalEvent
- 이후: 분석 → fix → replay

---

## Gap 3: Outbox Pattern — Event Publishing Guarantee

### 핵심 철학

```
event publish ≠ business transaction
event 기록 = business transaction
publish = infra concern
```

이벤트는 "보내는 것"이 아니라 "기록하고 전달되는 것"이다.

### outbox_events 테이블

```sql
CREATE TABLE outbox_events (
    event_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type       VARCHAR(64) NOT NULL,
    job_id           UUID NOT NULL,
    payload          JSONB,
    published        BOOLEAN DEFAULT false,
    publish_attempts INT DEFAULT 0,
    created_at       TIMESTAMPTZ DEFAULT now(),
    published_at     TIMESTAMPTZ,
    UNIQUE (job_id, event_type)
);
```

**idempotency key**: `UNIQUE (job_id, event_type)` — 같은 이벤트는 한 번만 기록.

### Write Path 트랜잭션

```sql
BEGIN;
  -- 1. result 저장 (hash 비교)
  INSERT INTO calculation_results (job_id, ..., hash, status)
  VALUES (...)
  ON CONFLICT (job_id) DO UPDATE SET ...;

  -- 2. job COMPLETED 전이 (conditional)
  UPDATE calculation_jobs
  SET status = 'COMPLETED', completed_at = now()
  WHERE job_id = :jobId AND status = 'CALCULATING';

  -- 3. outbox 이벤트 기록 (idempotent)
  INSERT INTO outbox_events (event_type, job_id, payload)
  VALUES ('CALCULATION_COMPLETED', :jobId, :payload)
  ON CONFLICT (job_id, event_type) DO NOTHING;
COMMIT;
```

### Outbox Relay Worker

```sql
SELECT * FROM outbox_events
WHERE published = false
ORDER BY created_at
LIMIT N
FOR UPDATE SKIP LOCKED;

-- MQ publish 성공 후:
UPDATE outbox_events
SET published = true, published_at = now(), publish_attempts = publish_attempts + 1
WHERE event_id = :eventId;
```

**중복 발행 허용**: publish 성공 후 DB update 실패 시 재발행 가능.
Consumer(Read Path)는 반드시 idempotent해야 함 (job_id 기반 skip).

### Compensating Scanner (보험)

```sql
SELECT j.job_id
FROM calculation_jobs j
WHERE j.status = 'COMPLETED'
  AND NOT EXISTS (
    SELECT 1 FROM outbox_events o
    WHERE o.job_id = j.job_id AND o.event_type = 'CALCULATION_COMPLETED'
  )
  AND j.completed_at < now() - INTERVAL '1 minute';
```

Outbox Relay 장애/버그/데이터 꼬임 시 복구 용도. 1분 간격 실행.

---

## Gap 4: Calculation Interface Evolution (Pure Function)

### 목표

```
calculate(input: CalculationInput): CalculationResult
```

input 외에 외부 의존성 = 0. input만으로 결과 100% 결정.

### 점진적 전환 (4단계)

**1단계: Dependency 드러내기**

기존 fetch 로직을 인터페이스로 분리:

```kotlin
interface EquipmentProvider {
    fun getEquipment(userIgn: String): Equipment
}
```

**2단계: CalculationInput 도입**

```kotlin
calculate(input: CalculationInput): CalculationResult
```

내부에서 아직 provider 사용 가능.

**3단계: Provider 제거**

input에 필요한 데이터 전부 포함. provider dead code.

**4단계: 완전 Pure**

계산 로직에서 IO 완전 제거. cache, DB, API 참조 없음.

### 절대 하면 안 되는 것

- input에 ID만 넣고 내부에서 조회 → 현재 구조와 동일
- input + cache fallback → pure function 깨짐
- 불완전한 optional field → 조건 분기 지옥

---

## result_ready 이벤트 스키마

```json
{
  "eventType": "CALCULATION_COMPLETED",
  "schemaVersion": 1,
  "jobId": "uuid",
  "traceId": "uuid",
  "occurredAt": "2026-04-28T12:00:00Z",
  "payload": {
    "resultId": "uuid",
    "characterId": "...",
    "presetNo": 1,
    "contentEncoding": "gzip",
    "schemaVersion": 1
  }
}
```

MQ에는 참조만. 응답 본문은 `calculation_results` 테이블에서 조회.

---

## DB Schema: calculation_results

```sql
CREATE TABLE calculation_results (
    result_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    character_class  VARCHAR(64),
    preset_no        INT DEFAULT 1,
    schema_version   INT DEFAULT 1,
    content_type     VARCHAR(64) DEFAULT 'application/json',
    content_encoding VARCHAR(16) DEFAULT 'gzip',
    response_body    BYTEA,
    original_size    INT,
    compressed_size  INT,
    hash             VARCHAR(128),
    status           VARCHAR(16) DEFAULT 'SUCCESS',
    created_at       TIMESTAMPTZ DEFAULT now(),
    expires_at       TIMESTAMPTZ
);

CREATE INDEX idx_calc_results_job ON calculation_results (job_id);
CREATE INDEX idx_calc_results_char ON calculation_results (character_class, preset_no);
CREATE INDEX idx_calc_results_expires ON calculation_results (expires_at) WHERE expires_at IS NOT NULL;
```

### Upsert 전략 (hash 비교)

```
기존 result 없음 → INSERT
기존 result 있음 + hash 동일 → SKIP (동일 결과)
기존 result 있음 + hash 다름 → OVERWRITE (재시도로 개선된 결과)
```

### status 필드

| 값 | 의미 |
|----|------|
| SUCCESS | 정상 완료 |
| FAILED | 계산 실패 |
| PARTIAL | 부분 완료 (일부 preset만) |

Read Path에서 결과 상태 판단 가능.

---

## DB Schema: calculation_snapshot_inputs

```sql
CREATE TABLE calculation_snapshot_inputs (
    input_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    schema_version  INT DEFAULT 1,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_snapshot_inputs_job ON calculation_snapshot_inputs (job_id);
```

---

## Migration Strategy

### Phase 1: Infrastructure (Write Path 내부 개선)

1. `calculation_snapshot_inputs` 테이블 생성
2. `calculation_results` 테이블 생성
3. `outbox_events` 테이블 생성
4. `result_ready` topic/queue 생성
5. CalculationInput typed contract 모델 구현

### Phase 2: External API Path 변환

6. External API Path에 EquipmentResponse → CalculationInput 변환 로직 추가
7. CalculationInput을 `calculation_snapshot_inputs`에 저장
8. snapshot_ready 메시지에 inputRef 포함

### Phase 3: Write Path Pure Function 전환

9. ApiResponseWorker가 CalculationInput만 소비하도록 수정
10. EquipmentResponse DTO 참조 완전 제거
11. 계산 결과 gzip 압축 + calculation_results 저장
12. Outbox 이벤트 insert (atomic with result save)
13. Outbox Relay Worker 구현

### Phase 4: Legacy 정리

14. 레거시 ExpectationCalcWorker/ExpectationCalcLowWorker 비활성화
15. EquipmentFanOutPort 제거 (이미 no-op)
16. 기존 batchL2CachePut/batchViewUpsert를 Read Path로 이관

---

## Out of Scope (Future Brainstorming)

- Read Path consistency (replica lag 대응)
- Client 응답 전략 (polling vs SSE/WebSocket vs hybrid)
- Request/Response Path 상세 설계
- Write Path 독립 JVM 배포 구성
- Connection pool 분리
- Observability (metrics, tracing)
