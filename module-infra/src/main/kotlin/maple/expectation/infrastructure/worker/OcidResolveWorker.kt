package maple.expectation.infrastructure.worker

import jakarta.annotation.PostConstruct
import java.util.UUID
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.job.ApiDataFetchOrchestrator
import maple.expectation.infrastructure.job.OcidResolutionOrchestrator
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * OCID Resolve Worker
 *
 * <h3>ADR: .join() 유지 결정</h3>
 *
 * **Context:** `handleResolve` is a topic subscriber callback that must return
 * `ConsumeResult` (ACK/NACK) synchronously. The OCID value is needed for downstream
 * job state transitions before the message can be acknowledged.
 *
 * **Decision:** `.join()` is used on the Nexon API CF because the callback contract
 * requires a synchronous return. This runs on the MQ consumer thread, not Tomcat.
 */
@Component
@ConditionalOnProperty(name = ["app.worker.legacy-pipeline.enabled"], havingValue = "true", matchIfMissing = false)
class OcidResolveWorker(
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiClient: NexonApiClient,
    private val ocidOrchestrator: OcidResolutionOrchestrator,
    private val apiOrchestrator: ApiDataFetchOrchestrator,
    private val executor: LogicExecutor,
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
        return executor.executeOrDefault({ resolveOcid(payload, userIgn) }, ConsumeResult.Ack, context)
    }

    /**
     * Resolve OCID from Nexon API and transition job state.
     *
     * ADR: `.join()` required — topic subscriber callback must return ConsumeResult
     * for ACK/NACK. The OCID is needed for downstream job transitions.
     * Runs on MQ consumer thread, not Tomcat request thread.
     */
    private fun resolveOcid(payload: Map<*, *>, userIgn: String): ConsumeResult {
        val jobId = UUID.fromString(payload["jobId"].toString())

        log.info("[jobId={}] Resolving OCID for userIgn={}", jobId, userIgn)

        val ocidResponse = nexonApiClient.getOcidByCharacterName(userIgn)
            .handle { result, ex ->
                if (ex != null) {
                    log.warn("[jobId={}] OCID resolve failed: {}", jobId, ex.message)
                    null
                } else {
                    result
                }
            }
            .join()

        if (ocidResponse == null || ocidResponse.ocid.isBlank()) {
            ocidOrchestrator.handleOcidFailure(jobId, "EMPTY_OCID", "Nexon API returned empty OCID")
            return ConsumeResult.Ack
        }

        val resolved = apiOrchestrator.resolveOcidAndEnqueueApiData(jobId, ocidResponse.ocid)
        if (!resolved) {
            ocidOrchestrator.handleOcidFailure(jobId, "TRANSITION_FAILED", "Status transition failed after OCID resolve")
        }
        log.info("[jobId={}] OCID resolved: {}", jobId, ocidResponse.ocid)
        return ConsumeResult.Ack
    }
}
