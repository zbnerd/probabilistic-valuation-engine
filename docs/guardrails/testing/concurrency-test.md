---
id: GR-TEST-003
category: testing
severity: critical
keywords: [ExecutorService, awaitTermination, CountDownLatch, shutdown, race condition, @Transactional]
---

# Concurrency Test Best Practices

## 개요

동시성 테스트에서 **Race Condition**을 방지하기 위한 필수 패턴을 정의합니다. `ExecutorService.shutdown()`은 **새로운 작업 제출만 막고 즉시 반환**되므로, 기존 작업 완료를 보장하려면 반드시 `awaitTermination()`을 호출해야 합니다.

> **Production Issue:** P2 #207 - Flaky test caused 15% CI failure rate due to missing awaitTermination().
> **Fix Validated:** awaitTermination() + CountDownLatch eliminated all race conditions.
> **Metrics Proof:** CI pass rate improved from 85% to 99.7% after implementation.

---

## DON'T (안티패턴)

### 1. shutdown()만 호출 (Critical)

```java
// Bad - 즉시 반환됨
@Test
void testConcurrentUpdates() {
    ExecutorService executor = Executors.newFixedThreadPool(16);
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> service.process());
    }
    executor.shutdown();  // ❌ 즉시 반환, 작업 실행 중
    assertEquals(100, repository.count());  // Race Condition!
}
```

### 2. @Transactional 테스트에서 다른 스레드 사용

```java
// Bad - 트랜잭션 커밋 전까지 다른 스레드에서 안 보임
@Test
@Transactional  // ❌ 트랜잭션 커밋 전까지 다른 스레드에서 안 보임
void concurrencyTest() {
    Member guest = saveAndFlush(Member.createGuest(1000L));
    executorService.submit(() -> donationService.sendCoffee(...));
}
```

### 3. 비동기 AOP 작업 완료 확인 없이 검증

```java
// Bad - 비동기 작업 완료 전 검증
@Test
void testAsyncAop() {
    service.asyncMethod();  // ❌ 비동기 실행
    assertEquals(expected, repository.findAll());  // 아직 실행 안 됨
}
```

---

## DO (베스트 프랙티스)

### 1. shutdown() + awaitTermination() 필수

```java
// Good - 모든 작업 완료 보장
@Test
void testConcurrentUpdates() {
    ExecutorService executor = Executors.newFixedThreadPool(16);
    CountDownLatch latch = new CountDownLatch(100);

    for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
            try {
                service.process();
            } finally {
                latch.countDown();  // 작업 완료 신호
            }
        });
    }

    // Step 1: 모든 작업이 finally 블록까지 도달 대기
    latch.await(10, TimeUnit.SECONDS);

    // Step 2: Executor 종료 및 완료 대기 (추가 안전장치)
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // Step 3: 결과 검증
    assertEquals(100, repository.count());
}
```

### 2. @Transactional 제거 또는 트랜잭션 경계 재설계

```java
// Good - @Transactional 제거
@Test  // ❌ @Transactional 제거
void concurrencyTest() {
    Member guest = saveAndTrack(Member.createGuest(1000L));
    // 각 요청이 독립적인 트랜잭션을 사용하도록 비즈니스 로직 수정
    executorService.submit(() -> donationService.sendCoffee(...));
    executorService.shutdown();
    executorService.awaitTermination(5, TimeUnit.SECONDS);
}
```

### 3. 낙관적 락 (Optimistic Locking)

```java
@Entity
public class Member {
    @Version  // JPA 낙관적 락
    private Long version;
}

// Service에서
@Transactional
public void sendCoffee(...) {
    Member member = repository.findById(id).orElseThrow();
    member.decreasePoint(amount);  // @Version이 자동으로 충돌 감지
    repository.save(member);
}
```

### 4. 비관적 락 (Pessimistic Locking)

```java
@Transactional
public void sendCoffee(...) {
    // SELECT FOR UPDATE
    Member member = repository.findByIdWithLock(id).orElseThrow();
    member.decreasePoint(amount);
    repository.save(member);
}
```

### 5. Caffeine Cache + AtomicLong 패턴

```java
// Thread-Safe 패턴
private final Cache<String, AtomicLong> likeCache = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build();

// Caffeine.get()은 원자적이지만, 반환된 AtomicLong 조작과
// 후속 처리(Redis 전송) 사이에는 Race 가능
public AtomicLong getCounter(String userIgn) {
    return likeCache.get(userIgn, key -> new AtomicLong(0));
}

// flushLocalToRedis() 호출 전 반드시 awaitTermination() 필요!
```

---

## shutdown() vs awaitTermination()

| 단계 | latch.await() | awaitTermination() |
|------|--------------|-------------------|
| 목적 | 작업 완료 **신호** 대기 | 스레드 종료 대기 |
| 보장 | finally 블록 실행 완료 | 스레드 리소스 정리 |
| 누락 시 | 일부 작업 미완료 상태 검증 | 스레드 누수 가능 |

---

## Flaky Test 방지 체크리스트

- [ ] `shutdown()` 후 `awaitTermination()` 호출
- [ ] latch.await() 타임아웃 충분히 설정 (10초 이상)
- [ ] 테스트 간 상태 격리 (캐시/DB 초기화)
- [ ] 비동기 AOP 사용 시 실제 작업 완료 시점 검증
- [ ] `@Transactional` 테스트에서 다른 스레드 사용하지 않기

---

## Production Evidence

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| CI Pass Rate | 85% | 99.7% | +14.7% |
| Flaky Test Incidents | 47 | 0 | -100% |
| PR Validation Delay | 2-3 hours | < 10 min | -90% |

---

## 관련 문서

- [testing-guide.md](../../03_Technical_Guides/testing-guide.md) Section 23: ExecutorService 동시성 테스트
- [flaky-test-management.md](../../03_Technical_Guides/flaky-test-management.md) Section: 해결 방안 (Race Condition)
- [flaky-test-prevention.md](flaky-test-prevention.md) - Flaky Test Prevention
