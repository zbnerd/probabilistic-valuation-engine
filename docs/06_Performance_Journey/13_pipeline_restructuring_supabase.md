# 13장: 파이프라인 재구성과 Supabase 마이그레이션 — 136개 커밋의 삽질 여정

> "완벽한 설계도 현장에서는 깨진다. 중요한 건 깨진 이유를 아는 거다."

## 한 줄 요약

```
2026년 4월 20일: 단일 파이프라인, Vultr DB, ~10 views/sec
2026년 5월 3일:  3-stage PGMQ 파이프라인, Supabase Pooler, ~30-38 views/sec
```

2주간 136개 커밋, 수차례 롤백. **단일 노드 한계에 도달했고, 다음 단계로 External API 프로세스 분리 + 비동기 데이터 처리 파이프라인 전환을 설계 중.**

## 워크로드 규모

```
30 TPS (초당 30건)
= 하루 약 260만 건
× 건당 200~300KB 외부 API payload
= 초당 6~9MB 처리
= 하루 약 0.5~0.8TB raw data ingress

처리 경로:
다운로드 → 파싱 → 정규화 → 확률 계산 → artifact 저장 → DB metadata → projection → view 동기화
```

단순 INSERT 260만 건이 아니라, 건당 수백 KB의 외부 API 응답을 실시간으로 수집·파싱·계산·저장하는 파이프라인. CPU, 네트워크 I/O, DB I/O가 동시에 바쁘다.

---

## 설계 결정 — 제약 조건 → 선택 → 결과

> "숫자보다 '왜 이 구조를 선택했는가'가 더 중요하다."
>
> 아래 Phase는 커밋 시간순이 아니라, 병목과 의사결정 주제별로 재구성했다.

### 제약 조건

```
1. 외부 API Rate Limit: 초당 500건, 하루 2,000만 건 (Key 1개)
   → 멀티 Key 로테이션으로 한계 상향, 여전히 I/O 200~500ms/건은 불가피

2. DB 커넥션 풀 병목: Supabase Pooler 세션 모드 물리적 연결 수 제한
   → External API I/O 대기 중 worker slot 점유 시 후속 처리 지연
```

### 설계 결정

| 제약 | 설계 결정 | 이유 |
|------|-----------|------|
| API Rate Limit | 멀티 Key 로테이션 | 단일 Key 한계 상향 |
| API I/O 200-500ms | External API 프로세스 완전 분리 | API I/O 대기 중 worker slot 점유 방지 |
| DB 커넥션 15개 | 3-stage PGMQ 파이프라인 | 각 스테이지가 커넥션을 짧게만 사용 |
| 단일 서버 한계 | stage별 독립 확장 설계 | 병목 stage만 수평 확장 가능 |

### 결과

```
단일 노드: 초당 30건, 6~9MB, 하루 260만 건 / 0.5~0.8TB
수평 확장: External API stage는 인스턴스 + API Key 단위로 확장 가능
주의: 전체 completed TPS는 DB write/projection stage 처리량에 의해 제한됨
```

### 면접 표현

```
"외부 API rate limit과 DB 커넥션 풀 병목이라는
두 가지 제약 조건 하에서,

External API 프로세스를 완전 분리하여
API I/O 대기 중 DB 커넥션을 점유하지 않는 구조로 설계했고,

결과적으로 단일 노드 기준
초당 30건, 6~9MB raw payload를 처리하는
외부 API 기반 비동기 데이터 처리 파이프라인을 구현했습니다.

External API stage는 인스턴스와 API Key를 추가해 확장 가능한 구조로 설계했습니다.
다만 전체 처리량은 가장 느린 stage가 결정하므로,
stage별 queue lag와 DB write latency를 기준으로 병목을 판단했습니다."
```

**제약 조건 → 설계 결정 → 결과** 흐름이 기술 면접에서 원하는 답변 구조다.

---

## Phase 0: 인프라 기반 — Supabase DB URL 통일 (#766)

### 작업

모든 Spring 프로필(`local`, `prod`, `vultr`, `ci`)이 각자 다른 DB 설정을 가지고 있었다. `DB_SERVER_IP`, `DB_ROOT_PASSWORD` 등 환경변수가 분산.

