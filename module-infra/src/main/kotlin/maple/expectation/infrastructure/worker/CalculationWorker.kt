package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.ProcessOutcome
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import maple.expectation.infrastructure.queue.pgmq.CalculationQueueProducer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 계산 Worker (ADR-002)
 *
 * <h3>역할</h3>
 * <p>계산 큐에서 메시지를 소비하고 장비 기대값 계산 수행
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>calculation_queue에서 메시지 읽기</li>
 *   <li>ExpectationV4Port를 통해 계산 수행</li>
 *   <li>성공 시 아카이브, 실패 시 재시도 또는 삭제</li>
 * </ol>
 *
 * <h3>Feature Flag</h3>
 * <p>pgmq.worker.calculation.enabled=true로 활성화
 *
 * <h3>Async Migration</h3>
 * <p>Async via [processAsync] (returns [CompletableFuture] of [ProcessOutcome]).
 * Sync [process] is kept as a [Deprecated] compatibility shim.
 *
 * @see CalculationQueueProducer 프로듀서
 * @see ExpectationV4Port 계산 포트
 */
@Component
@Profile("!test")
class CalculationWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val expectationPort: ExpectationV4Port,
) : PgmqWorker<CalculationRequest>(pgmqClient, executor, config, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val queueName: String = CalculationQueueProducer.QUEUE_NAME
    override val payloadClass: Class<CalculationRequest> = CalculationRequest::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.calculation

    /**
     * Async-native process — returns [ProcessOutcome] without blocking on the worker pool thread.
     *
     * Returns:
     * - [ProcessOutcome.Ack] on calculation success.
     * - [ProcessOutcome.Nack] with `retryable=true` on any failure.
     */
    override fun processAsync(message: PgmqMessage<CalculationRequest>): CompletableFuture<ProcessOutcome> {
        val request = message.payload
        log.info("Processing: ign={}, ocid={}", request.userIgn, request.ocid)

        return expectationPort.calculateExpectationAsync(
            request.userIgn,
            request.forceRecalculation,
            message.messageId.toString(),
            request.presetNo,
        ).handle<ProcessOutcome> { _, ex -> classifyCalculationOutcome(request.userIgn, ex) }
    }

    /**
     * Classify the outcome of a calculation [CompletableFuture].
     *
     * - Failure → [ProcessOutcome.Nack] with `retryable=true` (PGMQ retry loop handles backoff).
     * - Success → [ProcessOutcome.Ack] (PGMQ archives the message).
     */
    private fun classifyCalculationOutcome(userIgn: String, ex: Throwable?): ProcessOutcome =
        if (ex != null) {
            log.warn("Calculation failed: ign={}, error={}", userIgn, ex.message)
            ProcessOutcome.Nack(retryable = true)
        } else {
            log.info("Completed: ign={}", userIgn)
            ProcessOutcome.Ack
        }

    /**
     * Legacy sync API. Delegates to [processAsync] for migration compatibility.
     *
     * @deprecated Use [processAsync] for new callers.
     */
    @Deprecated("Use processAsync", ReplaceWith("processAsync(message).get() == ProcessOutcome.Ack"))
    override fun process(message: PgmqMessage<CalculationRequest>): Boolean =
        try {
            processAsync(message).get() == ProcessOutcome.Ack
        } catch (e: Exception) {
            false
        }

    /**
     * Test bridge — exposes the [processAsync] method (protected in PgmqWorker) to unit tests.
     * Internal visibility keeps it out of the public server API surface.
     */
    internal fun callProcessAsync(message: PgmqMessage<CalculationRequest>): CompletableFuture<ProcessOutcome> =
        processAsync(message)

    companion object {
        private val log = LoggerFactory.getLogger(CalculationWorker::class.java)
    }
}
