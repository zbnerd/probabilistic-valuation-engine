# Fan-Out 안정화: Queue-Driven Pipeline 전환

> **Date**: 2026-04-03 (Consensus Review v2 — P0 5건 반영)
> **Branch**: `feature/queue-driven-pipeline`
> **Base**: `develop`
> **ADR**: `docs/01_ADR/` 에 별도 작성

---

## Consensus Review 반영 (v2)

### P0 수정 내역

| P0 | 이슈 | 원안 | 수정 |
|----|------|------|------|
| 1 | V4 API Breaking Change | V4를 202 Accepted로 전환 | **V4는 legacy 동기 유지**. Phase 1은 V5 Task API + PGMQ 성능에 집중 |
| 2 | PgmqClient.send() @Transactional 위반 | 명시 없음 | `@Transactional(REQUIRES_NEW)` 명시 추가 |
| 3 | Task 상태 조회: Archive 누락 | PGMQ archive 우선 조회 | **PostgreSQL을 source of truth**로 변경 |
| 4 | NexonRateLimiter Scale-out 위험 | 단일 Semaphore 제안 | JVM Semaphore 유지 + **Scale-out은 별도 작업으로 분리** |
| 5 | TaskStatus API Authorization Bypass | `GET /tasks/{taskId}` | **userIgn 바인딩** + `@PreAuthorize` 추가 |

### P1 수정 내역

| P1 | 이슈 | 수정 |
|----|------|------|
| 6 | Virtual Thread Carrier Pinning | NexonRateLimiter에 `ReentrantLock` 사용 |
| 7 | Batch Size ↑ without VT Adjustment | VT를 batch 크기에 비례하여 조정 (300s) |
| 9 | TaskReceipt DIP 위반 | `module-app`으로 이동 (core에 queue detail 배제) |
| 10 | Archive Cleanup(30일) 후 NOT_FOUND | PostgreSQL 조회로 대체 (archive 의존 제거) |

---

## 1. Current State 분석

### 1.1 V4 흐름 (문제)

```
V4 Controller
  → AdmissionPort.submitOrWait(key) { expectationPort.getGzipExpectation(ign, force) }
  → GlobalAdmissionControl: ArrayBlockingQueue(1000) → Worker(16) → Semaphore(100)
  → Worker 내에서 동기 처리 (JDBC + Nexon API call + 계산)
  → CompletableFuture 반환 → HTTP 응답
```

- **문제**: V4는 AdmissionControl 내부 worker가 **직접** heavy work 수행
- HTTP thread는 future 반환 후 대기 (semi-sync)
- Worker pool이 Nexon API latency에 종속 → thread pool 고갈 위험

### 1.2 V5 흐름 (부분 해결)

```
V5 Controller
  → PostgreSQL 조회 (Query Side)
  → HIT → 즉시 반환 (1-10ms)
  → MISS → CalculationQueuePort.offerHighPriority() → PGMQ enqueue → 202 Accepted
  → PGMQ Worker가 비동기 처리
```

- **V5는 이미 Queue-Driven**: PGMQ enqueue → 202 반환
- **V4는 여전히 Request-Driven**: AdmissionControl worker가 직접 처리

### 1.3 Rate Limiter 분산 현황

| 위치 | 타입 | 제한 | 문제 |
|------|------|------|------|
| `MetricsNexonApiClientWrapper` | `Semaphore(50)` | 전체 Nexon API 동시 호출 | hard-coded |
| `Resilience4j Bulkhead` | `maxConcurrentCalls=50` | 동일 제한 중복 | 설정 분산 |
| `NexonFanOutBatchLoader` | `Semaphore(30)` | Batch Lane 제한 | 3중 중첩 |
| `GlobalAdmissionControl` | `Semaphore(100)` | cold-path 동시 실행 | 사실상 의미 없음 |

### 1.4 핵심 진단

