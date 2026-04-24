# ADR: Three-Path Independence — MQ-Only Boundary

**Status**: Approved with Changes (review 2026-04-24)
**Date**: 2026-04-24
**Context**: V5 CQRS Architecture

---

## Decision

세 개의 독립 실행 패스(Read, Write, External API)로 분리하고, 패스 간 통신은 PGMQ 메시지 큐로만 수행한다.

---

## Problem

현재 Read / Write / External API가 하나의 JVM에 혼재:

```
Read Path ──→ EquipmentExpectationServiceV4 ←── External API Path
Write Path ──→ EquipmentExpectationServiceV4 ←── External API Path
EquipmentExpectationServiceV4: 7가지 역할 (God Class)
공유 상태: NexonApiClient, CacheManager, LogicExecutor, HikariCP, RateLimiter (6개+)
```

### 구체적 문제

1. **God Class**: EquipmentExpectationServiceV4가 캐릭터 조회, API 호출, 파싱, 계산, 캐싱, 영속화, View 동기화를 모두 담당
2. **공유 상태**: 하나의 Bean 장애가 전체 JVM에 영향 (예: RateLimiter 고갈 → Read 응답 지연)
3. **독립 배포 불가**: 같은 JAR, 같은 클래스패스, Write 변경 시 Read도 재배포
4. **독립 스케일 불가**: CPU 4코어를 Tomcat + Worker + API Client가 공유

---

## Target Architecture

```
┌──────────────┐  MQ(1)  ┌──────────────┐  MQ(2)  ┌──────────────┐
│  Read Path   │────────→│  Write Path  │────────→│ External API │
│              │         │              │         │    Path      │
│ HTTP 요청    │         │ 상태머신     │         │ Nexon 호출   │
│ View 조회    │         │ 계산/파싱    │         │ Rate Limit   │
│ 큐잉만       │         │ DB 저장      │         │ Retry/CB     │
│              │         │ View Sync    │         │              │
│ 독립 배포    │         │ 독립 배포    │         │ 독립 배포    │
│ 독립 스케일  │         │ 독립 스케일  │         │ 독립 스케일  │
└──────────────┘         └──────┬───────┘         └──────┬───────┘
                               │                         │
                               └─────────┬───────────────┘
                                         │
                              공유: PostgreSQL, PGMQ
```

---

## Core Principles

1. **Read Path**: HTTP + 조회 + enqueue only. 외부 API 직접 호출 금지. business data write를 금지하되 `calculation_jobs` 생성과 `calculation_queue` enqueue는 허용한다.
2. **Write Path**: 비동기 상태머신 + 저장 + View Sync. 외부 API 직접 호출 금지. API 응답을 **기다리지 않는다**.
3. **External API Path**: 외부 API 호출 + Rate Limit + Retry only. 비즈니스 로직 없음.
4. **Path 간 통신**: MQ only. 동기 대기 금지.
5. **Write는 External API 응답을 기다리지 않는다.** 응답 메시지를 받아 계산을 재개한다.
6. **상태 전이는 원자적이어야 한다.** 모든 상태 변경은 conditional UPDATE로 수행하여 중복 응답, 지연 응답, 재시도 충돌을 방지한다.

---

## State Machine (Write Path)

### 상태 전이

```
                    calculation_queue 소비
                           │
                           ▼
                      ┌─────────┐
                      │REQUESTED │  ← job 생성, 캐릭터 조회
                      └────┬─────┘
                           │ API 데이터 필요 시
                           ▼
                      ┌──────────────┐
                      │API_REQUESTED │  ← nexon_api_request_queue 발행
                      └────┬─────────┘
                           │
            ┌──────────────┼──────────────┐
            │ response     │ timeout      │ error
            ▼              ▼              ▼
     ┌─────────────┐ ┌──────────┐  ┌──────────┐
     │API_RESPONDED│ │ RETRYING │  │  FAILED  │
     └──────┬──────┘ └─────┬────┘  └──────────┘
            │               │ 재시도    │
            │               └──→ API_REQUESTED
            ▼
     ┌──────────────┐
     │ CALCULATING  │  ← 파싱 + 계산
     └──────┬───────┘
            │
            ▼
     ┌──────────────┐
     │  COMPLETED   │  ← DB 저장 + View Sync
     └──────────────┘
```

