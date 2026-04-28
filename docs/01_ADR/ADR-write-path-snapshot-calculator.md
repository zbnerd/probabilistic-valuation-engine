# ADR: Write Path — Snapshot Calculator

**Status**: Approved
**Date**: 2026-04-28
**Parent**: ADR-three-path-independence-mq-boundary
**Context**: Phase 1 Write Path 경계 확정

---

## Decision

Write Path를 "Nexon 응답 처리기"가 아니라 **"Snapshot 계산기"**로 정의한다.
Write Path는 External API의 산출물인 Snapshot만 소비하고, 계산 결과를 응답 가능한 artifact로 영속화한 뒤 이벤트를 발행한다.

---

## Architecture: Four-Boundary Pipeline

기존 ADR의 3-path 구조를 4개 경계로 명확히 분리한다.

```
┌──────────────┐   MQ   ┌──────────────┐   MQ   ┌──────────────┐   MQ   ┌──────────────────┐
│ External API │───────▶│  Write Path  │───────▶│  Read Path   │───────▶│ Request/Response │
│    JVM       │        │    JVM       │        │    JVM       │        │      JVM          │
│              │        │              │        │              │        │                   │
│ API 호출     │        │ Snapshot 읽기│        │ cache warm   │        │ HTTP 요청/응답    │
│ OCID resolve │        │ 계산         │        │ read model   │        │ jobId 조회        │
│ Snapshot 저장│        │ Result 생성  │        │ cache 준비   │        │ gzip 응답         │
│              │        │ gzip 압축    │        │              │        │                   │
│ snapshot_    │        │ result_      │        │ response_    │        │ Client polling    │
│ ready 발행   │        │ ready 발행   │        │ ready 발행   │        │ 또는 SSE/push     │
└──────────────┘        └──────────────┘        └──────────────┘        └──────────────────┘
```

### 데이터 흐름

```
1. External API
   - 외부 API 호출, OCID resolve, Snapshot 생성/저장
   - snapshot_ready 이벤트 발행

2. Write Path (본 ADR의 대상)
   - snapshot_ready 소비
   - Snapshot 읽기 → 계산 → 응답 JSON 생성 → gzip 압축 → result 저장
   - result_ready 이벤트 발행

3. Read Path
   - result_ready 소비
   - cache/read model 반영
   - response_ready 이벤트 발행

4. Request/Response
   - Client HTTP 생명주기 관리
   - jobId 기준 결과 조회 → gzip 응답 반환
```

### 경계 원칙

| 경계 | 역할 | 하지 않는 것 |
|------|------|-------------|
| External API | 외부 호출 + Snapshot 생성 | 계산, 비즈니스 로직 |
| Write Path | 계산 + 결과 artifact 영속화 | 외부 API 호출, Client 응답 |
| Read Path | 결과 준비 상태 반영 | 계산, 외부 API, Client 직접 응답 |
| Request/Response | Client HTTP 처리 | 계산, 외부 API, 캐시 관리 |

---

## Write Path 상세 설계

### 책임 범위

```
IN:
  - MQ: nexon_api_response_queue (snapshot_ready 소비)
  - SnapshotObjectStore (port): Snapshot 읽기
  - DB: calculation_jobs 상태 조회/전이

OUT:
  - DB: calculation_results 저장 (gzip compressed)
  - DB: calculation_jobs COMPLETED 전이
  - MQ: result_ready 이벤트 발행
```

### 금지 사항

```
❌ NexonApiClient 직접/간접 호출
❌ resolveOcid() 호출
❌ External API DTO 직접 참조 (EquipmentResponse 등)
❌ FanOut / raw JSON / JsonNode (외부 API 응답 구조)
❌ HTTP Controller
❌ Client 응답 처리
```

### 상태 전이

```
SNAPSHOT_READY → CALCULATING → COMPLETED
                              → FAILED
```

모든 전이는 `CalculationJobService`를 통해서만 수행. Worker는 직접 status update하지 않음.

---

## `calculation_results` 테이블

Write Path의 산출물을 저장. Read Path와 Request/Response는 이 테이블에서 결과를 조회.

```sql
CREATE TABLE calculation_results (
    result_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    character_id     VARCHAR(64),
    preset_no        INT DEFAULT 1,
    schema_version   INT DEFAULT 1,
    content_type     VARCHAR(64) DEFAULT 'application/json',
    content_encoding VARCHAR(16) DEFAULT 'gzip',
    object_key       VARCHAR(256),           -- 향후 Object Store 이관용
    response_body    BYTEA,                  -- gzip compressed JSON
    original_size    INT,                    -- 압축 전 크기
    compressed_size  INT,                    -- 압축 후 크기
    hash             VARCHAR(128),           -- content hash (무결성)
    created_at       TIMESTAMPTZ DEFAULT now(),
    expires_at       TIMESTAMPTZ             -- TTL
);

CREATE INDEX idx_calc_results_job ON calculation_results (job_id);
CREATE INDEX idx_calc_results_char ON calculation_results (character_id, preset_no);
CREATE INDEX idx_calc_results_expires ON calculation_results (expires_at) WHERE expires_at IS NOT NULL;
```

