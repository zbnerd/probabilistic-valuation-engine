# Fan-Out Batch Worker with Request Coalescing (v2 - Consensus-Reviewed)

## Context

### Problem
현재 V4/V5 컨트롤러는 캐시 미스 시 개별적으로 Nexon API를 호출한다. 동시 다수 요청 시:
- 동일 OCID에 대한 중복 API 호출
- Nexon API rate limit (429) 도달 위험
- 서버 리소스 비효율적 사용

### Key Discovery (Consensus Review)
**`AdaptiveMicroBatchUserService`**가 이미 구현되어 있음:
- Request Coalescing (inFlightRequests + CompletableFuture)
- Adaptive Routing (Fast Lane: semaphore, Batch Lane: Channel)
- Batch Collection (Channel + 10ms window + max size)
- Deduplication (uniqueKeys = batch.map { it.key }.distinct())
- Chunked Processing (chunked(chunkSize))
- Coroutine-based (CoroutineScope + Dispatchers.IO)
- Caffeine cache integration + Metrics

**기존 Adapter 패턴**: `GameCharacterMicroBatchAdapter`, `L2CacheMicroBatchAdapter`가 이미 `AdaptiveMicroBatchUserService`를 감싸서 사용 중.

### Scope
- **대상**: V4/V5 캐릭터 기대값 조회 흐름 개선
- **캐시**: 기존 TieredCache + Caffeine 유지 (Redis 없음)
- **동시성**: 기존 `ResilientNexonApiClient`의 Bulkhead(50) + Retry(3) + CB 재사용, 추가 RateLimiter는 불필요

---

## Architecture

```
V4/V5 Controller (cache miss)
  |
  v
NexonEquipmentMicroBatchAdapter (GameCharacterMicroBatchAdapter pattern)
  |
  |-- L1 (Caffeine) HIT -> return immediately
  |
  |-- In-Flight (coalescing) -> wait for existing request
  |
  |-- Fast Lane (semaphore available)
  |   +-- EquipmentFetchProvider.fetchWithCache(ocid) -> @Cacheable + ResilientNexonApiClient
  |
  +-- Batch Lane (Channel -> 10ms window)
      +-- NexonFanOutBatchLoader.load(ocids)
          |-- Concurrent (CompletableFuture.supplyAsync, 30-permit Semaphore)
          |-- Each -> ResilientNexonApiClient.getItemDataByOcid(ocid).join()
          |-- Success -> cache.put + archive
          +-- 429 -> FanOutQueueProducer.enqueue() -> PGMQ

NexonFanOutWorker (extends PgmqWorker)
  |-- Polls PGMQ (50ms interval)
  |-- 429 -> setVisibilityTimeout (1~1.3s jitter)
  |-- retry_count >= 5 -> delete (DLQ)
  +-- Success -> archive + cache write
```

---

## Implementation Steps

### Step 1: FanOut 메시지 페이로드

**File: `module-infra/.../pgmq/PgmqMessage.kt`** (기존 파일에 추가)
```kotlin
data class FanOutRequest(
    val ocid: String,
    val userIgn: String,
    val retryCount: Int = 0,
    val requestedAt: String,
)
```

### Step 2: FanOutQueuePort (Hexagonal Architecture 준수)

**File: `module-core/.../port/out/FanOutQueuePort.kt`** (신규)
```kotlin
interface FanOutQueuePort {
    fun enqueue(ocid: String, userIgn: String, retryCount: Int = 0): Long
}
```

### Step 3: FanOutQueueProducer (Port 구현)

**File: `module-infra/.../queue/pgmq/FanOutQueueProducer.kt`** (신규)
- `CalculationQueueProducer` 패턴 참고
- `PgmqClient.send()` 로 메시지 발행
- **`@Transactional` 필수** (PgmqClient.send() 가 트랜잭션 체크)
- `LogicExecutor`로 예외 처리 위임 (Zero Try-Catch)

### Step 4: NexonFanOutBatchLoader (Batch Lane의 batchLoader)

