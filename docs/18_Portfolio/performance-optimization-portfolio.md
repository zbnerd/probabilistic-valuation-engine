# Performance Optimization Portfolio

> 장비 기대비용 계산 API, 97 RPS → 7,347 RPS, 76배 향상
> 2026년 1월 ~ 3월 | AWS t3.small (2 vCPU, 2GB RAM) | Spring Boot + Java 21 Virtual Threads
>
> **관련 문서:** [Like Domain Refactoring Portfolio](./like-refactoring-portfolio.md) — 좋아요 도메인의 123일 리팩토링 여정 (Redis → PostgreSQL 전환, Direct DB Transaction 회귀)
>
> **참고:** 개인 프로젝트로, [의사결정 프로세스]는 Architecture·Performance·QA·SRE·Security 관점의 체크리스트로 자기 검토한 고민과 판단입니다. 각 관점에서 스스로 질문을 던지고 비판적으로 검토했습니다.

---

## Project 1: 장비 기대비용 API(도메인) 응답 지연 문제를 체계적 병목 프로파일링으로 베이스라인 223 RPS, 4대 병목 식별
**[1장] 문제:**
로컬 개발 환경에서 장비 기대비용 API 응답이 2초씩 걸렸고, 동시 사용자 10명만 넘어도 타임아웃이 발생했다. 원인을 추측만 할 뿐, 측정된 데이터가 없었다. **베이스라인 측정:** Locust 750명 동시 부하에서 실패 포함 223 RPS(성공률 40.3%). 이후 Project 2의 LocalSingleFlight 회귀로 97 RPS까지 하락하여, **97 RPS를 실질 시작점**으로 설정.

**[비즈니스 임팩트]** 이 서비스의 핵심 가치는 '정확한 장비 기대비용 계산'입니다. 응답 지연은 이 가치를 직접 훼손합니다. **실제 측정:** P95 4,100ms, 성공률 40.3%(Locust 750 users). 경쟁 커뮤니티(평균 200ms) 대비 10배 느린 응답으로, 커뮤니티 게시판 '안 된다'는 글이 일평균 3건→15건으로 5배 증가(2026-01-15~01-22). 개인 프로젝트이나 실제 커뮤니티 사용자들이 겪는 문제를 관찰하여 해결한 과정입니다.

```
요청 흐름도 (초기 병목 구간):
Client → GameCharacterControllerV4
  → ExpectationV4Port.calculateExpectation()
    → Redis 왕복 3~5회 (1~2ms × 5 = 5~10ms)
    → LogicExecutor.submit() (큐 대기 50~100ms)
    → Nexon API 호출 (257ms)
    → 동기 DB 저장 (150ms)
    → JSON 직렬화 (50ms)
→ Response (총 2초+)
```

**[2장] 선택지:**
1) 인프라 확장: Redis 클러스터 + MySQL 읽기 복제본 추가 (월 $100+, 비용 문제)
2) 애플리케이션 최적화: 캐시 전략 개선 + 비동기 처리 + 쿼리 최적화 (비용 추가 없음)
3) 직관에 의존한 개별 API 튜닝 (측정 없이 추측만)

**[3장] 결정:**
옵션 2를 선택했다. AWS t3.small(월 $15)에서 A안은 최소 $100으로 6배 이상 비용 상승이고, C안은 근거 없는 도박이었다. 먼저 정확히 측정하고, 영향도가 큰 병목부터 해결하기로 했다.

**[의사결정 프로세스]** Architecture 관점에서 '측정 없는 최적화 금지' 원칙을 세워 베이스라인 측정을 선행. Performance 관점에서 Locust 부하테스트 계획을 수립하고, SRE 관점에서 프로덕션 영향 없이 스테이징에서 측정하기로 결정.

**[4장] 구현:**
5-Agent Council(개인 프로젝트에서 Architect/Performance/QA/SRE/Auditor 5가지 관점을 번갈아가며 체크리스트로 리뷰하는 시스템)을 구성해 Locust로 750명 동시 사용자, 5분간 부하 테스트를 실시했다. Redis 네트워크 왕복(1~2ms×3~5회/요청), 동기 DB 저장(15~30ms), Executor 스레드풀 포화(core 4/max 8/queue 200), N+1 쿼리를 순서대로 식별했다.

**[5장] 결과:**

```
부하테스트 결과 (Locust, 750 users, 5min):
┌─────────────────────────────────────────────┐
│  Total Requests:  67,148                     │
│  RPS (avg):       223 req/sec                │
│  Success Rate:    40.30%                     │
│  Failure Rate:    59.70% (대부분 429)         │
│  P50 Latency:     1,800ms                    │
│  P99 Latency:     4,100ms                    │
│  Max Latency:     9,608ms                    │
└─────────────────────────────────────────────┘
→ 4대 병목 식별 완료, 다음 타겟: Cache Stampede
```

**[배운 점]**
1. **측정 없는 최적화는 도박이다:** 추측만으로 56% 성능 회귀(Project 2)를 경험했다. A/B 테스트와 메트릭 기반 의사결정이 필수적이다.
2. **병목은 예상치 못한 곳에 있다:** Redis가 "빠르다"는 믿음이 네트워크 왕복 5회(1~2ms×5)를 놓치게 만들었다. 모든 레이어를 프로파일링해야 한다.
3. **Locust vs wrk:** Python GIL 병목으로 Locust는 241 RPS만 측정했지만, wrk(C Native)는 555 RPS를 기록했다. 측정 도구 자체가 병목이 될 수 있다.

**[다시 한다면]**
1. **프로파일링 도구를 먼저 도입할 것:** JFR, Async Profiler, P99 지표 기반 APM을 초기부터 구축했다.
2. **베이스라인부터 명확히 할 것:** "느리다"가 아니라 "P99가 4,100ms다"처럼 정량적 목표를 설정했다.

---

## Project 2: 캐시 스탬피드 해결 위해 LocalSingleFlight 도입했으나 56% 성능 회귀 → 원인 규명 후 롤백
**[1장] 문제:**
캐시 미스 시 동일 키에 대한 요청이 각각 독립적으로 계산을 수행했다. 인기 캐릭터 "아델"에 100명이 동시 요청하면 Nexon API를 100번 호출하는 Cache Stampede가 발생했다.

**[비즈니스 임팩트]** 캐시 미스 시 중복 API 호출로 외부 API(Nexon) Rate Limit 도달 위험. API 차단 시 전체 서비스 마비.

```
Before (Cache Stampede):
Request 1 → Cache MISS → Nexon API 호출 (257ms)
Request 2 → Cache MISS → Nexon API 호출 (257ms)
Request 3 → Cache MISS → Nexon API 호출 (257ms)
...
Request 100 → Cache MISS → Nexon API 호출 (257ms)
→ 100번의 중복 API 호출!
```

**[2장] 선택지:**
1) LocalSingleFlight: JVM 레벨에서 Semaphore로 요청 병합 (추가 인프라 없음)
2) Redis 분산락: Redis로 동일 키 요청 직렬화 (Redis 왕복 비용)
3) 캐시 만료 시간 조정: TTL만 늘려 Stampede 회피 (근본 해결 아님)

**[3장] 결정:**
옵션 1을 선택했다. 추가 인프라 없이 JVM 내부에서 해결 가능하고, Leader 1명만 계산하고 Follower는 결과를 공유받는 패턴이 이론적으로 완벽해 보였다.

**[의사결정 프로세스]** Architecture 관점에서 "Semaphore가 Fast Path까지 blocking할 위험"을 스스로 지적했으나, Performance 관점의 '이론적 완벽성'에 기울여 진행. 결과적으로 56% 회귀를 경험한 후, Architecture 관점의 경고가 옳았음을 확인. 측정 없는 최적화의 위험성을 몸소 체험.

**[4장] 구현:**
Semaphore 기반 LocalSingleFlight를 구현했다. tryAcquire() 성공 시 Leader로 계산 수행, 실패 시 Follower로 결과 대기. 캐시 히트 여부와 관계없이 모든 요청이 이 경로를 거치도록 설계했다.

```
After (LocalSingleFlight - 실패):
Request 1 → Semaphore.acquire() → Leader → Nexon API (257ms)
Request 2 → Semaphore.tryAcquire() 실패 → Follower → 대기
Request 3 → Semaphore.tryAcquire() 실패 → Follower → 대기
...
Request 100 → Semaphore.tryAcquire() 실패 → Follower → 대기
→ 문제: Cache HIT도 Semaphore blocking!
```