| 진단 | 상태 |
|------|------|
| V5 cold-path | ✅ 이미 PGMQ 기반 |
| V4 cold-path | ❌ AdmissionControl worker가 직접 처리 |
| HTTP-Worker 분리 | ❌ V4는 semi-sync (future 대기) |
| Rate Limiter 중앙화 | ❌ 4곳에 분산 |
| Task 상태 조회 | ❌ 없음 |
| PGMQ polling 성능 | ⚠️ 1초 batch=10 (개선 여지) |

---

## 2. Target Architecture

```
    V4 Controller (Legacy — 변경 없음)
    ┌──────────────────────────────────────┐
    │ AdmissionControl.submitOrWait()      │
    │ → Worker(16) → Semaphore(100)       │
    │ → 동기 처리 + 200 OK 반환           │
    │ ※ 향후 V6 API로 migration 시에만     │
    │    Queue-Driven 전환 고려            │
    └──────────────────────────────────────┘

    V5 Controller (Queue-Driven — 개선 대상)
    ┌──────────────────────────────────────┐
    │ PostgreSQL Query Side (Read)         │
    │ → HIT → 즉시 반환 (1-10ms)          │
    │ → MISS → PGMQ enqueue → 202 + taskId│
    └──────────────┬───────────────────────┘
                   │
    ┌──────────────▼───────────────────────┐
    │  PGMQ Workers (소비자)               │
    │  - ExpectationCalcWorker (HIGH)      │
    │  - ExpectationCalcLowWorker          │
    │  - NexonFanOutWorker (429)           │
    │                                      │
    │  NexonRateLimiter (중앙 집중)        │
    │  - ReentrantLock 기반 (VT 안전)      │
    │  - target RPS: 100~150              │
    └──────────────┬───────────────────────┘
                   │
    ┌──────────────▼───────────────────────┐
    │  결과 조회 API (Authorization 포함)  │
    │  GET /api/v5/characters/{ign}/tasks  │
    │  ※ PostgreSQL를 source of truth      │
    └──────────────────────────────────────┘
```

---

## 3. Implementation Steps

### Phase 1: V5 Task Receipt + PGMQ 성능 (Core Change)

**목표**: V5에 taskId 반환 체계를 확립하고, PGMQ 성능을 개선

> **P0-1 반영**: V4 Controller는 **변경하지 않음** (Legacy 동기 유지).
> V4의 `AdmissionPort.submitOrWait()` 경로는 그대로 유지.
> Queue-Driven 전환은 **V5 API에만** 적용.

#### Step 1-1: TaskReceipt (module-app에 정의 — P0-5/DIP 반영)

**File**: `module-app/src/main/java/maple/expectation/application/service/expectation/queue/TaskReceipt.java` (신규)

```java
public record TaskReceipt(
    String taskId,       // PGMQ message ID
    String userIgn,
    boolean queued
) {}
```

- `module-core`가 아닌 **module-app**에 배치 (DIP 준수 — core는 queue detail을 모름)
- Java record로 Java-Kotlin interop 용이

#### Step 1-2: CalculationQueuePort 확장

**File**: `module-core/src/main/kotlin/maple/expectation/core/port/inbound/CalculationQueuePort.kt` (수정)

```kotlin
interface CalculationQueuePort {
    fun offerHighPriority(userIgn: String, forceRecalculation: Boolean): Boolean
    // 기존 유지 — V5는 offerHighPriority()만 사용
}
```

- Port 인터페이스는 **변경 없음** (기존 Boolean 반환 유지)
- TaskReceipt는 module-app 내부에서만 사용

#### Step 1-3: ExpectationCalculationQueue에 taskId 반환 지원

**File**: `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java` (수정)

- `offerWithReceipt()` 메서드 추가
- **`@Transactional(propagation = REQUIRES_NEW)`** 필수 (P0-2 반영)
  - HTTP thread(비트랜잭션 컨텍스트)에서 호출 시 `PgmqClient.send()`의 트랜잭션 체크 통과
