# 97 RPS 장애 상태 시스템을 병목 분석과 장애 대응을 통해 7,000 RPS까지 개선한 시스템 진화 프로젝트

```
캐시 미적용과 고비용 연산 구조로 97 RPS, p99 4,100ms, 59.7% 에러율의 장애 상태를 보이던 시스템을
부하 테스트 기반 병목 분석 → 애플리케이션 최적화 → PostgreSQL 단일 아키텍처 통합으로
7,000+ RPS, p99 36ms까지 개선한 성능 최적화 여정
```

> **일정:** 2025.12 ~ 2026.04 (5개월) | **기술 스택:** Java 21, Kotlin 2.1.0, Spring Boot 3.5.4, PostgreSQL | **인프라:** AWS t3.small (2 vCPU, 2GB RAM)
>
> **참여 인력:** 백엔드 1인 | **관련 문서:** [Like Domain Portfolio](./like-refactoring-portfolio.md), [Performance Journey](../06_Performance_Journey/README.md)

---

## 1. 캐시 미적용 및 요청당 고비용 연산 구조로 인해 97 RPS / p99 4,100ms / 59.7% 에러율 장애 상태 발생 → 부하 테스트 기반 병목 분석으로 성능 개선 방향 도출

<aside>

**캐시 미적용 상태에서 요청마다 외부 API 호출 + JSON 파싱 + 동기 DB 저장이 누적되어**
**p99 4,100ms, 에러율 59.7%의 장애 상태를 보였고,**
**측정 없이 추측만 하던 상황에서 부하 테스트로 4대 병목을 식별하여 최적화 방향을 설정한 과정**

</aside>

### 문제 (Problem)

로컬 개발 환경에서 장비 기대비용 API 응답이 2초씩 걸렸고, 동시 사용자 10명만 넘어도 타임아웃이 발생했다. 원인을 추측만 할 뿐, 측정된 데이터가 없었다.

**요청 흐름 (초기 병목 구간):**

```
Client → Controller
  → Redis 왕복 3~5회 (1~2ms × 5 = 5~10ms)
  → Executor.submit() 큐 대기 (50~100ms)
  → Nexon API 호출 (257ms)
  → 동기 DB 저장 (150ms)
  → JSON 직렬화 (50ms)
→ Response (총 2초+)
```

**비즈니스 임팩트:**
- 장비 기대비용 계산 서비스의 핵심 가치인 '정확한 계산 결과'를 응답 지연이 직접 훼손
- P95 4,100ms, 성공률 40.3%로 10건 중 6건이 실패
- 경쟁 커뮤니티(평균 200ms) 대비 10배 느린 응답

### 선택지 (Options)

- **A. 인프라 확장**: Redis 클러스터 + MySQL 읽기 복제본 추가
    - 장점: 빠른 대응
    - 단점: 월 $100+ 비용 (기존 $15 대비 6배), 근본 해결 아님
- **B. 애플리케이션 최적화**: 캐시 전략 개선 + 비동기 처리 + 쿼리 최적화
    - 장점: 비용 추가 없음, 근본 원인 해결
    - 단점: 시간 소요, 정확한 병목 식별 선행 필요
- **C. 직관 기반 개별 API 튜닝**: 측정 없이 추측으로 개별 최적화
    - 장점: 즉시 실행
    - 단점: 근거 없는 도박, Project 2에서 56% 회귀 경험

### 결정 (Decision)

**B. 애플리케이션 최적화를 선택**

**선택 이유:**
1. AWS t3.small(월 $15)에서 A안은 최소 $100으로 6배 이상 비용 상승
2. C안은 근거 없는 도박 — Project 2에서 56% 성능 회귀를 경험할 것임
3. 먼저 정확히 측정하고, 영향도가 큰 병목부터 해결하기로 결정
4. 5-Agent Council(Architecture/Performance/QA/SRE/Auditor 관점 체크리스트)로 자기 검증

### 구현 (Implementation)

Locust로 750명 동시 사용자, 5분간 부하 테스트를 실시하여 4대 병목을 순서대로 식별:

1. **Redis 네트워크 왕복**: 1~2ms × 3~5회/요청
2. **동기 DB 저장**: 15~30ms/요청
3. **Executor 스레드풀 포화**: core 4/max 8/queue 200
4. **N+1 쿼리**: 반복 DB 조회

### 결과 (Result)

```
부하테스트 결과 (Locust, 750 users, 5min):
┌─────────────────────────────────────────────┐
│  Total Requests:  67,148                     │
│  RPS (avg):       223 req/sec                │
│  Success Rate:    40.30%                     │
│  Failure Rate:    59.70%                     │
│  P50 Latency:     1,800ms                    │
│  P99 Latency:     4,100ms                    │
│  Max Latency:     9,608ms                    │
└─────────────────────────────────────────────┘
→ 4대 병목 식별 완료, 최적화 방향 설정
```

**핵심 교훈:** 측정 없는 최적화는 도박이다. "느리다"가 아니라 "P99가 4,100ms다"처럼 정량적 목표를 설정해야 한다.

---

