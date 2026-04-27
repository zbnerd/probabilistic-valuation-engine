package maple.expectation.application.worker

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.queue.pgmq.NexonApiResponseMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ApiResponseWorker(
    private val pgmqClient: PgmqClient,
    private val expectationPort: ExpectationV4Port,
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val terminalStatuses = setOf(
        CalculationJobStatus.COMPLETED,
        CalculationJobStatus.FAILED,
        CalculationJobStatus.CALCULATING
    )

    @Scheduled(fixedDelayString = "\${pgmq.worker.api-response.polling-interval-ms:100}")
    fun processMessages() {
        val context = TaskContext.of("ApiResponseWorker", "Poll", "response_queue")

        executor.executeVoid({
            val messages = pgmqClient.read(
                QueueNames.NEXON_API_RESPONSE,
                NexonApiResponseMessage::class.java,
                10,
                120
            )

            for (message in messages) {
                processSingle(message)
            }
        }, context)
    }

    private fun processSingle(message: PgmqMessage<NexonApiResponseMessage>) {
        val response = message.payload
        val context = TaskContext.of("ApiResponseWorker", "Process", response.userIgn)

        executor.executeVoid({
            val job = jobPort.findJobById(response.jobId)
            if (job == null) {
                log.warn("[jobId={}] Job not found, archiving", response.jobId)
                pgmqClient.archive(QueueNames.NEXON_API_RESPONSE, message.messageId)
                return@executeVoid
            }

            if (job.status in terminalStatuses) {
                log.info("[jobId={}] Already in terminal state: {}, skipping", response.jobId, job.status)
                pgmqClient.archive(QueueNames.NEXON_API_RESPONSE, message.messageId)
                return@executeVoid
            }

            val started = jobService.startCalculation(response.jobId, "ApiResponseWorker")
            if (!started) {
                log.warn("[jobId={}] Could not start calculation, archiving", response.jobId)
                pgmqClient.archive(QueueNames.NEXON_API_RESPONSE, message.messageId)
                return@executeVoid
            }

            log.info("[jobId={}] Processing API response: eventType={}", response.jobId, response.eventType)

            expectationPort.calculateExpectationAsync(
                response.userIgn,
                false,
                response.jobId.toString(),
                response.presetNo
            ).join()

            jobService.completeCalculation(response.jobId)
            pgmqClient.archive(QueueNames.NEXON_API_RESPONSE, message.messageId)
            log.info("[jobId={}] Calculation completed from snapshot", response.jobId)
        }, context)
    }
}