- PGMQ `send()` 반환값(messageId)을 taskId로 활용

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public TaskReceipt offerWithReceipt(ExpectationCalculationTask task) {
    // PGMQ queueLength 체크 → send → TaskReceipt(messageId, userIgn, true)
}
```

#### Step 1-4: CalculationQueuePortAdapter 업데이트

**File**: `module-app/src/main/java/maple/expectation/application/usecase/CalculationQueuePortAdapter.java` (수정)

- `offerHighPriority()`는 기존 Boolean 반환 유지 (호환성)
- `offerHighPriorityWithReceipt()` 추가 (TaskReceipt 반환)

#### Step 1-5: V5 Controller에 X-Task-Id 헤더 추가

**File**: `module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt` (수정)

**Before**:
```kotlin
val queued = executorPort.executeOrDefault(
    { queuePort.offerHighPriority(userIgn, forceRecalculation) }, false, context)
return if (queued) {
    ResponseEntity.accepted().build<Unit>()
} else { ... }
```

**After**:
```kotlin
val receipt = queuePortAdapter.offerHighPriorityWithReceipt(userIgn, forceRecalculation)
return if (receipt.queued()) {
    ResponseEntity.accepted()
        .header("X-Task-Id", receipt.taskId())
        .build<Unit>()
} else { ... }
```

- V5 응답에 `X-Task-Id` 헤더 추가 (기존 202 Accepted는 유지)
- V4 Controller는 **건드리지 않음** (P0-1)

---

### Phase 2: GlobalAdmissionControl 정리 (V4 전용으로 명확화)

**목표**: GlobalAdmissionControl은 V4 전용으로 명확히 하고, V5 경로에서는 완전 분리

> V4를 legacy로 유지하므로, GlobalAdmissionControl의 worker pool은 **제거하지 않음**.
> V4가 계속 사용. 대신 V5 경로에서 참조하지 않음을 명확히 하는 작업만 수행.

#### Step 2-1: V5에서 AdmissionPort 참조 제거 확인

**File**: `module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt` (검증)

- V5가 `AdmissionPort`를 참조하지 않는지 확인 (이미 `CalculationQueuePort` 사용 중)
- 참조가 있다면 제거

#### Step 2-2: GlobalAdmissionControl에 V4-Only 주석 추가

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/GlobalAdmissionControl.kt` (수정)

```kotlin
/**
 * V4 Legacy Admission Control
 *
 * NOTE: This component is used ONLY by V4 controllers.
 * V5 uses PGMQ-based Queue-Driven Pipeline instead.
 * Do NOT add new consumers. New features should use PGMQ.
 */
@Component
class GlobalAdmissionControl(...) { ... }
```

- 코드 변경 없음, Javadoc만 업데이트
- 향후 V6 API 도입 시에만 제거 고려

---

### Phase 3: Rate Limiter 중앙 집중화

**목표**: 분산된 Semaphore를 단일 NexonRateLimiter로 통합

> **P1-6 반영**: `Semaphore.acquire()`는 Virtual Thread에서 carrier thread pinning 유발.
> `ReentrantLock` + `Condition` 사용.
>
> **P0-4 반영**: 현재 1-2 인스턴스 운영이므로 JVM-local로 충분.
> Scale-out 시 PostgreSQL Advisory Lock으로 전환 (별도 Issue).

#### Step 3-1: NexonRateLimiter (신규 — ReentrantLock 기반)

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/NexonRateLimiter.kt` (신규)

```kotlin
@Component
class NexonRateLimiter(
    @Value("\${nexon.rate-limit.max-concurrent:50}") maxConcurrent: Int,
    private val meterRegistry: MeterRegistry,
) {
    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    private var permits = maxConcurrent
    private val maxPermits = maxConcurrent

    fun <T> withLimit(task: () -> T): T {
        acquire()
        return try {
            task()
        } finally {
            release()
        }
    }

    private fun acquire() {
        lock.lock()
        try {
            while (permits <= 0) {
                notFull.await(100, TimeUnit.MILLISECONDS)
            }
            permits--
        } finally {
            lock.unlock()
        }
    }

    private fun release() {
        lock.lock()
        try {
            permits++
            notFull.signal()
        } finally {
            lock.unlock()
        }
    }

    fun availablePermits(): Int = permits
}
```

- **ReentrantLock** 기반으로 Virtual Thread에서 carrier pinning 방지
- 설정 기반 (`nexon.rate-limit.max-concurrent`)
- Metrics: `nexon.rate-limit.permits.available`, `nexon.rate-limit.wait-time`
- **Scale-out 제약**: JVM-local. 다중 인스턴스 시 50×N 허용. 별도 작업에서 분산 락 전환 필요.

#### Step 3-2: MetricsNexonApiClientWrapper 수정

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/MetricsNexonApiClientWrapper.kt` (수정)

