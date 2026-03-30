---
id: GR-INFRA-006
category: infra
severity: critical
keywords: [GracefulShutdown, Shutdown, DataLoss, RollingDeploy, SmartLifecycle, RedisFlag]
---

# Graceful Shutdown Coordination (Multi-Instance)

## DON'T (안티패턴)

### 1. 인스턴스별 독립 shutdown 플래그
```java
// Bad: 각 인스턴스가 독립적인 running 플래그
@Component
public class BufferFlushService implements SmartLifecycle {

    private volatile boolean running = true;

    @Override
    public void stop() {
        this.running = false;
        flushBuffer();  // 인스턴스별 독립 실행
    }
}
```

**영향:**
- Rolling Update 시 데이터 유실
- 인스턴스 A가 flush 중에도 B가 계속 수신
- 순서 보장 안 됨

### 2. shutdown 완료 전에 running=false
```java
// Bad: 3-phase shutdown 완료 전에 즉시 false
@Override
public void stop() {
    executor.executeVoid(() -> {
        // 비동기 3-phase shutdown
        phase1();
        phase2();
        phase3();
    }, context);

    this.running = false;  // ← 즉시 false (완료 전)
}
```

**영향:**
- SmartLifecycle 순서 위반
- 데이터 flush 완료 전 다른 컴포넌트 종료
- 데이터 유실

### 3. shutdown 타이밍 조정 없음
```java
// Bad: 모든 인스턴스가 동시에 flush 시도
@Scheduled(fixedRate = 1000)
public void scheduledFlush() {
    flushBuffer();
}
```

**영향:**
- Rolling Update 중 동시 flush
- DB 경합
- 데드락 가능성

### 4. shutdown 완료 확인 없음
```java
// Bad: shutdown 완료 대기 없음
@Override
public void stop() {
    flushBuffer();
    // flush 완료 여부 확인 없음
    this.running = false;
}
```

## DO (베스트 프랙티스)

### 1. Redis shutdown 플래그
```java
// Good: Redis로 shutdown 상태 공유
@Component
public class DistributedShutdownCoordinator {

    private static final String SHUTDOWN_KEY_PREFIX = "system:shutdown:%s";
    private final RedissonClient redissonClient;
    private final String instanceId;

    @PreDestroy
    public void shutdown() {
        String shutdownKey = String.format(SHUTDOWN_KEY_PREFIX, instanceId);

        // Redis에 shutdown 플래그 설정
        RBucket<Boolean> shutdownFlag = redissonClient.getBucket(shutdownKey);
        shutdownFlag.set(true, 1, TimeUnit.HOURS);

        log.info("[Shutdown] Shutdown flag set: instanceId={}", instanceId);

        // Graceful shutdown 완료 대기
        awaitShutdownComplete();

        shutdownFlag.delete();  // 완료 후 플래그 삭제
        log.info("[Shutdown] Shutdown complete: instanceId={}", instanceId);
    }

    public boolean isInstanceShuttingDown(String targetInstanceId) {
        String key = String.format(SHUTDOWN_KEY_PREFIX, targetInstanceId);
        RBucket<Boolean> flag = redissonClient.getBucket(key);
        return flag.isExists() && flag.get();
    }

    private void awaitShutdownComplete() {
        CompletableFuture<Void> shutdownFuture = CompletableFuture.runAsync(() -> {
            bufferService.flushAll();
            bufferService.awaitFlushComplete();
        });

        try {
            shutdownFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Shutdown] Timeout waiting for shutdown", e);
        }
    }
}
```

### 2. shutdown 완료 후 running=false
```java
// Good: shutdown 완료 후에만 false
@Component
public class ExpectationBatchShutdownHandler implements SmartLifecycle {

    private volatile boolean running = true;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @Override
    public void stop() {
        log.info("[Shutdown] Starting 3-phase shutdown");

        executor.executeVoid(() -> {
            try {
                // Phase 1: 신규 요청 중지
                stopAcceptingNewTasks();

                // Phase 2: 진행 중 작업 완료 대기
                awaitInProgressTasks();

                // Phase 3: 버퍼 flush
                flushAllBuffers();

                log.info("[Shutdown] 3-phase shutdown complete");

            } finally {
                // 모든 단계 완료 후에만 running=false
                this.running = false;
                shutdownLatch.countDown();
            }
        }, context);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void awaitShutdownComplete() throws InterruptedException {
        shutdownLatch.await(30, TimeUnit.SECONDS);
    }
}
```

### 3. Partitioned Flush (다중 인스턴스 분할 flush)
```java
// Good: 파티션 기반 flush로 경합 회피
@Component
public class PartitionedFlushStrategy {

    private final String instanceId;
    private final int totalPartitions;
    private final int myPartition;

    public PartitionedFlushStrategy(String instanceId, int totalInstances) {
        this.instanceId = instanceId;
        this.totalPartitions = totalInstances;
        // 인스턴스 ID 해시 기반 파티션 할당
        this.myPartition = Math.abs(instanceId.hashCode()) % totalInstances;
    }

    public void flushAssignedBuffers() {
        // 내 파티션에 해당하는 버퍼만 flush
        for (int i = 0; i < totalPartitions; i++) {
            if (i == myPartition) {
                flushPartition(i);
                log.info("[PartitionedFlush] Flushed partition: {}", i);
            }
        }
    }

    private void flushPartition(int partitionId) {
        // 파티션별 Redis Stream 또는 Outbox 처리
        String streamKey = "buffer:partition:" + partitionId;
        // flush 로직...
    }
}
```

