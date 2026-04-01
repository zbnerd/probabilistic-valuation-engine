# Like Domain Refactoring Portfolio

> 좋아요 도메인, 123일간의 진화: 비관적 락 → Redis Lua Script → Direct DB Transaction
> 2025년 11월 ~ 2026년 3월 | AWS t3.small (2 vCPU, 2GB RAM) | Spring Boot + Java 21 / Kotlin
>
> **관련 문서:** [Performance Optimization Portfolio](./performance-optimization-portfolio.md) — 장비 기대비용 API 성능 최적화 여정 (97→7,347 RPS, Redis+MySQL+MongoDB→PostgreSQL 단일화)

---

## Project 1: 좋아요 토글(도메인) 고부하 지연+서버 종료 시 데이터 유실을 낙관적 락 전환+Graceful Shutdown으로 안정화+Scale-out 기반 마련

**[아키텍처 다이어그램]**
```
Before (비관적 락):
Controller → SELECT FOR UPDATE (Blocking) → INSERT/DELETE → Commit
           ↑ 고부하 시 큐 대기 → 지연 발생

After (낙관적 락):
Controller → Caffeine L1 Cache → CAS(Compare-And-Set) → L2 PostgreSQL
           ↑ Non-blocking, 재시도만 비용
           ↓ Graceful Shutdown (SIGTERM → flush → 종료)
```

**[1장] 문제:**
비관적 락(SELECT ... FOR UPDATE)으로 좋아요 토글 시 고부하 환경에서 과도한 지연 발생. Like 버퍼는 In-Memory(Caffeine)에 저장되는데, 서버 재시작 시 flush되지 않은 좋아요 데이터가 **전부 유실**되었다. 좋아요 수가 이상해졌을 때 원인 추적이 불가능했다(메트릭이 아예 없었다).

**[2장] 선택지:**
1) 비관적 락 유지+타임아웃 조정: 근본 해결 아님, 지연만 완화
2) 낙관적 락 전환+재시도: 고빈도 토글에 적합, 경합 시에만 재시도 비용
3) 분산 락 도입(Redis): 오버엔지니어링, 단일 인스턴스에서 불필요

**[3장] 결정:**
옵션 2를 선택했다. 좋아요 토글은 고빈도 연산이므로 낙관적 락이 적합하다. 락 메커니즘을 Proxy/Decorator 패턴으로 캡슐화해 향후 교체 가능하게 만들었다. Graceful Shutdown으로 서버 종료 시 버퍼 flush를 보장하고, Micrometer 메트릭(like.toggle.duration, like.buffer.size 등)으로 관측 가능성을 확보했다.

**[4장] 구현:**
Proxy/Decorator 패턴으로 캐싱·동시성·로깅 계층을 분리. Spring @PreDestroy 훅으로 SIGTERM 수신 시 버퍼를 먼저 flush하고 안전 종료. pg_try_advisory_xact_lock 기반 분산 락 AOP로 Scale-out 시 스케줄러 중복 실행 방지. TieredCache(L1 Caffeine → L2 PostgreSQL → SingleFlight → Loader) 구조 도입.

```
구현 상세:
InMemoryLikeBufferStorage (Caffeine)
  - increment(userIgn, delta): AtomicLong.addAndGet()
  - fetchAndClear(limit): getAndSet(0)으로 원자적 획득
  - @PreDestroy → flushAndShutdown() → 모든 카운터 DB 반영

LogicExecutor AOP
  - @AdvisoryLock(key="like-sync", waitTime=0, leaseTime=30s)
  - pg_try_advisory_xact_lock으로 스케줄러 중복 실행 방지
```

**[5장] 결과:**
비관적 락 지연 해결(P99: 측정 이전~이후 비교 불가, 정성적 개선), 서버 종료 시 데이터 유실 방지(Graceful Shutdown flush 성공률 100%), 관측 가능성 확보(Micrometer 4개 핵심 메트릭: like.toggle.duration, like.buffer.size 등). Phase 1 통합 릴리즈 완료. 단, Check-Then-Act TOCTOU 레이스 컨디션, Relation과 Counter의 비원자적 이중 쓰기, unlike 시 동기 DB DELETE 문제가 여전히 남아 있었다.

```
Phase 1 해결 요약:
│ 문제               │ 해결                │ 상태 │
│ 비관적 락 지연     │ 낙관적 락+Proxy     │ ✅  │
│ 관측 불가          │ Micrometer 메트릭   │ ✅  │
│ 종료 시 데이터 유실│ Graceful Shutdown   │ ✅  │
│ 스케줄러 중복 실행 │ 분산 락 AOP         │ ✅  │
│ TOCTOU 레이스      │ 미해결              │ ❌  │ ← 2~3장에서 해결
│ 비원자적 이중 쓰기 │ 미해결              │ ❌  │
│ unlike 동기 DELETE │ 미해결              │ ❌  │
```

**[배운 점]**
1. **메트릭 없는 운영은 눈 감고 달리기와 같다:** like_toggle_duration, like_buffer_size, like_sync_success_rate 등 4개 핵심 메트릭 도입 후에야 장애 원인을 즉시 파악 가능했다.
2. **Graceful Shutdown은 데이터 유실 방지의 최후의 보험책:** @PreDestroy 훅에서 flush 성공률을 100%로 만들었지만, 여전히 크래시 시나리오에서는 취약했다. 이것이 Project 2, 3으로 이어지는 동기이었다.
3. **낙관적 락 선택 기준:** 고빈도 토글(5~20 QPS)에서 경합은 드물지만 발생 시 재시도 비용만 있다. 비관적 락은 모든 요청이 락 대기열에 들어가므로, 빈번한 경합이 예상될 때만 적합하다.

**[다시 한다면]**
1. **초기부터 메트릭을 설계할 것:** "나중에 추가하자"는 영원히 추가하지 않는다는 뜻이다. Micrometer Registry는 앱 시작부터 필수다.
2. **Buffer 유실 시나리오를 상시 테스트할 것:** Chaos Engineering(Late Arrival Chaos)으로 서버 강제 종료 테스트를 자동화했다면, Project 1 단계에서 유실을 발견했을 것이다.

---

## Project 2: 좋아요 수 중복 카운트+fetchAndClear 비원자성을 Redis Lua Script 원자화+보상 트랜잭션으로 데이터 정합성 100% 확보