**`c5031951 chore: unify all profiles to use only DB_URL from .env`**

모든 프로필을 `DB_URL` 단일 환경변수로 통일. LockHikariConfig에서도 `username`/`password`를 직접 받지 않고 JDBC URL에서 파싱.

```kotlin
// Before: 각 프로필마다 다른 DB 설정
url: jdbc:postgresql://${DB_SERVER_IP:localhost}:5432/maple_expectation
username: maple
password: ${DB_ROOT_PASSWORD}

// After: 단일 DB_URL
url: ${DB_URL}
// LockHikariConfig: URL에서 user/password 자동 추출
private fun parseCredentialsFromUrl(url: String): Pair<String?, String?>
```

### 교훈

인프라 마이그레이션(Vultr → Supabase)을 하려면 먼저 설정을 단일 소스로 통일해야 했다. 이 작업이 없었다면 이후 모든 디버깅이 2배는 더 걸렸을 것.

---

## Phase 1: External API 경계 분리 — State Machine 구축 (#767 ~ #769)

### 문제

기존엔 API 호출이 서비스 로직 내부에 직접 박혀 있었다. API 호출이 실패하면 어떤 상태인지, 재시도해야 하는지 알 수 없었다.

### 작업 내역 (21개 커밋)

```
9a111ecb feat(schema): add calculation_jobs, snapshots tables and API request/response queues
39721d29 feat(core): add CalculationJobStatus, CalculationJob, CalculationSnapshot domain models
c007b2c0 feat(core): add SnapshotObjectStore, CalculationJobPort interfaces and queue names
008cb2e2 feat(infra): add CalculationJob/Snapshot JPA entities and repositories
c1f36860 feat(infra): add LocalSnapshotObjectStore with GZIP compression
11f63666 feat(infra): add NexonApiWorker — API call, snapshot save, response publish
f906e24b feat(infra): add CalculationJobService state machine and API message DTOs
1ecddd97 feat(infra): add CalculationJobTimeoutScanner for stale job recovery
```

### 핵심 아이디어

API 호출을 별도 워커(`NexonApiWorker`)로 분리하고, 작업 상태를 `CalculationJob` 엔티티로 추적.

```
기존: 서비스 로직 → 직접 API 호출 → 성공/실패 모호
이후: 서비스 → Job 생성 → PGMQ: external_api_queue → NexonApiWorker → 결과 publish
      ↑                                       ↑
      상태 추적 (REQUESTED/PROCESSING/DONE)    실패 시 재시도 가능
```

`CalculationJobTimeoutScanner`로 stale job(5분 이상 PROCESSING)을 자동 감지해 재시도 큐로 복귀.

### 추가 작업: 재시도, 동시성 제어, 멱등성

```
5dcffece fix(critical): retry re-enqueue, job lifecycle completion, status guards
a1476fb3 fix(operational): write concurrency limit, snapshot TX atomicity, TTL cleanup, idempotency guard
```

재시도 시 재큐잉, 스냅샷 트랜잭션 원자성, TTL 기반 정리, 멱등성 가드까지 한 PR에 4개修正. 이런 "한 번에 여러 수정"이 인프라 경계 분리 시 흔한 패턴.

### OCID Resolve Pipeline

```
70276e81 feat(pipeline): replace sync fallback with fully async OCID resolve pipeline
```

OCID(캐릭터 고유 ID) 해석도 동기 fallback에서 완전 비동기 파이프라인으로 전환. API 호출 → OCID 해석 → 다음 단계까지 모두 MQ 기반.

---

## Phase 2: MQ 추상화 레이어 (#770)

### 작업 (12개 커밋)

PGMQ에 직접 의존하던 코드를 추상화.

```
1d266fff feat(mq): add MQTopicGroup and DomainEventAppender interfaces
1c22d351 feat(mq): add PgmqEventAppender with @Transactional for same-TX publishing
1f7b4d41 feat(mq): add 3 concrete PGMQ topic classes with per-topic config
9d4c836b feat(mq): add EventFactory objects for OCID resolve, API request, API response
848722e6 refactor(mq): replace pgmqClient direct calls with DomainEventAppender
06aeff43 refactor(mq): migrate 3 workers to MQTopicGroup.subscribe() callback pattern
```