**File: `module-infra/.../fanout/NexonFanOutBatchLoader.kt`** (신규)
- **CompletableFuture 기반** (기존 프로젝트 표준, `EquipmentFetchProvider` .join() ADR과 일치)
- `java.util.concurrent.Semaphore(30)` (Bulkhead(50)보다 작게 설정 -> 실제 제한 = min(30, 50) = 30)
- `ExecutorService` (전용, fixed thread pool = 10, `BulkLoaderService.BULK_EXECUTOR` 패턴 참고)
- 429 시: `FanOutQueuePort.enqueue()` 로 PGMQ에 전달, 해당 OCID는 결과에서 제외
- **`runBlocking` 없음**, **`Thread.sleep` 없음**

```kotlin
class NexonFanOutBatchLoader(
    private val nexonApiClient: NexonApiClient,
    private val fanOutQueuePort: FanOutQueuePort,
    private val executor: LogicExecutor,
) {
    private val semaphore = java.util.concurrent.Semaphore(30)
    private val executorService: ExecutorService = Executors.newFixedThreadPool(10)

    fun load(ocids: List<String>): Map<String, EquipmentResponse> {
        val futures = ocids.map { ocid ->
            CompletableFuture.supplyAsync({
                semaphore.acquire()
                try { fetchOrEnqueueRetry(ocid) } finally { semaphore.release() }
            }, executorService)
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).join()
            .let { futures.mapNotNull { it.join() }.associate { it } }
    }

    private fun fetchOrEnqueueRetry(ocid: String): Pair<String, EquipmentResponse>? {
        return executor.executeOrCatch(
            task = { ocid to nexonApiClient.getItemDataByOcid(ocid).orTimeout(10, SECONDS).join() },
            recovery = { e -> if (is429(e)) fanOutQueuePort.enqueue(ocid, "batch", 0); null },
            context = TaskContext.of("FanOutBatchLoader", "Fetch", ocid),
        )
    }
}
```

### Step 5: NexonEquipmentMicroBatchAdapter (핵심 - 기존 패턴 재사용)

**File: `module-infra/.../batch/NexonEquipmentMicroBatchAdapter.kt`** (신규)
- `GameCharacterMicroBatchAdapter` 패턴 그대로 사용
- `AdaptiveMicroBatchUserService<EquipmentResponse>` 인스턴스 생성
- `singleLoader`: `EquipmentFetchProvider.fetchWithCache(ocid)` (기존 @Cacheable 경로)
- `batchLoader`: `NexonFanOutBatchLoader.load(ocids)` (새로운 병렬 실행)
- `cache`: Caffeine "equipment" cache

```kotlin
@Service
class NexonEquipmentMicroBatchAdapter(
    properties: AdaptiveMicroBatchProperties,
    logicExecutor: LogicExecutor,
    meterRegistry: MeterRegistry,
    cacheManager: CacheManager,
    private val fetchProvider: EquipmentFetchProvider,
    private val batchLoader: NexonFanOutBatchLoader,
) {
    private val delegate = AdaptiveMicroBatchUserService<EquipmentResponse>(
        properties = properties,
        logicExecutor = logicExecutor,
        meterRegistry = meterRegistry,
        cache = cacheManager.getCache("equipment"),
        singleLoader = { key -> fetchProvider.fetchWithCache(key) },
        batchLoader = { keys -> batchLoader.load(keys) },
    )

    fun getByKey(ocid: String): EquipmentResponse? = delegate.getByKey(ocid)
}
```

### Step 6: PgmqWorkerConfig + NexonFanOutWorker

**File: `module-infra/.../pgmq/PgmqWorkerConfig.kt`** (수정)
```kotlin
/** Nexon FanOut Worker 설정 */
var nexonFanout: WorkerSettings = WorkerSettings()
```