**[시퀀스 다이어그램]**
```
Before (비원자적 3단계):
App → Redis → fetchAll() → {ign1: 5, ign2: 3}
App → DB → UPDATE like_count +5, +3
App → Redis → deleteAll()
       ↑ [Crash] → DB에 반영됐는데 Redis에 남음 → 다음 sync에 중복 카운트

After (Lua Script 원자화):
App → Redis.eval(Lua: HGETALL + DEL) → {ign1: 5, ign2: 3} + Redis clear
App → DB → UPDATE like_count +5, +3
       ↑ 실패 시 → RedisCompensationCommand → 복원
```

**[1장] 문제:**
장애 복구 과정에서 좋아요 수가 중복 카운트되었다. 원인: Buffer에서 fetch→DB persist→Buffer clear의 3단계가 원자적이지 않았다. Step 2와 3 사이에 서버가 크래시하면 DB에 이미 반영되었는데 Redis에는 여전히 남아 있어 다음 sync 사이클에서 재실행→중복 카운트. LikeSyncService에 순환 참조(BeanCurrentlyInCreationException)도 존재했다.

**[2장] 선택지:**
1) 분산 트랜잭션(2PC): Redis와 DB 간 원자성 보장 but 복잡도 과도, 성능 저하
2) Lua Script 원자화+보상 트랜잭션: Redis 내부는 Lua로 원자적, DB 실패 시 Redis에 데이터 복원
3) Outbox 패턴: DB에 먼저 쓰고 Redis는 참조용. Redis와 DB 정합성 보장 but 지연

**[3장] 결정:**
옵션 2를 선택했다. Redis의 Lua Script는 단일 스레드에서 원자적으로 실행되어 fetch+clear 사이에 끼어들 수 있는 간격이 0이 된다. DB 배치 업데이트 실패 시에는 보상 트랜잭션(RedisCompensationCommand)으로 획득한 데이터를 Redis에 복원하고, 복구 이력을 compensation_log 테이블에 저장(감사 추적).

```
보상 트랜잭션 흐름:
1. Lua Script: fetchAndClear() → 원자적으로 버퍼 데이터 획득 + 삭제
2. DB Batch: UPDATE like_count
   → 성공 시: 완료
   → 실패 시: RedisCompensationCommand 실행
      → 획득한 데이터를 Redis에 복원
      → 다음 sync 사이클에서 재시도
3. CompensationLog 기록 → 복구 이력 DB 저장 (감사 추적)
```

**[4장] 구현:**
Lua Script로 HGETALL+DEL을 원자 실행. DB 실패 시 보상 로직(Redis 복원+compensation_log 기록). 순환 참조는 인터페이스 분리(LikeSyncService→LikeBufferStrategy 인터페이스)로 해결. 이 분리는 향후 Redis↔In-Memory 버퍼를 전략 패턴으로 교체할 수 있는 기반이 됨.

```
Lua Script (HGETALL+DEL):
local data = redis.call('HGETALL', KEYS[1])
redis.call('DEL', KEYS[1])
return data

보상 트랜잭션 (CompensationCommand):
try { db.batchUpdate(counters) }
catch { bufferStrategy.restoreEntries(counters) }
finally { compensationLog.save(attempt, result) }
```

**[5장] 결과:**
fetchAndClear 간극 0달성(비원자적→Lua 원자 실행). Sync 소요시간 200~500ms→50~150ms. Redis 명령 수 15~20→5~8. DB 쿼리 N+1→Batch. 순환 참조 해결. 보상 트랜잭션으로 DB 실패 시에도 데이터 보호. BYOK 인증으로 self-like 방지의 전제 조건도 확보. **참고:** "정합성 100%"는 Redis Lua Script의 이론적 원자성 보장에 근거하며, 프로덕션 모니터링으로 실제 불일치 건수를 추가 검증했다.

```
성능 개선 (PR #189):
│ 항목          │ Before    │ After    │
│ Sync 소요     │ 200-500ms│ 50-150ms │
│ Redis 명령 수 │ 15-20    │ 5-8      │
│ DB 쿼리       │ N+1      │ Batch    │
```

**[배운 점]**
1. **분산 시스템에서 원자성은 어렵다:** Redis와 DB는 별도 트랜잭션 도메인이다. Lua Script는 Redis 내부 원자성만 보장하고, DB 실패 시 복구 메커니즘이 필수적이다.
2. **순환 참조는 아키텍처 냄새:** LikeSyncService가 LikeBufferStrategy를 직접 참조하면서 BeanCurrentlyInCreationException이 발생했다. Port 인터페이스 분리로 DIP를 위반하지 않고 해결했다.
3. **보상 트랜잭션의 감사 추적:** compensation_log 테이블에 복구 이력을 저장한 덕분에, 장애 시 어떤 데이터가 복원되었는지 추적 가능했다.

**[다시 한다면]**
1. **초기부터 아웃박스 패턴을 검토할 것:** DB를 먼저 쓰고 Redis는 참조용으로만 사용하면 이 원자성 문제가 애초에 발생하지 않는다. 하지만 당시에는 Redis가 성상 이유로 우선순위였다.
2. **Lua Script 복잡도를 제한할 것:** Script 안에서 비즈니스 로직을 너무 많이 넣으면 디버깅이 어려워진다. fetch+clear 정도의 간단한 조작에만 사용하는 것이 좋다.

---

## Project 3: 좋아요 토글(도메인) TOCTOU 레이스컨디션+Scale-out 불가를 Atomic Lua Script+Redis Pub/Sub로 DB QPS 12-17x 감소, P99 35ms→8ms 개선

**[시퀀스 다이어그램]**
```
Before (TOCTOU 레이스):
Thread A → SISMEMBER relations:ocid → 0 (false)
Thread B → SISMEMBER relations:ocid → 0 (false) [동시 요청]
Thread A → SADD relations:ocid → 성공
Thread B → SADD relations:ocid → 성공 (중복 카운트!)
Thread A → HINCRBY buffer:ocid +1
Thread B → HINCRBY buffer:ocid +1 (좋아요 +2가 됨)

After (Atomic Lua Script):
Thread A+B → Lua.eval(SISMEMBER+SADD+HINCRBY) → 단일 스레드 실행
           → 둘 중 하나만 성공, 다른 하나는 무시
           → 4개 자료구조 원자 조작
```

**[1장] 문제:**
5-Agent Council 전수 분석에서 P0 이슈 8건이 발견되었다. 가장 심각한 P0-1: Check-Then-Act TOCTOU 레이스 컨디션. 두 스레드가 거의 동시에 "아직 안 눌렀네"라고 판단하고 둘 다 좋아요를 실행→좋아요 수가 +2가 됨. P0-5: unlike 시 동기 DB DELETE가 500 DB writes/sec→HikariCP 포화. Scale-out 시 인스턴스 간 L1 캐시 불일치도 존재.

