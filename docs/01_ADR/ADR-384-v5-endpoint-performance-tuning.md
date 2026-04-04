# ADR-384: V5 Expectation Endpoint Performance Tuning

**Status**: Accepted
**Date**: 2026-04-04
**Context**: `GET /api/v5/characters/{ign}/expectation`

## Context

V5 엔드포인트는 CQRS 패턴으로 잘 설계되어 있으나, 실행 경로에 4가지 성능 병목이 존재한다.
대부분의 요청은 PostgreSQL HIT로 즉시 응답(1-10ms)되지만, MISS 시 latency가 불필요하게 증가한다.

### 병목 분석

| ID | 병목 | 심각도 | 원인 |
|----|------|--------|------|
| B1 | ForkJoinPool.commonPool 사용 | HIGH | CPU-1 스레드만 가능, 다른 parallel 작업과 경쟁 |
| B2 | Rate Limiter 이중 acquire | HIGH | Batch Lane에서 permit 2개 소비, 유효 throughput 절반 |
| B3 | preWarm 동기 실행 | MEDIUM | 202 응답 전 preWarm 대기, best-effort인데 지연 유발 |
| B4 | .join() blocking | LOW (의도적) | @Cacheable 호환성 위해 유지 (ADR 문서화됨) |

### 현재 실행 흐름 (MISS 시)

```
Request → ForkJoinPool.commonPool
  → PostgreSQL query (blocking JDBC)
  → OCID resolution (cache/DB, blocking)
  → preWarm equipment cache (blocking, best-effort)
  → Queue task (PGMQ write, blocking)
  → 202 Response
```

### B1: ForkJoinPool.commonPool

```kotlin
// GameCharacterControllerV5.kt:70
CompletableFuture.supplyAsync { processPostgreSQLCacheFirstLookup(userIgn) }
```

- `supplyAsync` executor 미지정 → `ForkJoinPool.commonPool()` 사용
- commonPool 크기 = CPU-1 (일반적으로 3~7개)
- parallelStream, CompletableFuture 등 모든 JVM 병렬 작업과 공유
- blocking 작업(JDBC, .join())이 commonPool 스레드를 점유하면 다른 병렬 작업 병목

**해결**: 전용 executor(`expectationComputeExecutor` 또는 virtual thread executor) 사용

### B2: Rate Limiter 이중 Acquire

Batch Lane 경로:
```
NexonFanOutBatchLoader.load()
  → rateLimiter.acquirePermit()        ← 1차 acquire
  → nexonApiClient.getItemDataByOcid()
    → MetricsNexonApiClientWrapper
      → rateLimiter.acquirePermit()    ← 2차 acquire (동일 semaphore)
```

Fast Lane은 `NexonEquipmentMicroBatchAdapter.fetchSingle()` → `EquipmentFetchProvider` → `nexonApiClient` 경로로,
MetricsNexonApiClientWrapper에서만 1회 acquire하므로 중복 아님.

**해결**: `NexonFanOutBatchLoader`에서 `rateLimiter.acquirePermit()/releasePermit()` 제거.
MetricsNexonApiClientWrapper가 모든 경로의 rate limiting을 통합 담당.

### B3: preWarm 동기 실행

```kotlin
// GameCharacterControllerV5.kt:94-96
if (fanOutEnabled) {
    preWarmEquipmentCache(userIgn, context)  // 동기, best-effort
}
```

preWarm은 best-effort(실패해도 큐잉 정상 수행)인데, 202 응답 전에 실행되어 latency 추가.

**해결**: preWarm을 CompletableFuture.runAsync로 fire-and-forget 분리.
별도 executor에서 실행, 예외는 로깅만.

### B4: .join() Blocking

`EquipmentFetchProvider.fetchWithCache()`의 `.join()`은 ADR로 이미 문서화됨.
@Cacheable이 CompletableFuture를 지원하지 않아 의도적 유지.

**결정**: 변경 없음. B1~B3 수정으로 충분한 효과 기대.

## Decision

4가지 수정을 적용한다:

### 1. 전용 Executor 사용 (B1)

Controller의 `CompletableFuture.supplyAsync`에 `expectationComputeExecutor` 주입.

```kotlin
// Before
CompletableFuture.supplyAsync { processPostgreSQLCacheFirstLookup(userIgn) }

// After
CompletableFuture.supplyAsync(
    { processPostgreSQLCacheFirstLookup(userIgn) },
    expectationComputeExecutor  // Core:4, Max:8, 전용 풀
)
```

이미 `ExecutorConfig`에 정의된 `expectationComputeExecutor` 재사용.
새 executor 생성하지 않음.

### 2. Rate Limiter 이중 Acquire 제거 (B2)

`NexonFanOutBatchLoader.load()`에서 rate limiter 호출 제거.

