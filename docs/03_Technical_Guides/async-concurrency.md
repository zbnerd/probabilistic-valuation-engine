# Async & Concurrency Guide

> **상위 문서:** [CLAUDE.md](../CLAUDE.md)
>
> **Last Updated:** 2026-02-05
> **Applicable Versions:** Java 21, Spring Boot 3.5.4
> **Documentation Version:** 1.0
> **Production Status:** Active (Validated through load testing achieving 240 RPS on t3.small)

이 문서는 probabilistic-valuation-engine 프로젝트의 비동기 처리, Thread Pool, 동시성 관련 규칙을 정의합니다.

## Documentation Integrity Statement

This guide is based on **production load testing** and **performance analysis** from high-traffic scenarios:
- Load test results: 240 RPS sustained on AWS t3.small (Evidence: [N23_WRK_V4_RESULTS.md](../05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md))
- Thread pool analysis: 11 bottlenecks identified and resolved (Evidence: [high-traffic-performance-analysis.md](../05_Reports/high-traffic-performance-analysis.md))
- P1 performance improvements: 40% latency reduction (Evidence: [p1-p2-performance-improvements-report.md](../05_Reports/p1-p2-performance-improvements-report.md))

## Terminology

| 용어 | 정의 |
|------|------|
| **Virtual Thread** | Java 21의 경량 스레드 (Project Loom) |
| **Two-Phase Snapshot** | Light → Full 단계적 데이터 로드 패턴 |
| **Write-Behind** | 응답 후 비동기 DB 저장 패턴 |
| **CallerRunsPolicy** | 거부된 작업을 호출자 스레드에서 실행 (위험) |
| **AbortPolicy** | 작업 거부 시 예외 발생 (권장) |

---

## 21. Async Non-Blocking Pipeline Pattern (Critical)

> **Performance Evidence:** Async pipeline achieved 719 RPS vs 89 RPS blocking (8.1x improvement) (Evidence: [Performance Report](../05_Reports/PERFORMANCE_260105.md)).
> **Why NOT blocking:** Blocking on tomcat threads limits concurrency to ~200 threads. Virtual threads enable 10,000+ concurrent operations.
> **Known Limitations:** Async debugging complexity; mitigate with structured logging (TraceAspect).
> **Rollback Plan:** Disable `@Async` and return to synchronous controllers if observability becomes unmanageable.

고처리량 API를 위한 비동기 논블로킹 파이프라인 설계 패턴입니다. (Trace Log 분석 기반)

### 핵심 원칙: 톰캣 스레드 즉시 반환 (0ms)

```java
// Bad (톰캣 스레드 블로킹 -> 동시성 저하)
@GetMapping("/{userIgn}/expectation")
public ResponseEntity<Response> getExpectation(@PathVariable String userIgn) {
    Response result = service.calculate(userIgn);  // 블로킹 호출
    return ResponseEntity.ok(result);
}

// Good (톰캣 스레드 즉시 반환 -> RPS 719 달성)
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<Response>> getExpectation(@PathVariable String userIgn) {
    return service.calculateAsync(userIgn)  // 비동기 호출
            .thenApply(ResponseEntity::ok);
}
```

### Two-Phase Snapshot 패턴

캐시 HIT 시 불필요한 DB 조회를 방지하는 단계적 데이터 로드 패턴입니다.

| Phase | 목적 | 로드 데이터 |
|-------|------|------------|
| **LightSnapshot** | 캐시 키 생성 | 최소 필드 (ocid, fingerprint) |
| **FullSnapshot** | 계산 (MISS 시만) | 전체 필드 |

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

### Write-Behind 패턴 (비동기 DB 저장)

API 응답 시간 단축을 위해 DB 저장을 응답 후 비동기로 처리합니다.

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

### 스레드 풀 분리 원칙

| Thread Pool | 역할 | 설정 기준 |
|-------------|------|----------|
| `http-nio-*` | 톰캣 요청 | 즉시 반환 (0ms 목표) |
| `expectation-*` | 계산 전용 | CPU 코어 수 기반 |
| `SimpleAsyncTaskExecutor-*` | Fire-and-Forget | @Async 비동기 |
| `ForkJoinPool.commonPool-*` | CompletableFuture 기본 | JVM 관리 |

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

### .join() 완전 제거 규칙 (Issue #118)

