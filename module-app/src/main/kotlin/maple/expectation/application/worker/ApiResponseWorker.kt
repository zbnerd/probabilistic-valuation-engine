package maple.expectation.application.worker

import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
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
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${pgmq.worker.nexon-api.polling-interval-ms:100}")
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
            log.info("[jobId={}] Processing API response: eventType={}", response.jobId, response.eventType)

            expectationPort.calculateExpectationAsync(
                response.userIgn,
                false,
                response.jobId.toString(),
                response.presetNo
            ).join()

            pgmqClient.archive(QueueNames.NEXON_API_RESPONSE, message.messageId)
            log.info("[jobId={}] Calculation completed from snapshot", response.jobId)
        }, context)
    }
}