### 설계 의도

- **Read Path는 계산 DTO를 몰라도 됨**: 그냥 `response_body` (gzip blob)를 읽어서 `Content-Encoding: gzip`으로 전달
- **job_id UNIQUE**: 멱등성 보장. 같은 job의 결과는 하나만 존재
- **object_key nullable**: 초기에는 BYTEA 직접 저장, 나중에 Object Store로 이관 가능
- **hash**: 무결성 검증 (재시도 시 동일 결과인지 확인)

---

## MQ Contract: `result_ready` 이벤트

Write Path → Read Path 통신.

```
발행: Write Path (CalculationJobService.completeCalculation() 내)
소비: Read Path (ReadModelUpdaterWorker — 신규)

Payload:
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

**주의**: MQ에는 참조만. 응답 본문은 `calculation_results` 테이블에서 조회.

---

## 기존 코드에서의 변경 사항

### P0: ApiResponseWorker DTO 분리

**현재 문제**: `ApiResponseWorker.kt:101-103`이 External API DTO 직접 참조

```kotlin
// ❌ 현재
val equipmentResponse = objectMapper.readValue(
    snapshotData,
    maple.expectation.infrastructure.external.dto.v2.EquipmentResponse::class.java
)
```

**해결**:
- Equipment cache population 로직을 External API Path로 이관
- Write Path는 snapshot에서 계산 입력 DTO만 복원
- 또는 포트 인터페이스를 통해 도메인 중립 타입으로 역직렬화

### P1: `calculation_results` 저장 추가

ApiResponseWorker 계산 완료 후:
1. 계산 결과를 JSON으로 직렬화
2. gzip 압축
3. `calculation_results` 테이블에 저장
4. job COMPLETED 전이
5. `result_ready` 이벤트 발행

### P2: batchWrite 로직 이관

`AbstractExpectationCalcWorker`의 `batchL2CachePut()` + `batchViewUpsert()`를
Read Path Consumer로 이관. Write Path는 계산 + result 저장까지만.

### P3: JsonNode → typed DTO

`AbstractExpectationCalcWorker.kt:138-152`의 raw JsonNode 파싱을
타입 안전한 `CalculationResultDto`로 교체.

### P4: 레거시 정리

- `AbstractExpectationCalcWorker.kt:72-73`: OCID resolve 호출 제거
- `AbstractExpectationCalcWorker.kt:41`: `EquipmentFanOutPort` 제거 (이미 no-op)
- 레거시 `ExpectationCalcWorker` / `ExpectationCalcLowWorker` 비활성화

---

## 멱등성 보장

| 시나리오 | 방어 |
|---------|------|
| 같은 response 메시지 두 번 처리 | 터미널 상태 체크 (COMPLETED/FAILED → ack) |
| 같은 job 두 번 계산 | CAS: `WHERE status = 'CALCULATING'` |
| 같은 job 결과 두 번 저장 | `job_id UNIQUE` on calculation_results |
| stuck-in-CALCULATING | redelivery 시 감지 → FAILED 전이 |
| 크래시 후 재시작 | Timeout Scanner가 locked_until 만료 후 복구 |

---

## 트랜잭션 경계

```
Write Path 핵심 트랜잭션:
  BEGIN;
    -- 1. 결과 저장 (calculation_results upsert)
    -- 2. job COMPLETED 전이 (conditional update)
    -- 3. result_ready 이벤트 발행 (PGMQ send)
  COMMIT;

실패 시:
  BEGIN;
    -- job FAILED 전이
    -- error_code 기록
  COMMIT;
```

결과 저장 + COMPLETED 전이 + 이벤트 발행이 같은 트랜잭션에 있어야
부분 완료(partial commit)를 방지.

---

## 마이그레이션 전략

1. `calculation_results` 테이블 생성
2. `result_ready` topic/queue 생성
3. ApiResponseWorker에 결과 저장 + 이벤트 발행 추가
4. EquipmentResponse DTO 참조 제거 (cache bridge 이관)
5. 레거시 worker 비활성화 (feature flag)
6. 검증: 기존 경로와 신규 경로 병행 운영 후 전환