```
P0-1 TOCTOU 레이스 컨디션:
Thread A: checkLikeStatus(ocid) → false (아직 안 누름)
Thread B: checkLikeStatus(ocid) → false (아직 안 누름)
Thread A: addToBuffer(ocid)     → +1
Thread B: addToBuffer(ocid)     → +1  ← 같은 사람이 두 번 좋아요!
```

**[2장] 선택지:**
1) 애플리케이션 레벨 락: synchronized/ReentrantLock. 단일 인스턴스에서만 유효, Scale-out 불가
2) DB 레벨 원자성: INSERT ON CONFLICT UNIQUE 제약. 정확하지만 DB 부하 증가
3) Redis Atomic Lua Script: SISMEMBER+SADD/SREM+HINCRBY를 하나의 Lua Script로 원자 실행. 4개 Redis 자료구조를 동시 조작

**[3장] 결정:**
옵션 3을 선택했다. 하나의 Lua Script 안에서 4개 Redis 자료구조(relations SET, pending SET, buffer HASH, unliked SET)를 원자적으로 조작한다. P0-1~P0-3, P1-1, P1-2, P1-5를 **하나의 Lua Script로 동시 해결**. Unlike도 Write-Behind로 이관해 Hot path에서 DB 호출 완전 제거. Scale-out은 Redis Pub/Sub+Self-skip 메커니즘으로 인스턴스 간 캐시 일관성 유지.

```
Atomic Toggle Lua Script 처리 흐름:
KEYS: {likes}:relations, {likes}:relations:pending,
      {likes}:buffer, {likes}:relations:unliked

LIKE:  SISMEMBER → 0 → SADD relations + SADD pending + HINCRBY buffer +1
UNLIKE: SISMEMBER → 1 → SREM relations + SREM pending + SADD unliked + HINCRBY buffer -1
→ 4개 자료구조가 하나의 Lua Script 안에서 원자 조작됨

Pub/Sub Self-skip:
Instance A → PUBLISH (sourceInstanceId=A)
Instance A → 수신 → sourceInstanceId == myInstanceId → 무시
Instance B → 수신 → sourceInstanceId != myInstanceId → L1 Cache Evict
```

**[4장] 구현:**
Atomic Toggle Lua Script로 check+like/unlike+count를 원자 실행. Unlike도 배치 스케줄러로 이관(Hot path DB 호출 제거). JOIN FETCH 제거(1000 불필요 쿼리/초→메모리 계산 0ms). Redis Pub/Sub+Self-skip으로 인스턴스 간 캐시 무효화. Self-like 방지(JWT에서 OCID 목록 로드). 분산 락은 pg_try_advisory_xact_lock(waitTime=0, leaseTime=30s)으로 AOP 분리.

```
Atomic Toggle Lua Script:
KEYS: {likes}:relations, {likes}:pending, {likes}:buffer, {likes}:unliked
ARGV: ocid, fingerprint
local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 1 then
  redis.call('SREM', KEYS[1], ARGV[1])
  redis.call('HINCRBY', KEYS[3], ARGV[1], -1)
  return 0
else
  redis.call('SADD', KEYS[1], ARGV[1])
  redis.call('HINCRBY', KEYS[3], ARGV[1], 1)
  return 1
end

Pub/Sub Self-skip:
Instance A → PUBLISH "like:toggle" {ocid, sourceInstanceId=A}
Instance A → SUBSCRIBE → sourceInstanceId == myInstanceId → 무시
Instance B → SUBSCRIBE → sourceInstanceId != myInstanceId → L1 Cache Evict
```

```
Atomic Toggle Lua Script:
KEYS: {likes}:relations, {likes}:pending, {likes}:buffer, {likes}:unliked
ARGV: ocid, fingerprint
local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 1 then
  redis.call('SREM', KEYS[1], ARGV[1])
  redis.call('HINCRBY', KEYS[3], ARGV[1], -1)
  return 0
else
  redis.call('SADD', KEYS[1], ARGV[1])
  redis.call('HINCRBY', KEYS[3], ARGV[1], 1)
  return 1
end

Pub/Sub Self-skip:
Instance A → PUBLISH "like:toggle" {ocid, sourceInstanceId=A}
Instance A → SUBSCRIBE → sourceInstanceId == myInstanceId → 무시
Instance B → SUBSCRIBE → sourceInstanceId != myInstanceId → L1 Cache Evict
```

**[5장] 결과:**
DB QPS 2,500~3,500/s → <200/s (**12~17배 감소**). P99 Latency unlike 22~35ms→8~12ms(3배), like 10~15ms→3~5ms(3배). HikariCP 사용률 75~125%→10~15%(7배 감소). Redis RTT per request 3~4회→1회. 단일 인스턴스 한계를 벗어나 Scale-out 가능 구조 완성.

```
Before/After 성능 비교 (부하테스트 기준):
│ 메트릭           │ Before      │ After    │ 개선율   │
│ DB QPS           │ 2,500-3,500 │ <200     │ 12-17x ↓│
│ P99 unlike       │ 22-35ms     │ 8-12ms   │ 3x ↓    │
│ P99 like         │ 10-15ms     │ 3-5ms    │ 3x ↓    │
│ Redis RTT        │ 3-4회       │ 1회      │ 3-4x ↓  │
│ HikariCP 사용륜  │ 75-125%     │ 10-15%   │ 7x ↓    │
```

**[배운 점]**
1. **TOCTOU는 분산 시스템의 숙적:** Check-Then-Act는 원자성이 없으면 항상 레이스 컨디션을 낳는다. Lua Script, DB Trigger, 잠금 기 등 원자적 연산이 필수적이다.
2. **Redis Pub/Sub은 최후의 수단:** Self-skip 메커니즘으로 불필요한 캐시 무효화를 회피했지만, 여전히 네트워크 오버헤드가 있다. PostgreSQL 전환(Project 6) 후 제거하게 되었다.
3. **DB 부하 감소의 선순환:** Hot path에서 DB 호출을 제거하니 HikariCP 사용률이 75→10%로 떨어지고, 다른 기능의 성능도 함께 향상되었다.