### 핵심: Write는 기다리지 않는다

```
BAD (동기 대기):
  Write → API 요청 → response_queue 폴링 → 10s 대기 → 계산 재개
  → Worker thread 점유, timeout 복잡, correlation 관리 부담

GOOD (비동기 상태머신):
  Write → API 요청 발행 → job 상태 API_REQUESTED로 전환 → ack (thread 해제)
  ... (시간 경과) ...
  Write → response_queue 소비 → job 상태 조회 → API_RESPONDED → 계산 재개
  → Worker thread 점유 없음, 재시작 시 상태 복구 가능, 장애 격리 완전
```

---

## State Table: `calculation_jobs`

```sql
CREATE TABLE calculation_jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ocid            VARCHAR(64) NOT NULL,
    user_ign        VARCHAR(64) NOT NULL,
    preset_no       INT DEFAULT 1,
    status          VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    api_request_id  UUID,                    -- nexon_api_request와 correlation
    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 3,
    next_retry_at   TIMESTAMPTZ,             -- 다음 재시도 시각 (exponential backoff)
    locked_by       VARCHAR(128),            -- 처리 중인 worker 식별자
    locked_until    TIMESTAMPTZ,             -- 잠금 만료 시각 (worker 장애 대비)
    last_error_code VARCHAR(64),             -- 가장 최근 에러 코드
    equipment_data  JSONB,                   -- API 응답 (data_ref로 조회 후 캐시)
    character_class VARCHAR(64),
    calculation_result JSONB,                -- 최종 계산 결과
    error_message   TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_calc_jobs_status ON calculation_jobs (status) WHERE status NOT IN ('COMPLETED');
CREATE INDEX idx_calc_jobs_ocid ON calculation_jobs (ocid, preset_no);
CREATE INDEX idx_calc_jobs_stale ON calculation_jobs (locked_until) WHERE locked_until IS NOT NULL AND status NOT IN ('COMPLETED', 'FAILED');
```

### 원자적 상태 전이 (Atomic State Transitions)

모든 상태 변경은 conditional UPDATE로 수행한다. 예상 상태가 아니면 0 row 업데이트 → 중복/지연/충돌 자동 방지.

```sql
-- 예: API 응답 수신 시 — 반드시 API_REQUESTED 상태에서만 전이
UPDATE calculation_jobs
SET status = 'API_RESPONDED',
    locked_by = NULL,
    locked_until = NULL,
    updated_at = now()
WHERE job_id = :jobId
  AND api_request_id = :requestId   -- correlation 보장
  AND status = 'API_REQUESTED';     -- 예상 상태 일치해야 함
-- affected rows = 0 이면 중복/지연 응답 → 무시

-- 예: Job 잠금 — 동시에 두 worker가 같은 job을 잡지 않도록
UPDATE calculation_jobs
SET locked_by = :workerId,
    locked_until = now() + INTERVAL '5 minutes',
    status = 'CALCULATING',
    updated_at = now()
WHERE job_id = :jobId
  AND status = 'API_RESPONDED'
  AND (locked_until IS NULL OR locked_until < now());
-- affected rows = 0 이면 이미 다른 worker가 처리 중

-- 예: 재시도 스케줄링
UPDATE calculation_jobs
SET status = 'API_REQUESTED',
    retry_count = retry_count + 1,
    next_retry_at = now() + :backoffInterval,
    last_error_code = :errorCode,
    locked_by = NULL,
    locked_until = NULL,
    updated_at = now()
WHERE job_id = :jobId
  AND status IN ('API_REQUESTED', 'RETRYING')
  AND retry_count < max_retries;
```

