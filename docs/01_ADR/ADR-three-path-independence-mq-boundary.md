# ADR: Three-Path Independence — MQ-Only Boundary

**Status**: Proposed
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

1. **Read Path**: HTTP + 조회 + enqueue only. 외부 API 직접 호출 금지.
2. **Write Path**: 비동기 상태머신 + 저장 + View Sync. 외부 API 직접 호출 금지. API 응답을 **기다리지 않는다**.
3. **External API Path**: 외부 API 호출 + Rate Limit + Retry only. 비즈니스 로직 없음.
4. **Path 간 통신**: MQ only. 동기 대기 금지.
5. **Write는 External API 응답을 기다리지 않는다.** 응답 메시지를 받아 계산을 재개한다.

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
    equipment_data  JSONB,                   -- API 응답 캐시 (data_ref 대체)
    character_class VARCHAR(64),
    calculation_result JSONB,                -- 최종 계산 결과
    error_message   TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_calc_jobs_status ON calculation_jobs (status);
CREATE INDEX idx_calc_jobs_ocid ON calculation_jobs (ocid, preset_no);
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
  "idempotency_key": "ocid:event_type",
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

금지:
  ❌ NexonApiClient 직접/간접 호출
  ❌ 계산 로직
  ❌ EquipmentDataProvider / EquipmentFetchProvider
  ❌ @Transactional WRITE
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