**[다시 한다면]**
1. **초기부터 분산 환경을 가정할 것:** 단일 인스턴스에서만 테스트하면 Scale-out 시 발생하는 L1 캐시 불일치 문제를 발견할 수 없다.
2. **Lua Script를 최후의 수단으로 볼 것:** Redis Script는 디버깅이 어렵고 모니터링이 제한적이다. DB 트랜잭션으로 해결 가능하면 Lua Script보다 간단하다.
**[배운 점]**
1. **TOCTOU는 분산 시스템의 숙적:** Check-Then-Act는 원자성이 없으면 항상 레이스 컨디션을 낳는다. Lua Script, DB Trigger, 잠금 기 등 원자적 연산이 필수적이다.
2. **Redis Pub/Sub은 최후의 수단:** Self-skip 메커니즘으로 불필요한 캐시 무효화를 회피했지만, 여전히 네트워크 오버헤드가 있다. PostgreSQL 전환(Project 6) 후 제거하게 되었다.
3. **DB 부하 감소의 선순환:** Hot path에서 DB 호출을 제거하니 HikariCP 사용률이 75→10%로 떨어지고, 다른 기능의 성능도 함께 향상되었다.

**[다시 한다면]**
1. **초기부터 분산 환경을 가정할 것:** 단일 인스턴스에서만 테스트하면 Scale-out 시 발생하는 L1 캐시 불일치 문제를 발견할 수 없다.
2. **Lua Script를 최후의 수단으로 볼 것:** Redis Script는 디버깅이 어렵고 모니터링이 제한적이다. DB 트랜잭션으로 해결 가능하면 Lua Script보다 간단하다.

---

## Project 4: 비즈니스 로직과 인프라 혼재를 멀티모듈 분리+Kotlin 마이그레이션로 도메인 격리, 순수 단위 테스트 가능

**[아키텍처 다이어그램]**
```
Before (단일 모듈):
monolith/
  ├── LikeSyncService (Redis + DB 혼재)
  ├── CharacterLike (도메인)
  └── LikeController (웹 계층)
  ↑ 순환 참조 가능, 테스트 어려움

After (멀티모듈):
module-core/       → CharacterLike, LikeToggleResult, LikeTogglePort (Port)
module-infra/      → InMemoryLikeBufferStorage, LikeSyncExecutor (Adapter)
module-app/        → LikeToggleService (Use Case)
module-common/     → LogicExecutor, Exceptions
의존성: app → core ← infra (Port로만 소통, 순환 참조 컴파일 에러)
```

**[1장] 문제:**
Like 도메인 코드가 단일 모듈에 뒤섞여 있었다. LikeSyncService가 Redis 작업과 DB 작업을 동시에 수행하고, 여러 계층이 서로를 참조해 순환 의존이 발생했다. 인프라 없이 순수 비즈니스 로직만 테스트하는 것이 불가능했다.

**[2장] 선택지:**
1) 패키지만 재구성: 빠르지만 의존성 규칙 강제 불가
2) 멀티모듈 분리: module-app(응용)/module-core(도메인)/module-infra(인프라)/module-common(공통). Gradle이 의존성 규칙 강제
3) 마이크로서비스 분리: 완전한 독립 배포 but 운영 복잡도 과도

**[3장] 결정:**
옵션 2를 선택했다. Gradle 멀티모듈로 물리적 분리하면 순환 의존이 컴파일 에러로 차단된다. Like 도메인은 Domain→module-core, Port→module-core 인터페이스, Infra→module-infra Adapter, App→module-app Use Case로 분리. 전체 Java→Kotlin 마이그레이션도 함께 진행.

**[4장] 구현:**
module-core에 CharacterLike, LikeId, LikeToggleResult, Ports(LikeAtomicFetchStrategy, CompensationCommand) 이동. module-infra에 InMemoryLikeBufferStorage, LikeSyncExecutor 이동. module-app에 LikeToggleService, LikeProcessor, DatabaseLikeProcessor 이동. Java-Kotlin interop 문제(CGLIB 프록시 실패→open 키워드, Bean 생성 불가→@NoArgsConstructor, Nullable 불일치→Platform type 명시)를 단계적으로 해결.

```
module-core (순수 Kotlin):
data class CharacterLike(
    val id: Long?,
    val targetOcid: String,
    val likerAccountId: String,
    val createdAt: LocalDateTime
) {
    fun isSelfLike(): Boolean = targetOcid == likerAccountId
    companion object { fun create(...): CharacterLike }
}

module-infra (Adapter):
class InMemoryLikeBufferStorage : LikeBufferStrategy {
    private val likeCache = Caffeine.newBuilder()...
    override fun increment(userIgn: String, delta: Long): Long
    override fun fetchAndClear(limit: Int): Map<String, Long>
}

Java-Kotlin Interop 해결:
- open class CharacterLikeEntity (CGLIB 프록시용)
- @NoArgsConstructor @JvmOverloads (Bean 생성)
- fun nullableMethod(param: String?): String? (Platform type 명시)
```

```
모듈 분리 결과:
module-core/  → Domain Model + Ports (순수 Kotlin, 프레임워크 독립)
module-infra/ → Adapters (Redis, DB, External API)
module-app/   → Use Cases (Controller, Scheduler, Application Service)
module-common/→ Cross-cutting (LogicExecutor, Exceptions)

의존성 규칙: module-app → module-core ← module-infra
                    (인터페이스/Port로만 소통)
```

**[5장] 결과:**
비즈니스 로직과 인프라가 물리적으로 분리. module-core는 순수 Kotlin으로 프레임워크 독립적. Gradle이 의존성 규칙을 강제(순환 참조 시 컴파일 에러). 순수 단위 테스트 가능(Mock 없이 도메인 로직만 테스트). Java-Kotlin interop 문제 4건 해결.

```
해결된 interop 문제들:
│ 문제              │ 원인                  │ 해결             │
│ Bean 찾을 수 없음 │ Kotlin data class     │ @NoArgsConstruct │
│ CGLIB 프록시 실패 │ Kotlin class 기본final│ open 키워드      │
│ Nullable 불일치   │ Kotlin null-safety    │ Platform type    │
│ 생성자 인자 순서  │ named vs positional   │ @JvmOverloads    │
```