**File: `module-infra/.../worker/NexonFanOutWorker.kt`** (신규)
- `PgmqWorker<FanOutRequest>` 상속 (`CalculationWorker` 패턴)
- 기본 `processMessages()` 사용 (**runBlocking 없음**, 기본 `process()` 위임)
- 429: `pgmqClient.setVisibilityTimeout()` + 30% jitter
- `readCount >= 5`: `pgmqClient.delete()` (DLQ)
- 성공: `pgmqClient.archive()`

### Step 7: PGMQ Queue 생성

**File: `module-app/.../db/migration/V{next}__create_nexon_fanout_queue.sql`** (신규)
```sql
SELECT pgmq.create('nexon_fanout_queue');
```

### Step 8: V5 Controller 통합

**File: `module-web/.../controller/v5/GameCharacterControllerV5.kt`** (수정)
- `fanout.enabled=true` 시: 캐시 미스 -> `NexonEquipmentMicroBatchAdapter.getByKey(ocid)`
- `fanout.enabled=false` 시: 기존 `CalculationQueuePort.offerHighPriority()`

### Step 9: V4 Controller 통합 (Optional)

**File: `module-web/.../controller/v4/GameCharacterControllerV4.kt`** (수정)
- `fanout.enabled=true` 시: 캐시 미스 -> adapter.getByKey() + 300ms polling
- `fanout.enabled=false` 시: 기존 `AdmissionPort.submitOrWait()` 경로 유지

### Step 10: Configuration

**File: `application.yml`** (수정)
```yaml
fanout:
  enabled: false
  semaphore-permits: 30
  batch-size: 50
  max-retry-count: 5
  retry-delay-base-ms: 1000
  jitter-factor: 0.3
  polling-interval-ms: 50
  queue-name: nexon_fanout_queue

pgmq:
  worker:
    nexon-fanout:
      enabled: false
      batch-size: 50
      max-retries: 5
```

---

## Files to Create

| File | Module | Description |
|------|--------|-------------|
| `core/port/out/FanOutQueuePort.kt` | module-core | Port 인터페이스 (DIP) |
| `infra/queue/pgmq/FanOutQueueProducer.kt` | module-infra | PGMQ 발행 (@Transactional) |
| `infra/fanout/NexonFanOutBatchLoader.kt` | module-infra | Batch Lane 병렬 실행 |
| `infra/batch/NexonEquipmentMicroBatchAdapter.kt` | module-infra | AdaptiveMicroBatch 래퍼 |
| `infra/worker/NexonFanOutWorker.kt` | module-infra | 429 전용 PGMQ Worker |
| `db/migration/V{next}__create_nexon_fanout_queue.sql` | module-app | Queue 생성 |

## Files to Modify

| File | Change |
|------|--------|
| `PgmqMessage.kt` | `FanOutRequest` data class 추가 |
| `PgmqWorkerConfig.kt` | `nexonFanout: WorkerSettings` 추가 |
| `application.yml` | fanout + pgmq.worker.nexon-fanout 설정 |
| `application-local.yml` | `fanout.enabled=true` 로컬 설정 |
| `GameCharacterControllerV5.kt` | fanout 경로 추가 (feature flag) |
| `GameCharacterControllerV4.kt` | fanout 경로 추가 (optional) |

## Existing Code to Reuse

| Component | File Path | Usage |
|-----------|-----------|-------|
| **AdaptiveMicroBatchUserService** | `infra/batch/AdaptiveMicroBatchUserService.kt` | **핵심 엔진**: coalescing + dedup + batch + cache |
| **GameCharacterMicroBatchAdapter** | `infra/batch/GameCharacterMicroBatchAdapter.kt` | **패턴 참고**: 래핑 방식 |
| `AdaptiveMicroBatchProperties` | `infra/config/AdaptiveMicroBatchProperties.kt` | 설정 재사용 |
| `EquipmentFetchProvider` | `infra/provider/EquipmentFetchProvider.kt` | `fetchWithCache()` -> @Cacheable |
| `ResilientNexonApiClient` | `infra/external/impl/ResilientNexonApiClient.kt` | CB + Bulkhead(50) + Retry(3) |
| `PgmqClient` | `infra/pgmq/PgmqClient.kt` | send, read, archive, delete, setVisibilityTimeout |
| `PgmqWorker` | `infra/pgmq/PgmqWorker.kt` | Base class |
| `PgmqWorkerConfig` | `infra/pgmq/PgmqWorkerConfig.kt` | Worker 설정 |
| `CalculationWorker` | `infra/worker/CalculationWorker.kt` | Worker 패턴 참고 |
| `CalculationQueueProducer` | `infra/queue/pgmq/CalculationQueueProducer.kt` | Producer 패턴 참고 |
| `LogicExecutor` | `infra/executor/LogicExecutor.kt` | Zero try-catch |
| `BulkLoaderService` | `infra/bulk/BulkLoaderService.kt` | Semaphore + ThreadPool 패턴 |

