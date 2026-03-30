---
id: GR-CONC-006
category: backend/concurrency
severity: critical
keywords: [Deadlock, LockTimeout, SKIP_LOCKED, LockOrdering, TryLock]
languages: [java, kotlin]
---
# Deadlock Prevention

## DON'T (안티패턴)

### 1. 다중 리소스 락 순서 위반 (Deadlock)
```java
// Bad (리소스 락 순서 불일치 -> Deadlock)
public void transferAccount(String from, String to, Long amount) {
    // Thread A: account1 -> account2 순서 락
    lock("account:" + from);
    lock("account:" + to);

    // Thread B: account2 -> account1 순서 락 (순서 반대!)
    // -> Deadlock 발생
}
```

**Deadlock 발생 조건 (4가지 모두 충족 시):**
1. **Mutual Exclusion**: 리소스는 한 번에 한 프로세스만 사용 가능
2. **Hold and Wait**: 리소스를 보유한 상태에서 다른 리소스 대기
3. **No Preemption**: 리소스를 강제로 뺏을 수 없음
4. **Circular Wait**: 프로세스들이 원형 대기 (A→B→A)

### 2. 대기 시간 무제한 설정
```java
// Bad (무제한 대기 -> 시스템 정지)
RLock lock1 = redissonClient.getLock("resource1");
RLock lock2 = redissonClient.getLock("resource2");

lock1.lock();  // 무제한 대기
lock2.lock();  // lock2가 이미 락된 상태면 Deadlock
```

### 3. Lock 해제 없이 반환
```java
// Bad (Lock 해제 누락 -> Deadlock)
public void process(String key) {
    RLock lock = redissonClient.getLock(key);
    lock.lock();
    if (someCondition) {
        throw new RuntimeException();  // lock.unlock() 호출 안 됨!
    }
    lock.unlock();
}
```

### 4. 일반 Pessimistic Lock으로 대기열 생성
```java
// Bad (분산 배치에서 대기열 발생 -> 처리량 저하)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM DonationOutbox o WHERE o.status = 'PENDING'")
List<DonationOutbox> findPendingForProcessing();
```

## DO (베스트 프랙티스)

### 1. 락 순서 강제 (Lock Ordering)
```java
// Good (일관된 락 순서 -> Circular Wait 방지)
public void transferAccount(String from, String to, Long amount) {
    // 항상 작은 ID 순서로 락 획득
    String firstLock = from.compareTo(to) < 0 ? from : to;
    String secondLock = from.compareTo(to) < 0 ? to : from;

    lock("account:" + firstLock);
    lock("account:" + secondLock);

    try {
        // 이체 로직
    } finally {
        unlock("account:" + secondLock);
        unlock("account:" + firstLock);
    }
}
```

### 2. TryLock + 타임아웃 (No Preemption 보장)
```java
// Good (TryLock으로 타임아웃 -> Preemption 가능)
public boolean processWithTimeout(String lockKey, long timeout, TimeUnit unit) {
    RLock lock = redissonClient.getLock(lockKey);
    try {
        boolean acquired = lock.tryLock(timeout, unit);  // 타임아웃 내 획득 시도
        if (!acquired) {
            log.warn("Lock acquisition timeout: {}", lockKey);
            return false;
        }

        try {
            // 비즈니스 로직
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }
}
```

### 3. SKIP LOCKED (분산 배치에 최적)
```java
// Good (SKIP LOCKED: 잠긴 행 건너뛰기 -> 병렬 처리)
@Repository
public interface DonationOutboxRepository extends JpaRepository<DonationOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
    @Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses " +
            "AND o.nextRetryAt <= :now ORDER BY o.id")
    List<DonationOutbox> findPendingWithSkipLocked(
            @Param("statuses") List<OutboxStatus> statuses,
            @Param("now") LocalDateTime now
    );
}
```

**SKIP LOCKED 장점:**
- 잠긴 행을 건너뛰고 다음 행을 조회 -> 대기열 없음
- 4개 스케줄러 인스턴스가 병렬로 Outbox 처리 가능
- Outbox 패턴, 메시지 큐 폴링에 최적