---

## MQ Contracts

### MQ(1): `calculation_queue` (기존, 변경 최소화)

```
Read → Write
목적: "이 캐릭터 계산해줘"

발행: Read Path (Controller, cache miss 시)
소비: Write Path (CalculationWorker)

Payload:
{
  "job_id": "uuid",              -- 신규: calculation_jobs PK
  "ocid": "abc123",
  "user_ign": "닉네임",
  "preset_no": 1,
  "force_recalculation": false,
  "requested_at": "2026-04-24T10:00:00Z"
}
```

### MQ(2): `nexon_api_request_queue` (신규)

```
Write → External API
목적: "이 데이터 가져와줘"

발행: Write Path (job 상태 API_REQUESTED 전환 시)
소비: External API Path (NexonApiWorker)

Payload:
{
  "request_id": "uuid",           -- 개별 API 요청 고유 ID
  "job_id": "uuid",               -- calculation_jobs FK
  "event_type": "FETCH_EQUIPMENT", -- FETCH_BASIC | FETCH_OCID
  "ocid": "abc123",
  "idempotency_key": "job_id:event_type:attempt",
  "attempt": 1,
  "requested_at": "..."
}
```

### MQ(3): `nexon_api_response_queue` (신규)

```
External API → Write
목적: "API 결과 알려줌"

발행: External API Path (API 응답 수신 후)
소비: Write Path (ApiResponseWorker — 신규)

Payload (data_ref 방식):
{
  "request_id": "uuid",
  "job_id": "uuid",
  "event_type": "FETCH_EQUIPMENT",
  "status": "SUCCESS",
  "data_ref": "raw_equipment:abc123:2026-04-24",  -- 참조 키
  "error_code": null,
  "error_message": null
}
```

**주의: 250KB API 응답을 MQ에 직접 넣지 않는다.**
- API 응답은 `nexon_raw_data` 테이블(또는 유사)에 저장
- MQ에는 `data_ref`만 전달
- Write Path는 `data_ref`로 DB에서 조회

### Raw Data TTL & Cleanup Policy

250KB × 90 TPS = ~22MB/s. 관리 없이 쌓이면 DB bloat 발생.

| 항목 | 정책 |
|------|------|
| 보관 기간 | 24시간 (계산 재시도에 충분한 여유) |
| 중복 저장 기준 | `request_id` UNIQUE 제약 (동일 요청은 한 번만 저장) |
| 삭제 방식 | `pg_cron` 또는 application scheduled task로 `DELETE FROM nexon_raw_data WHERE created_at < now() - INTERVAL '24 hours'` 매시간 실행 |
| 용량 추정 | 250KB × 90/s × 86400s = ~1.9TB/day. TTL 없이는 불가 → 24h 유지 시 ~1.9TB 중 최신 24h만 = 약 1.9TB (과도). 실제로는 캐시 적중률을 고려하면 API MISS 건만 raw 저장 → 예상 ~10-20GB/day |

**최적화**: External API Path에서 이미 캐시된 데이터(`@NexonDataCache` HIT)는 raw data에 다시 저장하지 않는다. `data_ref`에 캐시 히트 여부를 포함하여 Write Path가 L2 캐시에서 직접 조회하도록 한다.

---

## Idempotency Guarantees

MQ는 at-least-once 전달을 기본으로 한다. 메시지 중복은 정상 케이스이며, Consumer/DB 레벨에서 멱등성을 보장해야 한다.

### 원칙

1. **모든 메시지는 `request_id` / `job_id` 포함** — 중복 감지의 기준
2. **모든 Consumer는 중복 메시지를 정상 케이스로 처리** — 실패가 아니라 ack
3. **DB write는 unique key + conditional update** — 이미 처리된 건은 0 row update → 무시
4. **외부 API 호출 결과 저장은 upsert** — `request_id` UNIQUE 제약
5. **이미 처리된 메시지는 실패가 아니라 ack** — PGMQ archive 처리