### What We're NOT Building (기존 인프라로 대체)

| Original Plan | Reused Component |
|---------------|------------------|
| New FanOutExecutor | `AdaptiveMicroBatchUserService` |
| New FanOutProperties | `AdaptiveMicroBatchProperties` |
| New CachePollingHelper | `AdaptiveMicroBatchUserService.getByKey()` |
| New CoroutineScope | `AdaptiveMicroBatchUserService`가 이미 사용 |
| New RateLimiter(500/sec) | Bulkhead(50) + PGMQ 429 처리 |
| `runBlocking()` | 없음 (기본 `processMessages()` 사용) |
| `Thread.sleep()` | 없음 (`delay()` 또는 `CompletableFuture.get(timeout)`) |

---

## Consensus Review 반영

### P0 해결

| P0 Issue | Resolution |
|----------|------------|
| `runBlocking` + Virtual Thread | `AdaptiveMicroBatchUserService`가 이미 `CoroutineScope` 사용. Worker는 기본 `processMessages()` -> `runBlocking` 불필요 |
| `Thread.sleep()` 금지 | `AdaptiveMicroBatchUserService`가 이미 `delay()` 사용. CachePollingHelper 제거 |
| `PgmqClient.send()` @Transactional | `FanOutQueueProducer.enqueue()`에 `@Transactional` 추가 |

### P1 해결

| P1 Issue | Resolution |
|----------|------------|
| Semaphore(30) + Bulkhead(50) 이중 제한 | 의도적: 실제 처리량 = 30. 초과분은 Batch Lane -> PGMQ 분산 |
| Cache 충돌 | `singleLoader`는 `@Cacheable` 경로, `batchLoader`는 `cache.put()` 직접. 동일 Caffeine cache에 무해 중복 쓰기 |
| DIP 위반 | `FanOutQueuePort` 인터페이스를 module-core에 정의 |
| Feature flag if/else | Phase 1에서는 단순 if 분기. 추후 Strategy 패턴으로 리팩토링 |

---

## Verification

1. `fanout.enabled=false` -> 기존 V4/V5 동작 완전 동일 (no-op)
2. `fanout.enabled=true` + 캐시 미스 -> `NexonEquipmentMicroBatchAdapter.getByKey()` 호출
3. 동시 100 요청 -> coalescing + dedup으로 API 호출 수 감소
4. 429 -> PGMQ에 적재, worker가 1~1.3초 후 재시도
5. max 5회 초과 -> delete (DLQ)
6. 컴파일: `./gradlew compileKotlin compileJava --continue`
7. 테스트: `./gradlew test`

---

## Execution Order

1. Step 1: `FanOutRequest` (PgmqMessage)
2. Step 2: `FanOutQueuePort` (module-core)
3. Step 3: `FanOutQueueProducer` (module-infra, @Transactional)
4. Step 4: `NexonFanOutBatchLoader` (module-infra)
5. Step 5: `NexonEquipmentMicroBatchAdapter` (핵심)
6. Step 6: `NexonFanOutWorker` (PgmqWorker 상속)
7. Step 7: Migration SQL
8. Step 8-9: V4/V5 Controller 통합
9. Step 10: Configuration
10. Verification: 컴파일 + 테스트