```java
// Bad (.join()은 호출 스레드 블로킹)
return service.calculateAsync(userIgn).join();

// Good (체이닝으로 논블로킹 유지)
return service.calculateAsync(userIgn)
        .thenApply(this::postProcess)
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(this::handleException);
```

### CompletableFuture 체이닝 Best Practice

| 메서드 | 용도 | 예외 전파 |
|--------|------|----------|
| `thenApply()` | 동기 변환 | O |
| `thenApplyAsync()` | 비동기 변환 (다른 스레드) | O |
| `thenCompose()` | Future 평탄화 | O |
| `orTimeout()` | 데드라인 설정 | TimeoutException |
| `exceptionally()` | 예외 복구 | 복구 값 반환 |
| `whenComplete()` | 완료 후 정리 (결과 변경 불가) | X |

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

### 참고 문서
- `docs/expectation-sequence-diagram.md` - 전체 데이터 흐름 시각화

---

## 22. Thread Pool Backpressure Best Practice (Issue #168)

> **Production Incident:** P1 #168 (2025-11) - CallerRunsPolicy caused tomcat thread exhaustion during traffic spike.
> **Root Cause:** Rejected tasks ran on tomcat threads, blocking new requests and creating death spiral.
> **Fix Validated:** AbortPolicy + 503 response prevented cascade failure (Evidence: [P1 Report](../05_Reports/P1_Nightmare_Issues_Resolution_Report.md) Section 3.4).
> **Metrics Proof:** Rejected count increased from 0 (hidden) to visible metrics, enabling proper autoscaling.

ThreadPoolTaskExecutor의 RejectedExecutionHandler 설정 및 메트릭 수집을 위한 필수 규칙입니다.

### CallerRunsPolicy 금지 (Critical)

```java
// Bad (톰캣 스레드 고갈 -> 전체 API 마비)
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

// Good (즉시 거부 -> 503 응답 -> 클라이언트 재시도)
executor.setRejectedExecutionHandler(CUSTOM_ABORT_POLICY);
```

**CallerRunsPolicy 문제점:**
- "backpressure" 의도였으나 실제로는 **톰캣 스레드 고갈** 유발
- 큐 포화 시 요청 처리 시간 비정상 증가 (SLA 위반)
- 메트릭 기록 불가 (rejected count = 0으로 보임)
- 서킷브레이커 동작 불가 (예외가 발생하지 않음)

### AbortPolicy + 샘플링 로깅 패턴

```java
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
```

### Micrometer 메트릭 등록 (Context7 공식)

```java
// ExecutorServiceMetrics 등록
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

### 503 응답 + Retry-After 헤더 (HTTP 표준)

```java
// GlobalExceptionHandler에서 처리
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

### Write-Behind 패턴 주의 (Critical)

AbortPolicy는 **읽기 전용 작업에만** 적용하세요!

```java
// DANGER: Write-Behind + AbortPolicy = 데이터 유실
CompletableFuture.runAsync(() -> {
    dbWorker.persist(ocid, data);  // DB 저장
}, writeExecutor);  // AbortPolicy 적용 시 거부 = 데이터 유실!

// Safe: Write-Behind에는 CallerRunsPolicy 또는 DLQ 패턴
executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // 지연 > 유실
```

**적용 가이드:**
| Executor 용도 | 권장 정책 | 이유 |
|--------------|----------|------|
| 조회/계산 (읽기) | AbortPolicy | 재시도 가능, 멱등성 |
| DB 저장 (쓰기) | CallerRunsPolicy/DLQ | 데이터 유실 방지 |
| 알림 전송 | AbortPolicy | Best-effort 허용 |

### Evidence Links
- **ExecutorConfig:** `src/main/java/maple/expectation/config/ExecutorConfig.java` (Evidence: [CODE-EXEC-001])
- **EquipmentService:** `src/main/java/maple/expectation/service/v2/EquipmentService.java` (Evidence: [CODE-SVC-002])
- **Test:** `src/test/java/maple/expectation/service/v2/EquipmentServiceTest.java` (Evidence: [TEST-ASYNC-001])
- **Load Test Results:** `docs/05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md` (Evidence: [LOAD-N23-001])

---

## 23. IO/CPU Split Pattern (Issue #1125)

> **Related:** [ADR-723](../01_ADR/ADR-723_io-cpu-split-pattern.md), Issue #1125
>
> **Last Updated:** 2026-06-08
>
> **Production Status:** Pattern established; per-module adoption tracked in #1128, #1129, #1130, #1131.

