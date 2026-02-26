---
id: GR-NIGHTMARE-N06
category: testing/chaos
severity: critical
keywords: [skip locked, lock timeout, pessimistic locking, select for update]
languages: [java, kotlin]
---

# N06: SKIP LOCKED Missing (Timeout Cascade)

## DON'T (안티패턴)

```java
// Java - SELECT FOR UPDATE만 사용 (대기열 발생)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM Order o WHERE o.status = 'PENDING'")
List<Order> findPendingOrdersForProcessing();

// 워커가 10개면 10개가 동일 Row를 대기하며 경합!
```

```kotlin
// Kotlin - SELECT FOR UPDATE만 사용 (대기열 발생)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM Order o WHERE o.status = 'PENDING'")
fun findPendingOrdersForProcessing(): List<Order>

// 워커가 10개면 10개가 동일 Row를 대기하며 경합!
```

**장애 수치 (Before):**
- 10개 워커 스레드 대기: 5-10초
- Lock Wait Timeout 발생: 2-3건/분
- 처리량: 10 tps (낭비 심함)
- DB Connection 고갈 위험

## DO (베스트 프랙티스)

```java
// Java - SKIP LOCKED로 대기열 방지
@Query("SELECT o FROM Order o WHERE o.status = 'PENDING' ORDER BY o.id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHint(name = "javax.persistence.lock.timeout", value = "1000")
List<Order> findPendingOrdersForProcessing(Pageable pageable);

// Native Query로 SKIP LOCKED 사용
@Query(value = "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY id LIMIT ?1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
List<Order> findPendingOrdersSkipLocked(int limit);
```

```kotlin
// Kotlin - SKIP LOCKED로 대기열 방지
@Query("SELECT o FROM Order o WHERE o.status = 'PENDING' ORDER BY o.id")
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHint(name = "javax.persistence.lock.timeout", value = "1000")
fun findPendingOrdersForProcessing(pageable: Pageable): List<Order>

// Native Query로 SKIP LOCKED 사용
@Query(value = "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY id LIMIT ?1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
fun findPendingOrdersSkipLocked(limit: Int): List<Order>
```

**개선 수치 (After):**
- 10개 워커 즉시 배정: Lock 대기 없음
- Lock Wait Timeout: 0건
- 처리량: 100 tps (10x 향상)
- DB Connection 효율적 사용

## 핵심 원칙

1. **FOR UPDATE SKIP LOCKED**: 잠긴 Row는 건너뛰고 available Row만 획득
2. **Multi-worker 환경**: Job Scheduler, Batch 처리에 필수
3. **Timeout 설정**: `javax.persistence.lock.timeout`으로 무한 대기 방지
4. **Order By + LIMIT**: 순차적 처리 보장

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N06-timeout-cascade.md`
- Nightmare Test N06: Timeout Cascade
- Test Class: `TimeoutCascadeNightmareTest`