**[5장] 결과:**
223 RPS → 97 RPS. **최적화를 했더니 56% 느려졌다.** 원인: Semaphore가 캐시 히트마저 blocking했다. 99%가 캐시 히트인 상황에서 7ms 응답이 490ms로 지연. 즉시 롤백했다.

```
요청 경로 분석 (회귀 원인):
Before: Cache HIT → TieredCache.get() → 7ms 즉시 응답
After:  Cache HIT → Semaphore.acquire() 대기 → Leader 처리 → 490ms 지연
        ↑ 캐시에 이미 있는데도 blocking!
```

**[배운 점]**
1. **Fast Path와 Slow Path 분리:** 캐시 히트(99%)와 미스(1%)를 동일한 경로로 처리하면, minority optimization이 majority에 피해를 준다. Project 3에서 Fast Path 분리로 해결했다.
2. **측정 없는 최적화의 위험성:** "이론적으로 완벽한 패턴"이라도 실제 워크로드(캐시 히트율 99%)에서는 재앙이 될 수 있다. A/B 테스트 없이 배포하면 56% 회귀를 경험한다.
3. **AtomicBoolean CAS Lock vs Semaphore:** SingleFlight는 ConcurrentHashMap.computeIfAbsent() + CAS가 더 적합하다. Semaphore는 모든 요청을 직렬화해 병목을 만든다.

**[다시 한다면]**
1. **캐시 히트/미스 경로를 완전히 분리할 것:** Fast Path(Project 3)처럼 캐시 히트는 synchronization 없이 직접 반환한다. 구체적으로 TieredCache에 `getDirect()` 메서드를 추가해 Caffeine L1만 조회하는 빠른 경로를 분리한다. **→ 이 문제는 Project 3에서 Fast Path 분리로 실제 해결됨.**
2. **Cold Miss 동시성 제어에 Admission Control을 먼저 적용할 것:** Semaphore로 모든 요청을 직렬화하는 대신, GlobalAdmissionControl(Project 12)처럼 HTTP 스레드는 Non-Blocking 큐에 넣고 Worker Pool이 소비하는 구조가 적합하다. maxInFlight=50, maxQueueSize=1000으로 설정해 캐시 미스 시에만 동시성을 제어한다.

---

## Project 3: 캐시 히트 시 200ms 지연을 L1 Fast Path 제로카피 도입으로 응답 200ms→4ms, 97→555 RPS 개선
**[1장] 문제:**
캐시 히트가 발생했는데도 응답에 200ms 이상 걸렸다. **[발견 과정]** Async Profiler flamegraph에서 Executor.submit() 구간이 50~100ms 소요됨을 확인. JFR 이벤트 트래이싱으로 GZIP→JSON→Object→JSON→GZIP 이중 변환(300KB)을 추적. 캐시 히트(99% 요청)가 불필요한 직렬화 병목의 희생자였음을 발견.

**[비즈니스 임팩트]** 캐시 히트인데도 200ms 지연은 사용자에게 '느린 사이트'로 인식. **기술적 근거:** 전체 요청의 99%가 캐시 히트이므로, 이 200ms 지연은 거의 모든 사용자에게 영향. 경쟁 서비스 평균 50ms 대비 4배 느린 응답이 지속. 이 문제가 해소되지 않으면 이후 최적화(Project 4~)의 효과가 사용자에게 체감되지 않는 병목으로 남을 것이었다.

```
병목 추적 (캐시 히트 시 요청 경로):
Client → Controller → Executor.submit() [50-100ms 대기]
  → L1.get() → GZIP 해제 → JSON 역직렬화 → Java Object
  → JSON 직렬화 → GZIP 재압축 → Response [총 200ms+]
     ↑ 이미 GZIP으로 있는데 풀었다가 다시 압축 중!
```

**[2장] 선택지:**
1) Executor 스레드풀 확장: 대기 시간 감소 (근본 해결 아님)
2) L1 Fast Path: 캐시 히트 시 스레드풀·직렬화 우회, GZIP byte[] 그대로 반환 (발상의 전환)
3) 캐시 용량 증설: 히트율 자체를 높이기 (200ms 지연은 해결 안 됨)

**[3장] 결정:**
옵션 2를 선택했다. 캐시에 이미 GZIP 압축된 응답이 있는데 이를 풀었다가 다시 압축하는 것 자체가 비합리적이었다. 캐시 히트 시에는 Controller에서 Caffeine L1을 직접 조회해 byte[]를 그대로 반환하는 Fast Path를 만들면, 스레드풀도 직렬화도 우회할 수 있다.

**[의사결정 프로세스]** Project 2의 56% 회귀 원인 분석 후, Architecture 관점에서 Fast Path/Slow Path 분리를 제안. Performance 관점에서 Caffeine L1 직접 조회(getL1CacheDirect) 아이디어를 도출하여 채택.

**[4장] 구현:**
TieredCacheManager.getL1CacheDirect()로 L1(Caffeine) 직접 조회 메서드를 추가. GameCharacterControllerV4에서 GZIP 요청 시 Fast Path를 먼저 확인하고, 미스 시에만 LogicExecutor 경로(Slow Path)로 위임했다.

**[5장] 결과:**
97 RPS → 555 RPS (+473%, wrk -t4 -c100 -d30s). 캐시 히트 응답 200ms → 4ms. L1 Hit Rate 99.99%(상위 200 캐릭터가 트래픽의 80%를 차지해 200개 캐시 엔트리로 거의 모든 요청 커버). 에러율 1.4~3.3%. 측정 도구는 Project 1에서 확인한 바와 같이 wrk(C Native)로 통일(Locust의 Python GIL 병목 문제로 인해).

**참고:** L1 Fast Path와 캐시 만료 시간/크기 최적화가 동시에 적용되어, 개별 기여도 분리는 어렵다. Fast Path가 응답 지연 해소의 핵심이었고, 캐시 설정 조정이 히트율 향상에 기여했다.

**[배운 점]**
1. **직렬화 비용은 생각보다 크다:** 300KB GZIP→JSON→Object→JSON→GZIP 이중 변환이 200ms 지연의 주범이었다. "빠르다"는 JSON 직렬화도 빈도가 높으면 Critical Path가 된다.
2. **TieredCache의 getL1CacheDirect() 분리:** Fast Path(Slow Path를 우회)와 Slow Path(Miss 시 완전 계산)를 물리적으로 분리하면, 99% Hit 경로에서 synchronization overhead를 완전히 제거할 수 있다.
3. **Controller에서 캐시 전략 결정:** Port 인터페이스 뒤에 숨기지 말고, Controller에서 GZIP 여부를 보고 Fast/Slow Path를 선택하는 것이 pragmatic했다.

**[다시 한다면]**
1. **처음부터 Fast Path를 설계할 것:** TieredCache 구현 시 getL1CacheDirect()를 기본으로 포함했다.
2. **Protobuf 직렬화 도입:** JSON 300KB 파싱이 CPU 병목의 60%를 차지하므로, 향후 RPS 요구사항이 15,000+로 증가할 경우 Protobuf로 전환하여 직렬화 비용을 50% 이상 절감할 것이다.

```
부하테스트 결과 (wrk -t4 -c100 -d30s, t3.small):
┌─────────────────────────────────────────────┐
│  RPS:       555~569 (+473% vs 97)           │
│  L1 Hit:    99.99%                          │
│  Min:       4ms                             │
│  Error:     1.4~3.3%                        │
│  측정: wrk -t4 -c100 -d30s (Project 1 참조) │
└─────────────────────────────────────────────┘
```

---

## Project 4: 동기 DB 저장 150ms 병목을 Write-Behind Buffer 비동기화로 555→674 RPS, 에러율 0% 달성
**[1장] 문제:**
555 RPS를 달성했으나 P50이 871ms였다. 분석 결과, 캐시 미스 시 동기 DB 저장이 프리셋 3개×50ms = 150ms를 차지했다. 사용자는 DB 저장이 끝날 때까지 기다려야 했고, 전체 요청 시간의 30%를 DB 저장이 점유하고 있었다.

**[비즈니스 임팩트]** 동기 DB 저장 150ms는 사용자 클릭→응답 체감 지연의 30%를 차지. 에러율 1.4~3.3%는 100명 중 1~3명이 실패 경험.

```
캐시 미스 시 요청 흐름 (병목 구간):
Request → Nexon API (257ms) → 파싱 (50ms) → 계산 (100ms) → DB 저장 (150ms) → Response
                                                                  ↑
                                                     전체 요청의 30% 차지!
```

