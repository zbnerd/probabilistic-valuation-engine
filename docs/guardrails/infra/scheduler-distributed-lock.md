---
id: GR-INFRA-005
category: infra
severity: critical
keywords: [Scheduler, @Scheduled, DistributedLock, @Locked, LeaderElection, Duplicates]
---

# Scheduler Distributed Lock (Multi-Instance)

## DON'T (안티패턴)

### 1. @Scheduled만 사용 (모든 인스턴스에서 실행)
```java
// Bad: 분산 락 없이 모든 인스턴스에서 동시 실행
@Scheduled(fixedRate = 5000)
public void processRetryQueue() {
    // 모든 인스턴스에서 5초마다 실행
    // → 중복 처리, DB 경합, 데이터 정합성 깨짐
}
```

**영향:**
- N개 인스턴스 = N배 중복 실행
- 동시성 경합 (DB Lock, Redis Lock)
- 데이터 정합성 깨짐

### 2. @ConditionalOnProperty로만 제어
```java
// Bad: Feature Flag로만 제어
@Scheduled(fixedRate = 5000)
@ConditionalOnProperty(name = "scheduler.enabled", matchIfMissing = true)
public void processRetryQueue() {
    // 여전히 모든 인스턴스에서 실행
}
```

### 3. 로컬 AtomicBoolean로 동기화
```java
// Bad: 인스턴스별 독립 플래그
private final AtomicBoolean running = new AtomicBoolean(false);

@Scheduled(fixedRate = 5000)
public void scheduledTask() {
    if (!running.compareAndSet(false, true)) {
        return;  // 이미 실행 중
    }
    try {
        // 작업 수행
    } finally {
        running.set(false);
    }
}
```

**영향:**
- 인스턴스 간 동기화 안 됨
- 각 인스턴스에서 독립적으로 실행

### 4. 일정 시간 간격 겹침
```java
// Bad: 동일 주기로 여러 스케줄러 실행
@Scheduled(fixedRate = 3000)
public void taskA() { }

@Scheduled(fixedRate = 3000)
public void taskB() { }

// 동시에 실행되어 Redis 경합 가능
```

## DO (베스트 프랙티스)

### 1. @Locked 분산 락 적용
```java
// Good: Redisson @Locked 사용
@Component
@RequiredArgsConstructor
public class BufferRecoveryScheduler {

    private static final String LOCK_KEY = "scheduler:buffer-recovery:retry";

    @Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
    @Locked(
        key = LOCK_KEY,
        leaseTime = 4,  // 실행 주기(5초)보다 짧게
        waitTime = 0,   // 즉시 획득 시도 only
        lockType = LockType.REENTRANT_LOCK
    )
    public void processRetryQueue() {
        log.info("[BufferRecovery] Starting retry queue processing");

        // 단일 인스턴스만 실행됨
        bufferService.retryFailedMessages();

        log.info("[BufferRecovery] Completed retry queue processing");
    }

    @Scheduled(fixedRateString = "${scheduler.buffer-recovery.redrive-rate:30000}")
    @Locked(
        key = "scheduler:buffer-recovery:redrive",
        leaseTime = 25,
        waitTime = 0
    )
    public void redriveExpiredInflight() {
        log.info("[BufferRecovery] Starting inflight redrive");
        bufferService.redriveExpiredInflightMessages();
        log.info("[BufferRecovery] Completed inflight redrive");
    }
}
```

### 2. Leader Election 패턴
```java
// Good: 리더 인스턴스만 실행
@Component
@RequiredArgsConstructor
public class LeaderBasedScheduler {

    private final LeaderElectionService leaderService;

    @Scheduled(fixedRate = 5000)
    public void scheduledTask() {
        // 리더 인스턴스만 실행
        if (!leaderService.isLeader()) {
            log.trace("Not leader, skipping scheduled task");
            return;
        }

        log.info("Executing scheduled task as leader");
        // 작업 수행
    }
}

@Service
public class LeaderElectionService {

    private final RedissonClient redissonClient;
    private final String instanceId;

    public boolean isLeader() {
        RLock lock = redissonClient.getLock("leader:election");

        // 비동기 리더십 확인 (예: Kubernetes Leader Election)
        return lock.isHeldByCurrentThread();
    }
}
```

### 3. 일정 조정 (Stagger)
```java
// Good: 스케줄러 실행 시간 분산
@Component
public class StaggeredScheduler {

    // A는 0초, B는 10초, C는 20초에 실행
    @Scheduled(cron = "0,10,20,30,40,50 * * * * ?")
    public void taskA() { }

    // A는 5초, B는 15초, C는 25초에 실행
    @Scheduled(cron = "5,15,25,35,45,55 * * * * ?")
    public void taskB() { }
}
```