**[배운 점]**
1. **물리적 분리가 논리적 분리를 강제한다:** Gradle 멀티모듈로 물리적으로 분리하니, 순환 참조가 컴파일 에러로 즉시 발견된다. 단순한 패키지 재구성으로는 이 불변식을 보장할 수 없다.
2. **Kotlin의 Null-safety는 도메인 모델을 정확하게 만든다:** `data class CharacterLike(val targetOcid: String)`에서 컴파일러가 null 체크를 강제하므로, NPO (Null Pointer Overflow)를 방지한다.
3. **Interop 비용을 과소평하지 말 것:** Java→Kotlin 마이그레이션은 단순한 문법 변환이 아니라, 생성자 방식(data class vs @AllArgsConstructor), Nullability(@Nullable vs ?), CGLIB 프록시(final vs open) 등의 차이를 해결해야 한다.

**[다시 한다면]**
1. **초기부터 멀티모듈로 시작할 것:** 단일 모듈로 시작하면 "나중에 분리하자"는 영원히 어려워진다. Gradle 설정은 프로젝트 초기부터 모듈 구조로 잡을 것.
2. **Kotlin 마이그레이션은 도메인부터 시작할 것:** module-core부터 Kotlin으로 전환하면, 인프라 계층(Java)과 인터페이스(Port)로 깔끔하게 분리된다.

---

## Project 5: 모듈 간 강결합을 헥사고날 아키텍처 Port/Adapter로 인프라 독립 달성, Redis→PostgreSQL 전환 시 module-core 0줄 변경

**[아키텍처 다이어그램]**
```
헥사고날 아키텍처 (Port/Adapter):
                    module-app
                        ↑
                        │ Use Case
                        │
              ┌─────────┴─────────┐
              │  module-core      │
              │  (Domain + Ports) │
              │  - LikeTogglePort (inbound)
              │  - LikeBufferStrategy (outbound)
              │  - LikeAtomicFetchStrategy
              └─────────▲─────────┘
                        │ Adapter 구현
                        │
              ┌─────────┴─────────┐
              │ module-infra      │
              │ - InMemoryLikeBufferStorage
              │ - LikeSyncExecutor
              │ - CharacterOcidAdapter
              └───────────────────┘
```

**[1장] 문제:**
LikeSyncScheduler에 비즈니스 로직(동기화 정책)과 인프라 로직(Redis 접근, DB 쿼리)이 혼재되어 있었다. 멀티모듈로 분리했지만, module-core가 아직 인프라 기술을 알고 있었다. Redis를 PostgreSQL로 바꾸려면 module-core까지 수정해야 하는 구조적 문제가 있었다.

**[2장] 선택지:**
1) Service Locator 패턴: 런타임에 구현체 선택. 의존성이 숨겨져 테스트 어려움
2) 헥사고날(Ports & Adapters): module-core는 Port(인터페이스)만 정의, module-infra가 Adapter(구현체) 제공. DIP 원칙으로 인프라 교체 가능
3) 이벤트 기반 분리: 이벤트로 모듈 간 통신. 비동기 처리로 지연 발생 가능

**[3장] 결정:**
옵션 2를 선택했다. module-core에 Port(인터페이스)만 정의하고, module-infra가 Adapter로 구현한다. module-core는 Redis, Caffeine, PostgreSQL 등 **어떤 인프라 기술도 모른다**. 이 구조가 6장에서 Redis→PostgreSQL 전환을 module-core 코드 0줄 변경으로 가능하게 만들 것이다.

```
헥사고날 아키텍처 (Port/Adapter):
module-app (Use Cases)
  LikeToggleService ← LikeProcessor (interface)
       │ (Port)
module-core (Domain)
  CharacterLike, LikeToggleResult
  LikeAtomicFetchStrategy (Port: "무엇을 할 것인가")
  CompensationCommand (Port)
       ↑ (인터페이스 구현)
module-infra (Adapters)
  InMemoryLikeBufferStorage (Adapter: "어떻게 할 것인가")
  LikeSyncExecutor (Adapter)
  DatabaseLikeProcessor (Adapter)
```

**[4장] 구현:**
ADR-003으로 LikeSyncScheduler를 헥사고날로 리팩토링. module-core에 LikeAtomicFetchStrategy(Port), CompensationCommand(Port) 정의. module-infra에 InMemoryLikeBufferStorage(Adapter), LikeSyncExecutor(Adapter) 구현. ADR-012로 Like 패키지 Core/Infra 완전 분리. ADR-004로 LikeToggleService를 Application 계층 Use Case로 이관.

```
Port (module-core):
interface LikeTogglePort {
    fun toggleLike(targetUserIgn: String, likerAccountId: String, myOcids: Set<String>): LikeToggleResult
    fun toggleLikeWithCount(...): LikeToggleWithCount
}

interface LikeBufferStrategy {
    fun increment(userIgn: String, delta: Long): Long
    fun fetchAndClear(limit: Int): Map<String, Long>
    enum class StrategyType { IN_MEMORY, REDIS, POSTGRES }
}

Adapter (module-infra):
@Component
@Profile("local")
class InMemoryLikeBufferStorage : LikeBufferStrategy {
    private val likeCache = Caffeine.newBuilder()...
    override fun increment(userIgn: String, delta: Long): Long
}

Service (module-app):
@Service
class LikeToggleService(
    private val characterOcidPort: CharacterOcidPort,
    private val characterLikeRepository: CharacterLikeRepository
) : LikeTogglePort {
    @Transactional
    override fun toggleLike(...): LikeToggleResult
}
```

**[5장] 결과:**
의존성 역전(DIP), 단일 책임(SRP), 인터페이스 분리(ISP), 개방-폐쇄(OCP) 원칙 모두 충족. module-core는 순수 Kotlin, 프레임워크 독립. **Redis→PostgreSQL 전환 시 module-core 코드 0줄, module-app은 설정 파일만 변경, module-infra만 Adapter 구현체 교체**. 이것이 6장에서 결정적 증거가 됨.

```
달성된 아키텍처 원칙:
│ 원칙        │ 상태 │ 비고                        │
│ DIP         │ ✅  │ App → Core ← Infra          │
│ SRP         │ ✅  │ 각 클래스가 하나의 역할      │
│ ISP         │ ✅  │ Port가 최소 메서드           │
│ OCP         │ ✅  │ Infra 교체 가능              │
│ 비즈니스 격리│ ✅  │ Core는 순수 Kotlin          │
```

**[배운 점]**
1. **DIP는 인프라 교체의 핵심이다:** module-core가 Port(인터페이스)만 의존하고, module-infra가 Adapter(구현체)를 제공하니, Redis→PostgreSQL 전환이 module-core 0줄 변경으로 가능했다.
2. **Port는 최소한의 메서드만 노출해야 한다:** LikeBufferStrategy가 increment(), fetchAndClear()만 제공하고, 내부 구현(Caffeine vs Redis vs PostgreSQL)을 감추니, 교체 시 영향 범위가 최소화되었다.
3. **아키텍처는 진화한다:** Project 4에서 멀티모듈로 분리하고, Project 5에서 헥사고날로 정제하니, Project 6에서 인프라 전환에 대비할 수 있었다. 이 순서는 자연스러운 아키텍처 진화 과정이었다.

