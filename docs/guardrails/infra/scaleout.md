---
id: GR-INFRA-001
category: infra
severity: critical
keywords: [ScaleOut, Stateful, Stateless, FeatureFlag, Scheduler, DistributedLock]
---

# Scale-out Architecture Guardrails

## DON'T (안티패턴)

### 1. In-Memory 상태를 분산 환경에서 사용
```java
// BAD: 각 인스턴스가 독립적인 카운터 보유
private final AtomicInteger dailyAiCallCount = new AtomicInteger(0);
private final Map<String, Instant> lastAlertTimeByPattern = new ConcurrentHashMap<>();
```

**영향:**
- 2개 인스턴스 시 AI 호출 한도 100 → 200으로 증가
- AI SRE 알림 오버쿼터 (비용 초과)
- 스로틀 타임스탬프도 인스턴스별 독립

### 2. Feature Flag 기본값이 In-Memory 모드
```java
// BAD: 기본값이 In-Memory
@ConditionalOnProperty(
    name = "app.buffer.redis.enabled",
    havingValue = "true",
    matchIfMissing = false  // ← 위험: 프로덕션에서 설정 누락 시 In-Memory
)
```

**영향:**
- 프로덕션에서 설정 누락 시 Scale-out 불가
- 배포/장애 시 데이터 유실
- 인스턴스 간 메시지 중복 처리

### 3. Scheduler에 분산 락 없이 다중 실행
```java
// BAD: 모든 인스턴스에서 동시 실행
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
public void processRetryQueue() {
    // 분산 락 없이 모든 인스턴스에서 5초마다 실행
}
```

**영향:**
- 중복 DB 업데이트
- Redis Lock 경합
- 데이터 정합성 깨짐

### 4. In-Memory SingleFlightExecutor
```java
// BAD: 진행 중인 계산을 인스턴스 메모리에만 저장
private final ConcurrentHashMap<String, InFlightEntry<T>> inFlight = new ConcurrentHashMap<>();
```

**영향:**
- 인스턴스 B에 동일 요청 시 2번 계산
- SingleFlight 효과 상실
- API 오버로드 N배

### 5. volatile 플래그로 Shutdown 동기화
```java
// BAD: SmartLifecycle이 인스턴스별 독립 동작
private volatile boolean running = false;

@Override
public void stop() {
    this.running = false;  // ← 다른 인스턴스와 동기화 안 됨
}
```

**영향:**
- 인스턴스 A 종료 중에도 B는 계속 요청 수신
- 배포 중 요청 손실

## DO (베스트 프랙티스)

### 1. Redis 기반 분산 상태 관리
```java
// GOOD: Redis AtomicLong + TTL 자동 만료
private final String dailyCountKey = "ai:throttle:daily-count:" + LocalDate.now();

public long incrementDailyCount() {
    RAtomicLong atomicLong = redissonClient.getAtomicLong(dailyCountKey);
    atomicLong.expire(1, TimeUnit.DAYS);
    return atomicLong.incrementAndGet();
}

public boolean isThrottled() {
    long count = redissonClient.getAtomicLong(dailyCountKey).get();
    return count >= DAILY_LIMIT;
}
```

### 2. Safe Feature Flag 기본값
```java
// GOOD: Redis가 기본값
@ConditionalOnProperty(
    name = "app.buffer.redis.enabled",
    havingValue = "true",
    matchIfMissing = true  // ← Safe: Redis가 기본
)

// In-Memory는 local 프로필 전용
@ConditionalOnProperty(
    name = "app.buffer.redis.enabled",
    havingValue = "false",
    matchIfMissing = false
)
@Profile("local")
```

**application.yml 기본값:**
```yaml
app:
  buffer:
    redis:
      enabled: true  # ← 명시적 기본값 (defense in depth)
```

### 3. Scheduler에 분산 락 적용
```java
// GOOD: @Locked 분산 락
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
@Locked(key = "buffer-recovery:retry", leaseTime = 4, waitTime = 0)
public void processRetryQueue() {
    // 단일 인스턴스만 실행
}
```

**또는 리더 패턴:**
```java
@Scheduled(fixedRate = 5000)
public void processRetryQueue() {
    // 리더 인스턴스만 실행
    if (!leaderElectionService.isLeader()) {
        return;
    }
    // ...
}
```

### 4. Redis Distributed Single-Flight
```java
// GOOD: Redis 기반 Distributed Single-Flight
public CompletableFuture<T> executeAsync(String key, Supplier<CompletableFuture<T>> task) {
    String lockKey = "single-flight:" + DigestUtils.md5Hex(key);

    return redissonClient.getLock(lockKey).tryLockAsync()
        .thenCompose(locked -> {
            if (!locked) {
                // 다른 인스턴스에서 계산 중 → 완료 대기
                return waitForCompletion(key);
            }
            // 이 인스턴스가 계산 수행
            return task.get()
                .thenApply(result -> {
                    cache.put(key, result);
                    return result;
                })
                .whenComplete((r, e) -> {
                    redissonClient.getLock(lockKey).unlockAsync();
                });
        });
}
```

### 5. Redis Shutdown Flag
```java
// GOOD: Redis로 Shutdown 상태 공유
private static final String SHUTDOWN_KEY = "system:shutdown:%s";

@Override
public void stop() {
    String instanceId = getInstanceId();
    redissonClient.getBucket(String.format(SHUTDOWN_KEY, instanceId)).set(true, 1, TimeUnit.HOURS);

    // Graceful shutdown 완료 대기
    awaitShutdownComplete();

    this.running = false;
}

public boolean isInstanceShuttingDown(String instanceId) {
    return redissonClient.getBucket(String.format(SHUTDOWN_KEY, instanceId)).isExists();
}
```

