package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.random.Random
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.fanout.NexonFanOutBatchLoader
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.FanOutRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.ProcessOutcome
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import maple.expectation.infrastructure.queue.pgmq.FanOutQueueProducer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Nexon FanOut Worker (429 재시도 전용)
 *
 * <h3>역할</h3>
 * <p>nexon_fanout_queue에서 429 Rate Limit 재시도 메시지를 소비.
 * Batch Lane에서 enqueue된 FanOutRequest를 처리.
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>nexon_fanout_queue에서 메시지 읽기</li>
 *   <li>EquipmentFetchProvider.fetchWithCache()로 장비 데이터 조회</li>
 *   <li>성공 → archive (Base class 처리)</li>
 *   <li>429 → setVisibilityTimeout(1.0~1.3s jitter) 후 재시도</li>
 *   <li>readCount >= maxRetries → delete (DLQ)</li>
 * </ol>
 *
 * <h3>Jitter</h3>
 * <p>429 재시도 시 1.0초 ~ 1.3초 (30% jitter) 랜덤 지연.
 * Thundering Herd 방지.
 *
 * @see FanOutQueueProducer 프로듀서
 * @see EquipmentFetchProvider 캐시 적용 장비 데이터 조회
 * @see NexonFanOutBatchLoader Batch Lane 병렬 실행기
 */
@Component
@Profile("!test")
class NexonFanOutWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val fetchProvider: EquipmentFetchProvider,
    @Qualifier("expectationComputeCpuExecutor") private val cpuExecutor: Executor,
) : PgmqWorker<FanOutRequest>(pgmqClient, executor, config, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val queueName: String = FanOutQueueProducer.QUEUE_NAME
    override val payloadClass: Class<FanOutRequest> = FanOutRequest::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.nexonFanout

    /**
     * Async variant of [process]. Wraps the existing synchronous [process] in
     * [CompletableFuture.supplyAsync] on the dedicated CPU executor so the PGMQ
     * scheduler is never blocked on a synchronous call site.
     *
     * Matches sync semantics: [ProcessOutcome.Ack] when sync returns true (archive),
     * [ProcessOutcome.Nack] with `retryable=true` when sync returns false. The 429
     * visibility-reset (1.0~1.3s jitter) is still applied by [onProcessingFailed] via
     * the legacy `process()` path; for the async path the visibilityReset is left null
     * so the base class default visibility is used.
     */
    override fun processAsync(message: PgmqMessage<FanOutRequest>): CompletableFuture<ProcessOutcome> =
        CompletableFuture.supplyAsync(
            {
                if (process(message)) ProcessOutcome.Ack
                else ProcessOutcome.Nack(retryable = true)
            },
            cpuExecutor,
        )

    /**
     * Test bridge — exposes the [processAsync] method (protected in PgmqWorker) to unit tests.
     * Internal visibility keeps it out of the public server API surface.
     */
    internal fun callProcessAsync(message: PgmqMessage<FanOutRequest>): CompletableFuture<ProcessOutcome> =
        processAsync(message)

    @Deprecated("Use processAsync", ReplaceWith("processAsync(message).get() == ProcessOutcome.Ack"))
    override fun process(message: PgmqMessage<FanOutRequest>): Boolean {
        val request = message.payload
        val context = TaskContext.of("NexonFanOutWorker", "Process", request.ocid)

        return executor.executeOrDefault(
            {
                fetchProvider.fetchWithCache(request.ocid)
                log.info("[NexonFanOutWorker] Success: ocid={}, retry={}", request.ocid, request.retryCount)
                true
            },
            false,
            context,
        )
    }

    /**
     * 429 발생 시 Visibility Timeout을 짧게 설정 (1.0~1.3s jitter)
     *
     * <p>Base class의 processSingleMessage에서 process() 실패 후 호출.
     * 기본 30초 VT 대신 짧은 지연으로 빠른 재시도.
     */
    override fun onProcessingFailed(message: PgmqMessage<FanOutRequest>) {
        val jitterSec = BASE_DELAY_SEC + (Random.nextDouble() * JITTER_FACTOR)
        val jitterSecInt = ceil(jitterSec).toInt().coerceAtLeast(1)

        pgmqClient.setVisibilityTimeout(queueName, message.messageId, jitterSecInt.toLong())
        log.warn(
            "[NexonFanOutWorker] 429 retry with VT={}s: ocid={}, readCount={}",
            jitterSecInt,
            message.payload.ocid,
            message.readCount,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(NexonFanOutWorker::class.java)
        private const val BASE_DELAY_SEC = 1.0
        private const val JITTER_FACTOR = 0.3
    }
}