- 내부 `nexonSemaphore` 제거
- `NexonRateLimiter` 주입받아 사용
- Resilience4j Bulkhead 설정값과 동기화

#### Step 3-3: NexonFanOutBatchLoader 수정

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/fanout/NexonFanOutBatchLoader.kt` (수정)

- 내부 `Semaphore(30)` 제거
- `NexonRateLimiter` 사용
- 실제 동시성 = `NexonRateLimiter` 값으로 단일 제어

#### Step 3-4: 설정 통합

**File**: `application.yml` (수정)

```yaml
nexon:
  rate-limit:
    max-concurrent: 50        # 기존 Semaphore(50) 통합
    target-rps: 120           # 모니터링 목표치

resilience4j:
  bulkhead:
    instances:
      nexonApi:
        maxConcurrentCalls: 50        # NexonRateLimiter와 동일값
        maxWaitDuration: 500ms
```

---

### Phase 4: PGMQ Polling 성능 개선

**목표**: throughput 향상 (batch size ↑, polling interval ↓)

> **P1-7 반영**: batch size 증가에 비례하여 Visibility Timeout도 조정.
> batch=50 × Nexon API 평균 지연(~6s) = 300s 필요. VT 미조정 시 duplicate processing 위험.

#### Step 4-1: Worker 설정 변경 (VT 동시 조정)

**File**: `module-infra/src/main/resources/maple-infra-defaults.properties` (수정)

```properties
# Before
pgmq.worker.common.polling-interval-ms=1000
pgmq.worker.common.batch-size=10
pgmq.worker.common.visibility-timeout-sec=30

# After
pgmq.worker.common.polling-interval-ms=300
pgmq.worker.common.batch-size=50
pgmq.worker.common.visibility-timeout-sec=300   # batch=50 × ~6s/message
```

> **VT 산출 근거**: 50 msg × 6s(Nexon API avg) = 300s. 안전 마진 포함.
> 모니터링 후 실제 processing latency에 맞게 튜닝.

#### Step 4-2: Per-Worker 설정 분리

**File**: `application-prod.yml` (수정)

```yaml
pgmq:
  worker:
    common:
      polling-interval-ms: 300
      batch-size: 50
    expectation-calc-high:
      enabled: true
      batch-size: 50
      max-retries: 3
      polling-interval-ms: 200
    expectation-calc-low:
      enabled: true
      batch-size: 100
      max-retries: 3
      polling-interval-ms: 500
    nexon-fanout:
      enabled: true
      batch-size: 50
      max-retries: 5
      polling-interval-ms: 100
```

---

### Phase 5: Task 상태 조회 API

**목표**: V5 클라이언트가 계산 완료 여부를 polling

> **P0-3 반영**: PostgreSQL(CharacterView)을 **source of truth**로 사용.
> PGMQ archive는 보조만. Archive cleanup(30일) 후에도 PostgreSQL로 COMPLETED 판별 가능.
>
> **P0-5 반영**: taskId만으로 타 사용자 task 열람 방지.
> sequential messageId 대신 **userIgn 바인딩** + `@PreAuthorize` 적용.

#### Step 5-1: TaskStatusPort (Port 인터페이스)

**File**: `module-core/src/main/kotlin/maple/expectation/core/port/inbound/TaskStatusPort.kt` (신규)

```kotlin
interface TaskStatusPort {
    fun getStatus(userIgn: String, taskId: String): TaskStatus
}

