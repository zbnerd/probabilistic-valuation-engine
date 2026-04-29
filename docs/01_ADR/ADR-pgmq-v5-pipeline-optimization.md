# ADR: V5 Pipeline Optimization — Spike 방지 + Structural Latency 개선

**날짜:** 2026-04-30
**상태:** Proposed
**기반 데이터:** 10K load test app.log (92,331 slow events)

---

## 배경

V5 expectation API의 miss path는 2-hop PGMQ 구조로 동작한다.

```text
GET /api/v5/characters/{userIGN}/expectation?presetNo=N
  → 캐시 조회 (PostgreSQL character_valuation_views)
  → MISS → expectation_calc_high 큐 적재 → 202 Accepted
  → ExpectationCalcWorker (라우팅만)
  → external_api_queue 적재
  → ExternalApiWorker (OCID + 장비 API + 계산 + projection)
  → character_valuation_views UPSERT
  → 다음 GET에서 캐시 HIT → 200 OK
```

10K load test에서 두 종류의 병목이 확인되었다.

---

## 병목 1: Spike 병목 — TimeoutScanner 중복 dispatch

### 현상

```text
TimeoutScanner: avg=43,643ms, max=281,029ms, 총 2,357s
→ 30초마다 stale job 스캔
→ CAS 없이 retry dispatch
→ external_api_queue에 중복 메시지 폭등
→ AdvisoryLock 경합 증폭
→ p99/max spike (max=7,294ms)
```

시간 window 상관관계에서 TimeoutScanner와 OCID_RESOLVE_TIMEOUT, AdvisoryLock이 항상 동시 발생:

```text
04:34:00  slow_count=1192  TimeoutScanner=279, OCID_RESOLVE_TIMEOUT=277, AdvisoryLock=137
04:36:00  slow_count=1159  TimeoutScanner=426, OCID_RESOLVE_TIMEOUT=426, AdvisoryLock=102
```

### 원인

TimeoutScanner가 CAS 없이 stale job을 retry dispatch하면서, 정상 처리 중인 job의 메시지와 중복 메시지가 external_api_queue에 섞여들어감. 중복 메시지가 worker pool을 점유하고 AdvisoryLock 경합을 증폭시켜 cascading 지연 발생.

### 해결 (PR #781에서 1차 적용 완료)

1. 재시도 경로를 legacy topic에서 통합 external_api_queue로 변경
2. dispatch 전 job status 재확인 (CAS check)
3. scan당 batch limit 20건
4. stale threshold 상향 (30s→120s)

### 추가 개선 필요

- `findJobById` N+1 → `findJobsByIds` IN 쿼리
- `UPDATE ... WHERE status = 'OCID_RESOLVING'` CAS 조건 추가

---

## 병목 2: Structural Latency — 2-hop PGMQ 구조

### 현상

```text
miss 요청 하나가 통과하는 직렬 boundary:
  PGMQ hop 2개
  DB 상태 전이 16회
  외부 API 2회 직렬
  AdvisoryLock 경합
  View projection
  Archive 단건 ack

결과: p50=707ms, p95=1,291ms, p99=1,784ms, max=7,294ms
```

### 원인: ExpectationCalcWorker가 "라우팅만" 하는 구조

현재 ExpectationCalcWorker의 실제 작업:

```kotlin
// ExpectationCalcWorker.process()
fun process(message) {
    val job = jobService.createJob(null, userIGN, presetNo)      // DB INSERT
    jobService.dispatchToExternalApi(jobId, userIGN, presetNo)   // DB UPDATE + PGMQ send
    return true                                                   // → pgmqClient.archive()
}
```

비용: PGMQ read + DB INSERT + DB UPDATE + PGMQ send + PGMQ archive = ~300ms + DB 3회

이 중 **실제 계산이나 API 호출이 없다.** 순수 라우팅 오버헤드.

### 해결

```text
Before:
  Controller → expectation_calc_high → ExpectationCalcWorker (라우팅) → external_api_queue → ExternalApiWorker

After:
  Controller → job 생성 + external_api_high/low publish → 202
  ExternalApiWorker가 직접 소비
```

또는 Controller에서 직접:

```text
Controller → job 생성 + external_api_queue publish → 202
ExternalApiWorker가 job claim 포함하여 처리
```

우선순위가 필요하면:

```text
external_api_high → ExternalApiWorker (16 threads)
external_api_low  → ExternalApiWorker (4 threads)
```

### 예상 효과

- expectation_calc_high slow task **통째로 제거** (총 3,910s + archive 98s)
- DB/PGMQ 부하 감소: 요청당 DB 3회 + PGMQ read/send/archive 절약
- ExternalApiWorker 자체 p50은 큰 변화 없을 수 있으나, **시스템 전체 부하와 중복 경합 감소**