### 큐별 멱등성 전략

#### `calculation_queue` (Read → Write)

```
위험: 같은 캐릭터 계산 요청이 중복 enqueue
방어: calculation_jobs UNIQUE(ocid, preset_no, force_recalculation, created_at_bucket)
      → INSERT ON CONFLICT DO NOTHING → 기존 job_id 재사용
```

#### `nexon_api_request_queue` (Write → External API)

```
위험: API 요청이 중복 소비 → Nexon API 두 번 호출
방어: nexon_raw_data에 request_id UNIQUE 제약
      → INSERT ON CONFLICT DO NOTHING → 기존 결과 재사용
      → 응답만 response_queue에 다시 발행
```

#### `nexon_api_response_queue` (External API → Write)

```
위험: 응답 메시지 중복 → 계산 두 번 실행
방어: conditional UPDATE (가장 중요)

UPDATE calculation_jobs
SET status = 'API_RESPONDED',
    locked_by = NULL,
    locked_until = NULL,
    updated_at = now()
WHERE job_id = :jobId
  AND api_request_id = :requestId
  AND status = 'API_REQUESTED';

affected rows = 0 → 이미 처리됨 → ack (archive)
affected rows = 1 → 최초 처리 → 계산 재개
```

### 결과 저장 멱등성

```
위험: COMPLETED 상태에서 같은 job 재처리
방어: calculation_jobs에 UNIQUE(ocid, preset_no, status)
      → COMPLETED인 job은 재계산 불가
      → force_recalculation=true인 경우에만 기존 COMPLETED 무시
```

---

## Operational Considerations

> MQ 분리는 장애 격리는 해주지만, 상태·순서·중복·재시도·관측성을 직접 설계해야 안전하다.

### 1. 메시지 순서 보장

같은 `job_id`에 대해 늦은 응답이 먼저 처리될 수 있다. `api_request_id`, `attempt`로 오래된 메시지를 무시한다.

```sql
-- 응답 처리 시 attempt 확인
UPDATE calculation_jobs
SET status = 'API_RESPONDED', ...
WHERE job_id = :jobId
  AND api_request_id = :requestId
  AND status = 'API_REQUESTED';
-- 0 rows = stale/duplicate response → ack and discard
```

### 2. Poison Message / DLQ

계속 실패하는 메시지가 큐를 막는 것을 방지. `max_retries` 초과 시 DLQ로 이동하고 job은 `FAILED` 처리.

```
NexonApiWorker: API 호출 실패 → retry_count++
retry_count >= max_retries → nexon_retry_queue(기존 DLQ)로 archive
CalculationWorker: job retry_count >= max_retries → status = FAILED
```

### 3. Backpressure

External API가 느려지면 `nexon_api_request_queue`가 계속 쌓인다. 큐 깊이 기준으로 Read Path에서 신규 enqueue 제한.

```
backpressure 기준:
- nexon_api_request_queue depth > 500 → Read Path에서 503 Service Unavailable
- nexon_api_request_queue depth > 200 → 202 응답은 유지하되 warning 로그
- calculation_queue depth > 1000 → enqueue 거부 ( 이미 유사 로직 있음: TaskReceipt.rejected)
```

### 4. Timeout Job Scanner

응답 메시지가 영원히 안 올 수 있다. `API_REQUESTED` 상태에서 `updated_at + timeout`이 지난 job을 자동으로 `RETRYING`/`FAILED`로 전환.

