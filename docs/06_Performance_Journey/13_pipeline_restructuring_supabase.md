# 13장: 파이프라인 재구성과 Supabase 마이그레이션 — 205개 커밋의 삽질 여정

> "완벽한 설계도 현장에서는 깨진다. 중요한 건 깨진 이유를 아는 거다."

## 한 줄 요약

```
2026년 4월 20일: 단일 파이프라인, Vultr DB, ~10 views/sec
2026년 5월 3일: 3-stage PGMQ 파이프라인, Supabase Pooler, ~30-38 views/sec
```

2주간 205개 커밋, 15개 PR, 수차례 롤백. 최종 성과는 **3-4배 처리량 향상**. 숫자 자체보다 과정에서 배운 게 많았다.

## 워크로드 규모

이 시스템이 처리하는 워크로드:

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

이 시스템은 두 가지 물리적 제약 조건 위에서 설계되었다.

### 제약 조건 1: 외부 API Rate Limit

```
Nexon API: 초당 500건, 하루 2,000만 건 (Key 1개 기준)
→ 멀티 Key 로테이션으로 한계 상향
→ 여전히 I/O latency 200~500ms/건은 피할 수 없음
```

### 제약 조건 2: DB 커넥션 풀 병목

```
Supabase Pooler 세션 모드: 물리적 연결 수 제한
→ External API I/O 대기 중 DB 커넥션을 점유하면 풀 고갈
→ pool=15로도 충분해야 하는 구조여야 함
```

### 설계 결정

| 제약 | 설계 결정 | 이유 |
|------|-----------|------|
| API Rate Limit | 멀티 Key 로테이션 | 단일 Key 한계 상향 |
| API I/O 200-500ms | External API 프로세스 완전 분리 | I/O 대기 중 DB 커넥션 점유 방지 |
| DB 커넥션 15개 | 3-stage PGMQ 파이프라인 | 각 스테이지가 커넥션을 짧게만 사용 |
| 단일 서버 한계 | SKIP LOCKED 기반 수평 확장 설계 | 인스턴스 추가 시 선형 확장 가능 |

### 결과

```
단일 노드 기준:
  초당 30건 (cold miss 기준)
  초당 6~9MB raw payload 처리
  하루 260만 건, 0.5~0.8TB

수평 확장 시:
  인스턴스 + API Key 추가 → 처리량 선형 증가 구조
```

### 면접 표현

```
"외부 API rate limit과 DB 커넥션 풀 병목이라는
두 가지 제약 조건 하에서,

External API 프로세스를 완전 분리하여
Nexon API I/O 대기 중 DB 커넥션을 점유하지 않는 구조로 설계했고,

결과적으로 단일 노드 기준
초당 30건, 6~9MB raw payload를 처리하는
배치 파이프라인을 구현했습니다.

인스턴스와 API Key 스케일아웃 시
처리량이 선형으로 증가하는 구조입니다."
```

**제약 조건 → 설계 결정 → 결과** 흐름이 기술 면접에서 원하는 답변 구조다.

---

## Phase 1: 파이프라인 분리 — 하나에서 셋으로 (#730 ~ #742)

### 시작 상태

계산 파이프라인이 하나의 거대한 워커에 몰려 있었다. 메시지 하나 처리하는 데 API 호출 + 계산 + 결과 저장 + View 갱신까지 직렬로 실행. 한 단계가 느리면 전체가 느려졌다.

### 작업 내역

| PR | 내용 | 결과 |
|----|------|------|
| #730 | Two-phase batch + PGMQ 성능 튜닝 | 배치 처리 기반 마련 |
| #731 | Follower 타임아웃 튜닝 | 리더/팔로워 동기화 안정화 |
| #733 | Semaphore(64) 제거 | 불필요한 동시성 제약 해소 |
| #734 | Bulk JDBC upsert for view | 개별 upsert → 배치 JDBC |
| #735 | Virtual Thread → FixedThreadPool | 핀닝 문제 회피 |
| #738 | BlockingSubmitExecutor 제거 | 불필요한 래핑 제거 |

### 교훈

