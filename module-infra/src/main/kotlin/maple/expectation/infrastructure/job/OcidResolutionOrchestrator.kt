package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.OcidResolveEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OcidResolutionOrchestrator(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(value = "transactionManager", readOnly = false)
    fun requestOcidResolve(jobId: UUID, userIgn: String, presetNo: Int) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.OCID_RESOLVING,
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to OCID_RESOLVING", jobId)
            return
        }

        eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(jobId.toString(), userIgn, presetNo))
        log.info("[jobId={}] Transitioned to OCID_RESOLVING, resolve enqueued", jobId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun handleOcidFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] OCID resolve failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetryForOcid(jobId, errorCode)
            if (retried) {
                eventAppender.append(ocidResolveTopic, OcidResolveEventFactory.create(job.jobId.toString(), job.userIgn, job.presetNo))
                log.info("[jobId={}] OCID resolve retry (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }

    @Transactional(value = "transactionManager", readOnly = false)
    fun resolveOcidInPlace(jobId: UUID, ocid: String): Boolean = jobPort.resolveOcidAndTransition(jobId, ocid)
}