```sql
-- Scheduled task (30초 간격)
UPDATE calculation_jobs
SET status = 'RETRYING',
    retry_count = retry_count + 1,
    next_retry_at = now() + :backoffInterval,
    last_error_code = 'API_TIMEOUT',
    locked_by = NULL,
    locked_until = NULL,
    updated_at = now()
WHERE status = 'API_REQUESTED'
  AND updated_at < now() - INTERVAL '30 seconds'
  AND retry_count < max_retries;

-- max_retries 초과 건은 FAILED로 전환
UPDATE calculation_jobs
SET status = 'FAILED',
    error_message = 'API response timeout after max retries',
    completed_at = now(),
    updated_at = now()
WHERE status IN ('API_REQUESTED', 'RETRYING')
  AND updated_at < now() - INTERVAL '30 seconds'
  AND retry_count >= max_retries;
```

### 5. Transaction Boundary

DB 상태 변경과 MQ 발행이 따로 놀면 유실된다. 가능하면 같은 DB 트랜잭션에서 job INSERT/UPDATE + PGMQ send.

```
Read Path (cache miss):
  BEGIN;
    INSERT INTO calculation_jobs (...) VALUES (...);  -- job 생성
    SELECT pgmq.send('calculation_queue', ...);       -- 같은 TX에서 enqueue
  COMMIT;
  → job_id를 202 응답에 포함

Write Path (API 요청):
  BEGIN;
    UPDATE calculation_jobs SET status = 'API_REQUESTED' WHERE ...;
    SELECT pgmq.send('nexon_api_request_queue', ...);  -- 같은 TX에서 발행
  COMMIT;
```

PGMQ는 PostgreSQL extension이므로 같은 트랜잭션에서 send가 가능하다. 이게 PGMQ를 선택한 핵심 이유.

### 6. Observability

분리 후 디버깅이 어려워지므로 `job_id`, `request_id`, `ocid`를 모든 로그/메트릭/메시지에 포함.

```
로그: [job_id=abc][request_id=def][ocid=ghi] API request sent
메트릭: nexon.api.latency{job_id=abc, endpoint=equipment, attempt=1}
MDC: job_id, request_id, ocid → 모든 로그에 자동 포함
```

### 7. Queue SLO Metrics

단순 API latency보다 큐 대기 시간이 중요해진다.

```
측정 항목:
- queue_wait_time: 메시지 enqueue → consume 시간
- job_total_duration: job REQUESTED → COMPLETED 시간
- retry_count_histogram: 재시도 횟수 분포
- dlq_count: DLQ 이동 건수 (alert 임계치 필요)
- queue_depth: 각 큐별 현재 깊이 (backpressure 판단)
- job_status_distribution: 상태별 job 수 (stuck 탐지)
```

### 8. Raw Data 비용 관리

250KB 응답을 raw table에 저장 시 용량 급증.

```
전략:
- TTL: 24시간 후 자동 삭제 (scheduled cleanup)
- 압축: 저장 전 GZIP 압축 (250KB → ~30KB)
- 파티셔닝: created_at 기준 daily partition (cleanup 성능)
- 중복 제거: request_id UNIQUE 제약
- 캐시 히트 시 스킵: @NexonDataCache HIT이면 raw 저장 안 함
```

### 9. 중복 Job 폭증 방지 (Coalescing)

cache miss가 몰리면 같은 ocid 계산 job이 여러 개 생긴다.

```sql
-- Read Path에서 job 생성 시 중복 방지
INSERT INTO calculation_jobs (ocid, user_ign, preset_no, status)
VALUES (:ocid, :userIgn, :presetNo, 'REQUESTED')
ON CONFLICT (ocid, preset_no)  -- unique constraint
  WHERE status NOT IN ('COMPLETED', 'FAILED')
DO UPDATE SET updated_at = now()
RETURNING job_id;
-- 기존 job이 있으면 재사용, 없으면 새로 생성
```

### 10. 배포 호환성 (Schema Versioning)

세 프로세스가 동시에 배포되지 않을 수 있으므로 메시지 schema 버전 필드 추가.

