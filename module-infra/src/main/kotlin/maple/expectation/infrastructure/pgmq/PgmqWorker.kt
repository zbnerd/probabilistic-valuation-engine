package maple.expectation.infrastructure.pgmq

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import jakarta.annotation.PreDestroy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
            Thread.ofVirtual().name("$queueName-worker").unstarted(runnable)
        }
        ExecutorServiceMetrics.monitor(meterRegistry, pool, "$queueName-worker-pool", io.micrometer.core.instrument.Tags.of("type", "pgmq.worker"))
        pool
    }

    protected open val maxInflight: Int = 100

    private val inflightPermits by lazy { Semaphore(maxInflight) }

    /** Pipeline buffer for two-phase workers — Phase 1 results queue here before drain */
    private val pipelineBuffer = PipelineBuffer<CalculationResult>(
        microBatchSize = config.common.pipelineMicroBatchSize,
        maxBufferSize = maxInflight * 2,
    )

    /** Sequential batch buffer — accumulates messages for [sequentialBatchMs] before processing */
    private val accumulationBuffer = AccumulationBuffer<T>(config.common.sequentialBatchMs)
    private val pollCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** Convenience: sequential batch window from config */
    private val sequentialBatchMs: Long
        get() = config.common.sequentialBatchMs

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

        executor.executeWithFinally(
            task = {
                // Phase A: Flush accumulated messages if time window expired
                if (sequentialBatchMs > 0 && supportsTwoPhase && accumulationBuffer.shouldFlush()) {
                    flushSequentialBatch()
                }

                // Phase B: Read new messages
                val permits = inflightPermits.drainPermits()
                if (permits <= 0) return@executeWithFinally

                val batchSize = minOf(
                    workerSettings.batchSize ?: config.common.batchSize,
                    permits,
                )
                val visibilityTimeout = config.common.visibilityTimeoutSec

                val messages = pgmqClient.read(queueName, payloadClass, batchSize, visibilityTimeout)

                if (pollCounter.incrementAndGet() % 20 == 0) {
                    metrics.updateQueueDepth(pgmqClient.queueLength(queueName))
                }

                if (messages.isEmpty()) {
                    inflightPermits.release(permits)
                    return@executeWithFinally
                }

                val unused = permits - messages.size
                if (unused > 0) inflightPermits.release(unused)

                log.debug("[{}] Processing {} messages", queueName, messages.size)

                messages.forEach { message ->
                    metrics.inflightIncrement()
                    metrics.recordWaitDuration(message.enqueuedAt)
                }

                // Phase C: Route to processing mode
                if (sequentialBatchMs > 0 && supportsTwoPhase) {
                    accumulationBuffer.addAll(messages)
                    if (accumulationBuffer.shouldFlush()) {
                        flushSequentialBatch()
                    }
                } else if (supportsTwoPhase) {
                    if (pipelineBuffer.isFull()) {
                        log.warn("[{}] Pipeline buffer full ({}), draining before poll", queueName, pipelineBuffer.size())
                        drainMicroBatch()
                    }
                    processBatchPipelined(messages)
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
        if (sequentialBatchMs > 0) return // Sequential mode handles own writes
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
                    log.warn("[{}] Phase 1 returned null for msgId={}, releasing permit", queueName, result.second.messageId)
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

    @Volatile
    private var pendingBatchFuture: CompletableFuture<*>? = null

    private fun processBatchSinglePhase(messages: List<PgmqMessage<T>>) {
        val archiveIds = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val futures = messages.map { message ->
            CompletableFuture.supplyAsync({
                val needsArchive = processSingleMessage(message)
                if (needsArchive) archiveIds.add(message.messageId)
                needsArchive
            }, workerPool)
        }
        pendingBatchFuture = CompletableFuture.allOf(*futures.toTypedArray())
            .exceptionally { ex ->
                log.warn("[{}] Batch completion error: {}", queueName, ex.message)
                null
            }
            .thenAccept {
                if (archiveIds.isNotEmpty()) {
                    executor.executeOrDefault(
                        {
                            val archived = pgmqClient.archiveBatch(queueName, archiveIds.toList())
                            log.debug("[{}] Batch archived {}/{} messages", queueName, archived, archiveIds.size)
                        },
                        Unit,
                        TaskContext.of("PgmqWorker", "BatchArchive", queueName),
                    )
                }
                log.debug("[{}] Batch of {} messages completed", queueName, messages.size)
            }
    }

    /**
     * Flush the accumulation buffer: drain all messages, pre-warm, split into chunks,
     * submit each chunk as an independent task. Each chunk is processed sequentially
     * on its own thread — no context switching within, parallel across chunks.
     */
    private fun flushSequentialBatch() {
        val batch = accumulationBuffer.drain()
        if (batch.isEmpty()) return
        executor.executeOrCatch(
            {
                preWarmBatch(batch)
                val poolSize = config.common.workerPoolSize
                val chunkSize = maxOf(1, batch.size / poolSize)
                val chunks = batch.chunked(chunkSize)
                log.info("[{}] Batch flush: {} messages → {} chunks after {}ms buffer", queueName, batch.size, chunks.size, sequentialBatchMs)
                chunks.forEach { chunk ->
                    workerPool.submit { processSequentialBatch(chunk) }
                }
            },
            { e ->
                log.error("[{}] Batch flush failed, re-queueing {} messages", queueName, batch.size, e)
                accumulationBuffer.addAll(batch)
                null
            },
            TaskContext.of("PgmqWorker", "FlushSequentialBatch", queueName),
        )
    }

    /**
     * Coroutine-parallel chunk processing: all messages in a chunk processed concurrently.
     * Replaces sequential for-loop with coroutine async/awaitAll for ~2-3x per-chunk speedup.
     * Multiple chunks still run in parallel across workerPoolSize threads.
     */
    private fun processSequentialBatch(messages: List<PgmqMessage<T>>) {
        val results: List<CalculationResult> = runBlocking(Dispatchers.IO) {
            messages.map { message ->
                async(Dispatchers.IO) {
                    metrics.concurrentIncrement()
                    val context = TaskContext.of("PgmqWorker", "CoroutineCalc", "$queueName:${message.messageId}")
                    val result = executor.executeOrDefault(
                        { calculateOnly(message) },
                        null,
                        context,
                    )
                    metrics.concurrentDecrement()
                    result as? CalculationResult
                }
            }.awaitAll().filterNotNull()
        }

        val successCount = results.size

        executor.executeOrCatch(
            {
                if (results.isNotEmpty()) {
                    batchWrite(results)
                }
                repeat(successCount) { metrics.success.increment() }
            },
            { e ->
                log.error("[{}] Coroutine batchWrite failed, {} results lost", queueName, results.size, e)
                repeat(successCount) { metrics.failure.increment() }
                null
            },
            TaskContext.of("PgmqWorker", "BatchWrite", queueName),
        )

        // Always release permits and inflight metrics — prevent resource leak
        messages.forEach {
            metrics.inflightDecrement()
            inflightPermits.release()
        }

        log.debug("[{}] Coroutine chunk done: {}/{} succeeded", queueName, successCount, messages.size)
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
                        metrics.success.increment()
                    }
                    message.isRetryable(maxRetries) -> {
                        onProcessingFailed(message)
                        metrics.failure.increment()
                        log.warn("[{}] Message will be retried: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
                    }
                    else -> {
                        metrics.failure.increment()
                        metrics.dlq.increment()
                        log.error("[{}] Max retries exceeded: msgId={}, readCount={}", queueName, message.messageId, message.readCount)
                    }
                }

                success
            },
            finallyBlock = {
                metrics.concurrentDecrement()
                metrics.inflightDecrement()
                inflightPermits.release()
            },
            context = context,
        )
    }

    @PreDestroy
    fun shutdownWorkerPool() {
        if (sequentialBatchMs > 0 && !accumulationBuffer.isEmpty()) {
            log.warn("[{}] Shutdown with {} messages in buffer, forcing flush", queueName, accumulationBuffer.size())
            flushSequentialBatch()
        }
        log.info("[{}] Shutting down worker pool", queueName)
        pendingBatchFuture?.join()
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