CPU-bound 작업이 VT executor 의 carrier thread 를 pinning 하여 IO-bound VT 의 응답성을 저하시키는 문제를 해결하기 위한 dispatcher 라우팅 패턴.

### §23.1 원칙

| 작업 종류 | 예시 | 실행 컨텍스트 |
|---|---|---|
| **IO-bound** | DB query/update, HTTP call, Redis op, file R/W | VT executor (현재 유지) |
| **CPU-bound** | JSON parse/serialize, GZIP, SHA-256, large collection, 확률 계산 | `Dispatchers.Default` (coroutine) |

### §23.2 CPU-bound 분류 (Algorithm-based)

| 복잡도 \ 입력 크기 | n < 10K | 10K ≤ n < 100K | n ≥ 100K |
|---|---|---|---|
| O(n) (단순 map/filter) | inline OK | inline OK | **offload** |
| O(n log n) (sort) | inline OK | **offload** | **offload** |
| O(n²), O(n³+) (Markov, DP conv) | **offload** | **offload** | **offload** |

**무조건 offload (카테고리 무관):**

- `ObjectMapper.readTree / writeValueAsBytes / writeValueAsString`
- `GzipUtils.compress / decompress`
- `sha256Hex / Hashing.sha256`
- 확률 계산 (Markov chain, DP convolution) — 모든 크기

### §23.3 모듈별 wrap 방식

| 모듈 | 호출 컨텍스트 | 권장 패턴 |
|---|---|---|
| `module-external-api` | `CoroutineScope(dispatcher).future { ... }` 안 | `withContext(Dispatchers.Default) { cpuWork() }` |
| `module-calculator` | `launch(Dispatchers.Default) { ... }` 안 (coroutine) | `withContext(Dispatchers.Default) { cpuWork() }` |
| `module-synchronizer` | VT-only, no CoroutineScope | `runBlocking(Dispatchers.Default) { cpuWork() }` |
| `module-rest-controller` | `CompletableFuture.supplyAsync(..., executor)` | 컨텍스트 따라 위 1·2·3행 중 선택 |

**선택 기준:**

- 이미 `CoroutineScope` 안 → `withContext`
- VT-only → `runBlocking` bridge (이 경우만, **PGMQ batch worker 같은 single-threaded consumer 한정**)
- `runBlocking` 은 calling thread 를 block 한다. 따라서 **single-threaded batch worker (e.g. PgmqWorker) 에서만** 안전. multi-threaded consumer (e.g. async Kafka consumer) 에서는 message poll loop starvate 위험 → `withContext` + structured concurrency 로 리팩토링 필요.

### §23.4 올바른 사례 (Reference)

#### Case A: VT-only 모듈 (synchronizer 패턴, PGMQ single-thread batch)

**파일:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt:120-135`

```kotlin
// GOOD: runBlocking(Dispatchers.Default) bridge in single-threaded PGMQ batch worker
): List<PgmqProjectionOutcome> = runBlocking(Dispatchers.Default) {
    items.map { item ->
        // CPU-bound: JSON parse, grouping, transform
        transformOutcome(item)
    }
}
```

#### Case B: Coroutine 모듈 (single-item CPU offload)

**파일:** `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:60-80`

```kotlin
// GOOD: withContext inside launch (single-item CPU offload)
launch(Dispatchers.Default) {
    val parsed = withContext(Dispatchers.Default) { parseLines(batch) }
    val calculated = withContext(Dispatchers.Default) { processItems(parsed) }
    // IO-bound 부분은 별도 dispatcher
    withContext(ioDispatcher) { writeResult(calculated) }
}
```

#### Case C: Coroutine 모듈 (multi-item parallel CPU offload)

**용도:** 동일 coroutine scope 안에서 multiple items 를 병렬 처리. `async` + `awaitAll` 사용.

```kotlin
// GOOD: async + awaitAll for multi-item parallel CPU offload
coroutineScope {
    val deferreds = items.map { item ->
        async(Dispatchers.Default) {
            // CPU-bound per item
            processItem(item)
        }
    }
    val results = deferreds.awaitAll()
    // results 사용
}
```

### §23.5 안티 사례 (Anti-pattern)

#### Anti-A: CPU 작업을 IO dispatcher 에 dispatch

**파일:** `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt:377` (참고용, #1131 에서 수정)

```kotlin
// BAD: Dispatchers.IO 에 CPU 작업 dispatch (IO 는 64 thread 한정)
val results: List<CalculationResult> = runBlocking(Dispatchers.IO) {
    items.map { calculateOnly(it) }  // CPU-bound!
}
```

**Fix:** `Dispatchers.IO` → `Dispatchers.Default`

#### Anti-B: VT 에서 inline CPU-heavy

**파일:** `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt:185-205` (참고용, #1128 에서 수정)

```kotlin
// BAD: thenAcceptAsync callback 안에서 CPU-bound JSON parse + 직렬화
private fun fetchAndCollectOcidAsync(
    ign: String,
    workerExecutor: ExecutorService,
    ...
): CompletableFuture<Void> =
    clientPort.fetch(...)
        .thenAcceptAsync({ data ->
            val ocid = objectMapper.readTree(data).get("ocid")?.asText()  // CPU pinning
            val json = String(objectMapper.writeValueAsBytes(mapOf(...)))  // CPU pinning
            results.add(json)
        }, workerExecutor)  // workerExecutor 는 VT executor