파이프라인을 분리하면 각 단계를 독립적으로 튜닝할 수 있다. 하지만 분리 자체가 성능을 올려주진 않는다. **병목이 어디 있는지 먼저 알아야 분리가 의미 있다.**

---

## Phase 2: Compute Key Dedup (#743 ~ #750)

### 문제

같은 캐릭터의 같은 프리셋을 여러 워커가 동시에 계산하고 있었다. 큐에 중복 메시지가 들어오면 동일한 계산을 N번 실행.

### 작업 내역

| PR | 내용 |
|----|------|
| #743 | BatchComputeBuffer — 계산 키 기반 배치 중복 제거 |
| #744 | 동시성/API 수정 (Semaphore 제거, 응답 표준화) |
| #748 | PipelineBuffer backpressure 정렬 |
| #749 | Chunked parallel batch — 4x throughput |
| #750 | Time-window batch L2 lookup/write |

### 핵심 인사이트

```
Before: 1000 메시지 → 1000번 계산
After:  1000 메시지 → dedup 후 200번 계산 (80% 감소)
```

`AccumulationBuffer`로 500ms 윈도우 내 메시지를 모은 뒤 중복 키 제거. 같은 캐릭터의 10개 요청이 들어와도 1번만 계산.

---

## Phase 3: 파이프라인 또 분리 (#788 ~ #793)

### 문제

Phase 1에서 분리한 파이프라인이 여전히 한 워커에 너무 많은 책임을 짊어지고 있었다.

### 구조 변경

```
Before (단일 워커):
  메시지 수신 → API 호출 → 계산 → 결과 저장 → View 갱신

After (3-stage PGMQ):
  Stage 1: expectation_calc_high → 계산만 (CPU-bound)
  Stage 2: external_api_queue   → API 호출 + 결과 저장 (IO-bound)
  Stage 3: result_ready_queue   → View projection (DB-bound)
```

각 스테이지가 독립적으로 스케일링 가능. IO-bound와 CPU-bound를 분리.

### 트러블슈팅

Outbox 패턴을 시도했다가 롤백. PGMQ에 직접 send하는 게 더 단순하고 빨랐다.

```
시도: Outbox 테이블 + 폴링 → PGMQ send
문제: 폴링 레이턴시, 추가 DB write, 복잡도 증가
결정: PGMQ에 직접 send (PR #793)
```

---

## Phase 4: Kafka 시도와 롤백 (#791 ~ #792)

### 시도

PGMQ를 Kafka로 교체하면 더 높은 처리량과 내구성을 얻을 수 있을 거라고 판단.

```
PR #791: Kafka foundation (PR-1)
PR #792: Kafka business wiring (PR-2, PR-3)
```

### 롤백 이유

1. **로컬 개발 복잡도 폭발**: Kafka + ZooKeeper/KRaft 띄워야 함
2. **Supabase 환경과 부조화**: DB가 PGMQ를 이미 내장. Kafka는 또 다른 인프라
3. **PGMQ로 충분**: 현재 워크로드(30 TPS)에서 PGMQ가 병목이 아님
4. **운영 부채**: Kafka 클러스터 모니터링, 파티션 관리, 컨슈머 lag 처리

### 교훈

> **기술 선택은 "가능한가"가 아니라 "필요한가"로 결정해야 한다.**

PGMQ가 30 TPS를 처리하는 데 아무런 문제가 없었다. Kafka는 1000+ TPS에서 고려할 일.

---

## Phase 5: 병렬 계산과 Projection 최적화 (#794 ~ #796)

### 작업

| PR | 내용 | 효과 |
|----|------|------|
| #794 | Preset 내 아이템 병렬 계산 + async read model write | 계산 시간 단축 |
| #795 | ResultReadyProjectionWorker에서 dual DB 쿼리 병렬화 | 대기 시간 절반 |
| #796 | Projection field를 write 시점에 추출 | BYTEA decompress skip |

### #796의 핵심 아이디어

```
Before: View 조회 시 BYTEA → decompress → JSON parse → field extract (N번)
After:  결과 저장 시 미리 field 추출해서 별도 컬럼에 저장 (1번)
```

