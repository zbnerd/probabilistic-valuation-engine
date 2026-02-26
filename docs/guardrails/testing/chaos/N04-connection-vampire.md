---
id: GR-NIGHTMARE-N04
category: testing/chaos
severity: critical
keywords: [connection pool, @transactional blocking, jdbc, hikari]
languages: [java, kotlin]
---

# N04: @Transactional Blocking (Connection Vampire)

## DON'T (안티패턴)

```java
// Java - @Transactional이 Connection을 장시간 점유
@Transactional
public OrderResult processOrder(OrderDto dto) {
    // 1. DB Connection 획득
    Order order = repository.save(dto.toEntity());

    // 2. 느린 외부 API 호출 (최대 5초)
    // Connection을 여전히 점유 중!
    ApiResult result = externalApiClient.validate(order);

    // 3. 검증 후 업데이트
    order.updateStatus(result.getStatus());
    return OrderResult.from(order);
}
```

```kotlin
// Kotlin - @Transactional이 Connection을 장시간 점유
@Transactional
fun processOrder(dto: OrderDto): OrderResult {
    // 1. DB Connection 획득
    val order = repository.save(dto.toEntity())

    // 2. 느린 외부 API 호출 (최대 5초)
    // Connection을 여전히 점유 중!
    val result = externalApiClient.validate(order)

    // 3. 검증 후 업데이트
    order.updateStatus(result.status)
    return OrderResult.from(order)
}
```

**장애 수치 (Before):**
- HikariCP Pool (10개) 소진 시간: ~10초
- Connection 대기열 빌드업: 50+ threads
- Connection Timeout: 5건/분
- API 응답 시간: 5초 + DB 시간

## DO (베스트 프랙티스)

```java
// Java - 트랜잭션 범위 최소화
public OrderResult processOrder(OrderDto dto) {
    // Phase 1: DB 저장만 (트랜잭션 짧게)
    Order order = saveOrder(dto);

    // Phase 2: 외부 API 호출 (트랜잭션 외부)
    ApiResult result = externalApiClient.validate(order);

    // Phase 3: 업데이트 (별도 트랜잭션)
    updateOrderStatus(order.getId(), result.getStatus());

    return OrderResult.from(order);
}

@Transactional
public Order saveOrder(OrderDto dto) {
    return repository.save(dto.toEntity());
}

@Transactional
public void updateOrderStatus(Long orderId, String status) {
    Order order = repository.findById(orderId)
        .orElseThrow();
    order.updateStatus(status);
}
```

```kotlin
// Kotlin - 트랜잭션 범위 최소화
fun processOrder(dto: OrderDto): OrderResult {
    // Phase 1: DB 저장만 (트랜잭션 짧게)
    val order = saveOrder(dto)

    // Phase 2: 외부 API 호출 (트랜잭션 외부)
    val result = externalApiClient.validate(order)

    // Phase 3: 업데이트 (별도 트랜잭션)
    updateOrderStatus(order.id, result.status)

    return OrderResult.from(order)
}

@Transactional
fun saveOrder(dto: OrderDto): Order {
    return repository.save(dto.toEntity())
}

@Transactional
fun updateOrderStatus(orderId: Long, status: String) {
    val order = repository.findById(orderId).orElseThrow()
    order.updateStatus(status)
}
```

**개선 수치 (After):**
- HikariCP Pool 회전율: 10x 향상
- Connection 점유 시간: < 50ms (트랜잭션만)
- Connection Timeout: 0건
- 동시 처리량: 200 RPS 유지

## 핵심 원칙

1. **트랜잭션 범위 최소화**: DB 작업만 포함, 외부 API 제외
2. **작업 분리**: 저장 → 검증 → 업데이트로 단계 분리
3. **Connection Pool 보호**: 장시간 점유 방지로 전체 시스템 보호

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N04-connection-vampire.md`
- Nightmare Test N04: @Transactional Blocking
- Test Class: `ConnectionVampireNightmareTest`