## 2. 캐시 미스 시 동일 요청 중복 실행으로 외부 API 호출 및 연산 폭증 → p99 4,100ms 지연 → 병목 원인을 Cache Stampede로 정의

<aside>

**캐시 미스 시 동일 키에 대한 100개 요청이 각각 독립적으로 계산을 수행하여**
**인기 캐릭터 "아델"에 100명이 동시 요청하면 Nexon API를 100번 호출하는 Cache Stampede가 발생**
**이를 병목 원인으로 명확히 정의한 과정**

</aside>

### 문제 (Problem)

베이스라인 측정에서 식별된 4대 병목 중 가장 치명적인 문제는 Cache Stampede였다. 캐시 미스 시 동일 키에 대한 요청이 각각 독립적으로 계산을 수행했다.

**비즈니스 임팩트:**
- 캐시 미스 시 중복 API 호출로 외부 API(Nexon) Rate Limit 도달 위험
- API 차단 시 전체 서비스 마비
- 100번의 중복 API 호출이 p99 4,100ms 지연의 핵심 원인

```
Before (Cache Stampede):
Request 1 → Cache MISS → Nexon API 호출 (257ms)
Request 2 → Cache MISS → Nexon API 호출 (257ms)
Request 3 → Cache MISS → Nexon API 호출 (257ms)
...
Request 100 → Cache MISS → Nexon API 호출 (257ms)
→ 100번의 중복 API 호출!
```

### 선택지 (Options)

- **A. LocalSingleFlight**: JVM 레벨에서 Semaphore로 요청 병합 (추가 인프라 없음)
    - 장점: 추가 인프라 없이 JVM 내부에서 해결
    - 단점: Fast Path까지 blocking할 위험
- **B. Redis 분산락**: Redis로 동일 키 요청 직렬화
    - 장점: 분산 환경에서도 동작
    - 단점: Redis 왕복 비용, 추가 인프라
- **C. 캐시 만료 시간 조정**: TTL만 늘려 Stampede 회피
    - 장점: 구현 간단
    - 단점: 근본 해결 아님

### 결정 (Decision)

**A. LocalSingleFlight를 선택**

**선택 이유:**
1. 추가 인프라 없이 JVM 내부에서 해결 가능
2. Leader 1명만 계산하고 Follower는 결과를 공유받는 패턴이 이론적으로 완벽해 보임
3. Architecture 관점에서 "Semaphore가 Fast Path까지 blocking할 위험"을 스스로 지적했으나, Performance 관점의 '이론적 완벽성'에 기울여 진행

→ **결과적으로 56% 회귀를 경험하게 됨 (다음 장)**

### 구현 (Implementation)

Semaphore 기반 LocalSingleFlight를 구현:
- tryAcquire() 성공 시 Leader로 계산 수행
- 실패 시 Follower로 결과 대기
- 캐시 히트 여부와 관계없이 모든 요청이 이 경로를 거치도록 설계

```
After (LocalSingleFlight):
Request 1 → Semaphore.acquire() → Leader → Nexon API (257ms)
Request 2 → Semaphore.tryAcquire() 실패 → Follower → 대기
...
→ 문제: Cache HIT도 Semaphore blocking!
```

### 결과 (Result)

223 RPS → **97 RPS**. 최적화를 했더니 **56% 느려졌다.**

```
요청 경로 분석 (회귀 원인):
Before: Cache HIT → TieredCache.get() → 7ms 즉시 응답
After:  Cache HIT → Semaphore.acquire() 대기 → Leader 처리 → 490ms 지연
        ↑ 캐시에 이미 있는데도 blocking!
```

**핵심 교훈:** "이론적으로 완벽한 패턴"이라도 실제 워크로드(캐시 히트율 99%)에서는 재앙이 될 수 있다. Architecture 관점의 경고("Fast Path blocking 위험")가 옳았음을 확인. 측정 없는 최적화의 위험성을 몸소 체험.

---

## 3. SingleFlight 패턴 적용 시 캐시 히트 요청까지 blocking되는 구조적 문제로 p99 1,800ms 지연 발생 및 223 RPS → 97 RPS 성능 회귀

<aside>

**Cache Stampede 해결을 위해 도입한 SingleFlight가 캐시 히트(99% 요청)까지 blocking하여**
**7ms 응답이 490ms로 지연되고, 223 RPS → 97 RPS로 56% 성능 회귀가 발생한 원인 분석**
**→ 즉시 롤백하고 근본 원인을 Fast Path/Slow Path 혼합 문제로 정의**

</aside>

### 문제 (Problem)

SingleFlight 도입 후 56% 성능 회귀가 발생했다. 분석 결과, 99%가 캐시 히트인 상황에서 Semaphore가 캐시 히트마저 blocking한 것이 원인이었다.

**핵심 발견:**
- 캐시 히트(99% 요청): 7ms → 490ms (70배 지연)
- 캐시 미스(1% 요청): 정상 동작 (SingleFlight 본래 목적 달성)
- 즉, **minority(1%)를 위한 최적화가 majority(99%)에 피해를 준 구조**