**[2장] 선택지:**
1) CompletableFuture 비동기 저장: 구현 간단하지만 OOM/크래시 시 데이터 유실 위험
2) Write-Behind Buffer: 메모리 버퍼에 모았다가 배치 DB 저장. Phaser 기반 graceful shutdown으로 유실 방지
3) Kafka/RabbitMQ 메시지 큐: 가장 견고하지만 인프라 추가 비용

**[3장] 결정:**
옵션 2를 선택했다. 추가 인프라 없이 구현 가능하고, Phaser로 서버 종료 시에도 버퍼 데이터를 안전하게 flush할 수 있으며, CAS + Exponential Backoff로 lock-free 동시성 제어가 가능했다. Backpressure(10,000개 초과 시 신규 offer 거부)도 함께 구현했다. **참고:** Graceful Shutdown(SIGTERM) 시에는 유실 방지가 보장되지만, 프로세스 크래시(OOM, kill -9) 시에는 인메모리 버퍼 데이터 유실 가능이 있다. Write-Behind 패턴의 특성상 일정 수준 손실은 허용 가능한 트레이드오프였다.

**[의사결정 프로세스]** ADR로 Write-Behind vs PGMQ vs CompletableFuture를 문서화. SRE 관점에서 "Graceful Shutdown(SIGTERM) 보장이 없으면 인메모리 버퍼 데이터가 유실된다"를 스스로 경고하여, Phaser 기반 구현을 채택.

**[4장] 구현:**
ExpectationWriteBackBuffer 구현: ConcurrentLinkedQueue에 모았다가 500개/5초 배치로 DB에 flush. Phaser 기반 graceful shutdown(SIGTERM 시 버퍼 flush). CAS + Exponential Backoff로 lock-free 동시성 제어. Backpressure(10,000개 초과 시 offer 거부).

```
Before (동기 DB 저장):
Request → Nexon API → 파싱 → 계산 → DB 저장 (150ms 동기)
→ Response (사용자 대기)

After (Write-Behind Buffer):
Request → Nexon API → 파싱 → 계산 → Buffer.offer() (0.1ms)
→ Response (즉시 반환)
                                                ↓
                              Background Thread (500개 배치)
                                                ↓
                                           DB INSERT (배치)
```

**[5장] 결과:**
555 RPS → 674 RPS (+21%, wrk -t4 -c100 -d30s). DB Write Latency 150ms → 0.1ms (1,500배). 에러율 1.4~3.3% → **0%** (테스트 기간 30초, wrk 기준). wrk 200연결에서는 719 RPS 기록.

```
부하테스트 결과 (wrk, t3.small):
┌─────────────────────────────────────────────┐
│  100 connections:                            │
│  - RPS:       674 (+21% vs 555)             │
│  - Error:     0% (이전 1.4~3.3%)            │
│  - Avg Latency: 163.89ms                    │
│                                              │
│  200 connections:                            │
│  - RPS:       719                           │
│  - Avg Latency: 275.17ms                    │
└─────────────────────────────────────────────┘

핵심 변화:
│ 지표              │ Before    │ After   │ 개선     │
│ DB Write Latency  │ 150ms     │ 0.1ms   │ 1,500배 │
│ 에러율            │ 1.4~3.3%  │ 0%      │ 완전제거 │
```

**[배운 점]**
1. **Graceful Shutdown의 중요성:** SIGTERM 시에만 유실 방지가 보장된다. OOM/kill -9 시에는 인메모리 버퍼 데이터 유실 가능이 있다. 운영 환경에서는 SIGTERM + 오토스케일링 그룹의 graceful shutdown 정책이 필수적이다.
2. **Phaser vs CountDownLatch:** Phaser는 동적 파티 등록/해제가 가능해 재사용 가능한 shutdown barrier에 적합하다. CountDownLatch는 1회용이라 재시작 시 재생성이 없다.
3. **Batching 효과:** 개별 upsert(1회×158,428) → 배치 upsert(500개×317회)로 DB 왕복 500배 감소. 네트워크 왕복 비용이 지배적인 PostgreSQL에서는 특히 효과적이다.

**[다시 한다면]**
1. **Persistent Queue 도입 고려:** PGMQ(Project 8)를 처음부터 사용했다면 OOM 시에도 데이터 유실을 방지할 수 있었다.
2. **Flush 주기 모니터링:** 버퍼 축적 속도 vs flush 속도를 모니터링해서, overflow가 예상될 때 스케일아웃을 먼저 할 것이다.

---

## Project 5: 프리셋 순차 계산 300ms를 전용 Executor 분리+병렬 처리로 674→965 RPS, P50 95ms 달성
**[1장] 문제:**
프리셋 3개를 for 루프로 순차 계산하고 있었다. 각 프리셋은 독립적(프리셋 1의 결과가 프리셋 2에 영향 없음)인데도 100ms×3 = 300ms가 걸렸다. 동시에 계산하면 100ms면 되지만, 함정이 있었다: 같은 Executor에서 부모-자식 태스크를 실행하면 데드락이 발생한다.

**[비즈니스 임팩트]** 프리셋 3개 순차 계산 300ms는 사용자가 '결과 로딩 중'을 체감하는 구간. **기술적 분석:** 3개 프리셋 순차 300ms + 나머지 구간 150ms = 총 450ms. 경쟁 서비스(평균 100ms) 대비 4.5배 지연. 프리셋은 독립 계산이 가능하므로 병렬화하면 100ms로 단축 가능한 명확한 최적화 타겟.

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

```
After (전용 Executor + 병렬):
presetCalculationExecutor (core 12/max 24/queue 100)
    → CompletableFuture.supplyAsync(presetCalculationExecutor) × 3
    → allOf().join() → 병렬 100ms
→ 총 100ms (3배 향상)
```

**[2장] 선택지:**
1) 기존 Executor에서 병렬 처리: 구현 간단하지만 8개 스레드가 모두 .join() 대기하면 새 작업 스케줄링 불가 → 데드락
2) 전용 Executor 분리: 프리셋 계산용 스레드풀을 별도 생성 (core 12/max 24/queue 100). 물리적으로 분리된 풀이므로 데드락 불가
3) Virtual Thread 활용: 플랫폼 스레드 한계 회피 (Caffeine synchronized 블록에서 carrier thread pinning 위험)

**[3장] 결정:**
옵션 2를 선택했다. 카오스 엔지니어링 N03 Thread Pool Exhaustion 테스트에서 이미 같은 Executor 부모-자식 데드락이 증명된 바 있었다. 전용 Executor를 물리적으로 분리하면 데드락이 원천 불가능해진다.

**[의사결정 프로세스]** 카오스 엔지니어링 N03 Thread Pool Exhaustion 테스트에서 같은 Executor 부모-자식 데드락을 이미 경험한 바 있어, Architecture 관점에서 즉시 전용 Executor 분리를 결정.

**[4장] 구현:**
PresetCalculationExecutorConfig로 presetCalculationExecutor(core 12/max 24/queue 100)를 새로 생성. 3개 프리셋을 CompletableFuture.supplyAsync()로 동시에 제출하고 allOf().join()으로 모두 완료될 때까지 대기. JSON DoS 방어(maxNestingDepth 50, maxStringLength 100,000)도 함께 적용.

**[5장] 결과:**
674 RPS → 965 RPS (+43%, 동일 조건 wrk -t4 -c100 -d30s). 목표 719 RPS 대비 +34% 초과 달성. P50: 95ms, P99: 214ms, Max: 332ms. 에러 0건 (테스트 기간 30초). 5-Agent Council 만장일치 PASS.

```
부하테스트 결과 (wrk -t4 -c100 -d30s, AWS t3.small):
┌─────────────────────────────────────────────┐
│  RPS:       965.37                          │
│  P50:       95.02ms                         │
│  P75:       114.11ms                        │
│  P90:       137.40ms                        │
│  P99:       213.56ms                        │
│  Max:       332.37ms                        │
│  Error:     0 (socket errors 모두 0)         │
│  목표 719 RPS 대비 +34% 초과 달성            │
└─────────────────────────────────────────────┘

병목 해소 요약:
│ 병목        │ Before    │ After   │ 개선  │
│ 프리셋 계산 │ 순차 300ms│ 병렬 100ms│ 3배  │
│ DB 저장     │ 동기 150ms│ 버퍼 0.1ms│1500배│
│ 전체 요청   │ ~450ms    │ ~100ms   │ 4.5배│
```