enum class TaskStatus {
    PENDING,        // PGMQ에 대기 중
    PROCESSING,     // Worker가 처리 중
    COMPLETED,      // 완료 (PostgreSQL에 결과 존재)
    FAILED,         // 실패 (DLQ 또는 max retry 초과)
    NOT_FOUND,      // 알 수 없는 taskId
}
```

#### Step 5-2: PgmqClient에 Archive 조회 메서드 추가

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqClient.kt` (수정)

```kotlin
/**
 * 메시지가 archive 테이블에 존재하는지 확인
 * TaskStatusService에서 COMPLETED 판별에 사용
 */
fun isArchived(queueName: String, messageId: Long): Boolean {
    val context = TaskContext.of("PgmqClient", "IsArchived", "$queueName:$messageId")
    return executor.executeOrDefault({
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pgmq.a_\$queueName WHERE msg_id = ?",
            Long::class.java,
            messageId
        ) ?: 0L > 0
    }, false, context)
}
```

#### Step 5-3: TaskStatusService (구현 — PostgreSQL 우선)

**File**: `module-app/src/main/java/maple/expectation/application/service/task/TaskStatusService.java` (신규)

조회 순서 (PostgreSQL 우선):
1. **PostgreSQL CharacterView 조회** → 존재 → `COMPLETED`
2. **PGMQ active queue** → messageId로 조회 → 존재 → `PENDING` 또는 `PROCESSING` (VT 만료 여부)
3. **PGMQ archive 테이블** → 존재 → `COMPLETED` (보조)
4. 어디에도 없음 → `NOT_FOUND`

```java
public TaskStatus getStatus(String userIgn, String taskId) {
    // 1. PostgreSQL (source of truth)
    var cached = queryPort.findByUserIgn(userIgn);
    if (cached.isPresent()) return TaskStatus.COMPLETED;

    // 2. PGMQ active queue (messageId 파싱)
    long messageId = Long.parseLong(taskId);
    // pgmq.read()는 SKIP LOCKED이므로 직접 조회 불가 → queueLength로 간접 판단
    // 또는 visibility timeout 만료 여부로 PROCESSING 추론

    // 3. PGMQ archive
    if (pgmqClient.isArchived("expectation_calc_high", messageId)) {
        return TaskStatus.COMPLETED;
    }

    return TaskStatus.PENDING;
}
```

#### Step 5-4: TaskStatusController (REST — Authorization 포함)

**File**: `module-web/src/main/kotlin/maple/expectation/web/controller/v5/TaskStatusController.kt` (신규)

```kotlin
@RestController
@RequestMapping("/api/v5/characters")
class TaskStatusController(
    private val taskStatusPort: TaskStatusPort,
) {
    @GetMapping("/{userIgn}/task/{taskId}")
    @PreAuthorize("permitAll()")
    fun getTaskStatus(
        @PathVariable userIgn: String,
        @PathVariable taskId: String,
    ): ResponseEntity<TaskStatusResponse> {
        val status = taskStatusPort.getStatus(userIgn, taskId)
        return ResponseEntity.ok(TaskStatusResponse(taskId, status))
    }
}
```

- `userIgn`을 path에 포함 → taskId 추측 공격 방어
- 응답에 `Retry-After` 헤더 추가 (PENDING/PROCESSING 시 5초 권장)

---

### Phase 6: Observability

**목표**: Queue-Driven Pipeline 핵심 metric 확보

> **P2 반영**: batch=50 시 per-message metric → overhead.
> 배치 단위 aggregate metric으로 대체.

#### Step 6-1: Queue Metrics

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/QueueMetrics.kt` (신규)

```kotlin
@Component
class QueueMetrics(
    private val pgmqClient: PgmqClient,
    private val meterRegistry: MeterRegistry,
) {
    @Scheduled(fixedRate = 5000)
    fun recordQueueDepths() {
        recordGauge("pgmq.queue.depth.expectation_high",
            pgmqClient.queueLength("expectation_calc_high"))
        recordGauge("pgmq.queue.depth.expectation_low",
            pgmqClient.queueLength("expectation_calc_low"))
        recordGauge("pgmq.queue.depth.fanout_retry",
            pgmqClient.queueLength("nexon_fanout_queue"))
    }
}
```

#### Step 6-2: Worker Metrics (PgmqWorker 확장 — 배치 단위 집계)

**File**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` (수정)