### 선택지 (Options)

- **A. SingleFlight 유지 + Semaphore 설정 튜닝**
    - 장점: Cache Stampede는 해결
    - 단점: 근본 문제(Fast Path blocking) 해결 안 됨
- **B. 즉시 롤백 + Fast Path/Slow Path 분리 설계**
    - 장점: 99% 캐시 히트 경로를 보호하면서 1% 미스 경로만 제어
    - 단점: 설계 복잡도 증가
- **C. Semaphore → ConcurrentHashMap.computeIfAbsent() 전환**
    - 장점: 더 가벼운 동기화
    - 단점: 여전히 캐시 히트 경로에 동기화 오버헤드 존재

### 결정 (Decision)

**B. 즉시 롤백 후 Fast Path/Slow Path 분리 설계**

**선택 이유:**
1. 99% Hit 경로에서 synchronization overhead를 완전히 제거해야 함
2. 캐시 히트(99%)와 미스(1%)를 동일한 경로로 처리하는 것이 근본 원인
3. 즉시 롤백하여 223 RPS를 복구하고, Fast Path 분리(Section 4)로 재도전

### 구현 (Implementation)

1. **즉시 롤백**: LocalSingleFlight 코드를 revert하여 223 RPS 복구
2. **원인 분석**: 99%가 캐시 히트인 워크로드에서 Semaphore가 모든 요청을 직렬화한 것이 병목
3. **새 설계 방향**: 캐시 히트 시에는 synchronization 없이 직접 반환(Fast Path), 미스 시에만 SingleFlight(Slow Path)

### 결과 (Result)

- 97 RPS → 223 RPS 복구 (롤백)
- **Fast Path/Slow Path 분리**라는 핵심 설계 원칙을 확립
- Section 4(L1 Fast Path)와 Section 8(경로 분리 전략)의 직접적 계기가 됨

**핵심 교훈:** Fast Path(99% 히트)와 Slow Path(1% 미스)를 물리적으로 분리하면, minority optimization이 majority에 피해를 주는 것을 원천 차단할 수 있다. 이 원칙은 이후 모든 최적화의 기반이 됨.

---

## 4. 캐시 히트 시 직렬화 및 스레드풀 사용으로 p99 약 1,800ms 지연 발생 → L1 Fast Path(Zero-Copy) 적용으로 p99 1,800ms → 213ms 감소 및 97 RPS → 555 RPS 개선

<aside>

**캐시 히트인데도 응답에 200ms 이상 걸리는 병목을 Async Profiler로 추적하여**
**GZIP→JSON→Object→JSON→GZIP 이중 변환(300KB)이 원인임을 발견**
**L1 Fast Path Zero-Copy로 캐시 히트 시 직렬화·스레드풀을 완전히 우회하여 97→555 RPS 달성**

</aside>

### 문제 (Problem)

캐시 히트가 발생했는데도 응답에 200ms 이상 걸렸다.

**발견 과정:**
1. Async Profiler flamegraph에서 Executor.submit() 구간이 50~100ms 소요됨을 확인
2. JFR 이벤트 트래이싱으로 GZIP→JSON→Object→JSON→GZIP 이중 변환(300KB)을 추적
3. 캐시에 이미 GZIP 압축된 응답이 있는데 이를 풀었다가 다시 압축하는 것이 병목

```
병목 추적 (캐시 히트 시 요청 경로):
Client → Controller → Executor.submit() [50-100ms 대기]
  → L1.get() → GZIP 해제 → JSON 역직렬화 → Java Object
  → JSON 직렬화 → GZIP 재압축 → Response [총 200ms+]
     ↑ 이미 GZIP으로 있는데 풀었다가 다시 압축 중!
```

**비즈니스 임팩트:**
- 전체 요청의 99%가 캐시 히트이므로, 이 200ms 지연은 거의 모든 사용자에게 영향
- 경쟁 서비스 평균 50ms 대비 4배 느린 응답 지속

### 선택지 (Options)

- **A. Executor 스레드풀 확장**: 대기 시간 감소 시도
    - 장점: 간단한 변경
    - 단점: 근본 해결 아님, 직렬화 비용은 그대로
- **B. L1 Fast Path (Zero-Copy)**: 캐시 히트 시 스레드풀·직렬화 우회, GZIP byte[] 그대로 반환
    - 장점: 99% 요청에서 synchronization·직렬화 오버헤드 제거
    - 단점: Controller에서 캐시 전략 결정 필요 (계층 분리 약화)
- **C. 캐시 용량 증설**: 히트율 자체를 높이기
    - 장점: 히트율 향상
    - 단점: 200ms 지연 자체는 해결 안 됨

### 결정 (Decision)

**B. L1 Fast Path (Zero-Copy)를 선택**

**선택 이유:**
1. Section 3에서 확립한 Fast Path/Slow Path 분리 원칙의 구현
2. 캐시에 이미 GZIP 압축된 응답이 있는데 이를 풀었다가 다시 압축하는 것 자체가 비합리적
3. 캐시 히트 시 Controller에서 Caffeine L1을 직접 조회해 byte[]를 그대로 반환하면, 스레드풀도 직렬화도 우회 가능