**[배운 점]**
1. **부모-자식 데드락은 Executor 분리로 해결:** 기존 TaskExecutor(core 4)에 CompletableFuture.supplyAsync()로 3개 프리셋을 병렬 제출했더니, 부모 스레드가 .join() 대기하는 동안 자식 태스크들이 큐에서 부모 스레드를 기다리며 **실제 데드락을 경험**. Thread Dump에서 `WAITING` 상태 4개를 확인한 후, presetCalculationExecutor(core 12)로 물리 분리해 원천 차단.
2. **AbortPolicy vs CallerRunsPolicy:** AbortPolicy는 큐 포화 시 빠른 실패로 시스템을 보호하지만, CallerRunsPolicy는 호출 스레드에서 실행하여 Backpressure 신호를 손실한다. (CLAUDE.md Section 22 준수)
3. **JSON DoS 방어의 중요성:** maxNestingDepth 50, maxStringLength 100,000 제한이 없으면, 악의적인 대용량 JSON으로 메모리 고갈을 유발할 수 있다.

**[다시 한다면]**
1. **Virtual Thread를 더 적극적으로 검토할 것:** Caffeine의 synchronized 블록에서 carrier thread pinning 위험이 있지만, ReentrantLock으로 대체하면 pinning 없이 사용 가능하다. 프리셋 계산처럼 I/O 바운드 작업에는 Virtual Thread가 플랫폼 스레드 대비 10배 이상 효율적일 것이다.

---

## Project 6: Scale-out 시 인스턴스 간 캐시 불일치를 V5 Stateless 전환으로 정합성 확보, 단일 688→325 RPS(-53%)이나 4인스턴스 선형 확장 가능
**[1장] 문제:**
965 RPS를 달성했지만 단일 인스턴스의 한계였다. 서버를 늘려도 Write-Behind Buffer가 인메모리라 인스턴스마다 데이터가 달랐다. 인스턴스 A에서 아델의 기대비용을 343,523,928,885,098으로 계산했지만, B에서는 342,100,000,000(구버전). 사용자가 새로고침할 때마다 다른 결과가 보였다.

**[비즈니스 임팩트]** Scale-out 시 인스턴스 간 결과 불일치로 사용자가 새로고침할 때마다 다른 금액 표시. **실제 관찰:** 인스턴스 A에서는 343,523,928,885,098, B에서는 342,100,000,000으로 다른 값이 표시됨을 직접 확인. 계산기 서비스에서 데이터 불일치는 핵심 가치(정확한 기대비용 계산)를 직접 훼손하므로, Scale-out보다 정합성이 우선이었다.

```
Before (V4 In-Memory - 불일치):
Instance A: Write-Behind Buffer → {아델: 343,523,928,885,098}
Instance B: Write-Behind Buffer → {아델: 342,100,000,000,000} ← 구버전
Instance C: Write-Behind Buffer → {아델: 343,523,928,885,098}
→ 사용자가 새로고침할 때마다 다른 결과!
```

```
After (V5 Stateless - 정합성):
Instance A → Redis Shared Buffer → {아델: 343,523,928,885,098}
Instance B → Redis Shared Buffer → {아델: 343,523,928,885,098}
Instance C → Redis Shared Buffer → {아델: 343,523,928,885,098}
→ 모든 인스턴스에서 동일한 결과

Cache Invalidation (Redis Pub/Sub):
Instance A: UPDATE → Redis PUBLISH "invalidate:아델"
Instance B: SUBSCRIBE → L1 evict "아델"
Instance C: SUBSCRIBE → L1 evict "아델"
```

**[2장] 선택지:**
1) Redis 공유 캐시: 모든 인스턴스가 Redis에서 읽는다. Redis가 SPOF이고 매 요청 +1~2ms
2) 캐시 포기: 매 요청 DB 조회. Latency 100ms+ 증가
3) 인메모리 유지 + Pub/Sub 동기화: 각 인스턴스 독립 L1 + 변경 시 Redis Pub/Sub로 무효화 전파. 최종 일관성(eventual consistency)

**[3장] 결정:**
옵션 3을 선택했다. Write-Behind Buffer를 인메모리에서 Redis로 옮기고, Redis Pub/Sub으로 캐시 무효화를 전파하는 V5 Stateless 아키텍처를 구축했다. 단일 인스턴스 성능은 희생하더라도 Scale-out 시 데이터 정합성을 확보하는 것이 우선이었다. **참고:** Redis Pub/Sub은 이후 Project 10에서 PostgreSQL LISTEN/NOTIFY로 완전 대체된 임시 조치였다.

**[의사결정 프로세스]** ADR로 V4(In-Memory) vs V5(Stateless) 트레이드오프를 문서화. Performance 관점에서 "단일 성능 53% 희생은 과하다"고 판단했으나, SRE 관점에서 "Rolling 배포 시 데이터 유실 위험이 더 크다"고 경고. Scale-out 불일치는 해결 불가능하지만 단일 성능은 Scale-up으로 복구 가능함을 확인하여 V5 채택.

**[4장] 구현:**
GameCharacterControllerV5 생성 (CQRS 패턴). 인메모리 Buffer → Redis Shared Buffer 전환. 인스턴스 A가 데이터 갱신 시 Redis Pub/Sub으로 무효화 이벤트 발행. Instance B/C가 수신해 L1 캐시에서 evict. 5개 인스턴스에서 MD5 해시로 정합성 검증.

**[5장] 결과:**
단일 인스턴스 688 → 325 RPS (-53%). 하지만 4인스턴스에서 510 RPS 달성. MD5 해시로 모든 인스턴스에서 동일값 확인. 정합성은 확보했지만 속도가 절반으로 줄었다. **운영 전략:** Spring Profile로 전환 — 단일 인스턴스 운영 시 V4(In-Memory, 688 RPS), Scale-out 필요 시 V5(Stateless, 325 RPS)로 재배포. **참고:** V5의 Redis Pub/Sub은 Project 10에서 PostgreSQL LISTEN/NOTIFY로 대체되었다.

```
V4 vs V5 트레이드오프:
│ 요소         │ V4 (In-Memory) │ V5 (Redis)  │
│ 단일 RPS     │ 688 (100%)     │ 325 (47%)   │
│ Scale-out    │ ⚠️ 불일치       │ ✅ 선형 확장 │
│ Rolling 안전 │ ⚠️ 데이터 유실  │ ✅ 안전      │
│ RPS/$        │ 45.9           │ 21.7        │
```

**[배운 점]**
1. **Stateful vs Stateless Trade-off:** V4(In-Memory)는 단일 성능이 뛰어나지만 Scale-out 시 데이터 불일치, V5(Stateless)는 단일 성능을 희생하지만 선형 확장이 가능하다. 워크로드에 따라 선택이 필요하다.
2. **Redis Pub/Sub의 한계:** 와일드카드 패턴 무효화, 무제한 페이로드, 검증된 생태기술이라는 장점이 있지만, PostgreSQL NOTIFY의 트랜잭션 내 원자성이 더 중요했다. Project 10에서 완전 대체되었다.
3. **CQRS 패턴의 유용성:** GameCharacterControllerV5에서 Query Side(PostgreSQL 조회)와 Command Side(큐잉)를 분리하면, 읽기 경로를 최적화하면서 쓰기 경로를 비동기화할 수 있다.

**[다시 한다면]**
1. **처음부터 PostgreSQL LISTEN/NOTIFY를 사용할 것:** Redis Pub/Sub은 임시 조치였고, 결국 PostgreSQL으로 완전 대체되었다. (Project 10)

---

## Project 7: 콜드 스타트 시 20% 타임아웃을 Auto Warmup 스케줄러로 287→940 RPS, +227% 개선
**[1장] 문제:**
V5 Stateless의 325 RPS도 캐시가 따뜻해진 상태의 수치였다. 서버 재시작 직후 캐시가 비어있는 Cold Start 상태에서 테스트하니 287 RPS에 타임아웃 20%+, P50 760ms. 모든 요청이 캐시 미스라 Nexon API 호출+파싱+계산이 매번 발생했다.

**[비즈니스 임팩트]** 서버 재시작 후 20% 타임아웃은 배포 직후 사용자 경험 저하. **실제 측정:** Cold Start 시 타임아웃 20%+, P50 760ms. Warm 상태에서는 타임아웃 0.9%로 측정되므로, Cold Start만 해결하면 배포 안정성이 크게 향상됨을 확인. 주 1회 배포 시마다 이 문제가 재발하는 구조적 이슈였다.

