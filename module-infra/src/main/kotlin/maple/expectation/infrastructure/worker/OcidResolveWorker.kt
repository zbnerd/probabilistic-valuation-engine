package maple.expectation.infrastructure.worker

import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OcidResolveWorker(
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiClient: NexonApiClient,
    private val jobService: CalculationJobService,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        ocidResolveTopic.subscribe { envelope, _ -> handleResolve(envelope) }
    }

    private fun handleResolve(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("OcidResolveWorker", "Resolve", userIgn)
        return executor.executeOrDefault({
            val jobId = UUID.fromString(payload["jobId"].toString())

            log.info("[jobId={}] Resolving OCID for userIgn={}", jobId, userIgn)

            val ocidResponse = nexonApiClient.getOcidByCharacterName(userIgn).join()
            val ocid = ocidResponse.ocid

            if (ocid.isBlank()) {
                jobService.handleOcidFailure(jobId, "EMPTY_OCID", "Nexon API returned empty OCID")
                return@executeOrDefault ConsumeResult.Ack
            }

            val resolved = jobService.resolveOcidAndEnqueueApiData(jobId, ocid)
            if (!resolved) {
                jobService.handleOcidFailure(jobId, "TRANSITION_FAILED", "Status transition failed after OCID resolve")
            }
            log.info("[jobId={}] OCID resolved: {}", jobId, ocid)
            ConsumeResult.Ack
        }, ConsumeResult.Ack, context)
    }
}