### 구현 (Implementation)

1. `TieredCacheManager.getL1CacheDirect()`로 L1(Caffeine) 직접 조회 메서드 추가
2. Controller에서 GZIP 요청 시 Fast Path를 먼저 확인
3. 미스 시에만 LogicExecutor 경로(Slow Path)로 위임

### 결과 (Result)

```
부하테스트 결과 (wrk -t4 -c100 -d30s, t3.small):
┌─────────────────────────────────────────────┐
│  RPS:       555~569 (+473% vs 97)           │
│  L1 Hit:    99.99%                          │
│  캐시 히트 응답: 200ms → 4ms               │
│  Error:     1.4~3.3%                        │
└─────────────────────────────────────────────┘
```

**핵심 교훈:**
1. 직렬화 비용은 생각보다 크다 — 300KB 이중 변환이 200ms 지연의 주범
2. Fast Path(Slow Path 우회)와 Slow Path(Miss 시 완전 계산)를 물리적으로 분리하면 99% Hit 경로에서 synchronization overhead를 완전히 제거 가능
3. Controller에서 캐시 전략을 결정하는 것이 pragmatic — Port 뒤에 숨기지 않고 GZIP 여부로 경로 선택

---

## 5. 요청 처리 시 DB 동기 저장으로 약 150ms 지연 발생 → Write-Behind Buffer 적용으로 555 RPS → 674 RPS 개선

<aside>

**캐시 미스 시 동기 DB 저장이 프리셋 3개 × 50ms = 150ms를 차지하여**
**전체 요청 시간의 30%를 DB 저장이 점유**
**Write-Behind Buffer로 비동기화하여 555→674 RPS, 에러율 0% 달성**

</aside>

### 문제 (Problem)

555 RPS를 달성했으나 P50이 871ms였다. 분석 결과, 캐시 미스 시 동기 DB 저장이 프리셋 3개 × 50ms = 150ms를 차지했다.

```
캐시 미스 시 요청 흐름 (병목 구간):
Request → Nexon API (257ms) → 파싱 (50ms) → 계산 (100ms) → DB 저장 (150ms) → Response
                                                                  ↑
                                                     전체 요청의 30% 차지!
```

**비즈니스 임팩트:** 동기 DB 저장 150ms는 사용자 클릭→응답 체감 지연의 30%를 차지. 에러율 1.4~3.3%는 100명 중 1~3명이 실패 경험.

### 선택지 (Options)

- **A. CompletableFuture 비동기 저장**: 구현 간단
    - 장점: 빠른 구현
    - 단점: OOM/크래시 시 데이터 유실 위험
- **B. Write-Behind Buffer**: 메모리 버퍼에 모았다가 배치 DB 저장
    - 장점: Phaser 기반 graceful shutdown으로 유실 방지, lock-free 동시성
    - 단점: 인메모리 버퍼이므로 프로세스 크래시 시 유실 가능
- **C. Kafka/RabbitMQ 메시지 큐**: 가장 견고
    - 장점: 영속적 메시지 보장
    - 단점: 인프라 추가 비용, 운영 복잡도

### 결정 (Decision)

**B. Write-Behind Buffer를 선택**

**선택 이유:**
1. 추가 인프라 없이 구현 가능
2. Phaser로 서버 종료 시에도 버퍼 데이터를 안전하게 flush (SIGTERM 보장)
3. CAS + Exponential Backoff로 lock-free 동시성 제어
4. Backpressure(10,000개 초과 시 신규 offer 거부)로 메모리 보호
5. ADR로 Write-Behind vs PGMQ vs CompletableFuture를 문서화하여 결정

### 구현 (Implementation)

```
Before (동기 DB 저장):
Request → Nexon API → 파싱 → 계산 → DB 저장 (150ms 동기) → Response

After (Write-Behind Buffer):
Request → Nexon API → 파싱 → 계산 → Buffer.offer() (0.1ms) → Response (즉시 반환)
                                                ↓
                              Background Thread (500개 배치)
                                                ↓
                                           DB INSERT (배치)
```

- `ConcurrentLinkedQueue`에 모았다가 500개/5초 배치로 DB에 flush
- Phaser 기반 graceful shutdown (SIGTERM 시 버퍼 flush)
- CAS + Exponential Backoff로 lock-free 동시성 제어
- Backpressure(10,000개 초과 시 offer 거부)

### 결과 (Result)

```
부하테스트 결과 (wrk, t3.small):
┌─────────────────────────────────────────────┐
│  100 connections:                            │
│  - RPS:       674 (+21% vs 555)             │
│  - Error:     0% (이전 1.4~3.3%)            │
│  - DB Write Latency: 150ms → 0.1ms (1,500배)│
│                                              │
│  200 connections:                            │
│  - RPS:       719                           │
└─────────────────────────────────────────────┘
```

**핵심 교훈:**
1. Graceful Shutdown(SIGTERM) 보장이 없으면 인메모리 버퍼 데이터가 유실된다. OOM/kill -9 시에는 유실 가능
2. Batching 효과: 개별 upsert(158,428회) → 배치 upsert(500개×317회)로 DB 왕복 500배 감소