```kotlin
// Before
rateLimiter.acquirePermit()
try {
    fetchOrEnqueueRetry(ocid)
} finally {
    rateLimiter.releasePermit()
}

// After
fetchOrEnqueueRetry(ocid)  // MetricsNexonApiClientWrapper에서 rate limiting 담당
```

NexonFanOutBatchLoader에서 `rateLimiter` 필드 및 import 제거.

### 3. preWarm Fire-and-Forget (B3)

```kotlin
// Before
if (fanOutEnabled) {
    preWarmEquipmentCache(userIgn, context)  // 동기
}

// After
if (fanOutEnabled) {
    CompletableFuture.runAsync(
        { preWarmEquipmentCache(userIgn, context) },
        expectationComputeExecutor
    )
    // fire-and-forget, 202 응답 지연 없음
}
```

### 4. .join() 유지 (B4)

변경 없음. 기존 ADR 의사결정 유지.

## Consequences

### 긍정적
- **202 응답 속도**: preWarm 비동기화로 MISS 시 latency 감소
- **처리량**: ForkJoinPool → 전용 executor로 다른 병렬 작업 영향 제거
- **Batch Lane throughput**: rate limiter 중복 제거로 유효 permit 2배
- **안정성**: commonPool 고갈로 인한 전체 JVM 병목 방지

### 위험
- preWarm fire-and-forget: `.exceptionally()` 핸들러로 로깅 보장. MetricsNexonApiClientWrapper에서 API 에러 counter 기록.
- expectationComputeExecutor 공유: 다른 계산 작업과 executor 공유. 요청당 최대 2 task(main + preWarm) 생성. Queue 200 + Max 8 = 208 동시 처리 가능. 기존 under-utilized 상태로 판단.
- DIP 약화: module-web에서 `@Qualifier("expectationComputeExecutor")` 직접 주입. 기존 `EquipmentDataResolver` 패턴과 동일한 infrastructure 예외 허용.

### Rollback Criteria (B2 — Rate Limiter 제거)

기존 `nexon.api.throttled` Micrometer counter로 모니터링.

| 지표 | 정상 임계치 | Rollback 트리거 |
|------|-------------|-----------------|
| `nexon.api.throttled` 증가율 | 기준 대비 <50% | 기준 대비 >100% 지속 5분 |
| Batch Lane p95 latency | <2s | >5s 지속 5분 |

### Code Path Trace (B2 검증)

```
Fast Lane (중복 없음):
  preFetchByOcid()
    → AdaptiveMicroBatchUserService.getByKey()
      → executeFastLane()
        → fetchSingle()
          → EquipmentFetchProvider.fetchWithCache()
            → nexonApiClient.getItemDataByOcid()
              → MetricsNexonApiClientWrapper.recordApiCall()
                → rateLimiter.acquirePermit()  ← 1회만

Batch Lane (수정 전 — 이중 acquire):
  load()
    → rateLimiter.acquirePermit()           ← 1차
    → fetchOrEnqueueRetry()
      → nexonApiClient.getItemDataByOcid()
        → MetricsNexonApiClientWrapper.recordApiCall()
          → rateLimiter.acquirePermit()     ← 2차 (동일 semaphore)

Batch Lane (수정 후 — 단일 acquire):
  load()
    → fetchOrEnqueueRetry()                 ← acquire 없음
      → nexonApiClient.getItemDataByOcid()
        → MetricsNexonApiClientWrapper.recordApiCall()
          → rateLimiter.acquirePermit()     ← 1회만
```

### 성능 영향 추정

| 지표 | Before | After | 근거 |
|------|--------|-------|------|
| 202 응답 latency (MISS) | ~300ms | ~50ms | preWarm 비동기화 |
| commonPool 가용성 | CPU-1 | 영향 없음 | 전용 executor 분리 |
| Batch Lane TPS | N/2 | N | rate limiter 중복 제거 |

## Files to Modify

1. `module-web/.../v5/GameCharacterControllerV5.kt` — B1 (executor), B3 (preWarm async)
2. `module-infra/.../fanout/NexonFanOutBatchLoader.kt` — B2 (rate limiter 제거)

Constraint: @Cacheable 동기 계약 유지 필요
Rejected: Virtual Thread Executor 신규 생성 | 기존 expectationComputeExecutor 재사용이 충분
Rejected: WebFlux 전환 | @Cacheable 호환성, 팀 학습 비용, 현재 트래픽 수준에서 과도
Confidence: high
Scope-risk: narrow
Directive: preWarm fire-and-forget 예외는 로그 + Micrometer counter로만 추적. 별도 알림 추가 금지.
Not-tested: fanout.enabled=true 환경에서의 실제 TPS 측정