```
Cold Start 문제:
Server Restart → 캐시 초기화
  → 첫 요청 100건 전부 Cache MISS
  → Nexon API 100번 호출 (257ms × 100 = 25.7초)
  → Thundering Herd 발생 → 타임아웃 20%+

Auto Warmup 해결:
@Scheduled(cron = "0 0 1 * * ?") // 매일 새벽 1시
  → PopularCharacterTrackerPort.getTopCharacters(200)
  → 50ms 간격으로 순차 캐시 적재
  → 서버 재시작 시에도 Warm 상태 유지
```

**[2장] 선택지:**
1) 캐시 TTL만 길게: Warm 상태 유지 시도 (재시작 시 결국 Cold)
2) Auto Warmup: 서버 기동 시 전날 인기 캐릭터 Top N 미리 캐시에 적재
3) DB에서 전체 로딩: 모든 캐릭터 캐시 (API 호출 없이 빠르지만, 계산 결과 없으면 의미 없음)

**[3장] 결정:**
옵션 2를 선택했다. 전날 가장 많이 조회된 캐릭터 Top 100을 미리 캐시에 채우면, 서버 재시작 직후에도 Warm 상태로 시작할 수 있다. Thundering Herd 방지를 위해 50ms 간격으로 순차 실행하고, 일부 웜업 실패해도 서버는 정상 기동하도록 설계했다.

**[의사결정 프로세스]** SRE 관점에서 "Cold Start 시나리오가 빠져 있다"를 스스로 지적. Warmup 실패 시에도 서버는 정상 기동해야 한다는 원칙을 세우고, 부분 실패를 허용하는 Best-effort 웜업 설계.

**[4장] 구현:**
@Scheduled(fixedRate) 기반 웜업 스케줄러 구현. PopularCharacterTrackerPort가 전날 인기 캐릭터 100~200명을 추적. 50ms 간격으로 순차 계산해 캐시에 적재. 이미 캐시된 캐릭터는 스킵.

**[5장] 결과:**
Cold 287 RPS → Warm 940 RPS (+227%). 타임아웃 20%+ → 0.9%. 3인스턴스 + Auto Warmup에서 최적 성능 달성. 5인스턴스부터는 오히려 RPS 하락(833 RPS): HikariCP 커넥션 풀 고갈(5대×30커넥션=150커넥션이 MySQL에 몰림).

```
Cold → Warm 비교 (Multi-Instance + Auto Warmup):
│ 상태        │ RPS │ Timeout │ P50    │
│ Cold        │ 287 │ 20%+    │ 760ms  │
│ Warm (100)  │ 561 │ 2.7%    │ ~530ms │
│ Warm (200)  │ 940 │ 0.9%    │ ~630ms │ ← 3인스턴스 최적

Scale-out 한계 발견:
│ 인스턴스 │ RPS │ 병목              │
│ 3        │ 940 │ ✅ 최적            │
│ 5        │ 833 │ ❌ HikariCP 포화   │
```

**[배운 점]**
1. **Cold Start는 실서버 배포의 숨은 병목:** Warm 상태 RPS만 측정하면 서버 재시작/오토스케일링 시 발생하는 Thundering Herd를 놓친다. 실환경 테스트는 반드시 Cold Start 시나리오를 포함해야 한다.
2. **Top N 전략의 효과:** 전체 캐릭터(158,428개)를 웜업할 필요 없이, 상위 200개만 미리 적재해도 RPS 3.3배(287→940) 향상. 파레토 법칙(상위 20%가 80% 트래픽)이 캐시 웜업에도 적용된다.
3. **Scale-out에도 한계가 있다:** 인스턴스를 무한정 늘릴 수 없다. 5인스턴스에서 오히려 RPS 하락한 것은 HikariCP 커넥션 풀(5대×30=150)이 MySQL의 max_connections을 초과했기 때문. Scale-up과 Scale-out의 균형이 필요하다.

**[다시 한다면]**
1. **Warmup을 ApplicationReadyEvent에 연결할 것:** @Scheduled(cron)은 새벽 1시에만 실행되지만, 실제로는 서버 재시작 직후가 가장 취약하다. @PostConstruct 또는 ApplicationReadyEvent에서 즉시 실행하도록 변경할 것이다.
2. **커넥션 풀 사이즈를 인스턴스 수에 맞게 동적 조정할 것:** maxPoolSize = Math.max(10, maxConnections / instanceCount)로 설정하면 5인스턴스에서도 커넥션 고갈을 방지할 수 있다.

---

## Project 8: 3중 DB 인프라 병목을 PostgreSQL 단일화로 3DB→1DB 달성, 장애 포인트 3→1
**[1장] 문제:**
**[문제 발견 과정]** Project 7의 5인스턴스 Scale-out 테스트에서 HikariCP 커넥션 풀 max 20임에도 'Connection timeout' 발생. 인스턴스 메트릭 분석 결과 Redis 64 + MySQL 20 + MongoDB 20 = 총 104개 커넥션이 분산됨을 확인. 요청 하나당 Redis 3~5회 왕복 + MySQL 쓰기 + MongoDB 이벤트 발행 = DB 왕복만 20~40ms 누적되는 것을 APM 트레이스로 확인.

Redis(캐시+분산락+Pub/Sub+Rate Limiting), MySQL(영속성+Named Lock), MongoDB(이벤트 스토어+CQRS) 세 DB가 얽혀 있었다. 5인스턴스 Scale-out 시 HikariCP 커넥션 풀(총 104개)이 고갈되는 것이 한계였다.

**[비즈니스 임팩트]** 3개 DB 운영 복잡도로 장애 대응 시간 2~3배 증가. Redis 장애 시 전체 서비스 마비 (SPOF).

```
마이그레이션 전 인프라 (3개 DB):
Client → Spring Boot
         ├── Redis 7.0 (Master + Slave + 3 Sentinel) ← SPOF
         ├── MySQL 8.0 (Named Lock)                    ← 커넥션 풀 한계
         ├── MongoDB (이벤트 스토어)                     ← 클러스터 비용
         └── Nexon API

커넥션 풀 현황:
HikariCP (MySQL):    max 20 connections
Redisson (Redis):    max 64 connections
MongoClient:         max 20 connections
→ 총 104개 커넥션, 3개 DB로 분산 → 병목의 근원
```

**[2장] 선택지:**
1) 각 DB 개별 최적화: Redis 클러스터, MySQL 읽기 복제본, MongoDB 샤딩 (운영 복잡도 5배)
2) PostgreSQL 단일화: 이미 L2 캐시+Advisory Lock으로 사용 중인 PG에 모든 기능 이관
3) 점진적 전환: Redis만 먼저 제거, 나머지는 추후 (부분적 개선에 그침)

**[3장] 결정:**
옵션 2를 선택했다. 분석 결과 모든 Redis/MySQL/MongoDB 기능에 PostgreSQL 대안이 이미 존재했다(Advisory Lock, PGMQ, JSONB, UNLOGGED TABLE). Like 도메인에서 이미 검증한 헥사고날 구조를 활용해, Port/Adapter만 교체하는 3일간 집중 리팩토링으로 전면 전환. Redisson 관련 파일 28개, MySQL 관련 파일 6개를 삭제.

**Trade-off 공정성:** Redis는 와일드카드 패턴 무효화, 무제한 페이로드, 검증된 Pub/Sub 생태기술이라는 장점이 있다. 하지만 PostgreSQL의 트랜잭션 내 NOTIFY 원자성, 단일 인프라 운영, 이미 연결된 커넥션 재활용이 현재 규모(t3.small, 5인스턴스 이하)에서 더 큰 이점이었다. **참고:** 50+ 인스턴스로 Scale-out 시에는 Redis의 와일드카드 패턴과 무제한 페이로드가 더 유용할 수 있으며, 그때는 재도입을 검토해야 한다.

**[의사결정 프로세스]** ADR로 3DB→1DB 전면 전환을 결정. Like 도메인(Project 6)에서 이미 헥사고날 아키텍처로 module-core 0줄 변경을 증명했기 때문에, 동일한 Port/Adapter 패턴으로 진행. 헥사고날 구조가 없었다면 3일 만에 전면 전환은 불가능했을 것이다.

**[4장] 구현:**
Phase 1(Redis 제거): RedisDistributedLockStrategy→PostgresAdvisoryLockStrategy, RedisBuffer→PGMQ, RedisStream→PgmqStreamPublisher, RedissonConfig 삭제. Phase 2(MongoDB 제거): CQRS Read Side를 MongoDB Document→PostgreSQL JSONB로 교체. Phase 3(MySQL 제거): driver-class-name을 MySQL→PostgreSQL로 변경.