---

## 6. 프리셋 순차 계산으로 p99 약 300ms 지연 발생 → 병렬 처리 적용으로 p99 300ms → 213ms 이하 감소 및 674 RPS → 965 RPS 성능 향상

<aside>

**프리셋 3개를 for 루프로 순차 계산(100ms×3=300ms)하던 것을**
**전용 Executor 분리 + CompletableFuture 병렬 처리로 100ms로 단축**
**같은 Executor에서 부모-자식 태스크 실행 시 데드락이 발생하는 함정을 카오스 엔지니어링으로 사전 검증**

</aside>

### 문제 (Problem)

프리셋 3개를 for 루프로 순차 계산하고 있었다. 각 프리셋은 독립적(프리셋 1의 결과가 프리셋 2에 영향 없음)인데도 100ms × 3 = 300ms가 걸렸다.

```
Before (순차 계산):
for (preset in presets) {
    calculatePreset(preset) // 100ms
}
→ 총 300ms

Deadlock Risk (같은 Executor 병렬):
Parent(TaskExecutor) → CompletableFuture.supplyAsync(TaskExecutor) × 3
    → Parent .join() 대기
    → Children은 큐에서 Parent 스레드 대기 → Deadlock!
```

**비즈니스 임팩트:** 프리셋 3개 순차 300ms + 나머지 150ms = 총 450ms. 경쟁 서비스(평균 100ms) 대비 4.5배 지연. 병렬화하면 100ms로 단축 가능한 명확한 최적화 타겟.

### 선택지 (Options)

- **A. 기존 Executor에서 병렬 처리**: 구현 간단
    - 장점: 빠른 구현
    - 단점: 8개 스레드가 모두 .join() 대기하면 데드락 발생 (실제 경험)
- **B. 전용 Executor 분리**: 프리셋 계산용 스레드풀 별도 생성
    - 장점: 물리적으로 분리되어 데드락 원천 불가능
    - 단점: 스레드풀 추가 관리
- **C. Virtual Thread 활용**: 플랫폼 스레드 한계 회피
    - 장점: 무제한 스레드
    - 단점: Caffeine synchronized 블록에서 carrier thread pinning 위험

### 결정 (Decision)

**B. 전용 Executor 분리를 선택**

**선택 이유:**
1. 카오스 엔지니어링 N03 Thread Pool Exhaustion 테스트에서 이미 같은 Executor 부모-자식 데드락이 증명됨
2. 전용 Executor를 물리적으로 분리하면 데드락이 원천 불가능
3. AbortPolicy(빠른 실패)로 시스템 보호

### 구현 (Implementation)

```
After (전용 Executor + 병렬):
presetCalculationExecutor (core 12/max 24/queue 100)
    → CompletableFuture.supplyAsync(presetCalculationExecutor) × 3
    → allOf().join() → 병렬 100ms
→ 총 100ms (3배 향상)
```

- `PresetCalculationExecutorConfig`로 전용 스레드풀 생성
- 3개 프리셋을 `CompletableFuture.supplyAsync()`로 동시 제출
- `allOf().join()`으로 모두 완료 대기
- JSON DoS 방어(maxNestingDepth 50, maxStringLength 100,000)도 함께 적용

### 결과 (Result)

```
부하테스트 결과 (wrk -t4 -c100 -d30s, AWS t3.small):
┌─────────────────────────────────────────────┐
│  RPS:       965.37 (+43% vs 674)            │
│  P50:       95.02ms                         │
│  P99:       213.56ms                        │
│  Max:       332.37ms                        │
│  Error:     0                               │
│  목표 719 RPS 대비 +34% 초과 달성            │
└─────────────────────────────────────────────┘
```

**핵심 교훈:**
1. 부모-자식 데드락은 Executor 분리로 해결 — 실제 Thread Dump에서 WAITING 상태 4개를 확인한 후 물리 분리로 원천 차단
2. AbortPolicy vs CallerRunsPolicy: AbortPolicy는 빠른 실패로 시스템을 보호하지만, CallerRunsPolicy는 Backpressure 신호를 손실한다

---

## 7. Python 기반 Locust 사용 시 GIL로 인해 실제 성능 대비 낮은 RPS 측정 문제 발생 → wrk 기반 측정으로 실제 555+ RPS 수준의 성능 정확도 확보

<aside>

**Locust(Python)로 측정한 RPS가 wrk(C Native) 대비 현저히 낮게 측정되는 문제를 발견**
**원인이 Python GIL(Global Interpreter Lock)에 있음을 확인하고**
**측정 도구를 wrk로 전환하여 실제 성능을 정확히 측정한 과정**

</aside>

### 문제 (Problem)

Section 1에서 Locust로 223 RPS, Section 4에서 wrk로 555 RPS를 측정했다. 동일 시스템에서 측정 도구만 바꿨는데 2.5배 차이가 발생했다.