- `processMessages()` 내에 **배치 단위** metric 추가 (per-message 아님):
  - `pgmq.worker.batch.size` — 처리된 메시지 수 (per batch)
  - `pgmq.worker.batch.latency` — 배치 전체 처리 시간
  - `pgmq.worker.lag` — 큐 적체 (queue length)

```kotlin
// aggregate pattern
val batchStart = System.nanoTime()
val successCount = messages.count { processSingleMessage(it) }
val batchDuration = System.nanoTime() - batchStart
meterRegistry.counter("pgmq.worker.processed", "queue", queueName, "status", "success")
    .increment(successCount.toDouble())
meterRegistry.timer("pgmq.worker.batch.latency", "queue", queueName)
    .record(batchDuration, TimeUnit.NANOSECONDS)
```

#### Step 6-3: NexonRateLimiter Metrics

- `nexon.rate-limit.permits.available` — 현재 가용 permit
- `nexon.rate-limit.wait.time` — 대기 시간 (ReentrantLock 경합)

---

## 4. Migration 전략

### Phase별 독립 배포

```
Phase 1  ──→  Phase 2  ──→  Phase 3  ──→  Phase 4~6
(V5 Task Receipt)  (V4 명확화)  (Rate Limiter)  (perf + API + metrics)

각 Phase는 독립 배포 가능.
V4 API는 변경하지 않으므로 Breaking Change 없음.
```

### Phase별 완료 기준

| Phase | 완료 기준 | Rollback |
|-------|-----------|----------|
| 1 | V5 cold-path → 202 + X-Task-Id 헤더 | TaskReceipt 반환 로직 제거 |
| 2 | GlobalAdmissionControl에 V4-Only 주석 | 주석 제거 |
| 3 | 단일 NexonRateLimiter로 통합 | 기존 Semaphore 복구 |
| 4 | PGMQ batch=50, VT=300s | properties 복구 |
| 5 | GET /api/v5/characters/{ign}/task/{taskId} 정상 동작 | API 비활성화 |
| 6 | Queue/Worker metric Grafana 표시 | metric 수집 중단 |

---

## 5. Files to Create

| # | File | Module | Phase | Description |
|---|------|--------|-------|-------------|
| 1 | `app/service/expectation/queue/TaskReceipt.java` | module-app | 1 | TaskReceipt record (DIP 준수) |
| 2 | `core/port/inbound/TaskStatusPort.kt` | module-core | 5 | Task 상태 조회 Port |
| 3 | `infra/ratelimit/NexonRateLimiter.kt` | module-infra | 3 | 중앙 집중 Rate Limiter (ReentrantLock) |
| 4 | `app/service/task/TaskStatusService.java` | module-app | 5 | Task 상태 조회 구현 (PostgreSQL 우선) |
| 5 | `web/controller/v5/TaskStatusController.kt` | module-web | 5 | Task 상태 REST API (userIgn 바인딩) |
| 6 | `infra/metrics/QueueMetrics.kt` | module-infra | 6 | Queue depth metrics |

## 6. Files to Modify

| # | File | Phase | Change |
|---|------|-------|--------|
| 1 | `app/service/expectation/queue/ExpectationCalculationQueue.java` | 1 | `offerWithReceipt()` + `@Transactional(REQUIRES_NEW)` |
| 2 | `app/usecase/CalculationQueuePortAdapter.java` | 1 | `offerHighPriorityWithReceipt()` 추가 |
| 3 | `web/controller/v5/GameCharacterControllerV5.kt` | 1 | X-Task-Id 헤더 반환 |
| 4 | `infra/admission/GlobalAdmissionControl.kt` | 2 | V4-Only Javadoc 추가 |
| 5 | `infra/external/impl/MetricsNexonApiClientWrapper.kt` | 3 | 내부 Semaphore → NexonRateLimiter |
| 6 | `infra/fanout/NexonFanOutBatchLoader.kt` | 3 | 내부 Semaphore → NexonRateLimiter |
| 7 | `infra/pgmq/PgmqClient.kt` | 5 | `isArchived()` 메서드 추가 |
| 8 | `infra/resources/maple-infra-defaults.properties` | 4 | batch, polling, VT 변경 |
| 9 | `infra/pgmq/PgmqWorker.kt` | 6 | 배치 단위 aggregate metrics |
| 10 | `application.yml` | 3,4 | rate-limit, PGMQ 설정 |
| 11 | `application-prod.yml` | 3,4 | 운영 설정 |