매 조회마다 반복하던 압축 해제 + 파싱을 write 시점 1회로 고정. 읽기 경로가 가벼워짐.

---

## Phase 6: PR #797 — DB 왕복 줄이기 시도와 롤백

### 시도

P0: 계산 전 DB write들을 하나의 트랜잭션으로 병합
P1: `createOrFindActiveJob` + `dispatchToExternalApi`를 단일 TX로

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

### 결과: 롤백

부하테스트에서 **성능 저하** 관측. ~7-13 views/sec로 오히려 느려짐.

### 원인 분석

외부 API(Nexon) 레이턴시가 지배적이어서, DB 최적화의 효과가 노이즈에 묻힘. DB 왕복을 3회에서 1회로 줄여도, 외부 API가 1-3초 걸리면 의미 없음.

### 교훈

> **병목이 아닌 곳을 최적화하면 측정 불가능하다. 외부 API 레이턴시가 지배적인 상황에서 DB 최적화는 무의미.**

---

## Phase 7: Supabase Pooler 마이그레이션

### 배경

Vultr 서버의 직접 PostgreSQL 연결에서 Supabase Pooler로 마이그레이션. 이유: 관리 부담 감소, connection pooling 외부화.

### 트러블슈팅 연대기

#### 문제 1: application-local.yml이 죽은 Vultr DB를 가리킴

```
[Lock Pool] JDBC URL: jdbc:postgresql://158.247.218.6:5432/maple_expectation
→ SocketTimeoutException: Connect timed out
```

`DB_SERVER_IP=158.247.218.6` (구 Vultr)이 여전히 .env에 설정되어 있었다.

**해결**: `application-local.yml`을 `${DB_URL}` 사용하도록 변경. `DB_USER`를 `postgres.ekcgdvwipcdfllhsqwjn`으로 업데이트.

#### 문제 2: LockHikariConfig의 minimumIdle = poolSize

```kotlin
config.minimumIdle = poolSize  // 20개 커넥션을 startup에 강제 생성
```

Supabase Pooler 세션 모드는 동시 연결 수에 제한이 있다. 메인 풀(15개) + Lock 풀(20개) = 35개를 동시에 열려고 하면 연결 거부.

**해결**: `minimumIdle = 2`, `initializationFailTimeout = -1` (lazy init)

#### 문제 3: cache_storage 테이블 누락

```
ERROR: relation "cache_storage" does not exist
```

이전 커밋 시점에 migration이 아직 실행되지 않았던 테이블.

**해결**: 수동 CREATE UNLOGGED TABLE

#### 문제 4: JDBC URL credential vs Spring Boot username/password

```
DB_URL: jdbc:postgresql://...?user=postgres.ekcgdvwipcdfllhsqwjn&password=xxx
spring.datasource.username: maple (from DB_USER)
```

Spring Boot가 `username` 프로퍼티로 URL의 `user` 파라미터를 오버라이드. Supabase Pooler는 `postgres.{project_ref}` 형식의 username을 요구.

**해결**: develop의 LockHikariConfig에서 URL 파라미터를 직접 파싱하는 `parseCredentialsFromUrl()` 추가.

### Supabase Pooler 아키텍처

```
Application (HikariCP)
    ↓ JDBC
Supabase Pooler (PgBouncer)
    ↓ 세션 모드 (port 5432)  ← 우리가 사용
    또는 트랜잭션 모드 (port 6543)
PostgreSQL (Supabase 관리)
```

세션 모드: 커넥션당 1:1 매핑. prepared statement, advisory lock 사용 가능. 하지만 연결 수 제한.
트랜잭션 모드: 트랜잭션 단위로 커넥션 할당. 더 많은 연결 허용. 하지만 session-level 기능 제한.

---

## Phase 8: Connection Pool 튜닝 — 15 vs 50

### 실험

| 설정 | pool=15 | pool=50 |
|------|---------|---------|
| Views (6샘플) | 3,105 | 813 |
| 최고 views/sec | 37.8 | 10.3 |
| 안정 views/sec | 9-12 | 0-8.8 |
| result_ready_queue | 52→864 (누적) | 90→154 (제어됨) |