**원인 분석:**
- Locust는 Python 기반으로 GIL(Global Interpreter Lock)이 멀티스레드 병목을 유발
- 부하 생성기(Locust) 자체가 병목이 되어 실제 서버 성능을 정확히 측정하지 못함
- 측정 도구가 병목이면 서버 최적화 효과를 과소평가하게 됨

### 선택지 (Options)

- **A. Locust 유지 + 워커 프로세스 증가**
    - 장점: 기존 스크립트 재사용
    - 단점: GIL 문제는 프로세스 증가로 완전 해결 안 됨
- **B. wrk(C Native)로 전환**
    - 장점: 측정 도구 병목 제거, 실제 성능 정확 측정
    - 단점: 스크립팅 기능 제한적, 시나리오 테스트 어려움
- **C. k6(JavaScript) 도입**
    - 장점: 스크립팅 가능한 네이티브 성능
    - 단점: 새 도구 학습 비용

### 결정 (Decision)

**B. wrk(C Native)로 전환**

**선택 이유:**
1. 측정의 정확성이 최우선 — 측정 도구가 병목이면 최적화 효과를 신뢰할 수 없음
2. wrk는 C Native로 GIL 문제 없이 높은 부하 생성 가능
3. 단순 HTTP 부하 테스트에는 wrk가 충분

### 구현 (Implementation)

- 측정 도구를 Locust → wrk로 통일
- 표준 측정 명령어: `wrk -t4 -c100 -d30s` (4 threads, 100 connections, 30 seconds)
- 모든 이전 측정 결과를 wrk 기준으로 재측정하여 일관성 확보

### 결과 (Result)

```
Locust vs wrk 비교:
│ 측정 도구 │ RPS    │ 특징                    │
│ Locust   │ 223    │ Python GIL 병목          │
│ wrk      │ 555    │ C Native, 실제 성능 반영  │
→ 측정 도구 자체가 2.5배 성능 차이를 만듦
```

**핵심 교훈:** 측정 도구 자체가 병목이 될 수 있다. 성능 테스트에서 측정 도구의 한계를 먼저 파악해야 신뢰할 수 있는 데이터를 얻을 수 있다.

---

## 8. JVM 기반 요청 병합 방식이 캐시 히트 요청까지 blocking하여 p99 지연을 증가시키는 문제 → 캐시 히트 경로를 완전히 분리하는 전략으로 p99 latency 안정화 및 성능 최적화 방향 재정의

<aside>

**Section 2-3에서 경험한 SingleFlight 회귀를 통해 얻은 핵심 인사이트:**
**"캐시 히트(99%) 경로와 캐시 미스(1%) 경로를 동일한 파이프라인으로 처리하면 안 된다"**
**이 원칙을 L1 Fast Path(Section 4)에서 검증하고, 이후 모든 최적화의 기반 전략으로 재정의한 과정**

</aside>

### 문제 (Problem)

Section 2-3에서 경험한 SingleFlight 회귀와 Section 4에서 성공한 L1 Fast Path를 통해, 요청 병합(merging) 방식의 근본적 한계를 발견했다.

**문제의 본질:**
- JVM 기반 요청 병합은 동일 키의 중복을 제거하지만, 캐시 히트 요청까지 같은 파이프라인에 들어가면 blocking 발생
- 99%가 캐시 히트인 워크로드에서, 1%를 위한 병합 메커니즘이 99%의 지연을 유발
- "빠르다"는 캐시 히트도 빈도가 높으면 Critical Path가 된다

### 선택지 (Options)

- **A. 병합 메커니즘 유지 + 경량화**
    - 장점: Cache Stampede 방어 유지
    - 단점: 근본 문제(99% 경로 blocking) 해결 안 됨
- **B. Fast Path/Slow Path 완전 분리 전략**
    - 장점: 99% 히트 경로는 zero-overhead, 1% 미스 경로만 SingleFlight
    - 단점: 설계 복잡도 증가, 두 경로의 일관성 관리 필요
- **C. 캐시 히트율을 100%로 만들기**
    - 장점: 미스 경로 자체를 제거
    - 단점: 불가능 (신규 캐릭터, 캐시 만료 등)

### 결정 (Decision)

**B. Fast Path/Slow Path 완전 분리를 핵심 전략으로 채택**

**선택 이유:**
1. Section 3(롤백)과 Section 4(L1 Fast Path 성공)에서 실증적으로 검증한 원칙
2. 99% 요청이 synchronization 없이 즉시 반환되는 구조가 성능의 핵심
3. 이후 모든 최적화(Write-Behind, 병렬 처리, Micro-Batching)의 기반 전략으로 활용

### 구현 (Implementation)

```
Fast Path / Slow Path 분리 구조:

Fast Path (99% 요청 - Zero Overhead):
Client → Controller.getL1CacheDirect() → byte[] 즉시 반환
  ↑ Caffeine L1만 조회, 스레드풀/직렬화/Semaphore 없음

Slow Path (1% 요청 - Full Pipeline):
Client → Controller → LogicExecutor.submit()
  → Nexon API → 파싱 → 계산 → Write-Behind Buffer → Response
  ↑ SingleFlight, Advisory Lock, Micro-Batching 적용
```

