package maple.expectation.application.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import maple.expectation.application.service.expectation.PureExpectationCalculator
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.worker.legacy-pipeline.enabled"], havingValue = "true", matchIfMissing = false)
class ApiResponseWorker(
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val pureCalculator: PureExpectationCalculator,
    private val jobPort: CalculationJobPort,
    private val executionService: CalculationExecutionService,
    private val calculationInputPort: CalculationInputPort,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val terminalStatuses = setOf(
        CalculationJobStatus.COMPLETED,
        CalculationJobStatus.FAILED,
    )

    init {
        nexonApiResponseTopic.subscribe { envelope, _ -> handleApiResponse(envelope) }
    }

    private fun handleApiResponse(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val jobId = UUID.fromString(payload["jobId"].toString())
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("ApiResponseWorker", "Process", userIgn)
        return executor.executeOrCatch(
            { processApiResponse(payload, jobId, userIgn) },
            { e ->
                log.error("[jobId={}] Calculation failed: {}", jobId, e.message)
                val msg = (e.message ?: "Unknown error").take(200)
                executor.executeVoid({ executionService.handleCalculationFailure(jobId, "CALCULATION_ERROR", msg) }, context)
                ConsumeResult.Ack
            },
            context,
        )
    }

    private fun processApiResponse(payload: Map<*, *>, jobId: UUID, userIgn: String): ConsumeResult {
        val job = jobPort.findJobById(jobId)
        if (job == null) {
            log.warn("[jobId={}] Job not found, archiving", jobId)
            return ConsumeResult.Ack
        }

        if (job.status in terminalStatuses) {
            log.info("[jobId={}] Already in terminal state: {}, skipping", jobId, job.status)
            return ConsumeResult.Ack
        }

        if (job.status == CalculationJobStatus.CALCULATING) {
            log.warn("[jobId={}] Stuck in CALCULATING on redelivery, marking as failed", jobId)
            jobPort.markFailed(jobId, "CALCULATION_STUCK", "Calculation stuck after redelivery")
            return ConsumeResult.Ack
        }

        val started = executionService.startCalculation(jobId, "ApiResponseWorker")
        if (!started) {
            log.warn("[jobId={}] Could not start calculation, archiving", jobId)
            return ConsumeResult.Ack
        }

        val presetNo = (payload["presetNo"] as Number).toInt()
        val characterId = payload["characterId"]?.toString() ?: ""

        val input = calculationInputPort.findByJobId(jobId)
        if (input == null) {
            log.error("[jobId={}] CalculationInput not found, cannot proceed", jobId)
            jobPort.markFailed(jobId, "INPUT_NOT_FOUND", "CalculationInput not found for job")
            return ConsumeResult.Ack
        }

        val result = pureCalculator.calculate(input)

        val resultJson = objectMapper.writeValueAsString(result)

        executionService.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = input.characterClass,
            presetNo = presetNo,
            characterId = characterId,
        )

        log.info("[jobId={}] Calculation completed from CalculationInput (pure)", jobId)
        return ConsumeResult.Ack
    }
}
