---
id: GR-CONC-001
category: backend/concurrency
severity: critical
keywords: [CompletableFuture, .join(), blocking, async-pipeline]
languages: [java, kotlin]
---
# Async Non-Blocking Pipeline Pattern

## DON'T (안티패턴)

### 1. 톰캣 스레드 블로킹
```java
// Bad (톰캣 스레드 블로킹 -> 동시성 저하)
@GetMapping("/{userIgn}/expectation")
public ResponseEntity<Response> getExpectation(@PathVariable String userIgn) {
    Response result = service.calculate(userIgn);  // 블로킹 호출
    return ResponseEntity.ok(result);
}
```

### 2. .join() 사용 (절대 금지 - Issue #118)
```java
// Bad (.join()은 호출 스레드 블로킹)
return service.calculateAsync(userIgn).join();
```

**문제점:**
- .join()은 호출 스레드를 블로킹하여 비동기 파이프라인의 이점을 상실
- 톰캣 스레드 고갈로 이어져 동시성 저하 (200 threads로 제한)
- RPS가 89에서 719로 8.1배 하락

### 3. CompletableFuture 체이닝 위반
```java
// Bad (예외 처리 없음, 타임아웃 없음)
return service.calculateAsync(userIgn)
        .thenApply(this::postProcess);
```

## DO (베스트 프랙티스)

### 1. 톰캣 스레드 즉시 반환 (0ms 목표)
```java
// Good (톰캣 스레드 즉시 반환 -> RPS 719 달성)
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<Response>> getExpectation(@PathVariable String userIgn) {
    return service.calculateAsync(userIgn)  // 비동기 호출
            .thenApply(ResponseEntity::ok);
}
```

### 2. .join() 완전 제거 - 체이닝으로 논블로킹 유지
```java
// Good (체이닝으로 논블로킹 유지)
return service.calculateAsync(userIgn)
        .thenApply(this::postProcess)
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(this::handleException);
```

### 3. CompletableFuture 체이닝 Best Practice
```java
// Good (완전한 비동기 파이프라인)
return CompletableFuture
        .supplyAsync(() -> step1(), executor)
        .thenComposeAsync(r -> step2(r), executor)
        .thenApplyAsync(this::step3, executor)
        .orTimeout(DEADLINE_SECONDS, TimeUnit.SECONDS)
        .exceptionally(e -> handleException(e, context))
        .whenComplete((r, e) -> cleanup(context));
```

**체이닝 메서드 가이드:**
| 메서드 | 용도 | 예외 전파 |
|--------|------|----------|
| `thenApply()` | 동기 변환 | O |
| `thenApplyAsync()` | 비동기 변환 (다른 스레드) | O |
| `thenCompose()` | Future 평탄화 | O |
| `orTimeout()` | 데드라인 설정 | TimeoutException |
| `exceptionally()` | 예외 복구 | 복구 값 반환 |
| `whenComplete()` | 완료 후 정리 (결과 변경 불가) | X |

### 4. Two-Phase Snapshot 패턴 (캐시 HIT 시 DB 조회 스킵)
```java
// Good (Two-Phase Snapshot)
return CompletableFuture
        .supplyAsync(() -> fetchLightSnapshot(userIgn), executor)  // Phase 1
        .thenCompose(light -> {
            // 캐시 HIT -> 즉시 반환 (FullSnapshot 스킵)
            Optional<Response> cached = cacheService.get(light.cacheKey());
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(cached.get());
            }
            // 캐시 MISS -> Phase 2
            return CompletableFuture
                    .supplyAsync(() -> fetchFullSnapshot(userIgn), executor)
                    .thenCompose(full -> compute(full));
        });
```

### 5. Write-Behind 패턴 (비동기 DB 저장)
```java
// Good (응답 즉시 반환, DB 저장은 백그라운드)
return nexonApiClient.getEquipment(ocid)
        .thenApply(response -> {
            // 캐시 저장 (동기 - 응답에 필요)
            cacheService.put(ocid, response);

            // DB 저장 (비동기 - Fire-and-Forget)
            CompletableFuture.runAsync(() -> dbWorker.persist(ocid, response),
                    asyncTaskExecutor);

            return response;
        });
```

### 6. 스레드 풀 분리 원칙
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
```

| Thread Pool | 역할 | 설정 기준 |
|-------------|------|----------|
| `http-nio-*` | 톰캣 요청 | 즉시 반환 (0ms 목표) |
| `expectation-*` | 계산 전용 | CPU 코어 수 기반 |
| `SimpleAsyncTaskExecutor-*` | Fire-and-Forget | @Async 비동기 |
| `ForkJoinPool.commonPool-*` | CompletableFuture 기본 | JVM 관리 |

## 출처
- async-concurrency.md Section 21
- Performance Evidence: Async pipeline achieved 719 RPS vs 89 RPS blocking (8.1x improvement)
- Issue #118: .join() 완전 제거
- Production Load Test: 240 RPS sustained on AWS t3.small

## 검증 명령어
```bash
# .join() 사용 확인 (금지)
grep -r "\.join()" src/main/java/maple/expectation --include="*.java"

# Async thread pool 설정 확인
grep -r "ThreadPoolTaskExecutor" src/main/java/maple/expectation/config/
```