## P0 Scale-out Blockers (22개 항목)

| ID | Component | Pattern | Severity |
|----|-----------|---------|----------|
| P0-1 | AlertThrottler | In-Memory AtomicInteger | Critical |
| P0-2 | InMemoryBufferStrategy | JVM Local Queue | Critical |
| P0-3 | LikeBufferStorage | Feature Flag | Critical |
| P0-4 | SingleFlightExecutor | In-Memory inFlight | Critical |
| P0-5 | AiSreService | Unbounded Virtual Threads | Critical |
| P0-6 | LoggingAspect | volatile running | Critical |
| P0-7 | CompensationLogService | Consumer Group 중복 | Critical |
| P0-8 | DynamicTTLManager | 이벤트 중복 처리 | Critical |
| P1-1 | RateLimiter | 인스턴스별 ProxyManager | High |
| P1-4 | LikeBufferConfig | matchIfMissing=false | High |
| P1-5 | RedisBufferConfig | matchIfMissing=false | High |
| P1-7 | BufferRecoveryScheduler | 분산 락 없음 | High |
| P1-8 | LikeSyncScheduler | 경합 일정 | High |
| P1-9 | OutboxScheduler | 중복 폴링 | High |
| P1-10 | ExpectationBatchWriteScheduler | 로컬 shutdown 플래그 | High |

## Monitoring & Alerts

```prometheus
# In-Memory 상태 탐지
ALERT InMemoryStateDetected
  IF redis_buffer_enabled == 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "Application running in In-Memory mode",
    description = "Scale-out is blocked. Check app.buffer.redis.enabled"
  }

# Scheduler 중복 실행 감지
ALERT SchedulerDuplication
  IF rate(scheduler_executions_total[1m]) / count(scheduler_instances) > 1.2
  SEVERITY warning

  ANNOTATIONS {
    summary = "Schedulers executing on multiple instances",
    description = "Missing @Locked annotation detected"
  }

# Shutdown 데이터 유실 위험
ALERT ShutdownDataLossRisk
  IF buffer_pending_count > 0 AND shutdown_in_progress == 1
  SEVERITY critical
```

## Verification Commands

```bash
# 1. In-Memory 상태 패턴 탐지
grep -r "new ConcurrentHashMap\|new AtomicInteger\|volatile.*=" src/main/java/ | wc -l

# 2. Feature Flag 기본값 확인
grep -r "matchIfMissing" src/main/java/ | grep "false"

# 3. Scheduler 분산 락 확인
grep -A5 "@Scheduled" src/main/java/ | grep -B1 "void " | grep -v "@Locked" | wc -l

# 4. Scale-out 테스트
docker-compose up -d --scale app=2
# 로그에서 Scheduler 중복 실행 확인
docker-compose logs app | grep "Scheduled task executed"

# 5. Redis 분산 락 확인
redis-cli --scan --pattern "*:lock:*" | wc -l
```

## Migration Priority

### Sprint 1 — Feature Flag 정리 (Low Risk)
- [ ] P0-2: `InMemoryBufferStrategy`에 `@Profile("local")` 적용
- [ ] P0-3: `LikeBufferConfig` / `RedisBufferConfig`의 `matchIfMissing=true`
- [ ] P0-5: `AiSreService` Virtual Thread Executor Bean 기반 + maxThreads 제한
- [ ] P1-4, P1-5: Feature Flag 기본값 검증 테스트

### Sprint 2 — In-Memory → Redis (Medium Risk)
- [ ] P0-1: `AlertThrottler` → Redis AtomicLong + TTL 자동 만료
- [ ] P0-4: `SingleFlightExecutor` → Redis Distributed Single-Flight
- [ ] P0-6: `LoggingAspect.running` → Redis shutdown flag
- [ ] P1-10: `ExpectationBatchWriteScheduler.isShuttingDown()` → Redis 플래그

### Sprint 3 — Scheduler 분산화 (High Risk)
- [ ] P0-7: `CompensationLogService` → unique consumerId 파티션 분산
- [ ] P0-8: `DynamicTTLManager` → Redis 중앙 상태 + 리더 이벤트
- [ ] P1-7: `BufferRecoveryScheduler` → `@Locked` 분산 락
- [ ] P1-8: `LikeSyncScheduler` → globalSync 경합 해소
- [ ] P1-9: `OutboxScheduler` → 분산 락 또는 파티셔닝

## Anti-Patterns Summary

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **In-Memory State** | 인스턴스별 독립 상태 | Redis primitives |
| **Unsafe Feature Flag** | `matchIfMissing=false` | `matchIfMissing=true` |
| **Scheduler Collision** | 다중 인스턴스 동시 실행 | `@Locked` 또는 Leader Election |
| **Local Shutdown Flag** | 인스턴스 간 동기화 부족 | Redis shutdown flag |
| **Unbounded Threads** | OOM/CPU Spike | Bean + maxThreads limit |

## References

- [Spring Cloud Distributed Lock](https://cloud.spring.io/spring-cloud-static/spring-cloud-stream/reference/html/spring-cloud-stream-binder-redis.html)
- [Redisson Distributed Java Objects](https://redisson.org)
- [Kubernetes Leader Election](https://kubernetes.io/docs/concepts/architecture/leader-election/)

## 출처
- [docs/05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md](../../../05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md)
- Evidence ID: EVIDENCE-S001 ~ EVIDENCE-S020