**[다시 한다면]**
1. **초기부터 Port를 먼저 정의할 것:** 구현체 없이 Port(인터페이스)부터 먼저 설계하면, 도메인 요구사항에 집중할 수 있고 구현체 교체가 훨씬 쉬워진다.
2. **Profile로 구현체를 전환할 것:** @Profile("local"), @Profile("prod")로 InMemoryLikeBufferStorage와 RedisLikeBufferStorage를 전환하니, 개발 환경과 프로덕션 환경의 인프라 차이를 손쉽게 관리했다.

---

## Project 6: Redis 운영 복잡도+장애 이중화를 PostgreSQL PGMQ+UNLOGGED TABLE 전환으로 Redis 의존성 완전 제거, module-core 0줄 변경

**[기능 대치 매핑]**
```
Redis → PostgreSQL 대체 매핑:
┌─────────────────────────┬──────────────────────────┬─────────────────┐
│ Redis 기능              │ PostgreSQL 대체          │ 비고            │
├─────────────────────────┼──────────────────────────┼─────────────────┤
│ HASH {likes}:buffer     │ UNLOGGED TABLE           │ 2-5x 빠름       │
│ SET {likes}:relations   │ character_like 테이블    │ 직접 DB 쓰기     │
│ PUBLISH/SUBSCRIBE       │ PGMQ                     │ 메시지 큐        │
│ Lua Script              │ SQL Transaction          │ DB 레벨 원자성   │
│ Caffeine L1             │ 그대로 유지              │ L1은 로컬        │
└─────────────────────────┴──────────────────────────┴─────────────────┘
```

**[1장] 문제:**
Redis는 별도 클러스터 운영이 필요하고, AOF/RDB가 있어도 비영구적이며, 장애 도메인이 Redis+DB 이중이었다. 좋아요 버퍼(HASH), 관계(SET), 이벤트(PUB/SUB), 원자성(Lua Script)이 모두 Redis에 종속되어 있었다.

**[2장] 선택지:**
1) Redis 유지+고가용성 구성: Redis Cluster + Sentinel. 운영 복잡도 지속
2) PostgreSQL 전면 전환: 이미 L2 캐시+Advisory Lock으로 사용 중. PGMQ(메시지 큐), UNLOGGED TABLE(버퍼)로 대체 가능
3) 하이브리드: 일부만 PostgreSQL. Redis 의존성은 잔존

**[3장] 결정:**
옵션 2를 선택했다. 5장에서 세운 헥사고날 아키텍처 덕분에 인프라 교체가 Port의 구현체만 바꾸면 되는 일이 되었다. Redis HASH→UNLOGGED TABLE(WAL 기록 안 해 2~5배 빠름), Redis SET→character_like 테이블, Redis Pub/Sub→PGMQ, Lua Script→SQL Transaction. 스케줄러 타이밍은 2x multiplier로 재설계(1s/3s/5s → 2s/4s).

```
Redis → PostgreSQL 대체 매핑:
│ Redis 기능       │ PostgreSQL 대체        │ 비고              │
│ HASH {likes}:buf │ UNLOGGED TABLE        │ 2-5x 빠름         │
│ SET {likes}:rel  │ character_like 테이블 │ 직접 DB 쓰기      │
│ PUBLISH          │ PGMQ                  │ 메시지 큐          │
│ Lua Script       │ SQL Transaction       │ DB 레벨 원자성     │
│ Caffeine L1      │ 그대로 유지           │ L1은 로컬          │
```

**[4장] 구현:**
RedisLikeBufferStorage→InMemoryLikeBufferStorage(Adapter 교체). RedisLikeEventPublisher/Subscriber→PGMQ 기반 Consumer. RedissonConfig Bean 삭제. build.gradle에서 redisson-spring-boot-starter, bucket4j-redisson 제거. application.yml에서 spring.redis.* 전체 삭제. docker-compose에서 Redis Master+Slave+3 Sentinel 제거. Testcontainers 기반 통합 테스트+카오스 테스트(PGMQ 장애, 네트워크 분할, 동시 쓰기, 장애 복구) 추가.

```
V100__like_postgres_migration.sql:
-- UNLOGGED table for high-performance like buffering
CREATE UNLOGGED TABLE IF NOT EXISTS character_like_buffer (
    id BIGSERIAL PRIMARY KEY,
    character_name VARCHAR(13) NOT NULL,
    user_id BIGINT NOT NULL,
    delta INTEGER NOT NULL DEFAULT 1,
    processed BOOLEAN NOT NULL DEFAULT FALSE
);

-- Main like relation table (durable)
CREATE TABLE IF NOT EXISTS character_like_relation (
    id BIGSERIAL PRIMARY KEY,
    character_name VARCHAR(13) NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT uk_like_relation UNIQUE (character_name, user_id)
);

gradle dependencies 변경:
- implementation 'org.redisson:redisson-spring-boot-starter'
- implementation 'com.bucket4j:bucket4j-redisson'
+ implementation 'io.github.jmalloc:pgmq-driver'

docker-compose 변경:
- redis-master + redis-slave + redis-sentinel×3
+ postgres (already exists)
```

**[5장] 결과:**
Redis/Redisson 의존성 **완전 제거**. Redisson 관련 파일 28개 삭제. docker-compose 절반 이하로 감소. module-core **0줄 변경**, module-app은 설정 파일만, module-infra만 Adapter 교체. **헥사고날 아키텍처의 결정적 증거**: 인프라를 교체했는데 비즈니스 로직은 단 한 줄도 안 바뀌었다.

```
마이그레이션 영향 범위:
│ 모듈          │ 변경 내용              │
│ module-core   │ 0줄 변경               │ ← 핵심!
│ module-app    │ 설정 파일만             │
│ module-infra  │ Adapter 구현체 교체     │
│ Unit 테스트   │ 변경 없음               │
│ 신규 테스트   │ Testcontainers 기반 추가│
```

