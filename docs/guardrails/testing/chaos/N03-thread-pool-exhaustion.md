---
id: GR-NIGHTMARE-N03
category: testing/chaos
severity: critical
keywords: [thread pool, starvation, virtual threads, async]
languages: [java, kotlin]
---

# N03: ThreadPool Starvation

## DON'T (안티패턴)

```java
// Java - @Transactional 내에서 외부 API 호출
@Transactional
public Order processOrder(OrderDto dto) {
    Order order = repository.save(dto.toEntity());

    // @Transactional은 DB Connection 점유 중
    // 여기서 느린 외부 API 호출 → ThreadPool 고갈
    externalApiService.notifyOrderCreated(order);

    return order;
}
```

```kotlin
// Kotlin - @Transactional 내에서 외부 API 호출
@Transactional
fun processOrder(dto: OrderDto): Order {
    val order = repository.save(dto.toEntity())

    // @Transactional은 DB Connection 점유 중
    // 여기서 느린 외부 API 호출 → ThreadPool 고갈
    externalApiService.notifyOrderCreated(order)

    return order
}
```

**장애 수치 (Before):**
- Tomcat ThreadPool (200개) 전체 소요 시간: ~30초
- Connection Pool (10개) 고갈: 동시 10건 이상 요청 시 타임아웃
- HTTP 503 응답: 최대 150건/분

## DO (베스트 프랙티스)

```java
// Java - @Transactional 범위 최소화 + 비동기 처리
@Transactional
public Order processOrder(OrderDto dto) {
    Order order = repository.save(dto.toEntity());
    return order; // 트랜잭션 종료
}

// 별도 비동기 메서드로 분리
@Async
public void notifyOrderCreated(Long orderId) {
    Order order = repository.findById(orderId);
    externalApiService.notifyOrderCreated(order);
}
```

```kotlin
// Kotlin - @Transactional 범위 최소화 + 비동기 처리
@Transactional
fun processOrder(dto: OrderDto): Order {
    val order = repository.save(dto.toEntity())
    return order // 트랜잭션 종료
}

// 별도 비동기 메서드로 분리
@Async
fun notifyOrderCreated(orderId: Long) {
    val order = repository.findById(orderId)
    externalApiService.notifyOrderCreated(order)
}
```

**개선 수치 (After):**
- Tomcat ThreadPool 여유: 평균 180개 사용
- Connection Pool 회전율: 10x 향상
- HTTP 503 응답: 0건

## 핵심 원칙

1. **Transactional 범위 최소화**: DB 작업만 포함, 외부 API 제외
2. **비동기 처리**: @Async 또는 Event 기반으로 외부 호출 분리
3. **Virtual Threads (Java 21+)**: Blocking I/O에 가상 스레드 활용

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N03-thread-pool-exhaustion.md`
- Nightmare Test N03: ThreadPool Starvation
- Test Class: `ThreadPoolStarvationNightmareTest`
