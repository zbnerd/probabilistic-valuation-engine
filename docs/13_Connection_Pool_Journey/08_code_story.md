# 8장: 코드로 보는 여정 — 스케줄러 통합 과정

> "코드가 말하게 하라. Git log가 증언하게 하라."

## Before: 세 개의 개별 Outbox 스케줄러

마이그레이션 전, 3개의 Outbox 스케줄러가 각각 독립적으로 커넥션을 소비하고 있었다.

### EventOutboxScheduler

```kotlin
// 삭제됨 — Phase 1에서 제거
@Component
class EventOutboxScheduler(
    private val processor: EventOutboxProcessor,
) {
    @Scheduled(fixedRate = 10_000)  // 10초마다 폴링
    fun poll() {
        processor.processPending()
    }
}

// EventOutboxProcessor도 삭제됨
class EventOutboxProcessor(
    private val fetchFacade: EventOutboxFetchFacade,
    private val publisher: PgmqStreamPublisher,  // ← 결국 PGMQ에 발행
    private val metrics: EventOutboxMetrics,
) {
    @Transactional("transactionManager")
    fun processPending() {
        val events = fetchFacade.fetchPending()  // SELECT ... SKIP LOCKED
        events.forEach { event ->
            publisher.publish(event.toMessage())  // PGMQ에 발행
            event.markCompleted()
        }
    }
}
```

커넥션 사용: `SELECT SKIP LOCKED` (1) + `pgmq.send` (1, same TX) + `UPDATE status` (1, same TX) = **트랜잭션당 1 connection**, 10초마다 점유.

### DonationOutboxScheduler

```kotlin
// 삭제됨 — Phase 2에서 제거
@Component
class OutboxScheduler(
    private val processor: OutboxProcessor,
) {
    @Scheduled(fixedRate = 15_000)  // 15초마다 폴링
    fun poll() {
        processor.processPending()
    }
}

class OutboxProcessor(
    private val fetchFacade: OutboxFetchFacade,
    private val notificationService: NotificationService,
    private val dlqHandler: DlqHandler,
    private val metrics: OutboxMetrics,
) {
    @Transactional("transactionManager")
    fun processPending() {
        val donations = fetchFacade.fetchPending()
        donations.forEach { donation ->
            try {
                notificationService.sendInfo(...)
                donation.markCompleted()
            } catch (e: Exception) {
                donation.markFailed()
                if (donation.shouldMoveToDlq()) {
                    dlqHandler.moveToDlq(donation)
                }
            }
        }
    }
}
```

### NexonApiOutboxScheduler

```kotlin
// 삭제됨 — Phase 3에서 제거
@Component
class NexonApiOutboxScheduler(
    private val processor: NexonApiOutboxProcessor,
) {
    @Scheduled(fixedRate = 10_000)  // 10초마다 폴링
    fun poll() {
        processor.pollAndProcess()
    }
}
```

### 커넥션 소비 패턴 (Before)

```
Timeline (60초):
00s ─ EventOutboxScheduler: SELECT (conn 1) → process → UPDATE → release
10s ─ EventOutboxScheduler: SELECT (conn 1) → empty → release
10s ─ NexonApiOutboxScheduler: SELECT (conn 2) → process → retry → release
15s ─ DonationOutboxScheduler: SELECT (conn 3) → process → notify → release
20s ─ EventOutboxScheduler: SELECT (conn 1) → empty → release
20s ─ NexonApiOutboxScheduler: SELECT (conn 2) → empty → release
30s ─ EventOutboxScheduler: SELECT (conn 1) → process → release
30s ─ NexonApiOutboxScheduler: SELECT (conn 2) → empty → release
30s ─ DonationOutboxScheduler: SELECT (conn 3) → empty → release
...

60초 동안:
  EventOutbox: 6회 폴링 × 1 connection = 6 connection-seconds
  NexonApi:    6회 폴링 × 1 connection = 6 connection-seconds
  Donation:    4회 폴링 × 1 connection = 4 connection-seconds
  ────────────────────────────────────────────────────────────
  총 16 connection-seconds / 60s = 평균 0.27 connections 상시 점유
  피크 시 (동시 폴링): 최대 3 connections
```

## After: PGMQ 통합 Worker

### PgmqWorker 추상 클래스

모든 Worker가 상속하는 공통 기반 클래스:

```kotlin
// module-infra/.../pgmq/PgmqWorker.kt — 현재 코드
abstract class PgmqWorker<T : Any>(
    private val pgmqClient: PgmqClient,
    protected val executor: LogicExecutor,
    private val config: PgmqWorkerConfig,
) {
    abstract val queueName: String
    abstract val payloadClass: Class<T>
    abstract val workerSettings: PgmqWorkerConfig.WorkerSettings

    protected abstract fun process(message: PgmqMessage<T>): Boolean

    @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:1000}")
    fun processMessages() {
        if (!workerSettings.enabled) return

        executor.executeVoid({
            val batchSize = workerSettings.batchSize ?: config.common.batchSize
            val messages = pgmqClient.read(queueName, payloadClass, batchSize, config.common.visibilityTimeoutSec)
            if (messages.isEmpty()) return@executeVoid

            messages.forEach { message -> processSingleMessage(message) }
        }, context)
    }

    private fun processSingleMessage(message: PgmqMessage<T>) {
        val success = executor.executeOrDefault({ process(message) }, false, context)
        when {
            success -> pgmqClient.archive(queueName, message.messageId)
            message.isRetryable(maxRetries) -> onProcessingFailed(message)
            else -> pgmqClient.delete(queueName, message.messageId)
        }
    }
}
```