### 보정: "p50 400ms 이하"는 보장이 아님

2-hop 제거로 ExternalApiWorker 내부 pipeline (OCID + API + DB writes)은 그대로 남음.
p50 개선은 시스템 부하 감소로 인한 간접 효과. 직접 측정 필요.

---

## 병목 3: ExternalApiWorker 직렬 DB write

### 현상

```text
ExternalApiWorker.processPipeline()당 DB write 7회:
  1. findJobById (SELECT, early exit check)
  2. resolveOcidInPlace (UPDATE)
  3. snapshotStore.put (file write)
  4. saveSnapshotInPlace (INSERT)
  5. markSnapshotReadyInPlace (UPDATE)
  6. completeCalculationWithResult (UPDATE + INSERT + INSERT)
  7. upsertFromCalculation (UPSERT)
  + pgmqClient.archive (DELETE + INSERT)

총 ~16회 DB 왕복
```

### 해결 방향

**critical write를 service transaction으로 묶기:**

```text
트랜잭션 1: snapshot metadata + calculation_input + job status update
트랜잭션 2: result save + outbox event
트랜잭션 3 (critical path에서 분리): view projection
```

ViewProjection은 결과 유실 방지에 필수가 아니라 read model write이므로 critical path에서 분리.

---

## 병목 4: Nexon API 직렬 호출

### 현상

```text
OCID 조회 (~200ms) → 장비 조회 (~300ms) = 총 ~500ms 외부 API 대기
```

### 보정: 병렬화 불가

장비 조회가 OCID를 필요로 하므로, OCID miss 시 두 API 호출의 병렬화는 **불가능**.

```text
resolveOcid(userIGN) → ocid 획득 → fetchEquipment(ocid)  // 의존성 존재
```

### 해결 방향

병렬화가 아니라 **OCID durable cache hit rate 향상**:

```text
character_identity(user_ign, ocid, resolved_at)
→ cache hit 시 Nexon OCID API 호출 자체 제거 → ~200ms 절약
```

현재 L1/L2 캐시가 있으나 miss율이 높음. DB-backed durable cache로 보완.

---

## 병목 5: AdvisoryLock 경합

### 현상

```text
AdvisoryLock:ElectLeader avg ~280ms, max ~732ms
→ 동일 ocid에 대해 16 worker 중 1명만 leader
→ 나머지 15명이 100ms 간격 DB polling
```

### 보정: Lock 자체가 원인이 아님

Single-flight 패턴은 맞음. 732ms spike의 근본 원인은 **동일 userIgn/ocid 중복 요청 과다**.

```text
같은 userIGN+presetNo가 이미 REQUESTED/PROCESSING 상태면
새 job 생성하지 말고 기존 jobId 반환
```

in-flight dedup이 경합의 근본 원인 제거.

---

## 합의된 실행 순서

| 순서 | 작업 | 유형 | 근거 |
|------|------|------|------|
| **1** | TimeoutScanner CAS + N+1 제거 | P0 | p99/max spike 원인, queue 오염 방지 |
| **2** | expectation_calc_high hop 제거 | P1 | structural latency, DB/PGMQ 부하 감소 |
| **3** | same userIGN+presetNo in-flight dedup | P2 | AdvisoryLock 경합 근본 제거 |
| **4** | OCID durable cache 강화 | P2 | 외부 API 호출 감소 |
| **5** | ViewProjection critical path 분리 | P2 | ExternalApiWorker pipeline 경량화 |
| **6** | archive batch화 | P2 | PGMQ ack 비용 감소 |

### 순서의 이유

```
TimeoutScanner = 폭발 방지 (spike 안정화)
2-hop 제거 = 기본 체력 개선 (상시 비용 감소)
나머지 = 누적 최적화
```

TimeoutScanner를 먼저 잡지 않으면, 2-hop 제거나 다른 최적화의 효과가 중복 메시지 폭풍에 가려져 측정이 어려움.

---

## 측정 계획

각 단계별 before/after 측정:

```bash
# 측정 스크립트
python3 scripts/analyze_slow_tasks_with_source.py --log module-app/logs/app.log --repo . -o docs/05_Reports/slow-task-before.md

# load test
python3 load_test_v5.py

# 측정
python3 scripts/analyze_slow_tasks_with_source.py --log module-app/logs/app.log --repo . -o docs/05_Reports/slow-task-after.md
```

핵심 지표:

| 지표 | 현재 | 목표 |
|------|------|------|
| TimeoutScanner max | 281,029ms | < 5,000ms |
| expectation_calc_high total | 3,910s | 0s (제거) |
| Pipeline p50 | 707ms | 측정 후 결정 |
| Pipeline p99 | 1,784ms | < 1,000ms |
| Pipeline max | 7,294ms | < 3,000ms |
| 중복 처리율 | ~40% | < 1% |