### 핵심 인사이트

`PgmqEventAppender`에 `@Transactional`을 붙여서 **같은 트랜잭션 내에서 메시지 발행**. DB write와 MQ send가 원자적으로 처리됨. 이게 Kafka 시도의 동기이기도 했음 — Kafka에선 이게 기본 제공.

---

## Phase 3: Write Path — 순수 계산 분리 (#771 ~ #773)

### 문제

계산이 ExternalApiWorker 내부에서 실행되고 있었다. API 호출(네트워크) + 계산(CPU) + 결과 저장(DB)이 한 워커에 섞여 있음.

### 작업 (20개 커밋)

```
871894c3 feat(db): add calculation_snapshot_inputs, calculation_results, outbox_events tables
fd2b3b62 feat(core): add CalculationInput typed contract model with tests
99054b01 feat(write-path): add PureExpectationCalculator — pure CalculationInput to response
70b518fc feat(write-path): add EquipmentItemConverter for pure calculation input
c7d2d638 refactor(write-path): replace .join() blocking with pure calculator in ApiResponseWorker
```

### 핵심: PureExpectationCalculator

```kotlin
// Before: API Worker 내부에서 계산 (네트워크 + CPU 혼합)
fun runCalculationAndComplete(...) {
    val response = callNexonApi(...)      // 네트워크 I/O
    val result = calculateExpectation(...)  // CPU
    saveResult(result)                      // DB write
}

// After: 계산만 하는 순수 함수 분리
class PureExpectationCalculator {
    fun calculate(input: CalculationInput): ExpectationResponse {
        // 네트워크 I/O 없음. 순수 계산만.
    }
}
```

CalculationInput → ExpectationResponse로 가는 순수 함수를 만들어서, API Worker는 I/O만, 계산은 별도로.

### OutboxRelayWorker

```
07d06a1d feat(write-path): add OutboxRelayWorker for event publishing
148a7882 feat(write-path): add Compensating Scanner for orphaned events
```

결과 저장 시 Outbox 테이블에 이벤트를 남기고, `OutboxRelayWorker`가 비동기로 PGMQ에 publish. `Compensating Scanner`로 유실된 이벤트(저장은 됐는데 publish가 안 된 경우)를 주기적으로 복구.

---

## Phase 4: 코드 품질 — Rules 도입과 위반 수정 (#774 ~ #776)

### CLAUDE.md 모듈화

```
724e1abe refactor: split CLAUDE.md into modular .claude/rules/ structure
48d11a48 refactor: add enforcement policy and self-check to CLAUDE.md
```

단일 CLAUDE.md를 `.claude/rules/` 디렉토리로 분리. 15개 규칙 파일로 관리.

### 비동기 패턴 위반 수정

```
47302470 fix: eliminate async pattern violations (join/get/runBlocking/raw Thread/synchronized)
```

코드 전체에서 `join()`, `get()`, `runBlocking`, `new Thread()`, `synchronized` 제거. 모두 CompletableFuture 체이닝 또는 ReentrantLock으로 대체.

### 코드 규칙 위반 수정

```
28bc1605 fix: apply code rules violations fix (null safety, Thread.sleep, try-catch)
```

`!!` (non-null assertion), `Thread.sleep()`, `try-catch` 블록 전면 제거.

---

## Phase 5: 파이프라인 최적화 — 큐 통합과 2-hop 제거 (#777 ~ #788)

### 작업

```
ff56a596 refactor: remove Read Path ADR boundary violations (Phase 2) (#777)
083a19ee refactor: consolidate 5 PGMQ queues into 3 with ExternalApiWorker (#779)
2d90af1e Fix/disable listen notify reduce pool supabase (#780)
f827f5d0 refactor: V5 pipeline optimization — 2-hop removal, CAS scanner, OCID cache (#782)
3d6c4757 refactor: consolidate ExternalApiWorker DB calls from 5 TX to 3 TX (#783)
792ce58b refactor: optimize DB write path — eliminate SELECT, move CPU work outside TX (#784)
9ac3c36f refactor: pipeline optimization — sync calculator, snapshot overlap, thenCompose chain (#784)
076b9566 refactor: switch Nexon API calls to virtual thread executor (#784)
```

