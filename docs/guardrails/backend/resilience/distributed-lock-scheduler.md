---
id: GR-RESILIENCE-007
category: backend/resilience
severity: warning
keywords: [Scheduler, DistributedLock, Redis, Warmup, DuplicateExecution, Redisson]
languages: [java, kotlin]
---

# Distributed Lock for Scheduler Guardrail

## 개요

다중 인스턴스 환경에서 **스케줄러 중복 실행**을 방지하기 위해 **분산 락**을 필수로 적용합니다. Redis 기반 분산 락으로 단일 인스턴스만 스케줄러를 실행하여 중복 API 호출과 캐시 불일치를 방지합니다.

> **설계 근거:** 웜업 스케줄러가 3대 인스턴스에서 동시에 실행되면 넥슨 API에 3배 중복 요청이 발생하여 Rate Limiting 위반이 가능합니다.

## DON'T (안티패턴)

### 1. @Scheduled만 사용 (중복 실행)

```java
// Bad - 분산 락 없는 스케줄러
@Component
public class WarmupScheduler {

    @Scheduled(cron = "0 0 5 * * *")
    public void dailyWarmup() {
        // 분산 락 없음
        List<String> topCharacters = getTopCharacters(100);
        topCharacters.forEach(ign -> warmup(ign));
    }
}

// 문제: 3대 인스턴스 배포 시
// - 00:00:00.000 - Instance 1: 웜업 시작
// - 00:00:00.050 - Instance 2: 웜업 시작 (중복!)
// - 00:00:00.100 - Instance 3: 웜업 시작 (중복!)
```

**위험성:**
- 인스턴스 수 × API 호출 중복
- Rate Limiting 위반
- 캐시 불일치 가능성
- 리소스 낭비

### 2. synchronized 사용 (JVM 로컬 락)

```java
// Bad - synchronized는 다중 인스턴스에서 무효
@Component
public class WarmupScheduler {

    private final Object lock = new Object();

    @Scheduled(cron = "0 0 5 * * *")
    public void dailyWarmup() {
        synchronized (lock) {  // 이 락은 이 JVM 내에서만 유효
            // 다른 인스턴스에서는 동시에 실행됨
            List<String> topCharacters = getTopCharacters(100);
            topCharacters.forEach(ign -> warmup(ign));
        }
    }
}

// 문제: synchronized는 다중 JVM에서 각각 실행됨
```

**위험성:**
- 다중 인스턴스에서 동시에 실행
- synchronized는 단일 JVM에서만 유효

### 3. @Singleton만 사용 (Spring Bean 단일 인스턴스)

```java
// Bad - @Singleton은 다중 인스턴스 배포에서 무효
@Component
@Singleton  // 이것만으로는 부족함
public class WarmupScheduler {

    @Scheduled(cron = "0 0 5 * * *")
    public void dailyWarmup() {
        // 각 인스턴스마다 @Singleton Bean이 생성됨
        List<String> topCharacters = getTopCharacters(100);
        topCharacters.forEach(ign -> warmup(ign));
    }
}

// 문제: 각 인스턴스마다 별도의 Spring 컨테이너
```

**위험성:**
- 각 인스턴스마다 별도 Bean 생성
- 중복 실행 방지 불가

## DO (베스트 프랙티스)

### 1. Redis 분산 락 사용

```java
// Good - Redis 기반 분산 락
@Component
@RequiredArgsConstructor
public class WarmupScheduler {

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

    private static final String WARMUP_LOCK_KEY = "warmup:lock:daily";

    @Scheduled(cron = "0 0 5 * * *")
    public void dailyWarmup() {
        executor.executeVoid(
            () -> {
                // 분산 락 획득 시에만 실행
                RLock lock = redissonClient.getLock(WARMUP_LOCK_KEY);

                try {
                    // 락 획득 시도 (5초 대기)
                    if (lock.tryLock(5, 300, TimeUnit.SECONDS)) {
                        try {
                            doWarmup();
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        log.info("Warmup already running on another instance");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Warmup lock acquisition interrupted", e);
                }
            },
            TaskContext.of("WarmupScheduler", "DailyWarmup")
        );
    }
}
```

### 2. RedisDistributedLockStrategy 활용

> **⚠️ OBSOLETE: Use PostgresAdvisoryLockStrategy instead (ADR-022). This example is kept for historical reference only.**