### 결과 (Result)

- Fast Path 응답: 200ms → **4ms** (50배 개선)
- Slow Path 응답: 최적화 누적 결과로 450ms → ~100ms
- **p99 안정화:** p99가 안정적으로 하락하는 구조 확립
- 이후 Write-Behind(Section 5), 병렬 처리(Section 6), PostgreSQL 단일화(Section 9)가 모두 Slow Path 최적화에 집중할 수 있는 기반 마련

**핵심 교훈:** Fast Path(99% 히트)를 Zero-Copy로 보호하고, Slow Path(1% 미스)에만 최적화 리소스를 집중하는 것이 97→965 RPS 달성의 핵심 전략이었다. 이 전략은 이후 PostgreSQL 단일화 + Micro-Batching(Section 9)으로 7,000+ RPS까지 이어짐.

---

## 9. 애플리케이션 레벨 최적화로 965 RPS까지 개선했으나 멀티 DB 구조로 인한 확장 한계 → PostgreSQL 단일 아키텍처 통합 + Write-Behind 기반 micro-batching으로 965 RPS → 7,000+ RPS 및 p99 213ms → 36ms 달성

<aside>

**애플리케이션 최적화로 965 RPS를 달성했으나 멀티 DB(Redis+MySQL+MongoDB) 구조로 인한**
**커넥션 분산과 확장 한계가 존재하여,**
**PostgreSQL 단일 아키텍처로 통합하고 Write-Behind 기반 micro-batching + 커넥션 재설계로**
**965 RPS → 7,000+ RPS, p99 213ms → 36ms를 달성한 최종 도약**

</aside>

### 문제 (Problem)

965 RPS를 달성했지만 **단일 인스턴스의 한계**였다. 멀티 DB 구조가 확장을 가로막았다.

**구조적 한계:**

```
마이그레이션 전 인프라 (3개 DB):
Client → Spring Boot
         ├── Redis 7.0 (Master + Slave + 3 Sentinel) ← SPOF
         ├── MySQL 8.0 (Named Lock)                   ← 커넥션 풀 한계
         ├── MongoDB (이벤트 스토어)                    ← 클러스터 비용
         └── Nexon API

커넥션 풀 현황:
HikariCP (MySQL):    max 20 connections
Redisson (Redis):    max 64 connections
MongoClient:         max 20 connections
→ 총 104개 커넥션, 3개 DB로 분산 → 병목의 근원
```

1. **커넥션 분산**: 104개 커넥션이 3개 DB에 분산되어 어느 하나도 충분하지 않음
2. **Scale-out 불가**: Write-Behind Buffer가 인메모리라 인스턴스 간 데이터 불일치
3. **Redis SPOF**: Redis 장애 시 전체 서비스 마비

**비즈니스 임팩트:** 3개 DB 운영 복잡도로 장애 대응 시간 2~3배 증가. Redis 장애 시 전체 서비스 마비.

### 선택지 (Options)

- **A. 각 DB 개별 최적화**: Redis 클러스터, MySQL 읽기 복제본, MongoDB 샤딩
    - 장점: 점진적 개선
    - 단점: 운영 복잡도 5배 증가, 근본 해결 아님
- **B. PostgreSQL 단일화**: 이미 L2 캐시+Advisory Lock으로 사용 중인 PG에 모든 기능 이관
    - 장점: 장애 포인트 3→1, 커넥션 집중, 운영 단순화
    - 단점: 대규모 설계 변경, DB 부하 집중
- **C. 점진적 전환**: Redis만 먼저 제거, 나머지는 추후
    - 장점: 리스크 분산
    - 단점: 부분적 개선에 그침

### 결정 (Decision)

**B. PostgreSQL 단일화를 선택**

**선택 이유:**
1. 모든 Redis/MySQL/MongoDB 기능에 PostgreSQL 대안이 이미 존재 (Advisory Lock, PGMQ, JSONB, UNLOGGED TABLE, NOTIFY)
2. Like 도메인에서 이미 검증한 헥사고날 구조(Port/Adapter)를 활용해 3일간 집중 리팩토링으로 전면 전환 가능
3. 단일 풀에 커넥션이 집중되어 Micro-Batching이 최대 효과를 발휘

**Trade-off 공정성:** Redis는 와일드카드 패턴 무효화, 무제한 페이로드, 검증된 Pub/Sub 생태기술이라는 장점이 있다. 하지만 PostgreSQL의 트랜잭션 내 NOTIFY 원자성, 단일 인프라 운영이 현재 규모(t3.small, 5인스턴스 이하)에서 더 큰 이점. **50+ 인스턴스로 Scale-out 시에는 Redis 재도입 검토 필요.**

### 구현 (Implementation)

**Phase 1 — Redis 제거:**
- RedisDistributedLockStrategy → PostgresAdvisoryLockStrategy
- RedisBuffer → PGMQ
- RedisStream → PgmqStreamPublisher
- RedissonConfig 삭제

**Phase 2 — MongoDB 제거:**
- CQRS Read Side를 MongoDB Document → PostgreSQL JSONB로 교체