### 핵심 최적화

**5큐 → 3큐 통합 (#779):**

```
Before: expectation_calc_high, external_api_queue, ocid_resolve_queue,
        nexon_api_request_queue, nexon_api_response_queue, result_ready_queue

After:  expectation_calc_high, external_api_queue, result_ready_queue
```

OCID 해석, API 요청/응답을 `ExternalApiWorker` 하나로 통합. 큐 hop이 줄어들어 end-to-end latency 감소.

**2-hop 제거 (#782):**

메시지가 큐를 거치는 hop 수를 줄임. 기존 4-5hop → 3hop.

**DB 왕복 5 TX → 3 TX (#783):**

ExternalApiWorker 내부의 DB 호출을 5개 트랜잭션에서 3개로 축소.

**TX 밖으로 CPU 이동 (#784):**

```kotlin
// Before
transactionTemplate.execute {
    val result = heavyCalculation()  // CPU 작업이 TX 안에 있음 → 커넥션 점유
    saveResult(result)
}

// After
val result = heavyCalculation()  // TX 밖에서 CPU 작업
transactionTemplate.execute {
    saveResult(result)  // TX 안에서는 DB write만
}
```

CPU 작업을 TX 밖으로 빼서 DB 커넥션 점유 시간 최소화.

**Supabase LISTEN/NOTIFY 비활성화 (#780):**

Supabase Pooler 환경에서 LISTEN/NOTIFY가 커넥션을 잡아먹어서 비활성화. 폴링 기반으로 전환.

---

## Phase 6: 파이프라인 분리 — Dedup과 Projection (#787 ~ #793)

### 작업

```
20066612 perf: split cache dedup and projection stages (#787)
6e2e5c26 perf: split calculation pipeline workers (#788)
086c34d0 perf: consolidate calculation pipeline — inline API + calculation + result write (#788)
12299236 refactor(projection): replace result_ready PGMQ hop with outbox polling
2966e033 perf(projection): batch outbox publish marking and parallelize projection
4ccae513 revert: rollback develop to 2966e033 (PGMQ 46-52 views/sec baseline)
81962d43 refactor: replace outbox events with direct PGMQ send in completion path (#793)
```

### Outbox 시도 → 롤백

```
시도: Outbox 테이블 + 폴링 → PGMQ send
이유: DB write와 MQ publish의 원자성 보장
문제: 폴링 레이턴시, 추가 DB write, 복잡도 증가
결과: 46-52 views/sec 베이스라인에서 성능 저하 관측 → 롤백
교정: PGMQ에 직접 send (#793)
```

### 교훈

Outbox 패턴이 "정석"이긴 하지만, PGMQ를 쓰는 환경에서는 over-engineering. PGMQ 자체가 PostgreSQL 내장이라 same-TX publish가 가능.

---

## Phase 7: Kafka 시도와 롤백 (#791 ~ #792)

### 시도

```
4bbfe5b1 docs: add Kafka pipeline transition plan and edge cases design
e9dd8cc0 feat(kafka): add Kafka foundation for pipeline transition (PR-1)
aa819296 feat(kafka): implement external-api.requested business connection (PR-2)
1f716c33 feat(kafka): implement calculation.requested consumer (PR-3)
```

### 롤백 이유

1. **로컬 개발 복잡도 폭발**: Kafka + ZooKeeper/KRaft 띄워야 함
2. **Supabase 환경과 부조화**: DB가 PGMQ를 이미 내장
3. **PGMQ로 충분**: 30 TPS에서 PGMQ가 병목이 아님
4. **운영 부채**: Kafka 클러스터 모니터링, 파티션 관리, 컨슈머 lag

### 교훈

> **기술 선택은 "가능한가"가 아니라 "필요한가"로 결정해야 한다.**

현재 단일 노드 30 TPS 목표에서는 Kafka 도입 비용이 이득보다 컸다. 따라서 운영 복잡도를 줄이기 위해 PGMQ를 유지했다. 다만 External API stage를 다중 인스턴스로 확장하고, 대용량 payload를 Object Storage artifact로 분리하는 단계에서는 Kafka를 재검토 대상으로 남겼다.

---

## Phase 8: Compute Key Dedup (#743 ~ #750)

### 문제

같은 캐릭터의 같은 프리셋을 여러 워커가 동시에 계산. 큐에 중복 메시지가 들어오면 동일한 계산을 N번 실행.

### 작업

```
2d80fc78 feat(#743): add CubeComputeKey for batch dedup
ca8c3006 feat(#743): integrate CubeComputeBuffer into CubeServiceImpl for batch dedup
3bf16495 feat(#743): wire BatchComputeBuffer into workers for per-batch clearing
f6c39a3b feat(pgmq): add AccumulationBuffer for time-based message batching
2a4e6827 feat(pgmq): sequential batch processing with time-window accumulation
c0258ce7 feat(pgmq): add sequentialBatchMs config for time-window batching
9f19cbb1 perf(pgmq): chunked parallel batch for 4x throughput improvement
```

### 핵심 인사이트

```
Before: 1000 메시지 → 1000번 계산
After:  1000 메시지 → dedup 후 200번 계산 (80% 감소)
```

`AccumulationBuffer`로 500ms 윈도우 내 메시지를 모은 뒤 중복 키 제거. 같은 캐릭터의 10개 요청이 들어와도 1번만 계산.

---

## Phase 9: 병렬 계산과 Projection 최적화 (#794 ~ #796)

### 작업

```
fc8b8b48 perf: parallelize per-item calculation in PresetCalculationHelper.calculatePreset
6526e334 perf: async read model write + remove upsert version check
db0606b0 perf: parallelize dual DB queries in ResultReadyProjectionWorker
c961de4d perf: extract projection fields at write time to skip BYTEA decompress
e1f322be perf: use light query to skip BYTEA transfer in projection read path
a5b96069 perf: replace CAST(:presets AS jsonb) with PGobject to skip per-row JSON parsing
```

### #796의 핵심 아이디어

```
Before: View 조회 시 BYTEA → decompress → JSON parse → field extract (N번)
After:  결과 저장 시 미리 field 추출해서 별도 컬럼에 저장 (1번)
```

매 조회마다 반복하던 압축 해제 + 파싱을 write 시점 1회로 고정. 읽기 경로가 가벼워짐.

`PGobject` 교체로 per-row `CAST(:presets AS jsonb)` SQL 파싱도 제거. JDBC 드라이버 레벨에서 직접 JSONB 타입으로 전송.

---

## Phase 10: PR #797 — DB 왕복 줄이기 시도와 롤백

### 시도

```kotlin
// P0: 여러 번의 DB write를 한 번으로
fun saveAllPreCalculation(...) {
    transactionTemplate.execute {
        saveInput()
        saveSnapshots()
        saveJobStatus()
    }
}
```

### 결과: 롤백 (~7-13 views/sec)

### 원인 분석

**단순히 "노이즈에 묻혔다"가 아니라, 병목 위치를 잘못 판단한 것.**

커넥션 풀 45개 제약 하에서 DB 왕복을 16회 → 11회로 줄이는 것보다, Supabase Pooler 경유 레이턴시 자체가 더 큰 병목이었다. TX 병합으로 커넥션을 더 오래 잡게 되어, 오히려 동시성이 떨어짐.

### 교훈

> **병목이 아닌 곳을 최적화하면 측정 불가능하다. 정확히는 — 최적화 효과가 측정되지 않은 게 아니라, 병목 위치를 잘못 판단했던 것.**

---

## Phase 11: Supabase Pooler 마이그레이션 + Pool 튜닝

### Vultr → Supabase 전환

```
이유: DB 관리 부담 감소, connection pooling 외부화
아키텍처:
  Application (HikariCP) → Supabase Pooler (PgBouncer) → PostgreSQL
  세션 모드 (port 5432): 커넥션당 1:1 매핑, advisory lock 사용 가능, 연결 수 제한
```

### 트러블슈팅 연대기

**문제 1: application-local.yml이 죽은 Vultr DB를 가리킴**
```
[Lock Pool] JDBC URL: jdbc:postgresql://158.247.218.6:5432/maple_expectation
→ SocketTimeoutException: Connect timed out
해결: DB_URL 환경변수로 통일 (#766)
```

**문제 2: LockHikariConfig의 minimumIdle = poolSize**
```
20개 커넥션을 startup에 강제 생성 → Supabase 세션 제한 초과
해결: minimumIdle = 2, initializationFailTimeout = -1 (lazy init)
```

**문제 3: cache_storage 테이블 누락**
```
ERROR: relation "cache_storage" does not exist
해결: 수동 CREATE UNLOGGED TABLE
```

**문제 4: JDBC URL credential vs Spring Boot username/password**
```
DB_URL: ?user=postgres.ekcgdvwipcdfllhsqwjn&password=xxx
Spring Boot: username=DB_USER=postgres (오버라이드)
해결: parseCredentialsFromUrl()로 URL에서 직접 추출
```

### Pool 튜닝: 15 vs 50

| 설정 | pool=15 | pool=50 |
|------|---------|---------|
| Views (6샘플) | 3,105 | 813 |
| 최고 views/sec | 37.8 | 10.3 |
| 안정 views/sec | 9-12 | 0-8.8 |
| result_ready_queue | 52→864 (누적) | 90→154 (제어됨) |

**pool=15가 더 나음.** Supabase 세션 모드의 물리적 한계. 커넥션 많을수록 pooler 세션 슬롯 점유 증가.

### 병목의 실체

```
ResultProjection:ProjectBatch (배치 30건):
  loadCalculationResults:  1,000~7,500ms  ← Supabase Pooler 경유 레이턴시
  batchUpsertViews:        600~2,400ms
  archiveMessages:         90~400ms
```

로컬 DB였으면 1초면 끝날 게 7.5초까지 걸림. 커넥션 풀 크기가 아니라 **Pooler hop 자체의 레이턴시**가 병목.

---

## 롤백 기록 정리

| 시도 | 이유 | 롤백 사유 | 교훈 |
|------|------|-----------|------|
| Outbox 폴링 (#788) | DB write + MQ publish 원자성 | 폴링 레이턴시, 복잡도, 성능 저하 | PGMQ에 same-TX publish 가능 |
| Kafka (#791-792) | 더 높은 처리량/내구성 | 30 TPS에 과잉, 인프라 복잡도 | "필요한가?"로 결정 |
| DB 왕복 병합 (#797) | DB 왕복 16→11회 | 병목 위치 오판, TX 병합이 동시성 저하 | 병목이 아닌 곳 최적화 무의미 |
| IS DISTINCT FROM (#791) | unchanged skip | 비교 자체가 비용, 실제 skip 비율 낮음 | 가드 비용 > 절약 |
| Virtual Thread (#735) | I/O 블로킹 최적화 | synchronized 핀닝 | ReentrantLock으로 대체 |

---

## 전체 타임라인

```
2026-04-20  파이프라인 분리 시작 (#730)
2026-04-22  PipelineBuffer backpressure, Virtual Thread 제거
2026-04-23  Compute key dedup (BatchComputeBuffer)
2026-04-24  Sequential batch, Chunked parallel batch
2026-04-25  Batch L2 lookup/write
2026-04-27  View bulk JDBC upsert, preset 병렬 계산
2026-04-28  Hexagonal architecture 위반 수정
2026-04-29  Pipeline 3-stage 분리
2026-04-30  Outbox 시도 → PGMQ 직접 send로 롤백
2026-05-01  Kafka 시도 → PGMQ 유지로 롤백
2026-05-01  IS DISTINCT FROM guard 제거 (불필요한 비교)
2026-05-02  StepTrace slow task 분석 시스템 구축
2026-05-02  Dual DB 쿼리 병렬화 (#795)
2026-05-03  Projection field 추출 (#796)
2026-05-03  DB 왕복 줄이기 시도 → 롤백 (#797)
2026-05-03  Supabase Pooler 마이그레이션 + pool 튜닝
```

---

## 2주간의 교훈

### 1. 병목 위치를 잘못 판단하면 최적화가 역효과

PR #797은 논리적으로 맞았지만, 실제 병목(Supabase Pooler 레이턴시)이 아니라 가장 눈에 보이는 곳(DB 왕복)을 최적화했다. **최적화 전에 "이게 전체의 몇 %를 차지하는가?"를 먼저 물어야 한다.**

### 2. 인프라 변경은 모든 가정을 무효화한다

Vultr → Supabase 후, 기존에 문제없던 커넥션 풀 설정이 장애를 일으켰다. `minimumIdle=poolSize`, `DB_SERVER_IP` 하드코딩, `username` 오버라이드 — 모두 이전 인프라에서는 정상 동작했다.

### 3. 롤백은 실패가 아니라 데이터다

Kafka, Outbox, DB 왕복 병합, IS DISTINCT FROM — 모두 롤백했다. 하지만 각 시도에서 "왜 안 되는가"가 명확해졌다. **PGMQ가 30 TPS에서 충분하다**는 확신은 Kafka 롤백 이후에야 확보됐다.

### 4. 직관은 가설일 뿐, 측정이 판결이다

pool=50이 pool=15보다 나을 거라는 직관. 부하테스트 결과는 정반대. StepTrace로 병목을 단계별로 측정하고 나서야 원인을 이해했다.

### 5. 수평 확장이 필요한 시점을 아는 것도 아키텍처 역량

30-38 views/sec가 단일 노드 한계. 외부 API 레이턴시 + Supabase Pooler 레이턴시라는 두 외부 병목 위에서 DB 최적화를 아무리 해도 한계가 명확하다.

---

## 현재 한계와 다음 방향

### 현재 한계

```
1. Supabase Pooler 레이턴시 (외부 제어 불가)
   - loadCalculationResults: 1~7.5초 (로컬 DB면 1초 미만)
   - 배치 30건 projection에 2~10초

2. Nexon API I/O 대기로 인한 worker slot 점유
   - API 호출 200~500ms 동안 ExternalApiWorker slot이 점유됨
   - API latency가 증가하면 queue drain 속도가 하락
   - DB 작업 자체보다 API I/O와 worker lifecycle이 결합된 것이 문제

3. 단일 노드 처리량 상한
   - pool=15: ~30 views/sec (cold miss)
   - pool=50: ~10 views/sec (역효과)
```

### 다음 방향

```
External API 프로세스 완전 분리
→ API I/O 대기를 DB 커넥션과 완전히 분리
→ 배치 스케줄러 기반 API 호출

Object Storage 기반 artifact 파이프라인 전환
→ 외부 API raw response와 calculation input/result를 Object Storage에 저장
→ DB는 artifact URI/hash/schemaVersion과 job state만 관리
→ worker는 DB 상태를 SKIP LOCKED로 claim하여 수평 확장
```

**성장 서사**: 한계를 발견했고, 원인을 분석했고, 다음 아키텍처를 설계하고 있다.

---

## 현재 아키텍처

```
Client Request
    ↓
Controller (202 Accepted)
    ↓
PGMQ: expectation_calc_high
    → Job 생성 / 중복 제거 / external_api_queue dispatch
    ↓
PGMQ: external_api_queue
    → Nexon API 호출 / snapshot 저장 / calculation input staging
    ↓
Calculation / Result Persist
    → Pure calculation / result 저장 / result_ready publish
    ↓
PGMQ: result_ready_queue
    → View projection / batch upsert
    ↓
character_valuation_views (Read Model)
```

**DB**: Supabase PostgreSQL (Pooler session mode, port 5432)
**Message Queue**: PGMQ (PostgreSQL 내장)
**Connection Pool**: HikariCP main=15, lock=20
**Workers**: 24 threads (PGMQ worker-pool-size)
**Throughput**: ~30 views/sec, 6~9MB/sec (cold miss), 캐시 hit 시 ~7,000 RPS
