package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.infrastructure.queue.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
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
class CalculationRequestedWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    private val workerConfig: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val jobPort: CalculationJobPort,
    private val calculationInputPort: CalculationInputPort,
    private val pureCalculationPort: PureCalculationPort,
    private val jobService: CalculationJobService,
    private val objectMapper: ObjectMapper,
) : PgmqWorker<CalculationRequestedPayload>(pgmqClient, executor, workerConfig, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val queueName: String = QueueNames.CALCULATION_REQUESTED
    override val payloadClass: Class<CalculationRequestedPayload> = CalculationRequestedPayload::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = workerConfig.calculationRequested

    override fun process(message: PgmqMessage<CalculationRequestedPayload>): Boolean {
        val payload = message.payload
        val jobId = UUID.fromString(payload.jobId)
        val context = TaskContext.of("CalculationWorker", "ProcessMessage", payload.userIgn)

        return executor.executeOrCatch(
            {
                processCalculation(jobId, payload)
                true
            },
            { e ->
                log.error("[jobId={}] Calculation failed: {}", jobId, e.message)
                handleFailure(message, jobId)
            },
            context,
        )
    }

    private fun processCalculation(jobId: UUID, payload: CalculationRequestedPayload) {
        val job = jobPort.findJobById(jobId)
        if (job == null) {
            log.warn("[jobId={}] Job not found, archiving calculation request", jobId)
            return
        }
        if (job.status == CalculationJobStatus.COMPLETED || job.status == CalculationJobStatus.FAILED) {
            log.debug("[jobId={}] Skipping calculation in terminal state {}", jobId, job.status)
            return
        }
        if (job.status == CalculationJobStatus.SNAPSHOT_READY) {
            val claimed = jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)
            if (!claimed) {
                log.warn("[jobId={}] Could not claim calculation", jobId)
                return
            }
        } else if (job.status != CalculationJobStatus.CALCULATING) {
            log.warn("[jobId={}] Calculation request ignored in state {}", jobId, job.status)
            return
        }

        val input = stage("LoadInput", jobId.toString()) {
            calculationInputPort.findByJobId(jobId) ?: error("Calculation input missing: $jobId")
        }
        val calcResult = stage("PureCalculate", payload.userIgn) {
            pureCalculationPort.calculate(input)
        }
        val resultBytes = stage("SerializeResult", payload.userIgn) {
            objectMapper.writeValueAsString(calcResult).toByteArray()
        }
        val gzipData = stage("GzipResult", payload.userIgn) {
            gzipCompress(resultBytes)
        }
        val hash = stage("HashResult", payload.userIgn) {
            sha256Hex(resultBytes)
        }

        stage("DispatchCompleted", jobId.toString()) {
            jobService.dispatchCalculationCompleted(
                CalculationCompletedPayload(
                    jobId = jobId.toString(),
                    characterId = payload.characterId,
                    characterClass = payload.characterClass,
                    presetNo = payload.presetNo,
                    gzipData = gzipData,
                    hash = hash,
                    originalSize = resultBytes.size,
                    compressedSize = gzipData.size,
                    totalExpectedCost = calcResult.totalExpectedCost.toLong(),
                    maxPresetNo = calcResult.maxPresetNo,
                    presetsJson = objectMapper.writeValueAsString(calcResult.presets),
                ),
            )
        }
    }

    private fun handleFailure(message: PgmqMessage<CalculationRequestedPayload>, jobId: UUID): Boolean {
        val maxRetries = workerSettings.maxRetries ?: workerConfig.common.maxRetries
        if (message.readCount >= maxRetries) {
            jobPort.markFailed(jobId, "CALCULATION_ERROR", "Max retries exceeded (attempts=${message.readCount})")
            log.error("[jobId={}] Calculation failed permanently after {} attempts", jobId, message.readCount)
            return true
        }

        log.warn("[jobId={}] Calculation will retry (attempt {})", jobId, message.readCount)
        return false
    }

    private fun <T> stage(name: String, key: String, block: () -> T): T = executor.execute(
        { block() },
        TaskContext.of("CalculationWorker", name, key),
    )

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CalculationRequestedWorker::class.java)
    }
}
