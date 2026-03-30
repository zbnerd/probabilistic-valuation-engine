# 2장: 역설적 회귀 — 최적화가 성능을 56% 떨어뜨리다

> "이론상 완벽한 최적화가 실제로는 최악의 결과를 낳을 수 있다."

## 문제: Cache Stampede

1장에서 발견한 핵심 문제 중 하나: 캐시 미스 시 동일 키에 대한 요청이 각각 독립적으로 계산을 수행한다. 인기 캐릭터 "아델"에 100명이 동시 요청하면 Nexon API를 100번 호출한다.

```
Before (Cache Stampede):
Request 1 → Cache MISS → Nexon API → Calculate → Store
Request 2 → Cache MISS → Nexon API → Calculate → Store
...
Request 100 → Cache MISS → Nexon API → Calculate → Store
= 100번의 API 호출 + 100번의 계산
```

## 고민: LocalSingleFlight

2026년 1월 24일, JVM 레벨에서 요청을 합치는 `LocalSingleFlight`를 구현했다. 아이디어는 단순했다:

```
After (Singleflight — 의도):
Request 1 → Cache MISS → Leader가 계산
Request 2-100 → Follower로 대기 → Leader 결과 공유
= 1번의 API 호출 + 1번의 계산
```

Semaphore로 동시 실행을 제어하고, 같은 키에 대한 요청은 첫 번째 요청의 결과를 기다리게 했다.

```kotlin
// 초기 로컬 SingleFlight 핵심 로직 (현재는 제거됨, PostgresSingleFlightStrategy로 대체)
fun <T> execute(supplier: Supplier<T>): T {
    return if (semaphore.tryAcquire())
        supplier.get()    // Leader: 계산 수행
    else waitForResult()  // Follower: 결과 대기
}
```

> **Note**: 이 초기 구현은 현재 코드베이스에서 제거되었다. `PostgresSingleFlightStrategy.kt`가 그 자리를 대체하고 있다.

## 결과: 97 RPS (−56% 회귀)

```
╔════════════════════════════════════════════════════════════╗
║  V4 SINGLEFLIGHT LOAD TEST                                ║
║  100 Users Test:                                           ║
║  - Total Requests:  2,932                                  ║
║  - RPS (avg):       97.42 req/sec                          ║
║  - Success Rate:    100%                                   ║
║  - p50 Latency:     490ms                                  ║
║  - p99 Latency:     1,800ms                                ║
║  - Min Latency:     7ms (cache hit)                        ║
╚════════════════════════════════════════════════════════════╝
```

**223 RPS → 97 RPS. 최적화를 했더니 56% 느려졌다.**

## 원인 분석

문제는 `LocalSingleFlight`가 **캐시 히트마저 blocking**했다는 것이다.

```kotlin
// 문제의 코드 (현재는 제거됨)
fun <T> execute(supplier: Supplier<T>): T {
    return if (semaphore.tryAcquire())
        supplier.get()      // Leader: 캐시 히트든 미스든 실행
    else waitForResult()    // Follower: 캐시 히트인데도 대기!
}
```

L1 캐시에 이미 결과가 있어도, Semaphore를 획득한 Leader가 처리할 때까지 Follower는 기다려야 했다. 99%가 캐시 히트인 상황에서 모든 요청이 불필요하게 직렬화되었다.

```
의도: Cache MISS만 병합
현실: Cache HIT도 병합 → 7ms 응답이 490ms로 지연
```

## 교훈

> **JVM-level request merging은 L1/L2 캐시 히트마저 blocking한다. 캐시가 효율적일수록 이 패턴의 오버헤드가 더 크다.**

## 결정: 롤백

LocalSingleFlight를 즉시 롤백했다. 대신 **다른 접근**이 필요했다:

1. 캐시 히트는 blocking 없이 즉시 반환해야 한다
2. 캐시 미스에만 최적화를 적용해야 한다
3. 아니면... 캐시 히트 경로 자체를 아예 우회하면 되지 않을까?

이 마지막 아이디어가 다음 장의 돌파구가 되었다.

---

> **이 시점의 RPS: 97 (초기 SingleFlight 롤백 후, 다른 최적화는 유지)**
> **커밋**: `418cc04d` feat: V4 API Singleflight 패턴 적용 및 GZIP 응답 최적화
> **관련 이슈**: #262, #263
> **PR**: #262 (Singleflight 도입), 이후 LocalSingleFlight 롤백

**다음 장**: [3장 — 발견: 캐시 히트인데 왜 느리지?](./03_l1_fast_path.md)