---

## 7. Verification

### 컴파일 + 테스트

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test
```

### 기능 검증

| # | 시나리오 | 기대 결과 |
|---|----------|-----------|
| 1 | V4 기존 요청 | **변경 없음** (200 OK + data) |
| 2 | V5 cold-path 요청 | 202 + `X-Task-Id` 헤더 |
| 3 | GET /api/v5/characters/{ign}/task/{taskId} | PENDING/COMPLETED (PostgreSQL 우선) |
| 4 | 타 사용자 taskId 조회 | userIgn 불일치 → NOT_FOUND |
| 5 | 1000 동시 unique key 요청 (V5) | queue 적재, 순차 처리 |
| 6 | NexonRateLimiter 동시성 | ReentrantLock 기반, VT pinning 없음 |
| 7 | PGMQ batch=50, VT=300s | 처리량 향상, duplicate 없음 |

### 성능 기준

| Metric | Before | Target |
|--------|--------|--------|
| V5 cold-path 응답 | ~50ms (202 반환, 기존과 동일) | ~50ms + taskId |
| PGMQ 처리량 | ~10 msg/sec | ~150 msg/sec |
| Nexon API RPS | ~118 | ~120 (안정선) |
| 1000 concurrent fan-out (V5) | queue 적재 후 순차 처리 | 동일 (개선은 PGMQ 성능) |

---

## 8. Risk & Mitigation

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| V4 API Breaking Change | **None** | — | **V4는 변경하지 않음** (P0-1) |
| PGMQ batch↑ 시 VT 부족 → duplicate | Medium | Medium | VT=300s로 조정 (P1-7). 모니터링 후 튜닝 |
| NexonRateLimiter JVM-local | Low | Medium | 현재 1-2 인스턴스. Scale-out 시 PG Advisory Lock (별도 작업) |
| TaskStatus API Authorization | None | — | userIgn 바인딩 + PreAuthorize (P0-5) |
| PgmqClient.send() @Transactional | None | — | `REQUIRES_NEW` 명시 (P0-2) |
| Archive cleanup 후 NOT_FOUND | None | — | PostgreSQL 우선 조회 (P0-3) |
| Virtual Thread Carrier Pinning | None | — | ReentrantLock 사용 (P1-6) |

---

## 9. Execution Order

```
Phase 1 (Task Receipt)  → Step 1-1 ~ 1-5   [1-2일]
Phase 2 (V4 명확화)     → Step 2-1 ~ 2-2   [0.5일]
Phase 3 (Rate Limiter)  → Step 3-1 ~ 3-4   [1일]
Phase 4 (PGMQ Perf)     → Step 4-1 ~ 4-2   [0.5일]
Phase 5 (Task API)      → Step 5-1 ~ 5-4   [1일]
Phase 6 (Metrics)       → Step 6-1 ~ 6-3   [0.5일]
```

**총 예상**: 4-6일 (Phase별 독립 배포 가능)

**권장 순서**: Phase 4 → Phase 1 → Phase 5 → Phase 3 → Phase 6 → Phase 2
- Phase 4 (PGMQ 성능) — 가장 낮은 리스크, 즉시 효과
- Phase 1 (Task Receipt) — V5 API 개선, V4 영향 없음
- Phase 5 (Task API) — 클라이언트 polling 지원
- Phase 3 (Rate Limiter) — 중앙 집중화, ReentrantLock 전환
- Phase 6 (Metrics) — 운영 가시성
- Phase 2 (V4 명확화) — Javadoc만, 가장 낮은 우선순위
