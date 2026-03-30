---
id: GR-CHAOS-N12
category: testing/chaos
severity: high
keywords: [Nightmare, chaos, N12, Async Context Loss, MDC, ThreadLocal, TaskDecorator]
languages: [java, kotlin]
---

# [N12] Async Context Loss (Phantom Context)

## DON'T (장애 원인)

@Async 메서드 호출 시 **MDC(Mapped Diagnostic Context)**와 **SecurityContext**가 새 스레드로 전파되지 않아 **추적성이 상실**됩니다.

### 위험 코드 패턴

```java
// 위험: TaskDecorator 없는 설정
@Bean
public ThreadPoolTaskExecutor asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    // TaskDecorator 미설정 ❌
    return executor;
}

@Service
public class OrderService {
    public void processOrder(String orderId) {
        MDC.put("orderId", orderId);  // 메인 스레드에 설정

        asyncExecutor.execute(() -> {
            String id = MDC.get("orderId");  // NULL! 다른 스레드
            log.info("Processing order");     // 로그에 orderId 없음 ❌
        });
    }
}
```

### 문제점
- 분산 추적(Tracing) 끊김
- 감사 로그에 사용자 정보 없음
- 디버깅 시 요청 흐름 추적 불가
- CompletableFuture 체인(`thenRunAsync`)에서 Stage 2,3 MDC 손실

### 장애 수치
- **MDC Propagation Success Rate**: 0% (TaskDecorator 없을 시)
- **Tracing Continuity**: 끊어짐
- **Debuggability**: 요청 추적 불가

---

## DO (재발 방지)

### 1. TaskDecorator 적용 (MDC 전파)

```java
@Bean
public ThreadPoolTaskExecutor asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    executor.setTaskDecorator(new MdcCopyingTaskDecorator()); // ✅ MDC 전파
    return executor;
}

public class MdcCopyingTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

### 2. SecurityContext 전파

```java
public class SecurityContextCopyingTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return () -> {
            try {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                runnable.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
```

### 3. Micrometer Context Propagation (CompletableFuture용)

```java
@Bean
public ContextPropagationExecutorService contextAwareExecutor() {
    return ContextPropagation.wrapExecutorService(
        Executors.newFixedThreadPool(10)
    );
}
```

### 4. CompletableFuture 체인에서 독립적인 runAsync 사용

```java
// 위험: thenRunAsync는 Stage 2,3에서 MDC 손실
CompletableFuture.runAsync(() -> step1())
    .thenRunAsync(() -> step2())  // ❌ MDC 손실
    .thenRunAsync(() -> step3());

// 권장: 독립적인 runAsync
CompletableFuture.runAsync(() -> step1(), executor);
CompletableFuture.runAsync(() -> step2(), executor);  // ✅ MDC 유지
CompletableFuture.runAsync(() -> step3(), executor);
```

### 개선 수치 (테스트 결과 기준)
- **MDC Propagation Success Rate**: 100% (단일 비동기 호출)
- **SecurityContext Propagation**: 100%
- **Executor Thread Names**: `alert-`, `expectation-` 프리픽스로 디버깅 용이
- **Known Limitation**: CompletableFuture `thenRunAsync` 체인은 Stage 2,3에서 MDC 손실 (의도된 동작)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N12-async-context-loss.md`
- `docs/05_Reports/05_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`
