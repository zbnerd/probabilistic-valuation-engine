---
id: GR-CONC-002
category: backend/concurrency
severity: critical
keywords: [CallerRunsPolicy, AbortPolicy, RejectedExecutionHandler, backpressure, Thread Pool]
languages: [java, kotlin]
---
# Thread Pool Backpressure Best Practice

## DON'T (안티패턴)

### 1. CallerRunsPolicy 사용 (절대 금지 - P1 #168)
```java
// Bad (톰캣 스레드 고갈 -> 전체 API 마비)
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```

**CallerRunsPolicy 문제점:**
- "backpressure" 의도였으나 실제로는 **톰캣 스레드 고갈** 유발
- 큐 포화 시 요청 처리 시간 비정상 증가 (SLA 위반)
- 메트릭 기록 불가 (rejected count = 0으로 보임)
- 서킷브레이커 동작 불가 (예외가 발생하지 않음)
- **Production Incident:** P1 #168 (2025-11) - CallerRunsPolicy caused tomcat thread exhaustion during traffic spike

### 2. 조회/계산 Executor에 CallerRunsPolicy 적용
```java
// Bad (읽기 전용 작업에는 CallerRunsPolicy 사용 금지)
@Bean("readExecutor")
public Executor readExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // 위험!
    return executor;
}
```

### 3. 메트릭 수집 없이 AbortPolicy만 사용
```java
// Bad (메트릭 없이 모든 reject 로깅 -> log storm)
private static final RejectedExecutionHandler CUSTOM_ABORT_POLICY = (r, executor) -> {
    log.warn("[Executor] Task rejected");  // 매 요청마다 로그!
    throw new RejectedExecutionException("Executor queue full");
};
```

## DO (베스트 프랙티스)

### 1. AbortPolicy + 샘플링 로깅 패턴
```java
// Good (즉시 거부 -> 503 응답 -> 클라이언트 재시도)
private static final AtomicLong rejectedCount = new AtomicLong(0);
private static final AtomicLong lastRejectNanos = new AtomicLong(0);
private static final long REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

private static final RejectedExecutionHandler CUSTOM_ABORT_POLICY = (r, executor) -> {
    // 1. Shutdown 구분
    if (executor.isShutdown() || executor.isTerminating()) {
        throw new RejectedExecutionException("Executor rejected (shutdown)");
    }

    // 2. 샘플링 로깅 (1초 1회, log storm 방지)
    long dropped = rejectedCount.incrementAndGet();
    long now = System.nanoTime();
    long prev = lastRejectNanos.get();

    if (now - prev >= REJECT_LOG_INTERVAL_NANOS &&
        lastRejectNanos.compareAndSet(prev, now)) {
        long count = rejectedCount.getAndSet(0);
        log.warn("[Executor] Task rejected. droppedInLastWindow={}, poolSize={}, queueSize={}",
                count, executor.getPoolSize(), executor.getQueue().size());
    }

    // 3. 예외 던지기 (Future 완료 보장)
    throw new RejectedExecutionException("Executor queue full");
};

@Bean("readExecutor")
public Executor readExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler(CUSTOM_ABORT_POLICY);
    return executor;
}
```

### 2. Micrometer 메트릭 등록 (Context7 공식)
```java
// Good (ExecutorServiceMetrics 등록)
new ExecutorServiceMetrics(
    executor.getThreadPoolExecutor(),
    "executor.name",
    Collections.emptyList()
).bindTo(meterRegistry);

// rejected Counter 추가 (ExecutorServiceMetrics 미제공)
Counter rejectedCounter = Counter.builder("executor.rejected")
        .tag("name", "executor.name")
        .description("Number of tasks rejected due to queue full")
        .register(meterRegistry);
```

**제공 메트릭:**
| 메트릭 | 설명 |
|--------|------|
| `executor.completed` | 완료된 작업 수 |
| `executor.active` | 현재 활성 스레드 수 |
| `executor.queued` | 큐에 대기 중인 작업 수 |
| `executor.pool.size` | 현재 스레드 풀 크기 |
| `executor.rejected` | 거부된 작업 수 (커스텀) |

### 3. 503 응답 + Retry-After 헤더 (HTTP 표준)
```java
// Good (GlobalExceptionHandler에서 처리)
@ExceptionHandler(CompletionException.class)
protected ResponseEntity<ErrorResponse> handleCompletionException(CompletionException e) {
    if (e.getCause() instanceof RejectedExecutionException) {
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "60")  // 60초 후 재시도 권장
            .body(errorResponse);
    }
    // ...
}
```

### 4. Executor 용도별 정책 분리
```java
// 조회/계산 (읽기) - AbortPolicy 권장
@Bean("readExecutor")
public Executor readExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler(CUSTOM_ABORT_POLICY);  // 재시도 가능, 멱등성
    return executor;
}

// DB 저장 (쓰기) - CallerRunsPolicy 또는 DLQ
@Bean("writeExecutor")
public Executor writeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // 지연 > 유실
    return executor;
}
```

**적용 가이드:**
| Executor 용도 | 권장 정책 | 이유 |
|--------------|----------|------|
| 조회/계산 (읽기) | **AbortPolicy** | 재시도 가능, 멱등성 |
| DB 저장 (쓰기) | **CallerRunsPolicy/DLQ** | 데이터 유실 방지 |
| 알림 전송 | **AbortPolicy** | Best-effort 허용 |

### 5. Write-Behind 패턴 주의 (Critical)
```java
// DANGER: Write-Behind + AbortPolicy = 데이터 유실
CompletableFuture.runAsync(() -> {
    dbWorker.persist(ocid, data);  // DB 저장
}, writeExecutor);  // AbortPolicy 적용 시 거부 = 데이터 유실!

// Safe: Write-Behind에는 CallerRunsPolicy 또는 DLQ 패턴
executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // 지연 > 유실
```

**AbortPolicy는 읽기 전용 작업에만 적용하세요!**

## 출처
- async-concurrency.md Section 22
- Production Incident: P1 #168 (2025-11) - CallerRunsPolicy caused tomcat thread exhaustion
- Fix Validated: AbortPolicy + 503 response prevented cascade failure
- Evidence: [P1 Report](../05_Reports/P1_Nightmare_Issues_Resolution_Report.md) Section 3.4

## 검증 명령어
```bash
# CallerRunsPolicy 확인 (금지 - 조회용 Executor)
grep -r "CallerRunsPolicy" src/main/kotlin --include="*.java" | grep -v "writeExecutor"

# Rejected metric 확인
curl -s http://localhost:8080/actuator/metrics/executor.rejected | jq

# Async thread pool active threads 확인
curl -s http://localhost:8080/actuator/metrics/executor.active | jq
```

## 롤백 계획
- AbortPolicy로 인한 데이터 유실이 확인될 경우: 즉시 CallerRunsPolicy로 복구
- 메트릭 수집이 불가능한 경우: 샘플링 로직 제거 후 모든 reject 로깅 (log storm 허용)