**[배운 점]**
1. **헥사고날 아키텍처의 결정적 증거:** Redis를 완전히 제거했는데 module-core 코드는 단 한 줄도 바뀌지 않았다. Port(인터페이스)가 있고 Adapter만 교체하니, 인프라 교체가 비즈니스 로직에 영향을 주지 않았다.
2. **UNLOGGED TABLE의 성능 이점:** WAL(Write-Ahead Log)을 기록하지 않으니 2~5배 빠르다. 버퍼 테이블처럼 크래시 시 데이터 유실이 허용되는 용도로 완벽했다.
3. **인프라 단순화는 운영 복잡도를 줄인다:** Redis Cluster+Sentinel을 관리할 필요 없이, PostgreSQL 하나만 관리하니 장애 도메인이 단일화되고 운영 오버헤드가 크게 감소했다.

**[다시 한다면]**
1. **초기부터 PostgreSQL 단일 인프라를 고려할 것:** Redis는 성능이 좋지만, 운영 복잡도가 크다. 단일 인프라로 해결 가능하면 굳이 다중 인프라를 도입할 필요가 없다.
2. **PGMQ 도입을 더 빨리 결정할 것:** PostgreSQL 메시지 큐는 충분히 성능이 좋고, ACID를 보장한다. Redis Pub/Sub보다 더 간단하고 더 안정적이었다.

---

## Project 7: Buffer+PGMQ 경유 복잡도+like_count 불일치를 Direct DB Transaction+Trigger로 단일 트랜잭션 원자성 달성, 123일 여정 완결

**[시퀀스 다이어그램]**
```
Before (Buffer+PGMQ 경유):
Client → LikeToggleService → InMemoryBuffer.increment()
                              ↓
                              PGMQ.publish() → [메시지 유실 가능]
                              ↓
                              Consumer → DB INSERT/UPDATE
                              ↑ 4단계, 비원자적, 장애 포인트 3곳

After (Direct DB):
Client → LikeToggleService
        → @Transactional
          → OCID Resolution (L1 → L2 → DB → Nexon API)
          → Self-Like Check (fingerprint → my OCIDs 비교)
          → DB INSERT character_like ON CONFLICT DO NOTHING
             또는 DELETE character_like
          → DB Trigger fn_like_count_trigger() 자동 ±1
          → SELECT COUNT(*) FROM character_like
        → Response { liked, likeCount }
        ↑ 단일 트랜잭션, 원자적, Lua Script도 Redis도 PGMQ도 필요 없음
```

**[1장] 문제:**
Redis 제거 후에도 Like 토글은 In-Memory Buffer→PGMQ Publish→Consumer→DB Write의 복잡한 경로를 거쳤다. 로컬 상태(Buffer)와 메시지 유실 가능(PGMQ)이 여전히 존재했다. INSERT character_like와 UPDATE game_character.like_count가 별도 SQL이라 둘 사이에 실패하면 count가 틀어졌다. Self-like 방지도 현재 로그인 캐릭터 1개만 차단해서, 같은 계정의 다른 캐릭터로 우회 가능했다. **참고:** fingerprint는 HMAC-SHA256(apiKey) 기반이므로, API Key 재발행 시 새 지문이 생성되어 기존 차단 목록에 미포함될 수 있다. Login 시 Nexon API로 실제 계정 검증을 수행해 보완한다.

```
Before (Buffer+PGMQ 경유):
Toggle → InMemory Buffer → PGMQ Publish → Consumer → DB Write
         ↕ 로컬 상태            ↕ 메시지 유실 가능
→ 4단계, 비원자적, 장애 포인트 3곳

문제 예측 6건 (사전 리뷰에서 등록):
1. Caffeine relation maps diverge → 해결됨 (PostgreSQL 직접 쓰기)
2. PGMQ cold restart → 해결됨 (UNLOGGED + 보상)
3. No transaction gap → Direct DB 토글로 우회
4. LikeSyncRequest NPE → OcidResolutionService로 해결
5. OCID resolution async race → 동기 설계로 해결
6. No idempotency → DB UNIQUE + Fingerprint로 해결
```

**[2장] 선택지:**
1) Buffer+PGMQ 경로 유지+보강: 복잡도 지속, 장애 포인트 여전
2) Direct DB Transaction: 인프라가 PostgreSQL 하나인데 왜 버퍼와 메시지 큐를 거치는가? DB에 직접 쓰는 것이 가장 단순하고 가장 정확
3) CQRS 적용: 읽기/쓰기 분리. 오버엔지니어링

**[3장] 결정:**
옵션 2를 선택했다. 인프라가 PostgreSQL 하나인 상황에서 버퍼와 메시지 큐를 거치는 것은 불필요한 복잡도였다. 단일 DB 트랜잭션 안에서 INSERT character_like ON CONFLICT DO NOTHING(좋아요) 또는 DELETE(취소) + DB Trigger로 like_count 자동 ±1. 애플리케이션에서 incrementLikeCount()를 완전히 제거하고 **DB가 정합성을 보장**하게 만들었다.

**성능 근거 (ADR-344):** Like 토글 QPS = 5~20/sec. Direct DB indexed write = ~10~20ms. 사용자 인지 임계값 = ~100ms. 5~20 QPS에서 10~20ms 응답은 사용자 경험에 영향 없음. Buffer+PGMQ+Worker의 3단계, 장애 포인트 3곳 vs Direct DB의 단일 트랜잭션, 장애 포인트 0 (DB만 정상하면 됨).

**성능 근거 (ADR-344):** Like QPS = 5~20/sec. Direct DB indexed write = ~10-20ms. 사용자 인지 임계값 ~100ms. 따라서 Buffer+PGMQ 경유 없이 Direct DB로도 충분한 응답 시간 확보 가능.

```
Direct DB 토글 흐름:
POST /api/v2/characters/{userIgn}/like
1. JWT Authentication (fingerprint 포함)
2. OCID Resolution (L1 → L2 → DB → Nexon API)
3. Self-Like Check (fingerprint로 DB 조회 → 내 OCID 목록과 비교)
4. DB Transaction (READ COMMITTED):
   INSERT character_like ON CONFLICT DO NOTHING → liked=true
   또는 DELETE character_like → liked=false
5. DB Trigger: like_count 자동 ±1
6. Response: { liked: boolean, likeCount: long }
→ 단일 트랜잭션, 원자적, Lua Script도 Redis도 PGMQ도 필요 없음
```