### 4. 환경별 분리 (Local에서만 다중 실행)
```java
// Good: Local 환경에서는 스케줄러 활성화
@Component
@ConditionalOnProperty(
    name = "scheduler.multi-instance.enabled",
    havingValue = "true",
    matchIfMissing = false  // Production에서는 기본적으로 비활성
)
@Profile("local")
public class MultiInstanceScheduler {

    @Scheduled(fixedRate = 5000)
    public void localOnlyTask() {
        // Local 개발에서만 여러 인스턴스 테스트
    }
}
```

### 5. ShedLock 라이브러리 (대안)
```java
// Good: ShedLock 사용
@Component
@RequiredArgsConstructor
public class ShedLockScheduler {

    @Scheduled(fixedRate = 5000)
    @SchedulerLock(
        name = "bufferRecoveryTask",
        lockAtMostFor = "4s",
        lockAtLeastFor = "0s"
    )
    public void processRetryQueue() {
        // ShedLock이 자동으로 분산 락 처리
    }
}
```

### 6. 실행 상태 모니터링
```java
// Good: 스케줄러 실행 메트릭
@Component
public class SchedulerMonitor {

    private final MeterRegistry meterRegistry;

    @Scheduled(fixedRate = 5000)
    @Locked(key = "scheduler:buffer-recovery:retry", leaseTime = 4, waitTime = 0)
    public void processRetryQueue() {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 작업 수행
            bufferService.retryFailedMessages();

            sample.stop(meterRegistry.timer("scheduler.execution",
                "task", "buffer-recovery", "status", "success"));

        } catch (Exception e) {
            sample.stop(meterRegistry.timer("scheduler.execution",
                "task", "buffer-recovery", "status", "failed"));

            meterRegistry.counter("scheduler.failures",
                "task", "buffer-recovery").increment();

            throw e;
        }
    }
}
```

## Monitoring & Alerts

```prometheus
# 스케줄러 중복 실행 감지
ALERT SchedulerDuplicationDetected
  IF count(scheduler_executed_total) by (task) > 1
  SEVERITY warning

  ANNOTATIONS {
    summary = "Scheduler running on multiple instances",
    description = "Missing @Locked annotation"
  }

# 스케줄러 실행 실패
ALERT SchedulerExecutionFailed
  IF rate(scheduler_failures_total[5m]) > 0.01
  SEVERITY warning

  ANNOTATIONS {
    summary = "Scheduler task failing",
    description = "Check logs for error details"
  }

# 분산 락 획득 실패
ALERT DistributedLockAcquisitionFailed
  IF rate(distributed_lock_acquisition_failed_total[5m]) > 0.1
  SEVERITY warning

  ANNOTATIONS {
    summary = "Failed to acquire distributed lock",
    description = "Possible lock contention or timeout"
  }
```

## Verification Commands

```bash
# 1. @Scheduled 메서드 확인
grep -A3 "@Scheduled" src/main/java/**/*Scheduler.java | grep -B1 "void " | grep -v "@Locked"

# 2. 분산 락 확인
grep -A5 "@Scheduled" src/main/java/**/*Scheduler.java | grep "@Locked"

# 3. Scale-out 테스트
docker-compose up -d --scale app=2

# 4. 로그에서 중복 실행 확인
docker-compose logs app | grep "BufferRecovery.*Starting"

# Expected: Only one instance logs "Starting"
# Bad pattern: Both instances log "Starting"

# 5. Redis 락 확인
redis-cli --scan --pattern "*:lock:*"
# Expected: scheduler:buffer-recovery:* keys present

# 6. 메트릭 확인
curl http://localhost:8080/actuator/metrics/scheduler.executed
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **@Scheduled만 사용** | N인스턴스 = N배 실행 | @Locked 또는 LeaderElection |
| **로컬 플래그** | 인스턴스 간 동기화 안 됨 | Redis 분산 락 |
| **일정 겹침** | 동시 실행 경합 | Cron 표현식으로 Stagger |
| **matchIfMissing=true** | Production에서도 다중 실행 | Production에서는 기본 비활성 |

## 분산 락 전략 비교

| 전략 | 장점 | 단점 | 사용 사례 |
|------|------|------|----------|
| **@Locked** | 간단, 자동 잠금 해제 | Redisson 종속 | 일반적 스케줄러 |
| **LeaderElection** | 리더 인스턴스만 실행 | 복잡함 | 긴 실행 시간 작업 |
| **ShedLock** | DB 기반 영속 락 | DB 의존 | 이미 DB 사용 중인 경우 |
| **Cron Stagger** | 설정만으로 분산 | 정확한 단일 실행 보장 안 됨 | 동시성 중요도 낮은 경우 |

## 출처
- [docs/05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md](../../../05/Reports/04_09_Scale_Out/scale-out-blockers-analysis.md)
- P1-7: BufferRecoveryScheduler
- P1-8: LikeSyncScheduler
- P1-9: OutboxScheduler
- [Redisson Distributed Lock](https://redisson.org)
