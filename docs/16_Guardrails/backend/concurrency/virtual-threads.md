---
id: GR-CONC-003
category: backend/concurrency
severity: warning
keywords: [VirtualThread, Project Loom, Java 21, thread-per-task]
languages: [java, kotlin]
---
# Virtual Threads Best Practice

## DON'T (안티패턴)

### 1. Platform Thread로 고처리량 처리 시도
```java
// Bad (톰캣 Platform Thread로 동시성 제한)
@GetMapping("/{userIgn}/expectation")
public ResponseEntity<Response> getExpectation(@PathVariable String userIgn) {
    // 톰캣 스레드 풀 (Platform Thread)로 블로킹 처리
    // 동시성 ~200 threads로 제한됨
    Response result = service.calculate(userIgn);
    return ResponseEntity.ok(result);
}
```

**문제점:**
- Platform Thread는 OS 스레드와 1:1 매핑 -> 메모리 비용 높음
- 톰캣 기본 설정: 최대 ~200 threads -> 동시성 제한
- Blocking I/O 시 스레드 대기 -> 자원 낭비

### 2. Virtual Thread를 일반 스레드처럼 사용
```java
// Bad (Virtual Thread를 불필요하게 사용)
Thread.ofVirtual().start(() -> {
    // 단순 CPU 연산 - Virtual Thread 이점 없음
    int result = heavyComputation();
});
```

### 3. 스레드 로컬 변수 과도 사용
```java
// Bad (Virtual Thread에서 스레드 로컬 남용)
ThreadLocal<ExpensiveObject> threadLocal = new ThreadLocal<>();
// Virtual Thread는 수십만 개 생성 가능 -> 메모리 압박
```

## DO (베스트 프랙티스)

### 1. I/O 바운드 작업에 Virtual Thread 사용
```java
// Good (Virtual Thread + 비동기 I/O)
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<Response>> getExpectation(@PathVariable String userIgn) {
    return service.calculateAsync(userIgn)  // 비동기 호출
            .thenApply(ResponseEntity::ok);
}
```

**Virtual Thread 장점:**
- 경량 스레드 (JVM 관리, OS 스레드 아님)
- 10,000+ 동시 실행 가능 (vs Platform Thread ~200)
- Blocking I/O 시 JVM이 스레드를 언마운트하여 자원 절약

### 2. CompletableFuture와 전용 Executor 사용
```java
// Good (전용 스레드 풀 지정)
@Bean("expectationComputeExecutor")
public Executor expectationComputeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
    executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("expectation-");
    executor.initialize();
    return executor;
}

// CompletableFuture에서 사용
return CompletableFuture
        .supplyAsync(() -> step1(), executor)
        .thenComposeAsync(r -> step2(r), executor);
```

### 3. Thread Pool 설정 기준
| Thread Pool | 역할 | 설정 기준 | Virtual Thread 사용 |
|-------------|------|----------|---------------------|
| `http-nio-*` | 톰캣 요청 | 즉시 반환 (0ms 목표) | Spring Boot 3.x 자동 지원 |
| `expectation-*` | 계산 전용 | CPU 코어 수 기반 | Platform Thread 권장 (CPU 바운드) |
| `SimpleAsyncTaskExecutor-*` | Fire-and-Forget | @Async 비동기 | Virtual Thread 고려 |
| `ForkJoinPool.commonPool-*` | CompletableFuture 기본 | JVM 관리 | Platform Thread |

### 4. Spring Boot 3.x에서 Virtual Thread 활성화 (선택사항)
```java
// application.properties (선택사항)
spring.threads.virtual.enabled=true
```

**주의사항:**
- Virtual Thread는 **I/O 바운드** 작업에 효과적
- **CPU 바운드** 작업은 Platform Thread 사용 권장
- 캐시, DB Connection Pool 등은 여전히 필요

### 5. 스레드 풀 분리 원칙 준수
```java
// Good (각 작업별 전용 스레드 풀)
@Bean("expectationComputeExecutor")
public Executor expectationComputeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
    executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("expectation-");
    executor.initialize();
    return executor;
}

@Bean("asyncTaskExecutor")
public Executor asyncTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(50);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("async-task-");
    executor.setRejectedExecutionHandler(CUSTOM_ABORT_POLICY);
    executor.initialize();
    return executor;
}
```

## 출처
- async-concurrency.md Section 21
- Java 21 Virtual Threads (Project Loom)
- Performance Evidence: 10,000+ concurrent operations with Virtual Threads
- Production Status: Active (Validated through load testing achieving 240 RPS on t3.small)

## 검증 명령어
```bash
# Thread Pool 설정 확인
grep -r "ThreadPoolTaskExecutor" src/main/kotlin/maple/expectation/config/

# Virtual Thread 활성화 확인
grep -r "virtual.enabled" src/main/resources/application*.properties

# 스레드 덤프로 Virtual Thread 확인
jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json
grep -c "virtualThread" /tmp/threads.json
```

## 롤백 계획
- Virtual Thread로 인한 성능 저하 확인 시: Platform Thread로 복구
- `spring.threads.virtual.enabled=false` 설정 추가

## 참고 문서
- `docs/expectation-sequence-diagram.md` - 전체 데이터 흐름 시각화
- `docs/adr/ADR-012-stateless-scalability-roadmap.md` - Async architecture decision