```

**Fix:** `thenAcceptAsync` callback 전체를 `withContext(Dispatchers.Default) { ... }` 로 wrap 하거나, callback 안의 `objectMapper.*` 호출만 별도 `withContext` 로 offload.

### §23.6 PR Review Checklist

PR review 시 다음 grep 으로 안티 사례 잔존 여부 확인:

```bash
# Anti-A: CPU-bound 를 IO dispatcher 에 dispatch
grep -rn "runBlocking(Dispatchers\.IO)" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller

# Anti-B: VT/IO 에서 JSON parse/serialize inline
grep -rn "objectMapper\.readTree\|objectMapper\.writeValueAs" --include="*.kt" \
    module-external-api module-synchronizer module-calculator module-rest-controller \
    | grep -v "Dispatchers.Default"
```

**Note:** `module-infra` 의 `PgmqWorker.kt:377` 은 위 grep 으로 잡히지 않음 — #1131 PR review 에서 별도 확인.

CPU-bound 코드 발췌 발견 시 → reviewer 가 §23.2 표에 따라 offload 권고.

### §23.7 Cross-reference

- §21 (Async Non-Blocking Pipeline Pattern) — `join()` 금지, 체이닝 유지. §23 과 조화: CPU offload 후에도 IO 단계는 VT 체이닝.
- §22 (Thread Pool Backpressure Best Practice) — `AbortPolicy` + `CallerRunsPolicy` 구분. §23 의 dispatcher 결정과 무관.

### §23.8 Related

- ADR-723: `docs/01_ADR/ADR-723_io-cpu-split-pattern.md`
- Spec: `docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md`
- 후속: #1128 (external-api), #1129 (synchronizer), #1130 (rest-controller), #1131 (infra worker)

---

## Technical Validity Check

This guide would be invalidated if:
- **Code examples don't compile with Java 21**: Verify Virtual Thread syntax and CompletableFuture chaining
- **Thread pool settings differ from application.yml**: Compare with actual runtime configuration
- **CallerRunsPolicy is used in production**: Verify tomcat thread exhaustion risk
- **.join() usage causes blocking**: Check async pipeline for blocking calls
- **RejectedExecutionHandler metrics not visible**: Confirm custom metrics are registered

### Verification Commands
```bash
# Thread Pool 설정 확인
grep -r "ThreadPoolTaskExecutor" src/main/java/maple/expectation/config/

# .join() 사용 확인 (금지)
grep -r "\.join()" src/main/java/maple/expectation --include="*.java"

# CallerRunsPolicy 확인 (금지 - 조회용 Executor)
grep -r "CallerRunsPolicy" src/main/java --include="*.java" | grep -v "writeExecutor"

# Rejected metric 확인
curl -s http://localhost:8080/actuator/metrics/executor.rejected | jq

# Async thread pool active threads 확인
curl -s http://localhost:8080/actuator/metrics/executor.active | jq
```

### Related Evidence
- Load Test: `docs/05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md`
- Performance Analysis: `docs/05_Reports/high-traffic-performance-analysis.md`
- ADR-012: `docs/01_ADR/ADR-012-stateless-scalability-roadmap.md` (Async architecture decision)
