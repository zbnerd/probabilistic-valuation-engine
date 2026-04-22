package maple.expectation.infrastructure.pgmq

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import jakarta.annotation.PreDestroy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

private typealias PhaseOneResult<T> = Pair<Any?, PgmqMessage<T>>

/**
 * PGMQ Worker 추상 클래스 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>PGMQ 큐에서 메시지를 소비하고 처리하는 Worker의 기본 구현
 *
 * <h3>처리 패턴</h3>
 * <ul>
 *   <li>성공: archive() 호출로 메시지 보관
 *   <li>재시도 가능 실패: 자동 재시도 (readCount < maxRetries)
 *   <li>최종 실패: delete() 호출로 DLQ 이동
 * </ul>
 *
 * <h3>Zero Try-Catch</h3>
 * <p>모든 예외 처리는 LogicExecutor에 위임 (Section 12 준수)
 *
 * @param T 메시지 페이로드 타입
 */
abstract class PgmqWorker<T : Any>(
    protected val pgmqClient: PgmqClient,
    protected val executor: LogicExecutor,
    private val config: PgmqWorkerConfig,
    private val meterRegistry: MeterRegistry,
    private val queueMetrics: WorkerQueueMetrics,
    private val lifecycleWrapper: ScheduledTaskLifecycleWrapper,
) {

    /** 메시지 병렬 처리용 Fixed Thread Pool (replaces Virtual Thread for CPU-bound stability) */
    private val workerPool: ExecutorService by lazy {
        val pool = Executors.newFixedThreadPool(config.common.workerPoolSize) { runnable ->
            Thread(runnable, "$queueName-worker").apply { isDaemon = true }
        }
        ExecutorServiceMetrics.monitor(meterRegistry, pool, "$queueName-worker-pool", "pgmq.worker")
        pool
    }

    protected open val maxInflight: Int = 100

    private val inflightPermits by lazy { Semaphore(maxInflight) }

    /** Pipeline buffer for two-phase workers — Phase 1 results queue here before drain */
    private val pipelineBuffer = PipelineBuffer<CalculationResult>(
        microBatchSize = config.common.pipelineMicroBatchSize,
        maxBufferSize = config.common.pipelineMaxBufferSize,
    )

    /** 큐별 종합 메트릭 (lazy init — 하위 클래스의 queueName 초기화 이후 접근 시 생성) */
    protected val metrics: WorkerQueueMetrics.Binder by lazy { queueMetrics.forQueue(queueName) }

    /**
     * 큐 이름
     */
    abstract val queueName: String

    /**
     * 메시지 페이로드 클래스
     */
    abstract val payloadClass: Class<T>

    /**
     * Worker별 설정
     */
    abstract val workerSettings: PgmqWorkerConfig.WorkerSettings

    /**
     * 메시지 처리 로직 (구현체에서 구현)
     *
     * @param message PGMQ 메시지
     * @return 처리 성공 여부 (true: archive, false: delete or retry)
     */
    protected abstract fun process(message: PgmqMessage<T>): Boolean

    /**
     * 메시지 처리 실패 시 후처리 훅 (선택적 오버라이드)
     *
     * <p>process()가 false를 반환하거나 예외 발생 시 호출.
     * 기본 구현은 no-op.
     * 429 Rate Limit 시 setVisibilityTimeout()으로 짧은 지연 설정에 사용.
     *
     * @param message 처리 실패한 메시지
     */
    protected open fun onProcessingFailed(message: PgmqMessage<T>) {
        // no-op by default
    }

    /**
     * 배치 pre-warm 훅 (ADR-700)
     *
     * <p>병렬 메시지 처리 전 호출. 하위 클래스에서 override하여
     * 배치 내 OCID 중복 제거, 장비 캐시 pre-warm 등 수행.
     * 기본 구현은 no-op. Best-effort: 실패해도 메시지 처리에 영향 없음.
     *
     * @param messages 배치로 읽은 메시지 목록
     */
    protected open fun preWarmBatch(messages: List<PgmqMessage<T>>) {
        // no-op by default
    }

    /**
     * Whether this worker supports two-phase batch processing.
     * P1-9 FIX: Explicit opt-in via property instead of runtime probe.
     */
    protected open val supportsTwoPhase: Boolean = false

    /**
     * Phase 1: Calculate without DB writes (BS2)
     *
     * Override in subclasses to enable two-phase batch processing.
     * Default: returns null -> falls back to single-phase process() per message.
     */
    protected open fun calculateOnly(message: PgmqMessage<T>): Any? = null

    /**
     * Phase 2: Batch write calculated results (BS4/BS5)
     *
     * Override in subclasses to batch persist results from Phase 1.
     * Default: no-op -> single-phase fallback.
     */
    protected open fun batchWrite(results: List<CalculationResult>) {}

    /**
     * 메시지 배치 처리
     *
     * <p>1. 큐에서 메시지 읽기
     * <p>2. 배치 pre-warm (ADR-700: OCID dedup + equipment cache pre-warm)
     * <p>3. 각 메시지 병렬 처리
     * <p>4. 성공 시 archive, 실패 시 delete
     */
    @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
    fun processMessages() {
        if (!lifecycleWrapper.beforeTask()) return
        if (!workerSettings.enabled) {
            lifecycleWrapper.afterTask()
            return
        }

        val context = TaskContext.of("PgmqWorker", "ProcessBatch", queueName)

        // P1-10 FIX: executeWithFinally guarantees afterTask() runs even when
        // pgmqClient.read() or setup throws, preventing counter leak.
        executor.executeWithFinally(
            task = {
                val permits = inflightPermits.drainPermits()
                if (permits <= 0) return@executeWithFinally

                val batchSize = minOf(
                    workerSettings.batchSize ?: config.common.batchSize,
                    permits,
                )
                val visibilityTimeout = config.common.visibilityTimeoutSec

                val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)

                metrics.updateQueueDepth(pgmqClient.queueLength(queueName))

                if (messages.isEmpty()) {
                    inflightPermits.release(permits)
                    return@executeWithFinally
                }

                // Return unused permits
                val unused = permits - messages.size
                if (unused > 0) inflightPermits.release(unused)

                log.debug("[{}] Processing {} messages", queueName, messages.size)

                messages.forEach { message ->
                    metrics.inflightIncrement()
                    metrics.recordWaitDuration(message.enqueuedAt)
                }

                preWarmBatch(messages)

                // P1-9 FIX: Use explicit property instead of runtime probe
                if (supportsTwoPhase) {
                    if (pipelineBuffer.isFull()) {
                        log.warn("[{}] Pipeline buffer full ({}), skipping poll", queueName, pipelineBuffer.size())
                        messages.forEach { metrics.inflightDecrement() }
                        inflightPermits.release(messages.size)
                    } else {
                        processBatchPipelined(messages)
                    }
                } else {
                    processBatchSinglePhase(messages)
                }
            },
            finallyBlock = { lifecycleWrapper.afterTask() },
            context = context,
        )
    }

    @Scheduled(fixedDelayString = "\${pgmq.worker.common.pipeline-drain-interval-ms:100}")
    fun drainBuffer() {
        if (!supportsTwoPhase) return
        if (!lifecycleWrapper.beforeTask()) return

        val context = TaskContext.of("PgmqWorker", "DrainBuffer", queueName)

        executor.executeWithFinally(
            task = {
                drainMicroBatch()
            },
            finallyBlock = { lifecycleWrapper.afterTask() },
            context = context,
        )
    }

    private fun drainMicroBatch() {
        val microBatchSize = config.common.pipelineMicroBatchSize
        val batch = pipelineBuffer.drain(microBatchSize)
        if (batch.isEmpty()) return

        batchWrite(batch)

        batch.forEach {
            metrics.success.increment()
            metrics.inflightDecrement()
            inflightPermits.release()
        }

        log.debug("[{}] Drained {} results", queueName, batch.size)
    }

    /**
     * Pipeline-based two-phase processing.
     * Phase 1 runs asynchronously per-message; results flow into PipelineBuffer.
     * Phase 2 is handled separately by [drainBuffer].
     */
    private fun processBatchPipelined(messages: List<PgmqMessage<T>>) {
        messages.forEach { message ->
            CompletableFuture.supplyAsync(
                { executePhaseOne(message) },
                workerPool,
            ).exceptionally { error ->
                log.warn("[{}] Phase 1 failed for msgId={}: {}", queueName, message.messageId, error.message)
                metrics.inflightDecrement()
                inflightPermits.release()
                null to message
            }.thenAccept { result ->
                val calcResult = result.first as? CalculationResult
                if (calcResult != null) {
                    if (!pipelineBuffer.offer(calcResult)) {
                        log.warn("[{}] Pipeline buffer full, dropping result: userIgn={}", queueName, calcResult.message.payload.userIgn)
                        metrics.inflightDecrement()
                        inflightPermits.release()
                    }
                } else {
                    log.warn("[{}] Phase 1 returned non-CalculationResult for msgId={}, dropping", queueName, result.second.messageId)
                    metrics.inflightDecrement()
                    inflightPermits.release()
                }
            }
        }
    }

    /**
     * P1-5 FIX: LogicExecutor wraps calculation.
     * P1-7 FIX: Extracted private method.
     * P0-1 FIX(R3): executeWithFinally for concurrentDecrement (Zero Try-Catch compliance).
     */
    private fun executePhaseOne(message: PgmqMessage<T>): PhaseOneResult<T> {
        val context = TaskContext.of("PgmqWorker", "CalculateOnly", "$queueName:${message.messageId}")
        return executor.executeWithFinally(
            task = {
                metrics.concurrentIncrement()
                val result = executor.executeOrDefault(
                    { calculateOnly(message) },
                    null,
                    context,
                )
                result to message
            },
            finallyBlock = { metrics.concurrentDecrement() },
            context = context,
        )
    }

    private fun processBatchSinglePhase(messages: List<PgmqMessage<T>>) {
        val futures = messages.map { message ->
            CompletableFuture.supplyAsync({
                processSingleMessage(message)
            }, workerPool)
        }
        CompletableFuture.allOf(*futures.toTypedArray())
            .exceptionally { ex ->
                log.warn("[{}] Batch completion error: {}", queueName, ex.message)
                null
            }
            .join()
    }

    /**
     * 단일 메시지 처리
     *
     * <p>재시도 로직 포함:
     * <ul>
     *   <li>처리 성공 -> archive
     *   <li>처리 실패 + 재시도 가능 -> 재시도 (다음 poll에서 다시 읽힘)
     *   <li>처리 실패 + 재시도 불가 -> delete (DLQ)
     * </ul>
     */
    private fun processSingleMessage(message: PgmqMessage<T>): Boolean {
        val maxRetries = workerSettings.maxRetries ?: config.common.maxRetries
        val context = TaskContext.of("PgmqWorker", "ProcessMessage", "$queueName:${message.messageId}")

        // 재시도 메시지 추적
        if (message.readCount > 1) {
            metrics.retry.increment()
        }

        return executor.executeWithFinally(
            task = {
                metrics.concurrentIncrement()
                val success = executor.executeOrDefault(
                    { process(message) },
                    false,
                    context,
                )

                when {
                    success -> {
                        pgmqClient.archive(queueName, message.messageId)
                        metrics.success.increment()
                        log.debug("[{}] Archived message: msgId={}", queueName, message.messageId)
                    }
                    message.isRetryable(maxRetries) -> {
                        onProcessingFailed(message)
                        metrics.failure.increment()
                        log.warn("[{}] Message will be retried: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
                    }
                    else -> {
                        pgmqClient.archive(queueName, message.messageId)
                        metrics.failure.increment()
                        metrics.dlq.increment()
                        log.error("[{}] Archived message after max retries: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
                    }
                }

                success
            },
            finallyBlock = {
                metrics.concurrentDecrement()
                metrics.inflightDecrement()
            },
            context = context,
        )
    }

    @PreDestroy
    fun shutdownWorkerPool() {
        log.info("[{}] Shutting down worker pool", queueName)
        workerPool.shutdown()
        if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("[{}] Worker pool did not terminate in 5s, forcing", queueName)
            workerPool.shutdownNow()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgmqWorker::class.java)
    }
}
