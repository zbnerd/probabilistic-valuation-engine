package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.pipeline.consolidated.enabled"], havingValue = "false")
class CalculationCompletedWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    private val workerConfig: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val jobPort: CalculationJobPort,
    private val executionService: CalculationExecutionService,
) : PgmqWorker<CalculationCompletedPayload>(pgmqClient, executor, workerConfig, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val queueName: String = QueueNames.CALCULATION_COMPLETED
    override val payloadClass: Class<CalculationCompletedPayload> = CalculationCompletedPayload::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = workerConfig.calculationCompleted

    override fun process(message: PgmqMessage<CalculationCompletedPayload>): Boolean {
        val payload = message.payload
        val jobId = UUID.fromString(payload.jobId)
        val context = TaskContext.of("ResultPersistWorker", "ProcessMessage", payload.jobId)

        return executor.executeOrCatch(
            {
                persistResult(jobId, payload)
            },
            { e ->
                log.error("[jobId={}] Result persist failed: {}", jobId, e.message)
                handleFailure(message, jobId)
            },
            context,
        )
    }

    private fun persistResult(jobId: UUID, payload: CalculationCompletedPayload): Boolean {
        val job = jobPort.findJobById(jobId)
        if (job == null) {
            log.warn("[jobId={}] Job not found, archiving completed payload", jobId)
            return true
        }
        if (job.status == CalculationJobStatus.COMPLETED) {
            log.debug("[jobId={}] Already completed, archiving duplicate completed payload", jobId)
            return true
        }
        if (job.status != CalculationJobStatus.CALCULATING) {
            log.warn("[jobId={}] Completed payload ignored in state {}", jobId, job.status)
            return job.status == CalculationJobStatus.FAILED
        }

        return stage("PersistResult", jobId.toString()) {
            executionService.completeCalculatedResult(
                jobId = jobId,
                gzipData = payload.gzipData,
                hash = payload.hash,
                originalSize = payload.originalSize,
                compressedSize = payload.compressedSize,
                characterClass = payload.characterClass,
                presetNo = payload.presetNo,
                characterId = payload.characterId,
            )
        }
    }

    private fun handleFailure(message: PgmqMessage<CalculationCompletedPayload>, jobId: UUID): Boolean {
        val maxRetries = workerSettings.maxRetries ?: workerConfig.common.maxRetries
        if (message.readCount >= maxRetries) {
            jobPort.markFailed(jobId, "RESULT_PERSIST_ERROR", "Max retries exceeded (attempts=${message.readCount})")
            log.error("[jobId={}] Result persist failed permanently after {} attempts", jobId, message.readCount)
            return true
        }

        log.warn("[jobId={}] Result persist will retry (attempt {})", jobId, message.readCount)
        return false
    }

    private fun <T> stage(name: String, key: String, block: () -> T): T = executor.execute(
        { block() },
        TaskContext.of("ResultPersistWorker", name, key),
    )

    companion object {
        private val log = LoggerFactory.getLogger(CalculationCompletedWorker::class.java)
    }
}