핵심: `fixedDelay` (이전 처리 완료 후 다음 폴링). `fixedRate`가 아니므로 이전 처리가 길어지면 폴링 간격이 자동으로 늘어난다.

### CalculationWorker

```kotlin
// module-infra/.../worker/CalculationWorker.kt — 현재 코드
@Component
@Profile("!test")
class CalculationWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    private val expectationPort: ExpectationV4Port,
) : PgmqWorker<CalculationRequest>(pgmqClient, executor, config) {

    override val queueName = CalculationQueueProducer.QUEUE_NAME
    override val payloadClass = CalculationRequest::class.java
    override val workerSettings = config.calculation

    override fun process(message: PgmqMessage<CalculationRequest>): Boolean {
        return executor.executeOrDefault({
            val future = expectationPort.calculateExpectationAsync(
                request.userIgn, request.forceRecalculation
            )
            future.join()
            true
        }, false, context)
    }
}
```

### DonationWorker

```kotlin
// module-infra/.../worker/DonationWorker.kt — 현재 코드
@Component
@Profile("!test")
class DonationWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    private val alertPublisher: AlertPublisher,
) : PgmqWorker<DonationRequest>(pgmqClient, executor, config) {

    override val queueName = DonationQueueProducer.QUEUE_NAME
    override val payloadClass = DonationRequest::class.java
    override val workerSettings = config.donation

    override fun process(message: PgmqMessage<DonationRequest>): Boolean {
        return executor.executeOrDefault({
            val messageText = buildDonationMessage(request)
            alertPublisher.sendInfo("☕ 새로운 후원", messageText)
            true
        }, false, context)
    }
}
```

### NexonApiPgmqProcessor (독립 컴포넌트)

`PgmqWorker`를 상속하지 않는 유일한 예외. `NexonApiOutboxProcessorPort`를 구현해야 하므로:

```kotlin
// module-infra/.../nexon/pgmq/NexonApiPgmqProcessor.kt — 현재 코드
@Component
@ConditionalOnProperty(name = ["nexon.retry.backend"], havingValue = "pgmq", matchIfMissing = true)
class NexonApiPgmqProcessor(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
    private val nexonApiClient: NexonApiClient,
    private val metrics: NexonApiPgmqMetrics,
) : NexonApiOutboxProcessorPort {

    @Scheduled(fixedDelayString = "\${nexon.retry.polling-interval-ms:5000}")
    override fun pollAndProcess() {
        val context = TaskContext.of("NexonApiPgmqProcessor", "PollAndProcess", queueName)
        executor.executeVoid({ performPollAndProcess() }, context)
    }

    // Exponential Backoff: visibility timeout으로 재시도 지연
    fun handleRetry(message: PgmqMessage<NexonRetryMessage>, error: String) {
        val retryCount = message.payload.retryCount + 1
        if (retryCount >= MAX_RETRIES) { moveToDlq(message, ...); return }
        val backoffSeconds = min(2.0.pow(retryCount.toDouble()).toLong() * 30, 3600)
        pgmqClient.setVisibilityTimeout(queueName, message.messageId, backoffSeconds)
    }
}
```

### PgmqWorkerConfig

모든 Worker의 설정을 중앙 집중 관리:

```kotlin
// module-infra/.../pgmq/PgmqWorkerConfig.kt — 현재 코드
@Configuration
@ConfigurationProperties(prefix = "pgmq.worker")
class PgmqWorkerConfig {
    var common: CommonSettings = CommonSettings()
    var calculation: WorkerSettings = WorkerSettings()
    var donation: WorkerSettings = WorkerSettings()
    var nexonCollector: WorkerSettings = WorkerSettings(enabled = true)
    var expectationCalcHigh: WorkerSettings = WorkerSettings()
    var expectationCalcLow: WorkerSettings = WorkerSettings()
    var nexonFanout: WorkerSettings = WorkerSettings()
    // ... 배치사이즈, 재시도, visibility timeout 등
}
```

### NexonApiCollectorScheduler (여전히 존재)

데이터 수집 스케줄러는 PGMQ에 발행하는 역할만 한다:

```kotlin
// module-infra/.../scheduler/NexonApiCollectorScheduler.kt — 현재 코드
@Component
class NexonApiCollectorScheduler(
    private val queueProducer: NexonDataQueueProducer,  // ← PGMQ에 발행
) {
    @Scheduled(fixedRate = 300000)  // 5분마다
    fun collectNexonData() {
        // 1. 활성 캐릭터 목록 조회
        // 2. Nexon API 호출
        // 3. Raw 데이터 저장
        // 4. CalculationQueue에 메시지 발행 ← PGMQ send
        queueProducer.publish(ocid, userIgn)
    }
}
```

이 스케줄러는 Outbox를 거치지 않고 **직접 PGMQ에 발행**한다. `queueProducer.publish()` 내부에서 `pgmqClient.send()`를 호출하며, 이는 호출자의 `@Transactional` 내에서 실행된다.

### BatchWriter (메시지 큐 소비)

```kotlin
// module-infra/.../scheduler/BatchWriter.kt — 현재 코드
@Component
class BatchWriter(
    @Qualifier("nexonDataQueue") private val messageQueue: MessageQueue<String>,
) {
    @Scheduled(fixedRate = 5000)
    @Transactional("transactionManager")
    fun processBatch() {
        val batch = ArrayList<IntegrationEvent<NexonApiCharacterData>>()
        for (i in 0 until aclWriterSize) {
            val jsonPayload = messageQueue.poll() ?: break
            batch.add(deserializeEvent(jsonPayload))
        }
        if (batch.isEmpty()) return
        batchWrite(batch)  // JDBC batch update
    }
}
```

## 커넥션 소비 패턴 (After)

```
Timeline (60초):
PgmqWorker.processMessages() — fixedDelay 1000ms:
  00s ─ read (conn 1) → process → archive → release
  01s ─ read (conn 1) → empty → release
  02s ─ read (conn 1) → process → archive → release
  ... (1초 간격으로 폴링, but fixedDelay이므로 처리 시간만큼 간격 증가)

NexonApiPgmqProcessor — fixedDelay 5000ms:
  00s ─ read (conn 1) → process → archive → release
  05s ─ read (conn 1) → empty → release
  ...

60초 동안:
  PgmqWorkers (4개): 평균 0.1 connections/worker × 4 = 0.4 connections
  NexonApiProcessor: 평균 0.05 connections
  BatchWriter: 12회 폴링 × ~0.1 = 0.2 connections
  ──────────────────────────────────────────────────
  총 평균: ~0.65 connections 상시 점유 (Before: ~0.27 × 3개 스케줄러 = ~2.5)
  피크 시: 최대 2 connections (Before: 3)

더 중요한 점:
  모든 Worker가 동일 HikariCP 풀 사용
  → 유휴 커넥션을 비즈니스 요청이 즉시 재사용
  → Outbox INSERT/UPDATE 미발생으로 DB 부하 감소
```

## 파일 삭제 → 생성 요약

```
삭제된 파일 (~42개):
├── module-infra/.../domain/v2/
│   ├── EventOutbox.kt
│   ├── DonationOutbox.kt
│   └── NexonApiOutbox.kt
├── module-infra/.../persistence/repository/
│   ├── EventOutboxRepository.kt
│   ├── DonationOutboxRepository.kt
│   └── NexonApiOutboxRepository.kt
├── module-infra/.../event/outbox/  (전체 디렉토리)
├── module-infra/.../donation/outbox/ (전체 디렉토리)
├── module-infra/.../nexon/outbox/  (전체 디렉토리)
├── module-infra/.../messaging/PgmqStreamPublisher.kt
├── module-infra/.../worker/LikeSyncWorker.kt
└── module-infra/.../queue/pgmq/LikeSyncQueueProducer.kt

생성/수정된 파일 (~15개):
├── module-infra/.../pgmq/PgmqWorker.kt (공통 추상 클래스)
├── module-infra/.../pgmq/PgmqWorkerConfig.kt (Worker 설정)
├── module-infra/.../pgmq/PgmqWorkerMetrics.kt
├── module-infra/.../pgmq/PgmqArchiveCleanupScheduler.kt
├── module-infra/.../nexon/pgmq/NexonApiPgmqProcessor.kt
├── module-infra/.../nexon/pgmq/NexonApiPgmqMetrics.kt
└── module-app/.../pgmq/PgmqTransactionAtomicityTest.kt
```

## 배운 점

> **"개별 스케줄러가 각각 커넥션을 물고 있으면, 그것은 공유 자원의 파편화다. PGMQ 하나로 통합하면, 커넥션은 유휴 시 다른 요청에 재사용된다."**

- `fixedDelay`는 `fixedRate`보다 커넥션 친화적 (이전 처리 완료 후 다음 폴링)
- `PgmqWorker` 추상 클래스로 공통 패턴(읽기/처리/아카이브/재시도/DLQ)을 재사용
- Worker 설정 중앙화(`PgmqWorkerConfig`)로 프로필별 조정 가능
- `@Transactional` 내에서 `pgmqClient.send()` = 추가 커넥션 없이 원자적 발행

---

**다음 장**: [에필로그 — 하나의 풀, 하나의 데이터베이스](./09_epilogue.md)