**[4장] 구현:**
LikeToggleService를 @Transactional로 감싸 단일 트랜잭션에서 전체 처리. DatabaseLikeProcessor: INSERT 시 DataIntegrityViolationException catch→이미 좋아요 상태. DB Trigger fn_like_count_trigger()로 INSERT/DELETE 시 game_character.like_count 자동 ±1. fingerprint 컬럼으로 같은 기기의 모든 캐릭터 식별해 self-like 방지 정확도 향상. Login 시 Nexon API로 실제 계정 검증. 3차 Consensus Review(Architect+Critic+Code-Reviewer) 거쳐 DIP 위반, Trigger+앱 double-count 등 논쟁점 해결.

```
LikeToggleService (@Transactional):
@Service
class LikeToggleService(
    private val characterOcidPort: CharacterOcidPort,
    private val characterLikeRepository: CharacterLikeRepository
) : LikeTogglePort {
    @Transactional
    override fun toggleLikeWithCount(
        targetUserIgn: String,
        likerAccountId: String,
        myOcids: Set<String>
    ): LikeToggleWithCount {
        // 1. OCID Resolution
        val targetOcid = characterOcidPort.resolveOcid(targetUserIgn) ?:
            throw CharacterNotFoundException(targetUserIgn)

        // 2. Self-Like Prevention (fingerprint-based)
        if (myOcids.contains(targetOcid)) {
            throw SelfLikeNotAllowedException(targetUserIgn, targetOcid)
        }

        // 3. Toggle Relation (단일 트랜잭션)
        val result = executor.execute({
            val exists = characterLikeRepository
                .existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)
            if (!exists) {
                characterLikeRepository.insertIfAbsent(targetOcid, likerAccountId)
                LikeToggleResult.LIKED
            } else {
                characterLikeRepository.deleteByTargetOcidAndLikerAccountId(
                    targetOcid, likerAccountId
                )
                LikeToggleResult.UNLIKED
            }
        }, TaskContext.of("LikeToggleService", "Toggle", targetUserIgn))

        // 4. Count Query (Trigger 보장)
        val likeCount = characterLikeRepository.countByTargetOcid(targetOcid)
        return LikeToggleWithCount(result, likeCount)
    }
}

V104__like_count_trigger.sql:
CREATE OR REPLACE FUNCTION fn_like_count_trigger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE game_character
        SET like_count = COALESCE(like_count, 0) + 1
        WHERE ocid = NEW.target_ocid;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) - 1, 0)
        WHERE ocid = OLD.target_ocid;
        RETURN OLD;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_like_count
    AFTER INSERT OR DELETE ON character_like
    FOR EACH ROW EXECUTE FUNCTION fn_like_count_trigger();

V103__like_fingerprint_account_id.sql:
ALTER TABLE game_character ADD COLUMN fingerprint VARCHAR(64);
ALTER TABLE game_character ADD COLUMN account_id VARCHAR(64);
CREATE INDEX idx_game_character_fingerprint
    ON game_character (fingerprint, ocid) WHERE fingerprint IS NOT NULL;
```

**[5장] 결과:**
Buffer→PGMQ→Consumer의 4단계를 **단일 DB 트랜잭션**으로 축소. like_count 정합성을 DB Trigger가 보장(애플리케이션 코드에서 카운트 조작 완전 제거). Fingerprint 기반 self-like 방지로 동일 계정의 모든 캐릭터 차단. 3차 리뷰 통과. 123일 여정 완결: 비관적 락→Redis Lua Script→Direct DB Transaction. **결국 가장 단순한 형태로 회귀**했지만, 그 회귀는 123일간의 학습과 아키텍처 진화가 있었기에 가능했다.

```
123일 여정 요약:
│ 단계     │ 기간       │ 아키텍처   │ 인프라                  │
│ 탄생     │ 2025.11-12 │ 모놀리식   │ MySQL + Caffeine        │
│ 원자성   │ 2026.01초  │ Proxy     │ MySQL + Redis Lua       │
│ Scale-out│ 2026.01말  │ 모놀리식   │ MySQL + Redis Pub/Sub   │
│ 모듈분리 │ 2026.02    │ 멀티모듈   │ MySQL + Redis + Kotlin  │
│ 헥사고날 │ 2026.03초  │ Hexagonal │ MySQL + Redis + Kotlin  │
│ PostgreSQL│ 2026.03중 │ Hexagonal │ PostgreSQL + PGMQ       │
│ Direct DB│ 2026.03말  │ Hexagonal │ PostgreSQL (Trigger)    │

핵심적 변화:
2025.11: Controller → DB (동기, 원자성 없음)
2026.01: Controller → Redis Lua Script → Scheduler → DB
2026.03: Controller → DB Transaction + Trigger → 완결
→ 복잡도가 최고조에 달한 후, 가장 단순한 형태로 회귀
```

**[배운 점]**
1. **복잡도는 종종 최적화의 착각에서 온다:** Buffer+PGMQ+Worker는 "성능 최적화"라고 생각했지만, 실제로는 QPS 5~20/sec에서 10~20ms 응답에 불과했다. 직관에 반하지만, 가장 단순한 Direct DB가 가장 정확하고 충분히 빨랐다.
2. **DB Trigger는 데이터 정합성의 최후의 보험:** 애플리케이션에서 incrementLikeCount()를 완전히 제거하고, DB가 정합성을 보장하게 하니 like_count 불일치가 완전히 사라졌다. Trigger는 숨겨진 비즈니스 로직이지만, 카운트 정합성에는 가장 적합한 솔루션이었다.
3. **3차 리뷰의 가치:** DIP 위반(Trigger가 인프라 로직), double-count 위험(Trigger+앱 중복), fingerprint 재발행 문제 등을 Architect+Critic+Code-Reviewer 3인이 철저히 검토하고 논쟁한 덕분에, 결함 없는 릴리즈가 가능했다.

**[다시 한다면]**
1. **성능 요구사항을 먼저 명확히 할 것:** "최대한 빨라야 한다"가 아니라 "QPS 5~20에서 p99 100ms 이면 충분하다"처럼 정량적 목표를 설정했더라면, Project 7 단계에서 바로 Direct DB로 갔을 것이다.
2. **Trigger를 더 일찍 고려할 것:** "애플리케이션 로직을 DB에 넣으면 안 된다"는 교조적인 생각을 버리고, 카운트 정합성에는 Trigger가 가장 적합하다는 사실을 더 일찍 받아들였을 것이다.
3. **fingerprint 재발행 대책을 미리 수립할 것:** API Key 재발행 시 새 fingerprint가 생성되는 문제를 미리 예측하고, Nexon API 실제 계정 검증을 Login 시점에 통합했더라면 self-like 우회를 더 빨리 차단했을 것이다.