```java
// Good - 추상화된 분산 락 전략 사용 (DEPRECATED - Redis removed)
@Component
@ConditionalOnProperty(name = "scheduler.warmup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PopularCharacterWarmupScheduler {

    private final PopularCharacterTracker tracker;
    private final V4ApiService v4Api;
    private final RedisDistributedLockStrategy lockStrategy;  // 핵심
    private final LogicExecutor executor;

    @Scheduled(cron = "0 0 5 *6 * *")
    public void dailyWarmup() {
        executor.executeVoid(
            () -> {
                // 분산 락으로 중복 실행 방지
                lockStrategy.executeWithLock(
                    "warmup:lock:daily",  // 락 키
                    5,                    // 락 획득 대기 시간 (초)
                    300,                  // 락 유지 시간 (초)
                    this::doWarmup        // 실행할 작업
                );
            },
            TaskContext.of("WarmupScheduler", "DailyWarmup")
        );
    }

    private void doWarmup() {
        log.info("Starting warmup");

        List<String> topCharacters = tracker.getYesterdayTopCharacters(100);

        for (String ign : topCharacters) {
            try {
                v4Api.warmup(ign);
                Thread.sleep(50);  // Thundering Herd 방지
            } catch (Exception e) {
                log.warn("Warmup failed: ign={}", ign);
            }
        }

        log.info("Warmup completed: total={}", topCharacters.size());
    }
}
```

### 3. RedisDistributedLockStrategy 구현

```java
// Good - 분산 락 전략 구현
@Component
@RequiredArgsConstructor
public class RedisDistributedLockStrategy implements DistributedLockStrategy {

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

    @Override
    public void executeWithLock(String lockKey, Runnable task) {
        executeWithLock(lockKey, 5, 300, task);
    }

    @Override
    public void executeWithLock(String lockKey, int waitTime, int leaseTime, Runnable task) {
        RLock lock = redissonClient.getLock(lockKey);

        return executor.executeVoid(
            () -> {
                try {
                    // 락 획득 시도
                    boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

                    if (!acquired) {
                        log.info("Lock acquisition failed: key={}, another instance running", lockKey);
                        return;
                    }

                    try {
                        // 락 획득 후 작업 실행
                        task.run();
                    } finally {
                        // 핵심: 락 해제
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockAcquisitionException("Lock acquisition interrupted", e);
                }
            },
            TaskContext.of("DistributedLock", "ExecuteWithLock", lockKey)
        );
    }
}
```

### 4. 락 키 설계

```java
// Good - 락 키 설계
public final class LockKeys {

    // 웜업 스케줄러 락
    public static final String WARMUP_DAILY = "warmup:lock:daily";
    public static final String WARMUP_INITIAL = "warmup:lock:initial";

    // 좋아요 동기화 락
    public static final String LIKE_SYNC = "sync:lock:likes";

    // 캐릭터 데이터 동기화 락
    public static final String CHARACTER_SYNC = "sync:lock:character:%s";

    // 동적 락 키 생성
    public static String characterSyncLock(String ign) {
        return String.format(CHARACTER_SYNC, ign);
    }
}
```

### 5. 락 대기 시간/유지 시간 설정

```java
// Good - 상황별 락 설정
@Scheduled(cron = "0 0 5 * * *")
public void dailyWarmup() {
    // 빠른 웜업: 5초 대기, 5분 유지
    lockStrategy.executeWithLock(
        "warmup:lock:daily",
        5,    // waitTime: 5초 (빠른 실패)
        300,  // leaseTime: 5분 (웜업 소요 시간)
        this::doWarmup
    );
}

@Scheduled(cron = "0 0 */4 * * *")  // 4시간마다
public void periodicSync() {
    // 긴 동기화: 10초 대기, 30분 유지
    lockStrategy.executeWithLock(
        "sync:lock:periodic",
        10,   // waitTime: 10초 (긴 대기)
        1800, // leaseTime: 30분 (긴 작업)
        this::doPeriodicSync
    );
}
```

### 6. 메트릭 수집

```java
// Good - 분산 락 메트릭 수집
@Component
public class DistributedLockMetrics {

    private final MeterRegistry meterRegistry;

    public void recordLockAcquisition(String lockKey, boolean acquired) {
        meterRegistry.counter("distributed.lock.acquisition",
            "key", lockKey,
            "result", acquired ? "success" : "failed"
        ).increment();
    }

    public void recordLockDuration(String lockKey, long durationMs) {
        meterRegistry.timer("distributed.lock.duration",
            "key", lockKey
        ).record(durationMs, TimeUnit.MILLISECONDS);
    }
}
```

