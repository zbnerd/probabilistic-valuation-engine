---
id: GR-PERF-001
category: backend/performance
severity: critical
keywords: [ThreadPool, ExecutorService, VirtualThreads, Backpressure, RPS]
---

# Thread Pool Tuning Guardrails

## DON'T (안티패턴)

### 1. ThreadPool 사이즈를 트래픽 요구사항에 맞추지 않기
```java
// BAD: 1000 RPS 목표인데 max=8로 설정
executor.setCorePoolSize(4);
executor.setMaxPoolSize(8);      // 1000 RPS에 턱없이 부족
executor.setQueueCapacity(200);  // 1초 미만에 가득 참
```

**영향:**
- Cache MISS 시나리오: API 호출(5s) + 파싱(100ms) + 캐싱(50ms) = ~5.1s/request
- 1000 RPS × 5.1s = **5,100개 동시 작업 필요**
- 현재 용량: 8 threads + 200 queue = **208개 최대**
- 결과: **80% 요청 거부 (503 에러)**

### 2. .join() 내부 호출로 Deadlock 유발
```java
// BAD: ThreadPool 내에서 .join() 호출
return CompletableFuture
    .supplyAsync(() -> {
        // expectationComputeExecutor (max 8 threads) 내에서
        return dataResolver.resolveAsync(...).join();  // ← DEADLOCK!
    }, expectationComputeExecutor);
```

**영향:**
- 8개 스레드가 모두 `.join()`으로 대기 중
- 새 요청 처리 불가 → 큐 가득 참 → 시스템 멈춤
- **완전 교착 상태 (Deadlock)**

### 3. unbounded Virtual Thread Executor 사용
```java
// BAD: 제한 없는 Virtual Thread 생성
private final Executor aiExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

**영향:**
- LLM 호출(5-10초) 누적
- JVM OOM, CPU 100% 스파이크 → 전체 서비스 장애

## DO (베스트 프랙티스)

### 1. RPS 요구사항에 맞는 ThreadPool 크기 계산
```java
// GOOD: 목표 RPS에 따른 ThreadPool 설정
// Required = (RPS × avg_request_time) + buffer
// = (1000 × 5.1) + 100 = 5,200 capacity

executor.setCorePoolSize(50);      // Warm pool (10% of max)
executor.setMaxPoolSize(500);      // Peak 대응
executor.setQueueCapacity(5000);   // Burst 흡수

// Rejection 모니터링
executor.setRejectedExecutionHandler((r, e) -> {
    meterRegistry.counter("executor.rejection").increment();
    throw new RejectedExecutionException("Queue full");
});
```

**계산 공식:**
```
Max Pool Size = (Target RPS × Average Request Time in Seconds) / Core Count
Queue Capacity = Max Pool Size × 10

Example (t3.small, 2 vCPU):
- Max Pool = (1000 × 5.1) / 2 = 2,550 → rounded to 500 (conservative)
- Queue = 500 × 10 = 5,000
```

### 2. thenCompose()로 비동기 체이닝
```java
// GOOD: thenCompose() 체이닝
future.thenCompose(result ->
    CompletableFuture.supplyAsync(() -> process(result), executor)
);
```

### 3. Bean 기반 Executor + 제어된 Virtual Threads
```java
// GOOD: Bean으로 관리되는 Executor
@Bean("aiExecutor")
public Executor aiExecutor() {
    return new ExecutorService(
        Executors.newVirtualThreadPerTaskExecutor(),
        100  // max concurrent limits
    );
}
```

## Monitoring & Alerts

```prometheus
# Thread Pool 고갈 경고
ALERT ThreadPoolExhaustion
  IF executor_active / executor_max > 0.9
  FOR 1m
  SEVERITY critical

  ANNOTATIONS {
    summary = "Thread Pool near exhaustion",
    runbook = "https://docs/runbooks/thread-pool.html"
  }

# Thread Pool Queue 거부 모니터링
ALERT ThreadPoolRejectionSpike
  IF rate(executor_rejection_total[1m]) > 10
  SEVERITY warning
```

## Verification Commands

```bash
# Thread Pool 상태 확인
curl -s http://localhost:8080/actuator/metrics/executor.pool.size | jq '.measurements[] | select(.statistic=="MAX")'

# Queue 잔여 공간 확인
curl -s http://localhost:8080/actuator/metrics/executor.queue.remaining | jq '.measurements'

# Active threads 확인
curl -s http://localhost:8080/actuator/metrics/executor.active.threads | jq '.measurements'
```

## Before/After Performance

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Max RPS | 235 | 1,000 | 4.25× |
| Thread Pool (max) | 8 | 500 | 62.5× |
| Queue Capacity | 200 | 5,000 | 25× |
| P99 Latency | 450ms | <100ms | 4.5× faster |
| Rejection Rate | 80% | <0.1% | -800× |

## Instance Type Guidelines

| Instance Type | vCPU | RAM | Recommended Max Pool | Queue |
|---------------|------|-----|---------------------|-------|
| **t3.small** | 2 | 2GB | 200-500 | 2,000-5,000 |
| **t3.medium** | 2 | 4GB | 500-1,000 | 5,000-10,000 |
| **t3.large** | 2 | 8GB | 1,000-2,000 | 10,000-20,000 |

Note: Max Pool Size는 vCPU 수에 비례하지 않고 **작업 유형(I/O_bound vs CPU_bound)**에 따라 결정

## References

- [Spring Boot Executor Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/io.html#io.task-execution)
- [Java 21 Virtual Threads](https://openjdk.org/jeps/444)
- [Thread Pool Sizing Strategy](https://blog.deferred.io/why-you-should-always-use-a-boundedqueue-and-how-to-size-it-correctly/)

## 출처
- [docs/05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md](../../../05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md)
- Evidence ID: EVIDENCE-001, EVIDENCE-003, EVIDENCE-006
