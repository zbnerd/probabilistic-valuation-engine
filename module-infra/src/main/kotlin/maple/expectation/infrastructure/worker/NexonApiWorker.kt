package maple.expectation.infrastructure.worker

import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.QueueNames
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.queue.pgmq.NexonApiRequestMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class NexonApiWorker(
    private val pgmqClient: PgmqClient,
    private val nexonApiClient: NexonApiClient,
    private val snapshotStore: SnapshotObjectStore,
    private val jobService: CalculationJobService,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${pgmq.worker.nexon-api.polling-interval-ms:100}")
    fun processMessages() {
        val context = TaskContext.of("NexonApiWorker", "Poll", "request_queue")

        executor.executeVoid({
            val messages = pgmqClient.read(
                QueueNames.NEXON_API_REQUEST,
                NexonApiRequestMessage::class.java,
                10,
                120
            )

            for (message in messages) {
                processSingle(message)
            }
        }, context)
    }

    private fun processSingle(message: PgmqMessage<NexonApiRequestMessage>) {
        val request = message.payload
        val jobId = request.jobId
        val context = TaskContext.of("NexonApiWorker", "Process", jobId.toString())

        val success = executor.executeOrDefault({
            log.info("[jobId={}] Processing API request: eventType={}", jobId, request.eventType)

            val equipmentResponse = nexonApiClient.getItemDataByOcid(request.ocid).join()
            val snapshotData = objectMapper.writeValueAsBytes(equipmentResponse)

            val objectKey = generateObjectKey(jobId)
            val snapshot = CalculationSnapshot(
                snapshotId = UUID.randomUUID(),
                jobId = jobId,
                objectKey = objectKey,
                storageType = "LOCAL",
                characterId = request.ocid,
                presetNo = request.presetNo,
                expiresAt = Instant.now().plusSeconds(86400)
            )

            val result = snapshotStore.put(snapshot, snapshotData)

            val snapshotEntity = CalculationSnapshotEntity(
                snapshotId = snapshot.snapshotId,
                jobId = jobId,
                objectKey = objectKey,
                storageType = "LOCAL",
                characterId = request.ocid,
                presetNo = request.presetNo,
                compressedSize = result.compressedSize,
                originalSize = snapshotData.size.toLong(),
                hash = result.hash,
                expiresAt = snapshot.expiresAt
            )

            jobService.saveSnapshotAndMarkReady(snapshotEntity, jobId, objectKey)

            pgmqClient.archive(QueueNames.NEXON_API_REQUEST, message.messageId)
            log.info("[jobId={}] API request processed, snapshot saved: {}", jobId, objectKey)
            true
        }, false, context)

        if (!success) {
            jobService.handleApiFailure(jobId, "API_ERROR", "Failed to process API request")
            pgmqClient.archive(QueueNames.NEXON_API_REQUEST, message.messageId)
        }
    }

    private fun generateObjectKey(jobId: UUID): String {
        val now = Instant.now()
        val zoned = now.atZone(ZoneOffset.UTC)
        val datePath = "%04d/%02d/%02d".format(zoned.year, zoned.monthValue, zoned.dayOfMonth)
        return "snapshots/$datePath/${jobId}.gz"
    }
}
