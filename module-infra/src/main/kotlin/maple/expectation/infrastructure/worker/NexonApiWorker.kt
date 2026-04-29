package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.infrastructure.converter.EquipmentResponseToCalculationInputConverter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NexonApiWorker(
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val snapshotStore: SnapshotObjectStore,
    private val jobService: CalculationJobService,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val equipmentFetchProvider: EquipmentFetchProvider,
    private val converter: EquipmentResponseToCalculationInputConverter,
    private val calculationInputPort: CalculationInputPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        nexonApiRequestTopic.subscribe { envelope, _ -> handleApiRequest(envelope) }
    }

    private fun handleApiRequest(envelope: IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val jobId = UUID.fromString(payload["jobId"].toString())
        val context = TaskContext.of("NexonApiWorker", "Process", jobId.toString())
        return executor.executeOrDefault({
            processApiRequest(payload, jobId)
        }, ConsumeResult.Ack, context)
    }

    private fun processApiRequest(payload: Map<*, *>, jobId: UUID): ConsumeResult {
        val ocid = payload["ocid"].toString()
        val userIgn = payload["userIgn"].toString()
        val eventType = payload["eventType"].toString()
        val presetNo = (payload["presetNo"] as Number).toInt()

        log.info("[jobId={}] Processing API request: eventType={}", jobId, eventType)

        val equipmentResponse = equipmentFetchProvider.fetchWithCache(ocid)
        val snapshotData = objectMapper.writeValueAsBytes(equipmentResponse)

        val objectKey = generateObjectKey(jobId)
        val snapshot = CalculationSnapshot(
            snapshotId = UUID.randomUUID(),
            jobId = jobId,
            objectKey = objectKey,
            storageType = "LOCAL",
            characterId = ocid,
            presetNo = presetNo,
            expiresAt = Instant.now().plusSeconds(86400),
        )

        val result = snapshotStore.put(snapshot, snapshotData)

        val inputItems = (equipmentResponse.itemEquipment ?: emptyList()).map { item ->
            val itemMap = objectMapper.convertValue(item, Map::class.java) as Map<*, *>
            converter.convertItem(itemMap)
        }
        val calcInput = CalculationInput(
            jobId = jobId.toString(),
            userIgn = userIgn,
            characterClass = equipmentResponse.characterClass ?: "",
            presetNo = presetNo,
            items = inputItems,
        )
        calculationInputPort.save(calcInput)

        val snapshotEntity = CalculationSnapshotEntity(
            snapshotId = snapshot.snapshotId,
            jobId = jobId,
            objectKey = objectKey,
            storageType = "LOCAL",
            characterId = ocid,
            presetNo = presetNo,
            compressedSize = result.compressedSize,
            originalSize = snapshotData.size.toLong(),
            hash = result.hash,
            expiresAt = snapshot.expiresAt,
        )

        jobService.saveSnapshotAndMarkReady(snapshotEntity, jobId, objectKey)

        log.info("[jobId={}] API request processed, snapshot saved: {}", jobId, objectKey)
        return ConsumeResult.Ack
    }

    private fun generateObjectKey(jobId: UUID): String {
        val now = Instant.now()
        val zoned = now.atZone(ZoneOffset.UTC)
        val datePath = "%04d/%02d/%02d".format(zoned.year, zoned.monthValue, zoned.dayOfMonth)
        return "snapshots/$datePath/$jobId.gz"
    }
}
