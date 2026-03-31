# 1장: 탄생 — 동시성의 늪

> *2025년 11월 28일 ~ 2025년 12월 28일*

---

## 1.1 시작: 비관적 락의 기각

2025년 11월 28일, 첫 번째 동시성 테스트가 실패했다.

```
92707f08 perf: Concurrency test failure - Rejected Pessimistic Lock due to high latency
```

**비관적 락(Pessimistic Lock)**이 고부하 환경에서 과도한 지연을 일으킨 것이다. 좋아요 토글은 사용자가 버튼을 누를 때마다 발생하는 고빈도 연산이다. `SELECT ... FOR UPDATE`로 매 요청마다 행을 잠그는 것은 성능적으로 받아들일 수 없었다.

다음 날인 11월 29일, 두 개의 커밋이 연달아 들어왔다:

```
054af475 feat(concurrency): Implement high-contention lock mechanism for like system
5e4f003f feat(concurrency): Implement concurrency control for 'Like' feature & Optimize DB Lock performance
```

**낙관적 락(Optimistic Lock)**으로의 전환과 더 정교한 락 메커니즘을 도입했다. 하지만 이것은 시작에 불과했다.

---

## 1.2 디자인 패턴의 도입

12월 19일, 대규모 리팩토링이 이루어졌다.

```
ac1bf6d4 refactor: 디자인 패턴 도입 및 동시성 제어 엔진 고도화 (Proxy, Decorator)
```

### 무엇이 바뀌었나

Like 도메인에 **Proxy 패턴**과 **Decorator 패턴**이 도입되었다. 핵심 아이디어는:

1. **캐싱 계층 분리**: 캐시 로직을 비즈니스 로직에서 분리
2. **동시성 제어 추상화**: 락 메커니즘을 교체 가능한 전략으로 캡슐화
3. **횡단 관심사 분리**: 로깅, 메트릭, 예외 처리를 별도 계층으로

이 시점의 Like 흐름은:

```
Controller → Service → Lock → Cache Check → DB Read/Write → Response
```

### 배운 교훈

> **"비관적 락은 정확하지만 느리다. 낙관적 락은 빠르지만 경합 시 재시도 비용이 든다. 정답은 상황에 맞는 전략을 교체 가능하게 만드는 것이다."**

---

## 1.3 관측 가능성의 부재

12월 23일, 첫 번째 모니터링 기능이 추가되었다.

```
f2323727 feat(monitoring): 시스템 관찰 가능성 확보 및 장애 자가 진단 알림 기능 구현 (#55)
```

**문제**: Like 도메인에는 메트릭이 없었다. 좋아요 수가 이상해졌을 때 원인을 추적할 수 없었다. "좋아요 수가 이상해요"라는 유저 리포트만 있고, 로그에는 아무것도 없었다.

**해결**: Micrometer 기반 핵심 지표를 정의:

| 메트릭 | 설명 |
|--------|------|
| `like.toggle.duration` | 토글 응답 시간 |
| `like.buffer.size` | 버퍼 적재량 |
| `like.sync.duration` | DB 동기화 소요 시간 |
| `like.error.count` | 에러 발생 횟수 |

---

## 1.4 Graceful Shutdown — 서버가 꺼질 때의 문제

같은 날, 중요한 문제가 하나 더 해결되었다.

```
d8840de8 feat: 좋아요 버퍼 동기화를 위한 Graceful Shutdown 구현 (#26) (#60)
```

### 발견된 문제

Like 시스템은 **Write-Behind Buffer** 패턴을 사용하고 있었다:

```
사용자 요청 → In-Memory Buffer 적재 → 주기적 DB Flush
```

서버가 재시작되면 **아직 DB에 flush되지 않은 버퍼의 좋아요가 전부 유실**되었다.

### 해결책: Graceful Shutdown

```java
@PreDestroy
public void gracefulShutdown() {
    // 1. 새로운 요청 차단
    // 2. 버퍼의 남은 데이터를 DB로 flush
    // 3. 진행 중인 동기화 완료 대기
}
```

Spring의 `@PreDestroy` 훅을 활용해, 서버 종료 신호(SIGTERM)를 받으면 버퍼를 먼저 flush하고 안전하게 종료한다.

### PR #60과 PR #89

이 기능은 PR #60으로 시작되어 PR #89에서 완성되었다:

```
d8840de8 feat: 좋아요 버퍼 동기화를 위한 Graceful Shutdown 구현 (#26) (#60)
52f576a2 Feature/27 graceful shutdown like buffer (#89)
```

관련 이슈 #27의 요구사항:
> *"Scale-out 확장을 대비한 저장소 전체 코드 분석 및 동시성 제어 구조 리팩토링"*

---

## 1.5 분산 락 — 여러 스케줄러가 동시에 달려들 때