### 7. 테스트로 중복 실행 검증

```java
// Good - 다중 인스턴스 테스트
@Test
@DisplayName("다중 인스턴스에서 스케줄러 중복 실행 방지 검증")
void schedulerShouldRunOnlyOnSingleInstance() throws InterruptedException {
    // Given - 3개의 스레드로 다중 인스턴스 시뮬레이션
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(3);
    AtomicInteger executionCount = new AtomicInteger(0);

    for (int i = 0; i < 3; i++) {
        new Thread(() -> {
            try {
                startLatch.await();  // 동시 시작 대기
                scheduler.dailyWarmup();
                executionCount.incrementAndGet();
            } catch (Exception e) {
                log.error("Scheduler execution failed", e);
            } finally {
                doneLatch.countDown();
            }
        }).start();
    }

    // When - 모든 스레드 동시 시작
    startLatch.countDown();
    boolean finished = doneLatch.await(10, TimeUnit.SECONDS);

    // Then - 단 한 번만 실행되어야 함
    assertThat(finished).isTrue();
    assertThat(executionCount.get()).isEqualTo(1);  // 단 1회 실행
}
```

## 락 파라미터 가이드

| 파라미터 | 설명 | 권장값 | 설명 |
|----------|------|--------|------|
| `waitTime` | 락 획득 대기 시간 | 5-10초 | 다른 인스턴스 실행 중이면 빠르게 실패 |
| `leaseTime` | 락 유지 시간 | 작업 소요 시간 + 10% | 작업 중 락 만료 방지 |
| `lockKey` | 락 키 | `domain:lock:operation` | 계층형 구조로 네이밍 |

## 다중 인스턴스 시나리오

```
┌─────────────────────────────────────────────────────────────┐
│  [분산 락 없는 경우 - 안티패턴]                               │
│                                                             │
│  Instance 1          Instance 2          Instance 3         │
│  [00:00:00.000]     [00:00:00.050]     [00:00:00.100]      │
│  dailyWarmup()      dailyWarmup()      dailyWarmup()        │
│       │                  │                  │                │
│       ▼                  ▼                  ▼                │
│  API × 100건       API × 100건       API × 100건          │
│                                                             │
│  ❌ 총 300건 중복 API 호출                                    │
│  ❌ Rate Limiting 위반                                       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  [분산 락 있는 경우 - 베스트 프랙티스]                         │
│                                                             │
│  Instance 1          Instance 2          Instance 3         │
│  [00:00:00.000]     [00:00:00.050]     [00:00:00.100]      │
│  tryLock() OK!      tryLock() FAIL    tryLock() FAIL       │
│       │                  │                  │                │
│       ▼                  │                  │                │
│  dailyWarmup()          │                  │                │
│       │                  │                  │                │
│       ▼                  │                  │                │
│  API × 100건            │                  │                │
│       │                  │                  │                │
│       ▼                  │                  │                │
│  unlock()                │                  │                │
│                                                             │
│  ✅ 총 100건 API 호출 (중복 없음)                              │
└─────────────────────────────────────────────────────────────┘
```

## 출처

### 문서
- `docs/03_Technical_Guides/auto-warmup.md` Section 2.1: Stateless 설계 원칙
- `docs/03_Technical_Guides/redis-ha-architecture.md` Section 3.1: 현재 프로젝트 Sentinel만으로 충분

### ADR
- Issue #275: Auto Warmup 기능 구현 (분산 락 필수)
- `docs/01_ADR/ADR-006-redis-lock (ARCHIVED: docs/_archive/redis-deprecated/).md` - Redis Lock 설정

### 코드 (Evidence)
- `src/main/kotlin/maple/expectation/global/lock/RedisDistributedLockStrategy.java`
- `src/main/kotlin/maple/expectation/scheduler/PopularCharacterWarmupScheduler.java`

## 검증 명령어

```bash
# 분산 락 사용 확인
grep -r "executeWithLock\|tryLock" src/main/kotlin --include="*.java"

# RedisDistributedLockStrategy 구현 확인
find src/main/kotlin -name "*DistributedLock*.java"

# 스케줄러 분산 락 사용 확인
grep -A 10 "@Scheduled" src/main/kotlin/maple/expectation/scheduler/*.java | grep -A 5 "executeWithLock"

# 다중 인스턴스 테스트 실행
./gradlew test --tests "*Warmup*Test"

# 분산 락 메트릭 확인
curl -s http://localhost:8080/actuator/metrics/distributed.lock.acquisition | jq
```
