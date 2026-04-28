package maple.expectation.application.worker

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ApiResponseWorker(
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val expectationPort: ExpectationV4Port,
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val snapshotStore: SnapshotObjectStore,
    private val objectMapper: ObjectMapper,
    private val cacheManager: CacheManager,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val terminalStatuses = setOf(
        CalculationJobStatus.COMPLETED,
        CalculationJobStatus.FAILED
    )

    @PostConstruct
    fun init() {
        nexonApiResponseTopic.subscribe { envelope, _ -> handleApiResponse(envelope) }
    }

    private fun handleApiResponse(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val jobId = UUID.fromString(payload["jobId"].toString())
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("ApiResponseWorker", "Process", userIgn)
        return executor.executeOrDefault({
            processApiResponse(payload, jobId, userIgn)
        }, ConsumeResult.Ack, context)
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

        val started = jobService.startCalculation(jobId, "ApiResponseWorker")
        if (!started) {
            log.warn("[jobId={}] Could not start calculation, archiving", jobId)
            return ConsumeResult.Ack
        }

        val eventType = payload["eventType"].toString()
        val presetNo = (payload["presetNo"] as Number).toInt()

        log.info("[jobId={}] Processing API response: eventType={}", jobId, eventType)

        populateEquipmentCacheFromSnapshot(payload)

        expectationPort.calculateExpectationAsync(
            userIgn,
            false,
            jobId.toString(),
            presetNo
        ).join()

        jobService.completeCalculation(jobId)
        log.info("[jobId={}] Calculation completed from snapshot", jobId)
        return ConsumeResult.Ack
    }

    private fun populateEquipmentCacheFromSnapshot(payload: Map<*, *>) {
        val objectKey = payload["objectKey"].toString()
        val characterId = payload["characterId"].toString()
        val jobId = payload["jobId"].toString()

        val snapshotData = snapshotStore.get(objectKey)
        val equipmentResponse = objectMapper.readValue(
            snapshotData,
            maple.expectation.infrastructure.external.dto.v2.EquipmentResponse::class.java
        )
        val cache = cacheManager.getCache("equipment")
        if (cache != null) {
            cache.put(characterId, equipmentResponse)
            log.debug("[jobId={}] Equipment cache populated from snapshot", jobId)
        }
    }
}