12월 24일, 새로운 문제가 발견되었다.

```
09cf532d [Ops] 분산 환경에서의 스케줄러 중복 실행 방지 (Distributed Lock) (#47) (#61)
```

### 상황

LikeSyncScheduler가 `@Scheduled`로 3초마다 실행된다. Scale-out 환경에서 **3대의 서버가 각각 스케줄러를 실행**하면:

```
[Instance A] 3초마다 syncCount() → DB UPDATE
[Instance B] 3초마다 syncCount() → DB UPDATE  ← 중복!
[Instance C] 3초마다 syncCount() → DB UPDATE  ← 중복!
```

같은 데이터가 3번 DB에 쓰인다. **좋아요 수가 3배로 카운트**될 수 있다.

### 해결: 분산 락 AOP

```java
@DistributedLock(key = "like-db-sync-lock", waitTime = 0, leaseTime = 30)
public void globalSyncCount() {
    likeSyncService.syncRedisToDatabase();
}
```

`pg_try_advisory_xact_lock` 기반의 AOP 분산 락을 도입했다. 핵심 설계:

| 설계 선택 | 이유 |
|-----------|------|
| `pg_try_advisory_xact_lock` | 트랜잭션 스코프 — 커밋/롤백 시 자동 해제 |
| `waitTime = 0` | Non-blocking — 락 획득 실패 시 즉시 스킵 |
| `leaseTime = 30s` | 최대 실행 시간 — 데드락 방지 |
| AOP 기반 | 비즈니스 로직과 락 로직 분리 |

> **"세션 스코프 락(`pg_advisory_lock`)은 HikariCP 환경에서 위험하다. 커넥션이 풀로 돌아가도 락이 해제되지 않기 때문이다."**

---

## 1.6 AOP 기반 캐싱 전략

12월 26일, 캐싱과 예외 처리의 체계적 리팩토링이 이루어졌다.

```
57ba1eca refactor: AOP 기반 캐싱 전략 도입 및 예외 처리 체계 리팩토링 (#69)
48aad9f3 refactor: 분산 락 AOP 가독성 개선 (#70)
```

### 등장한 패턴: TieredCache

```
L1 (Caffeine) → L2 (Redis) → SingleFlight → Loader
```

이 3계층 캐시 구조는 향후 Like 도메인 전체의 성능 기반이 된다.

### 예외 처리 체계화

모든 예외 처리를 `LogicExecutor`로 위임하는 규칙이 확립되었다:

```java
// Before: 직접 try-catch
try {
    likeService.toggle(ign);
} catch (Exception e) {
    log.error("...", e);
}

// After: LogicExecutor 위임
return executor.execute(
    () -> likeService.toggle(ign),
    TaskContext.of("LikeService", "toggle", ign)
);
```

---

## 1.7 첫 마일스톤: Scale-out 대응 통합 릴리즈

12월 28일, Phase 1이 마무리되었다.

```
4b326926 release: 분산 환경 확장성(Scale-out) 대응 및 시스템 안정화 통합 릴리즈 (#97)
```

### Phase 1 요약

| 문제 | 해결 | 커밋 |
|------|------|------|
| 비관적 락 지연 | 낙관적 락 + 고성능 락 메커니즘 | `92707f08`, `054af475` |
| 동시성 제어 부재 | Proxy/Decorator 패턴 | `ac1bf6d4` |
| 관측 불가 | Micrometer 메트릭 | `f2323727` |
| 서버 종료 시 데이터 유실 | Graceful Shutdown | `d8840de8` |
| 스케줄러 중복 실행 | 분산 락 AOP | `09cf532d` |
| 캐싱/예외 비체계적 | TieredCache + LogicExecutor | `57ba1eca` |

### 이 시점의 아키텍처

```
┌─────────────────────────────────────────────┐
│                  Controller                  │
│              (HTTP Layer)                    │
├─────────────────────────────────────────────┤
│                  Service                     │
│     (Business Logic + Lock + Cache)          │
├─────────────────────────────────────────────┤
│              In-Memory Buffer                │
│            (Caffeine Cache)                  │
├─────────────────────────────────────────────┤
│              Scheduler (3s/5s)               │
│          (Buffer → DB Flush)                 │
├─────────────────────────────────────────────┤
│                 Database                     │
│        (MySQL + JPA)                         │
└─────────────────────────────────────────────┘
```

**문제점이 여전히 존재했다:**
- Check-Then-Act TOCTOU 레이스 컨디션
- Relation과 Counter의 비원자적 이중 쓰기
- unlike 시 동기 DB DELETE
- Redis 미도입 (인메모리 버퍼 단일 인스턴스 한계)

하지만 기반은 마련되었다. 2장에서 이 문제들이 어떻게 해결되는지 본다.