```
모든 MQ 메시지 공통 필드:
{
  "schema_version": 1,
  "job_id": "uuid",
  "request_id": "uuid",
  "correlation_id": "uuid",
  "attempt": 1,
  "created_at": "2026-04-24T10:00:00Z"
}

Consumer 규칙:
- schema_version이 예상보다 높으면 ack + warning 로그
- schema_version이 예상보다 낮으면 호환성 유지 (optional 필드 무시)
```

---

## Cross-Cutting Checklist

구현 시 각 Phase에서 반드시 확인해야 할 항목:

- [ ] 모든 상태 전이가 conditional UPDATE인지 확인
- [ ] 모든 MQ 메시지에 `schema_version`, `job_id`, `request_id` 포함
- [ ] Timeout Job Scanner 구현 (30초 간경)
- [ ] Backpressure 기준 설정 및 Read Path 503 반환 로직
- [ ] DLQ 이동 시 `calculation_jobs.status = FAILED` 동기화
- [ ] Raw data cleanup scheduled task 구현
- [ ] Queue SLO 메트릭 수집 (wait_time, duration, depth)
- [ ] MDC에 job_id, request_id, ocid 자동 주입
- [ ] Job coalescing (같은 ocid 중복 job 방지)
- [ ] 배포 순서 무관하게 동작하는지 검증 (schema_version 기반)

---

## Path Boundary Definitions

### Read Path (API Server)

```
IN:
  - HTTP Request
  - PostgreSQL View (SELECT only)

OUT:
  - HTTP Response (200 or 202)
  - PGMQ: calculation_queue

포함:
  - Controllers (V5, V4)
  - CharacterViewQueryPort
  - CalculationQueuePort
  - Auth/JWT
  - OCID Resolution (cached only — DB/L2 조회만, API 호출 없음)

허용 WRITE:
  - calculation_jobs INSERT (REQUESTED 상태로 생성)
  - calculation_queue enqueue (같은 트랜잭션)

금지:
  ❌ NexonApiClient 직접/간접 호출
  ❌ 계산 로직
  ❌ EquipmentDataProvider / EquipmentFetchProvider
  ❌ business data WRITE (결과, View 등)
```

### Write Path (Calculation Worker)

```
IN:
  - PGMQ: calculation_queue (consume)
  - PGMQ: nexon_api_response_queue (consume)
  - PostgreSQL (READ: 캐릭터, job 조회)

OUT:
  - PGMQ: nexon_api_request_queue (API 요청)
  - PostgreSQL (WRITE: 결과, View upsert, job 상태 업데이트)

포함:
  - CalculationWorker (기존)
  - ApiResponseWorker (신규 — response_queue 소비)
  - CalculationJobService (신규 — 상태머신 관리)
  - EquipmentStreamingParser
  - PresetCalculationHelper
  - ExpectationPersistenceService
  - ViewTransformer + ViewService
  - GameCharacterService (조회/생성)
  - Calculators (CubeRate, Potential, Flame)

금지:
  ❌ NexonApiClient 직접 호출
  ❌ HTTP Controller
  ❌ WebClient (Nexon API용)
  ❌ RateLimiter / Bulkhead / Circuit Breaker (API용)
```

### External API Path (API Worker)

```
IN:
  - PGMQ: nexon_api_request_queue (consume)

OUT:
  - PGMQ: nexon_api_response_queue (발행)
  - Nexon API (HTTP outbound)
  - PostgreSQL (raw data 저장 — data_ref용)

포함:
  - NexonApiWorker (신규 — request_queue 소비)
  - RealNexonApiClient (WebClient)
  - NexonRateLimiter
  - Resilience4j (CB, Retry, Bulkhead)
  - MetricsNexonApiClientWrapper
  - PgmqFallbackPublisher (429 재시도)
  - NexonApiPgmqProcessor (기존 재시도 로직)
  - NexonFanOutBatchLoader (배치 API 호출)
  - NexonFanOutWorker (429 재시도)

금지:
  ❌ 계산 로직
  ❌ HTTP Controller (사용자 대상)
  ❌ EquipmentStreamingParser
  ❌ Business DB Write (View, results)
```