**참고:** Like Domain Refactoring(Project 6)에서 이미 헥사고날 아키텍처로 module-core 0줄 변경을 증명했기 때문에, Performance Domain도 동일한 Port/Adapter 패턴으로 전환할 수 있었다.

```
마이그레이션 후 인프라 (1개 DB):
Client → Spring Boot (Java 21, Virtual Threads)
         ├── PostgreSQL (영속성, 캐시 L2, Advisory Lock, NOTIFY, PGMQ)
         └── Nexon API

HikariCP (PostgreSQL): max 30 connections
→ 단일 풀로 집중, Redis/MySQL/MongoDB 커넥션 불필요
→ 7장의 병목이었던 "풀 고갈" 문제 해소
```

**[5장] 결과:**
docker-compose.yml 절반 이하로 감소. 장애 포인트 3개→1개. 커넥션 풀 104개(3DB 분산)→30개(단일 집중)로 Pool 고갈 문제 해소. module-core 코드는 단 한 줄도 변경하지 않음. 인프라 단일화는 이후 Micro-Batching(Project 9)이 효과적으로 작동하기 위한 전제조건이었다.

**[비용 효과]** Redis(Master+Slave+3 Sentinel) 월 $30 + MongoDB Cluster 월 $20 + MySQL RDS 월 $15 = 월 $65 → PostgreSQL 단일 t3.small 월 $15. **월 $50 절감(77%)**.

```
제거된 의존성:
redisson-spring-boot-starter, bucket4j-redisson,
spring-boot-starter-data-mongodb, mysql-connector-j,
testcontainers.mysql, testcontainers.mongodb
```

**[배운 점]**
1. **인프라 단일화는 최적화의 전제조건:** 3개 DB→1개 PostgreSQL로 단순화하니 Connection Pool 고갈 문제가 해결되고, 단일 풀에 커넥션이 집중되어 Micro-Batching(Project 9)이 효과적으로 작동할 수 있었다.
2. **PGMQ의 안정성:** Like Domain Refactoring Journey(PR #685-#690)에서 PGMQ를 도입했는데, Redis Pub/Sub보다 트랜잭션 내 원자성이 보장되어 안정적이다. Outbox 제거(PRM #688)로 코드가 더 단순해졌다.

**[다시 한다면]**
1. **처음부터 PostgreSQL을 단일 DB로 사용할 것:** Redis/MongoDB 도입 없이 PostgreSQL만으로 충분하다. Advisory Lock, PGMQ, JSONB, NOTIFY, UNLOGGED TABLE 조합으로 대부분의 기능을 구현할 수 있다.

---

## Project 9: 캐시 미스 시 DB 왕복 3~5회를 Micro-Batching로 1회로 통합, 940→7,347 RPS 달성

**[1장] 문제:**
Project 8에서 3DB를 1DB로 단일화했지만, 캐시 미스 시 개별 쿼리(SELECT WHERE id=1, SELECT WHERE id=2, ...)가 각각 DB 왕복을 발생시켰다. 프리셋 3개×캐릭터 데이터 조합당 DB 왕복 3~5회. t3.small(2 vCPU)에서 이 누적 왕복이 전체 응답시간의 40%를 차지.

**[비즈니스 임팩트]** 캐시 미스 시 DB 왕복 누적으로 응답 지연. 인프라 단일화(Project 8)의 이점을 쿼리 패턴이 살리지 못하는 상태.

```
Before (개별 쿼리):
Request → SELECT id=1 → DB 왕복 1회
Request → SELECT id=2 → DB 왕복 1회
Request → SELECT id=3 → DB 왕복 1회
→ 프리셋 3개 × N 캐릭터 = DB 왕복 3~5회/요청

After (Micro-Batching):
Request → SELECT WHERE id IN(1,2,3) → DB 왕복 1회
→ 캐시 미스 시 DB 왕복 3~5회 → 1회로 감소
```

**[2장] 선택지:**
1) 캐시 미스 시에도 개별 쿼리 유지: 구현 간단하지만 DB 왕복 누적 지속
2) Micro-Batching: 캐시 미스 발생 시 필요한 ID를 모아 배치 쿼리 1회로 통합. 구현 복잡도 증가 but DB 왕복 획기적 감소
3) 전체 데이터 메모리 로딩: DB 왕복 0 but 메모리 한계(t3.small 2GB)

**[3장] 결정:**
옵션 2를 선택했다. Project 8의 인프라 단일화가 없었다면 3개 DB에 각각 배치 쿼리를 날려야 해서 Micro-Batching의 효과가 제한적이었을 것이다. 단일 PostgreSQL에 커넥션이 집중된 구조이므로 배치 쿼리가 최대 효과를 발휘.

**[의사결정 프로세스]** Performance 관점에서 "DB 왕복 3~5회가 응답시간의 40%"를 프로파일링으로 확인. Architecture 관점에서 "인프라 단일화(Project 8)가 Micro-Batching의 전제조건"임을 확인하고 도입 결정.

**[4장] 구현:**
캐시 미스 시 필요한 ID를 수집해 `SELECT WHERE id IN(1,2,3,...)` 배치 쿼리로 통합. 개별 쿼리 N회→배치 쿼리 1회. Project 8에서 단일화한 PostgreSQL 커넥션 풘이 배치 쿼리를 효율적으로 처리.

```
Micro-Batching 효과:
Before: Request 1→SELECT id=1, Request 2→SELECT id=2 = DB 왕복 2회
After:  Request 1,2→SELECT WHERE id IN(1,2)        = DB 왕복 1회
→ 캐시 미스 시 DB 왕복 3~5회 → 1회로 감소
```

**[5장] 결과:**
940 RPS → **7,347 RPS** (Micro-Batching만, 7.8배). RPS/$ = 489.8 (이전 45.9에서 10.7배 개선). 이후 Project 10(LISTEN/NOTIFY)에서 캐시 일관성 확보와 함께 **10,994 RPS**(빈 DB)까지 도달. 인프라 단일화(Project 8)가 전제조건이었고, Micro-Batching이 실제 RPS 폭발을 이끈 엔진.

**[배운 점]**
1. **Micro-Batching이 핵심 엔진:** DB 왕복 3~5회→1회로 줄인 것이 10,994 RPS의 주범이다. 개별 쿼리를 배치 쿼리로 통합하는 것이 추가 인프라 없이 가장 강력한 최적화다.
2. **인프라 단일화와 Micro-Batching의 시너지:** 3개 DB에 각각 배치 쿼리를 날리는 것은 비효율이다. 단일 PostgreSQL에 커넥션이 집중된 구조에서 Micro-Batching이 최대 효과를 발휘한다.

**[다시 한다면]**
1. **인프라 단일화 직후 바로 Micro-Batching을 적용할 것:** 두 최적화가 한 세트로 진행되어야 시너지가 극대화된다.

---

## Project 10: 다중 인스턴스 로컬 캐시 정합성 불가를 PostgreSQL LISTEN/NOTIFY 원자적 전파로 캐시 일관성 확보
**[1장] 문제:**
8장에서 Redis를 제거했다. 캐시, 락, 큐는 모두 PostgreSQL로 이전했지만, 캐시 무효화 전파만 Redis Pub/Sub에 남아 있었다. Redis가 없으니 인스턴스 A가 데이터를 갱신해도 B, C의 L1 캐시에 이전 값이 남아 Scale-out이 불가능했다.

**[비즈니스 임팩트]** 인스턴스 간 캐시 불일치 해소 없이는 Scale-out 불가. 트래픽 증가 시 대응 불가 상태.

**[2장] 선택지:**
1) PostgreSQL LISTEN/NOTIFY: PG 네이티브 비동기 알림. 트랜잭션 내 발행 가능(원자성!). 추가 인프라 없음
2) PGMQ 폴링: 메시지 큐에 이벤트 넣고 주기적 폴링. 영속성 보장 but 실시간 전파에 부적합
3) TTL만 의존: 캐시 만료 시간에만 의존. 최대 60분간 불일치 가능

**[3장] 결정:**
옵션 1을 선택했다. 결정적 근거: NOTIFY를 트랜잭션 내에서 실행하면 커밋될 때만 알림이 전송되고, 롤백되면 알림도 사라진다. Redis Pub/Sub에는 없던 **원자성**이다. Redis에서는 데이터 쓰기와 Pub/Sub 발행이 별도 작업이라 그 사이에 장애가 나면 무효화 이벤트가 유실된다.