### 4. Redisson MultiLock (다중 리소스 락)
```java
// Good (Redisson MultiLock으로 원자적 다중 락)
public void transferAccount(String from, String to, Long amount) {
    RLock lock1 = redissonClient.getLock("account:" + from);
    RLock lock2 = redissonClient.getLock("account:" + to);

    // MultiLock: 다중 락을 단일 락처럼 원자적으로 획득
    RLock multiLock = redissonClient.getMultiLock(lock1, lock2);

    try {
        multiLock.lock(10, TimeUnit.SECONDS);  // 10초 타임아웃
        // 이체 로직
    } finally {
        if (multiLock.isHeldByCurrentThread()) {
            multiLock.unlock();
        }
    }
}
```

### 5. Lock Wrapper로 안전한 락 해제
```java
// Good (Lock Wrapper로 try-finally 자동화)
@Component
public class LockStrategy {

    public <T> T executeWithLock(String lockName, long waitTime, long leaseTime, Supplier<T> task) {
        RLock lock = redissonClient.getLock(lockName);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockException("Lock acquisition failed: " + lockName);
            }

            try {
                return task.get();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockException("Lock interrupted", e);
        }
    }
}

// 사용
lockStrategy.executeWithLock("transfer:" + from + ":" + to, 5, 10, () -> {
    // 이체 로직 (자동으로 락 해제됨)
    return null;
});
```

### 6. Watchdog 모드 (자동 락 갱신 - Redisson)
```java
// Good (Watchdog: 작업 시간 불확실 시 사용)
public void longRunningTask(String taskId) {
    RLock lock = redissonClient.getLock("task:" + taskId);

    try {
        // leaseTime = -1: Watchdog 모드 (자동 갱신, 기본 30초)
        // 주의: 프로세스 종료 시 락 해제됨
        lock.lock(-1, TimeUnit.SECONDS);

        // 긴 작업 (Watchdog이 자동으로 락 갱신)
        longRunningProcess();

    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**Watchdog vs LeaseTime:**
| 모드 | leaseTime | 동작 | 적합 케이스 |
|------|-----------|------|------------|
| **Watchdog** | -1 | 자동 갱신 (기본 30초) | 작업 시간 불확실 |
| **Fixed Lease** | N초 | N초 후 자동 해제 | 작업 시간 예상 가능 |

### 7. Deadlock 감지 및 재시도
```java
// Good (Deadlock 감지 -> 재시도)
@Retryable(
    value = {CannotAcquireLockException.class, LockAcquisitionException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 100, multiplier = 2)
)
public void processWithRetry(String key) {
    lockStrategy.executeWithLock(key, 1, 10, () -> {
        // 비즈니스 로직
    });
}
```

### 8. 락 전략별 Deadlock 위험도
| 락 전략 | Deadlock 위험도 | 대기 시간 | 처리량 | 적합 케이스 |
|---------|-----------------|-----------|--------|------------|
| **Pessimistic Lock** | 높음 (대기열) | 길음 | 낮음 | 금융 트랜잭션 |
| **SKIP LOCKED** | 없음 | 없음 | 높음 | 분산 배치, Outbox |
| **Optimistic Lock (@Version)** | 없음 | 없음 | 높음 | 충돌 드문 수정 |
| **TryLock + 타임아웃** | 낮음 | 제한적 | 중간 | 일반적인 분산 락 |
| **Redisson MultiLock** | 낮음 | 제한적 | 중간 | 다중 리소스 연산 |

## 출처
- lock-strategy.md - Section 2 (후원 도메인), Section 4 (분산 스케줄러)
- ADR-006: `docs/01_Adr/ADR-006-redis-lock (ARCHIVED: docs/_archive/redis-deprecated/).md` (Watchdog vs LeaseTime)

## 검증 명령어
```bash
# SKIP LOCKED 사용 확인
grep -r "SKIP LOCKED\|skipLocked\|lock.timeout.*-2" src/main/kotlin --include="*.java"

# TryLock 사용 확인
grep -r "tryLock(" src/main/kotlin --include="*.java"

# Lock Wrapper 사용 확인
grep -r "executeWithLock" src/main/kotlin --include="*.java"

# 무제한 lock() 사용 확인 (위험)
grep -r "\.lock()" src/main/kotlin --include="*.java" | grep -v tryLock
```

## 롤백 계획
- SKIP LOCKED 불안정 시: 일반 Pessimistic Lock + 타임아웃으로 복구
- MultiLock 성능 저하 시: 단일 리소스 락 + 트랜잭션으로 대체
