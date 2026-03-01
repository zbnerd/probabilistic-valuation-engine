---
id: GR-CHAOS-N10
category: testing/chaos
severity: critical
keywords: [Nightmare, chaos, N10, CallerRunsPolicy, ThreadPool, Async, Backpressure]
languages: [java, kotlin]
---

# [N10] CallerRunsPolicy Betrayal

## DON'T (장애 원인)

ThreadPoolTaskExecutor의 `CallerRunsPolicy`가 "안전한" 거부 정책으로 보이지만, 실제로는 **HTTP 요청 스레드를 블로킹**하여 타임아웃을 유발합니다.

### 위험 코드 패턴

```java
// 위험한 설정
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setRejectedExecutionHandler(new CallerRunsPolicy()); // ❌ 위험!
    return executor;
}
```

### 장애 시나리오

```
정상 상황:
HTTP Thread → submit(task) → Task runs in Pool Thread → HTTP Thread continues

CallerRunsPolicy 발동 시:
HTTP Thread → submit(task) → Task runs in HTTP Thread! → HTTP Thread blocked!
              ↑ Queue Full

결과:
- 5초 걸리는 백그라운드 작업 → HTTP 5초 블로킹
- Load Balancer가 응답 없는 서버로 계속 라우팅
- 모든 HTTP 스레드가 점유되면 서비스 중단
```

### 장애 수치
- **HTTP Thread Blocking Time**: 최대 5초 (작업 소요 시간)
- **Service Availability**: 0% (모든 HTTP 스레드 점유 시)
- **Little's Law 위반**: L = λW에서 W가 급증하여 시스템 과부하

---

## DO (재발 방지)

### 1. AbortPolicy + 빠른 실패 적용

```java
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()); // ✅ 빠른 실패
    executor.setThreadNamePrefix("async-");
    return executor;
}
```

### 2. 커스텀 거부 핸들러 (메트릭 기록)

```java
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setRejectedExecutionHandler((r, e) -> {
        log.warn("Task rejected: {}", r);
        meterRegistry.counter("threadpool.rejected").increment();
        throw new RejectedExecutionException("ThreadPool exhausted");
    });
    return executor;
}
```

### 3. 거부 시 적절한 HTTP 응답 반환

```java
try {
    taskExecutor.execute(task);
} catch (RejectedExecutionException e) {
    log.warn("Task rejected, returning 503");
    throw new ServiceUnavailableException("System busy");
}
```

### 4. Bounded Semaphore 패턴 (submit 속도 제한)

```java
private final Semaphore submitSemaphore = new Semaphore(50);

public void submitTask(Runnable task) {
    if (!submitSemaphore.tryAcquire()) {
        throw new ServiceUnavailableException("Too many pending tasks");
    }
    try {
        executor.execute(() -> {
            try {
                task.run();
            } finally {
                submitSemaphore.release();
            }
        });
    } catch (Exception e) {
        submitSemaphore.release();
        throw e;
    }
}
```

### 개선 수치 (테스트 결과 기준)
- **HTTP Thread Blocking Time**: 0초 (작업이 HTTP 스레드에서 실행되지 않음)
- **503 Response Rate**: 거부된 작업에 대해 100% 반환
- **Service Availability**: 유지 (큐 포화 시에도 HTTP 스레드는 계속 수신 가능)
- **Little's Law 준수**: W(대기시간) 급증 없이 시스템 안정성 유지

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N10-caller-runs-policy.md`
- `docs/05_Reports/04_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md` (Issue #222)