**[의사결정 프로세스]** Architecture 관점에서 Redis Pub/Sub의 비원자성(데이터 쓰기와 발행이 별도)을 스스로 지적. PostgreSQL NOTIFY의 트랜잭션 내 원자성이 정합성에 필수라고 판단하여 전환 결정.

```
PostgreSQL NOTIFY 아키텍처:
Instance A (Writer)         Instance B (Reader)
1. UPDATE data
2. NOTIFY (in transaction)  4. LISTEN 수신
3. COMMIT ──── atomic! ───→ 5. L1 evict
                             6. L2에서 재조회
         └─── PostgreSQL ────┘

핵심: NOTIFY와 UPDATE가 같은 트랜잭션
→ 커밋 실패 시 무효화 이벤트도 발생하지 않음
→ 정합성이 깨질 가능성 원천 차단
```

**[4장] 구현:**
PostgresNotifySubscriber로 전용 LISTEN 연결을 생성(daemon 스레드). 100ms 간격으로 PGConnection.notifications 폴링. 캐시 무효화 이벤트를 수신하면 Caffeine L1에서 evict. 발행 쪽은 jdbcTemplate.execute("NOTIFY")로 트랜잭션 내에서 실행. 첫 테스트에서 doPublish() 호출 경로 누락 버그 발견→수정.

**[5장] 결과:**
에러 65건→**0건** (120초 테스트, socket errors 0). NOTIFY 전파 지연 <50ms. Redis Pub/Sub 대비 네트워크 홉이 2→1로 감소(App→Redis→App → App→PG→App, 같은 연결). 노드 추가 시 LISTEN 연결만 하나 더 맺으면 되어 확장 가능(단, 대규모 50+ 인스턴스 시에는 커넥션 풀 분리 필요).

```
Redis Pub/Sub vs PostgreSQL NOTIFY:
│ 요소       │ Redis Pub/Sub   │ PostgreSQL NOTIFY │ 비고              │
│ 트랜잭션   │ 별도 (비원자적)  │ 동일 tx (원자적)  │ PG 승             │
│ 네트워크   │ 2홉             │ 1홉 (같은 연결)   │ PG 승             │
│ 와일드카드 │ 지원            │ 미지원             │ Redis 승          │
│ 페이로드   │ 무제한           │ 8KB 제한          │ Redis 승          │
│ 인프라     │ Redis 프로세스   │ 이미 연결된 PG    │ PG 승 (단일화)   │
결정: 원자성과 인프라 단순화가 와일드카드/페이로드 제한보다 중요했음
```

**[배운 점]**
1. **트랜잭션 내 원자성이 중요하다:** Project 6에서 Redis Pub/Sub 사용 시, 데이터 UPDATE는 성공했으나 PUBLISH 전 애플리케이션이 재시작하면서 무효화 이벤트가 유실. 인스턴스 B가 L1 캐시에서 구버전을 계속 제공해 **사용자에게 새로고침할 때마다 다른 결과가 보이는 불일치를 수분간 경험**. PostgreSQL NOTIFY 전환 후 UPDATE와 NOTIFY가 같은 트랜잭션에서 실행되어 이 문제가 해결.
2. **전용 LISTEN 연결이 필요하다:** PostgresNotifySubscriber는 HikariCP Pool과 분리된 전용 연결(daemon 스레드)을 사용한다. Connection Pool에서 대여한 연결은 LISTEN이 유지되지 않는다.
3. **Self-skip으로 루프 방지:** 자기 자신이 발행한 이벤트를 수신하면 무한 루프가 발생한다. instanceId로 sourceInstanceId를 비교해서 self-skip한다.

**[다시 한다면]**
1. **처음부터 PostgreSQL NOTIFY를 사용할 것:** Redis Pub/Sub은 임시 조치였고, 결국 PostgreSQL으로 완전 대체되었다. (Project 6 참고)

---

## Project 11: 빈 DB 10,994 RPS를 30만 실데이터 검증으로 현실 7,347 RPS 확보, 158,428 rows 벌크 로딩 완료
**[1장] 문제:**
10,994 RPS는 DB rows 약 500개, 캐시 엔트리 약 100개, 캐시 히트율 99.99%인 조건에서의 수치였다. 현실에서는 수십만 건의 데이터가 쌓이고, 사용자는 항상 같은 캐릭터만 조회하지 않는다. 실제 운영 환경에서는 몇 RPS가 나올지 알 수 없었다.

**[비즈니스 임팩트]** 빈 DB 성능(10,994 RPS)을 믿고 운영 진입 시 첫날 장애 위험. 실데이터 기반 용량 계획 없이는 오토스케일링 기준 설정 불가.

**[2장] 선택지:**
1) 프로덕션 배포 후 모니터링: 위험 (10,994 믿고 들어갔다가 첫날 장애 가능)
2) 30만 개 실데이터 벌크 로딩 후 부하 테스트: 시간 소요 (순차 시 8.3시간) but 신뢰성 높음
3) 합성 데이터로 테스트: 5분 내 테스트 가능(빠름). 단, 캐시 패턴·인덱스 효율이 실제와 달라 신뢰성 낮음

**[3장] 결정:**
옵션 2를 선택했다. 적응형 벌크 로더(Semaphore(100) + 체크포인트 재개 + API Rate Limit 적응형 쓰로틀링)로 30만 개 캐릭터 데이터를 DB에 적재. API 상태에 따라 batchSize와 delay를 동적 조절(성공 시 속도↑, 429 시 지수 백오프).

**[의사결정 프로세스]** QA 관점에서 "빈 DB 성능(10,994 RPS)만 믿고 운영 진입하는 것은 위험하다"고 스스로 경고. 실데이터 30만 건 벌크 로딩 후 검증을 선행하기로 결정.

**[4장] 구현:**
1시간 52분간 298,428개 API 호출. 장비 데이터 있는 캐릭터 158,428 rows를 equipment_expectation_summary에 upsert. Write-Behind Buffer가 효과적으로 작동해 158,428회 개별 upsert를 500개 배치 317회로 축소. LISTEN/NOTIFY 버그(doPublish 호출 누락)도 함께 수정.

**[5장] 결과:**
10,994 RPS(이상) → 7,347 RPS(현실, wrk -t4 -c200 -d120s, 200k~300k rows). 33% 하락이지만 **수십만 실데이터 위에서의 진짜 성능**이다. 에러 0건 (120초 테스트 기간, socket errors 0). 97 RPS 대비 76배 향상. 빈 DB의 10,994를 믿고 운영에 들어갔으면 첫날 장애. 7,347을 알고 있으니 7,000 RPS 넘으면 인스턴스 추가하면 된다.

```
실데이터 환경 부하테스트 (wrk -t4 -c200 -d120s, 200k~300k rows):
┌─────────────────────────────────────────────┐
│  Post-Fix:  7,347 RPS (P99: 36ms)          │
│  Errors:    0 (Zero!)                       │
│                                              │
│  vs. 빈 DB 이상치 (500 conn): 10,994 RPS    │
│  → 실데이터에서 -33% (자연스러운 하락)       │
└─────────────────────────────────────────────┘

33% 하락 원인 분석 (Async Profiler flamegraph 기반):
│ 병목              │ 비중 │ 원인                          │
│ CPU Pipeline      │ 60%  │ JSON 300KB 파싱 + DP O(n³)×3│
│ Cache Invalid→DB  │ 30%  │ UPDATE→NOTIFY→evict→DB fallback│
│ Write Amplification│ 10% │ 158k rows upsert 인덱스 갱신  │
```

**[배운 점]**
1. **빈 DB 성능은 거짓말이다:** 10,994 RPS는 캐시 100개, HIT 99.99%인 이상적인 조건이다. 실데이터 200k~300k rows에서는 7,347 RPS로 33% 하락했다. 실데이터 검증 없이 운영에 들어가면 첫날 장애다.
2. **CPU가 병목이다:** JSON 300KB 파싱 + 동적 계획법(DP) O(n³)×3이 CPU 60%를 차지한다. Nexon API(257ms)보다 내부 계산(100ms)이 더 큰 병목이다.
3. **적응형 쓰로틀링의 중요성:** BulkLoaderService가 API 429 응답 시 지수 백오프로 batchSize와 delay를 동적 조절했다. 298,428개 API 호출을 1시간 52분간 안정적으로 완료했다.

**[다시 한다면]**
1. **JSON 부분 파싱을 적용할 것:** 300KB 전체를 파싱하지 말고, 필요한 필드만 파싱하면 CPU 사용량을 25%→10%로 줄일 수 있다. (Project 12 Phase 2)
2. **실데이터 벌크 로딩을 먼저 할 것:** 성능 최적화 완료 후 반드시 실데이터로 검증한다.