---

## Module Structure

```
module-core/       Port interfaces, Domain models, Events
                   (변경 최소화 — port interface 추가만)

module-infra/      PGMQ client, DB repositories, 공통 인프라
                   (공유 — 양쪽에서 사용)

module-read/       Controllers, Query ports, Auth
                   (신규 분리 — module-web에서 이관)

module-write/      Workers, Calculators, Persistence, State Machine
                   (신규 분리 — module-app에서 이관)

module-external/   Nexon API client, Resilience, Rate Limiting
                   (신규 분리 — module-infra/external에서 이관)
```

---

## Migration Strategy

### Phase 1: Write ↔ External API 경계 (가장 복잡, 가장 큰 이점)

1. `calculation_jobs` 상태 테이블 생성
2. `nexon_api_request_queue` / `nexon_api_response_queue` 생성
3. `NexonApiWorker` (request consumer) 구현
4. `ApiResponseWorker` (response consumer) 구현
5. `CalculationJobService` (상태머신) 구현
6. 기존 `EquipmentFetchProvider` 직접 호출 → MQ 발행으로 전환
7. 검증: 기존 동기 호출 경로와 신규 MQ 경로 병행 운영 (feature flag)

### Phase 2: Read ↔ Write 경계 (거의 완료)

1. V5 Controller에서 Pre-warm 기능 제거 (또는 별도 MQ로 이관)
2. Read Path에서 NexonApi 관련 Bean 의존 완전 제거
3. Profile 분리 검증

### Phase 3: 물리적 분리 (별도 JAR/프로세스)

1. module-read, module-write, module-external 분리 빌드
2. 독립 배포 파이프라인 구축
3. 독립 스케일 설정

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| MQ 왕복 레이턴시 (~30ms 추가) | 낮음 | 격리 이점이 30ms보다 큼. 이미 250ms API 호출이 지배적 |
| 상태머신 복잡도 | 중간 | `calculation_jobs` 테이블로 명시적 상태 관리. 재시작 시 복구 가능 |
| data_ref 조회 실패 | 낮음 | raw data 테이블에 TTL 부여. 실패 시 job 상태를 FAILED/RETRY로 전환 |
| PGMQ 메시지 유실 | 낮음 | PGMQ는 PostgreSQL 기반. transactional send 보장. DLQ + retry 존재 |
| 기존 동기 경로와의 호환성 | 중간 | Phase 1에서 feature flag로 병행 운영. 검증 후 전환 |

---

## Rejected Alternatives

1. **MQ Request-Response 동기 대기**: `waitForResponse(10s)` 방식. Worker thread 점유, timeout/retry 복잡도, 재시작 시 상태 복구 불가. 비동기 상태머신에 비해 이점이 없음.

2. **Profile 분리만 (코드 분리 없음)**: 같은 JAR, 같은 클래스패스. 독립 배포/스케일 불가. 공유 상태로 인한 장애 전파 그대로.

3. **External API를 공유 라이브러리로**: Write Path가 NexonApiClient를 직접 포함. 장애 격리 안 됨. API Rate Limit 고갈 시 Write Worker도 영향.

---

## Consequences

- **독립 배포**: Read/Write/External API 각각 다른 타이밍에 배포 가능
- **독립 스케일**: External API Path만 스케일업/다운 가능
- **장애 격리**: Nexon API 장애 → External API Path에만 영향, Read/Write는 정상 동작 (지연/재시도)
- **상태 복구**: Write Worker 재시작 시 `calculation_jobs`에서 상태 조회 후 재개
- **복잡도 증가**: 상태머신, MQ 메시지 3개, job 테이블 추가. 모니터링/디버깅 난이도 상승
