package maple.expectation.infrastructure.worker

import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.queue.pgmq.OcidResolveMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OcidResolveWorker(
    private val pgmqClient: PgmqClient,
    private val nexonApiClient: NexonApiClient,
    private val jobService: CalculationJobService,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${pgmq.worker.ocid-resolve.polling-interval-ms:100}")
    fun processMessages() {
        val context = TaskContext.of("OcidResolveWorker", "Poll", "ocid_resolve_queue")

        executor.executeVoid({
            val messages = pgmqClient.read(
                QueueNames.OCID_RESOLVE,
                OcidResolveMessage::class.java,
                10,
                120
            )

            for (message in messages) {
                processSingle(message)
            }
        }, context)
    }

    private fun processSingle(message: PgmqMessage<OcidResolveMessage>) {
        val request = message.payload
        val jobId = request.jobId
        val context = TaskContext.of("OcidResolveWorker", "Resolve", request.userIgn)

        executor.executeVoid({
            log.info("[jobId={}] Resolving OCID for userIgn={}", jobId, request.userIgn)

            val ocidResponse = nexonApiClient.getOcidByCharacterName(request.userIgn).join()
            val ocid = ocidResponse.ocid

            if (ocid.isBlank()) {
                log.warn("[jobId={}] Nexon API returned empty OCID for userIgn={}", jobId, request.userIgn)
                jobService.handleOcidFailure(jobId, "EMPTY_OCID", "Nexon API returned empty OCID")
                pgmqClient.archive(QueueNames.OCID_RESOLVE, message.messageId)
                return@executeVoid
            }

            val resolved = jobService.resolveOcidAndEnqueueApiData(jobId, ocid)
            if (resolved) {
                log.info("[jobId={}] OCID resolved successfully: {}", jobId, ocid)
            } else {
                log.warn("[jobId={}] OCID resolve transition failed", jobId)
                jobService.handleOcidFailure(jobId, "TRANSITION_FAILED", "Status transition failed after OCID resolve")
            }
            pgmqClient.archive(QueueNames.OCID_RESOLVE, message.messageId)
        }, context)
    }
}