**Phase 3 — MySQL 제거:**
- driver-class-name을 MySQL → PostgreSQL로 변경

**Phase 4 — Micro-Batching:**
- 캐시 미스 시 필요한 ID를 수집해 `SELECT WHERE id IN(1,2,3,...)` 배치 쿼리로 통합
- 개별 쿼리 N회 → 배치 쿼리 1회

**Phase 5 — LISTEN/NOTIFY:**
- Redis Pub/Sub → PostgreSQL LISTEN/NOTIFY
- 트랜잭션 내 원자적 전파 (UPDATE + NOTIFY가 같은 tx)

```
마이그레이션 후 인프라 (1개 DB):
Client → Spring Boot (Java 21, Virtual Threads)
         ├── PostgreSQL (영속성, 캐시 L2, Advisory Lock, NOTIFY, PGMQ)
         └── Nexon API

HikariCP (PostgreSQL): max 30 connections
→ 단일 풀로 집중, Redis/MySQL/MongoDB 커넥션 불필요
```

### 결과 (Result)

```
최종 성능 (wrk -t4 -c200 -d120s, 200k~300k rows):
┌─────────────────────────────────────────────┐
│  RPS:       7,347 (76배 향상 vs 97)         │
│  P99:       36ms (99% 감소 vs 4,100ms)      │
│  Error:     0 (Zero!)                       │
│  인프라:    PostgreSQL 단일 (3DB → 1DB)      │
│  비용:      월 $65 → $15 (77% 절감)         │
└─────────────────────────────────────────────┘
```

```
전체 여정 요약:
│ 지표        │ 시작      │ 최종      │ 개선        │
│ RPS         │ 97        │ 7,347     │ 76배        │
│ P99 지연    │ 4,100ms   │ 36ms      │ 99% 감소    │
│ 인프라      │ 3 DB      │ PostgreSQL│ 3→1         │
│ 에러율      │ 59.7%     │ 0%        │ 완전 제거   │
│ 비용        │ 월 $65     │ 월 $15    │ 77% 절감    │
│ Scale-out   │ 불가       │ 선형 확장  │ LISTEN/NOTIFY│
```

**핵심 교훈:**
1. **인프라 단일화는 최적화의 전제조건:** 3개 DB→1개 PostgreSQL로 단순화하니 Connection Pool 고갈 문제가 해결되고, 단일 풀에 커넥션이 집중되어 Micro-Batching이 효과적으로 작동
2. **Micro-Batching이 핵심 엔진:** DB 왕복 3~5회→1회로 줄인 것이 10,994 RPS(빈 DB)의 주범. 실데이터에서는 7,347 RPS
3. **빈 DB 성능은 거짓말:** 10,994 RPS(빈 DB) → 7,347 RPS(실데이터). 실데이터 검증 없이 운영에 들어가면 첫날 장애
4. **헥사고날 아키텍처의 결정적 증거:** Like 도메인에서 먼저 검증한 Port/Adapter 패턴 덕분에 Redis→PostgreSQL 전환 시 module-core 코드 0줄 변경으로 완료

---

## 전체 여정 한눈에 보기

```
RPS Evolution (2026-01-20 ~ 2026-03-30):

  7,347 ┤ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ← 최종 (실데이터)
        │                                              ╭─── 10,994 (빈 DB)
    965 ┤            ╭───╭───
    674 ┤            │   │
    555 ┤       ╭───╯   │
    223 ┤───╭───╯       │
     97 ┤   │            │
        └───┴────────────┴────────────────────────────────────
        Jan20  Jan24  Jan25  Jan26  Jan27   Mar19   Mar24
        Base   Singl  Fast   Write  Parall  NOTIFY  Real
               eFlight Path  Behind el     +Micro  Data
                                   Buffer Batching
```

| 단계 | 병목 | 해결 | RPS 변화 |
|------|------|------|----------|
| Section 1 | 캐시 미적용 + 고비용 연산 | 부하 테스트로 4대 병목 식별 | 223 RPS (베이스라인) |
| Section 2 | Cache Stampede | 원인 정의 (SingleFlight로 해결 시도) | - |
| Section 3 | SingleFlight → Fast Path blocking | 즉시 롤백 + 경로 분리 원칙 확립 | 223→97→223 RPS |
| Section 4 | 직렬화 + 스레드풀 병목 | L1 Fast Path Zero-Copy | 97→555 RPS |
| Section 5 | 동기 DB 저장 150ms | Write-Behind Buffer 비동기화 | 555→674 RPS |
| Section 6 | 프리셋 순차 계산 300ms | 전용 Executor + 병렬 처리 | 674→965 RPS |
| Section 7 | Locust GIL 측정 오차 | wrk(C Native)로 전환 | 정확도 확보 |
| Section 8 | 히트/미스 경로 혼합 | Fast Path/Slow Path 분리 전략 확립 | p99 안정화 기반 |
| Section 9 | 멀티 DB 확장 한계 | PostgreSQL 단일화 + Micro-Batching | 965→7,347 RPS |
