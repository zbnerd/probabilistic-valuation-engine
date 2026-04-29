package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
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
 * <h3>ADR: .join() 유지 결정</h3>
 *
 * **Context:** `process()` returns Boolean for PgmqWorker ACK/NACK routing.
 * The abstract method signature cannot return CompletableFuture without changing
 * all PgmqWorker subclasses. This method runs on a dedicated worker pool thread
 * (not Tomcat), so blocking does not affect request-serving threads.
 *
 * **Decision:** Use `handle().join()` to await the async calculation result,
 * transforming success/failure into Boolean within the CF chain before joining.
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
     * Process calculation message.
     *
     * ADR: `.join()` is required because PgmqWorker.process() returns Boolean
     * for ACK/NACK routing. Runs on dedicated worker pool thread (not Tomcat).
     * Uses `handle()` to transform result before joining.
     */
    override fun process(message: PgmqMessage<CalculationRequest>): Boolean {
        val request = message.payload
        val context = TaskContext.of("CalculationWorker", "Process", request.userIgn)

        return executor.executeOrDefault({
            log.info("Processing: ign={}, ocid={}", request.userIgn, request.ocid)

            expectationPort.calculateExpectationAsync(
                request.userIgn,
                request.forceRecalculation,
                message.messageId.toString(),
                request.presetNo,
            ).handle { _, ex ->
                if (ex != null) {
                    log.warn("Calculation failed: ign={}, error={}", request.userIgn, ex.message)
                    false
                } else {
                    log.info("Completed: ign={}", request.userIgn)
                    true
                }
            }.join()
        }, false, context)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CalculationWorker::class.java)
    }
}