### 결과: pool=15가 더 나음

Supabase Pooler 세션 모드의 물리적 한계. 커넥션을 많이 물수록 pooler의 세션 슬롯을 차지하고, 새 커넥션 생성 비용도 증가.

### 병목의 실체

```
ResultProjection:ProjectBatch (배치 30건):
  loadCalculationResults:  1,000~7,500ms  ← 병목
  batchUpsertViews:        600~2,400ms
  archiveMessages:         90~400ms
```

커넥션 풀 크기가 문제가 아니라, **Supabase Pooler를 경유한 쿼리 자체의 레이턴시**가 병목. 로컬 DB였으면 1초면 끝날 게 7.5초까지 걸림.

---

## 기술 의사결정 기록

### 1. Kafka → PGMQ 유지

```
판단 기준:
- 현재 처리량 (30 TPS)에서 PGMQ가 병목이 아님
- Kafka는 1000+ TPS에서 재검토
- 인프라 복잡도 최소화가 우선
```

### 2. Vultr 직접 DB → Supabase Pooler

```
판단 기준:
- DB 관리 부담 감소
- Connection pooling 외부화
- 단점: 쿼리 레이턴시 증가 (pooler hop)
- 완화: 쿼리 수 자체를 줄이는 방향으로 최적화
```

### 3. Connection Pool 크기

```
판단 기준:
- Supabase 세션 모드: 연결 수에 물리적 제한
- pool=15가 pool=50보다 실제 처리량 높음
- 과도한 커넥션은 pooler 세션 고갈 유발
```

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

### 1. 병목이 아닌 곳을 최적화하면 측정 불가능하다

PR #797의 DB 왕복 최적화는 논리적으로 맞았지만, 외부 API 레이턴시가 지배적이라 효과가 측정되지 않았다. **최적화 전에 "이게 전체의 몇 %를 차지하는가?"를 먼저 물어야 한다.**

### 2. 인프라 변경은 모든 가정을 무효화한다

Vultr → Supabase 마이그레이션 후, 기존에 문제없던 커넥션 풀 설정이 장애를 일으켰다. **인프라 변경 후에는 모든 설정을 재검증해야 한다.**

### 3. 롤백은 실패가 아니라 데이터다

Kafka 시도, Outbox 시도, DB 왑복 병합 — 모두 롤백했다. 하지만 각 시도에서 무엇이 문제였는지 명확해졌다. **"왜 안 되는가"를 아는 것도 "되게 하는 것"만큼 가치 있다.**

### 4. 측정하지 않으면 모르는 것을 안다고 착각한다

pool=50이 pool=15보다 나을 거라는 직관이 있었다. 부하테스트 결과는 정반대. **직관은 가설일 뿐, 측정이 판결이다.**

### 5. 단일 노드의 한계를 아는 것도 가치다

30-38 views/sec가 이 시스템의 단일 노드 한계에 가깝다. 외부 API 레이턴시 + Supabase Pooler 레이턴시라는 두 개의 외부 병목이 있다. 이 위에서 DB 최적화를 아무리 해도 한계가 명확하다. **수평 확장이 필요한 시점을 아는 것도 아키텍처 역량이다.**

---

## 현재 아키텍처

```
Client Request
    ↓
Controller (202 Accepted)
    ↓
PGMQ: expectation_calc_high → [계산만, CPU-bound]
    ↓
PGMQ: external_api_queue   → [Nexon API 호출, IO-bound]
    ↓
PGMQ: result_ready_queue   → [View projection, DB-bound]
    ↓
character_valuation_views (Read Model)
```

**DB**: Supabase PostgreSQL (Pooler session mode, port 5432)
**Message Queue**: PGMQ (PostgreSQL 내장)
**Connection Pool**: HikariCP main=15, lock=20
**Workers**: 24 threads (PGMQ worker-pool-size)
**Throughput**: ~30 views/sec, 6~9MB/sec (cold miss), 캐시 hit 시 ~7,000 RPS