### 4. Kubernetes Readiness Probe 연동
```java
// Good: K8s Readiness Probe와 연동
@Component
public class ReadinessProbeCoordinator {

    private final DistributedShutdownCoordinator shutdownCoordinator;

    @Readiness
    public boolean isReady() {
        // 내 인스턴스가 shutdown 중이면 Not Ready
        if (shutdownCoordinator.isShuttingDown(getInstanceId())) {
            return false;
        }

        // 다른 조건 확인 (데이터베이스, Redis 등)
        return isHealthy();
    }
}
```

```yaml
# deployment.yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  # shutdown 중에는 트래픽 전송 중단
```

### 5. Graceful Period 설정
```yaml
# application.yml
server:
  shutdown: graceful  # Spring Boot 2.3+ graceful shutdown
  tomcat:
    connection-timeout: 5s

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 각 phase별 타임아웃
```

### 6. Rolling Update 전략
```yaml
# deployment.yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1        # 최대 1개 추가 인스턴스
      maxUnavailable: 0  # 0개만큼만 중단 (가용성 유지)
  minReadySeconds: 30    # 새 파드 준비 대기 시간
```

### 7. Shutdown 메트릭 및 로그
```java
// Good: shutdown 진행 상황 모니터링
@Component
public class ShutdownMonitor {

    private final MeterRegistry meterRegistry;

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        log.info("[Shutdown] Application context closing");

        // Shutdown 시작 메트릭
        meterRegistry.gauge("shutdown.start.time", System.currentTimeMillis());

        // Pending 작업 수 확인
        long pendingCount = bufferService.getPendingCount();
        meterRegistry.gauge("shutdown.pending.count", pendingCount);

        if (pendingCount > 0) {
            log.warn("[Shutdown] {} items pending flush", pendingCount);
        }
    }
}
```

## Monitoring & Alerts

```prometheus
# Shutdown 중 데이터 유실 위험
ALERT ShutdownDataLossRisk
  IF shutdown_pending_count > 0 AND shutdown_in_progress == 1
  SEVERITY critical

  ANNOTATIONS {
    summary = "Data loss risk during shutdown",
    description = "Pending buffer items during shutdown"
  }

# Shutdown 타임아웃
ALERT ShutdownTimeout
  IF shutdown_duration_seconds > 30
  SEVERITY warning

  ANNOTATIONS {
    summary = "Shutdown taking too long",
    description = "Possible blocking or deadlock"
  }

# 인스턴스가 Not Ready 상태
ALERT InstanceNotReadyDuringRollingUpdate
  IF up{job="maple-expectation"} == 0
  SEVERITY warning

  ANNOTATIONS {
    summary = "Instance not ready",
    description = "Check rolling update progress"
  }
```

## Verification Commands

```bash
# 1. shutdown 플래그 확인
redis-cli --scan --pattern "system:shutdown:*"

# 2. Rolling Update 테스트
kubectl rollout restart deployment/maple-expectation

# 3. shutdown 로그 확인
kubectl logs -f deployment/maple-expectation | grep "Shutdown"

# 4. Readiness Probe 확인
kubectl get pods -w

# 5. 데이터 유실 확인
# 배포 전후 Redis 버퍼 수 비교
redis-cli --scan --pattern "buffer:*" | wc -l

# 6. 메트릭 확인
curl http://localhost:8080/actuator/metrics/shutdown.pending.count
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **로컬 shutdown 플래그** | 인스턴스 간 동기화 안 됨 | Redis shutdown flag |
| **조기 running=false** | SmartLifecycle 순서 위반 | 완료 대기 후 설정 |
| **동시 flush 경합** | DB/RRedis 경합 | Partitioned Flush |
| **Readiness 무시** | 종료 중에도 트래픽 전송 | K8s Probe 연동 |

## Shutdown Phase Checklist

```
Pre-Shutdown (준비)
├── [ ] Redis shutdown 플래그 설정
├── [ ] Readiness Probe = false
├── [ ] 로드 밸런서 트래픽 중단 확인

Phase 1: 신규 요청 중지 (0-5s)
├── [ ] running 플래그 = false
├── [ ] 진입점에서 요청 거부
└── [ ] 메트릭 기록

Phase 2: 진행 중 작업 완료 (5-20s)
├── [ ] 진행 중 작업 수 확인
├── [ ] 작업 완료 대기 (최대 15s)
└── [ ] 타임아웃 시 강제 종료 로그

Phase 3: 버퍼 flush (20-30s)
├── [ ] 메모리 버퍼 → Redis/DB
├── [ ] Flush 완료 확인
└── [ ] Pending 수 = 0 검증

Post-Shutdown (정리)
├── [ ] Redis shutdown 플래그 삭제
├── [ ] 커넥션 풀 종료
└── [ ] 로그 flush
```

## 출처
- [docs/05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md](../../../05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md)
- P0-6: LoggingAspect
- P1-15: ExpectationWriteBackBuffer
- P1-16: GracefulShutdownCoordinator
- P1-17: ExpectationBatchShutdownHandler