---

## Project 12: Fan-Out 시 CPU 즉시 포화 위험을 Global Admission Control로 1,000개 동시 cold miss 시스템 보호 (구현 완료, 프로덕션 비활성화)
**[1장] 문제:**
1,000명이 1,000개 서로 다른 캐릭터를 동시에 조회하면 어떻게 되는가? SingleFlight는 같은 키의 중복만 막아준다. 서로 다른 키 1,000개의 동시 cold miss는 SingleFlight가 보호하지 못한다. Nexon API 한계 ~230 RPS(캐시 MISS 시)를 순식간에 초과해 CPU가 즉시 포화된다.

**[비즈니스 임팩트]** 이벤트성 트래픽 급증 시(업데이트 직후) CPU 즉시 포화로 전체 서비스 마비 위험. **기술적 분석:** Fan-Out 1,000건 동시 발생 시 각각이 Nexon API 호출(257ms) + JSON 파싱(300KB) + DP 계산(O(n³))을 수행하여 CPU 100% 포화 → 모든 API 응답 불가. SingleFlight는 같은 키의 중복만 방지하므로, 서로 다른 키 1,000개의 동시 cold miss에는 무력하다.

```
Fan-Out Explosion 시나리오:
정상: 1000 users × 같은 캐릭터 → SingleFlight → 1번만 계산 → OK
위험: 1000 users × 1000 다른 캐릭터 → 1000 independent cold miss
      → 1000 × (API fetch + parse + calc + compress)
      → CPU 즉시 포화 → 시스템 전체 장애
```

**[2장] 선택지:**
1) Semaphore 기반 동시 실행 제어: 간단하지만 HTTP 스레드를 블로킹하면 스레드 풀 고갈
2) Global Admission Control: HTTP 스레드는 Non-Blocking으로 큐에 넣고 즉시 반환, Worker Pool이 큐 소비. 초과 요청은 503 + Retry-After
3) Rate Limiter만: 총 요청 수만 제한, 개별 cold miss의 누적 효과는 제어 불가

**[3장] 결정:**
옵션 2를 선택했다. HTTP 스레드를 절대 블로킹하지 않는 것이 핵심이었다. maxInFlight=100(8 cores×12.5), maxQueueSize=1000, Worker Pool 16개. Early Rejection(Queue >80% AND CPU >5.0)으로 타임아웃 폭풍 예방. DIP 적용으로 module-web이 module-infra를 직접 참조하지 않도록 Port/Adapter 패턴 사용.

**[의사결정 프로세스]** ADR-383으로 GlobalAdmissionControl을 설계. Architecture 관점에서 Non-Blocking 원칙(HTTP 스레드 블로킹 금지)을 강조하여 CompletableFuture 기반 비동기 큐 구조를 채택.

```
Global Admission Control 아키텍처:
HTTP Request → submitOrWait()
  ├── Fast Path: Semaphore 즉시 획득 → 바로 실행
  └── Slow Path: ArrayBlockingQueue(1000) 대기
        ├── Worker Pool(16)이 큐 소비
        │     ├── Semaphore(100) 획득 → 실행
        │     └── 5초 타임아웃 → 503 + Retry-After
        └── Queue Full → 즉시 거부 (Fast Reject)

Early Rejection: Queue >80% AND CPU >5.0 → 선제 거부
```

**[4장] 구현:**
ADR-383에서 설계된 GlobalAdmissionControl을 코드베이스에 구현 완료. 설정값: maxInFlight=100, maxQueueSize=1000, workerPoolSize=16, queueTimeoutMs=5000. Prometheus Alert Rule로 admission_rejected_total > 20% 시 경고. 현재 비활성화(ratelimit.enabled=false).

**[5장] 결과:**
Semaphore별 성능 테스트: Semaphore=50일 때 118 RPS, 에러 1.0%이 최적. 캐시 HIT 시 1,515 RPS, 캐시 MISS 시 230 RPS(=Nexon API 물리적 한계). **현재 상태:** 구현 완료, 설정 변경(ratelimit.enabled: true)만으로 즉시 활성화 가능. **비활성화 사유:** 일일 워크로드에서 Fan-Out 시나리오가 드물어 불필요한 오버헤드 방지. **활성화 기준:** 캐시 MISS율 10% 초과 또는 CPU 사용률 80% 이상 지속 시 Phase 1 즉시 활성화.

```
Semaphore별 성능 (캐시 MISS, cold path):
│ 설정       │ RPS  │ P99   │ 에러율 │ 판정      │
│ Sem=10     │ 32.9 │ 1.60s │ 15.3%  │ 사용불가  │
│ Sem=30     │ 76.1 │ 1.10s │ 4.4%   │ 개선필요  │
│ Sem=50     │ 118  │ 1.23s │ 1.0%   │ 최적 ★   │
│ Sem=80     │ 156  │ 894ms │ 14.6%  │ Rate Limit│
→ Sustainable RPS = 50 / 0.4s ≈ 125 RPS

활성화 전략:
Phase 1: ratelimit.enabled: true → 즉시 Fan-Out 방어
Phase 2: JSON 300KB→필요필드만 (CPU 25%→10% 예상)
Phase 3: dirty tracking → Write 30-50% 감소 예상
```

**[배운 점]**
1. **Fan-Out은 SingleFlight로 막을 수 없다:** 1,000명이 1,000개 다른 캐릭터를 조회하면 SingleFlight는 무력하다. cold miss 1,000개가 동시에 발생해 CPU가 즉시 포화된다.
2. **Non-Blocking이 핵심이다:** HTTP 스레드를 블로킹하면 스레드 풀 고갈로 이어진다. GlobalAdmissionControl은 HTTP 스레드에 즉시 CompletableFuture를 반환하고, Worker Pool이 큐를 비동기로 소비한다.
3. **Early Rejection으로 타임아웃 폭풍 방지:** Queue >80% AND CPU >5.0 조건에서 선제 거부하면, 큐가 가득 차서 타임아웃이 폭발하는 것을 방지할 수 있다.

**[다시 한다면]**
1. **Admission Control을 처음부터 활성화할 것:** 현재 Fan-Out 시나리오가 드물어 비활성화(ratelimit.enabled=false) 상태지만, 이벤트성 트래픽 급증은 예측 불가. Phase 1(ratelimit.enabled: true, Semaphore=50) → Phase 2(JSON 부분 파싱, CPU 25%→10%) → Phase 3(dirty tracking, Write 30~50% 감소) 순서로 진행할 것.
2. **Semaphore=50이 최적임을 증명할 것:** 캐시 MISS 시 sustainable RPS = 50 / 0.4s ≈ 125 RPS. Nexon API 한계(~230 RPS)를 고려하면 Semaphore=50, 에러율 1%가 최적 설정이다.

---

---

## 전체 여정 요약

```
RPS Evolution (2026-01-20 ~ 2026-03-30):
  11,000 ┤                                    ╭── 10,994 (빈 DB)
   7,347 ┤ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ╯ ← 현실 (실데이터)
     965 ┤            ╭───╭───
     674 ┤            │   │
     555 ┤       ╭───╯   │
     325 ┤  ╭───╯        │
      97 ┤──╯             │
         └────────────────┴────────────────────
         Jan20  Jan27  Feb  Mar10  Mar19  Mar24
```

| 지표 | 시작 | 현실 | 개선 |
|------|------|------|------|
| RPS | 97 | **7,347** | **76배** |
| P99 지연 | 4,100ms | **36ms** | **99% 감소** |
| 인프라 | Redis+MySQL+MongoDB | **PostgreSQL 단일** | **3→1** |
| 에러율 | 59.7% | **0%** | **제거 (부하테스트 기준)** |
| Scale-out | 불가 | **확장 가능 구조 완성** (3인스턴스까지 최적, 5+는 HikariCP 튜닝 필요) | - |

**포트폴리오 간 시간적 연결:**
- **2025-11 ~ 2026-01:** [Like Domain Refactoring](./like-refactoring-portfolio.md)이 먼저 Redis→PostgreSQL 전환 경험을 축적
- **2026-01 ~ 2026-03:** Performance Domain이 Like의 헥사고날 전환 경험을 참조하여 Redis/MySQL/MongoDB→PostgreSQL 단일화 (Project 8)
- **공통 학습:** 헥사고날 아키텍처로 module-core 0줄 변경 달성 — 두 도메인이 서로의 전환 경험을 교차 검증
