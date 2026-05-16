package maple.externalapi.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.GzipJsonlChunkWriter
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.event.SnapshotChunkReadyEvent
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(
    name = ["external-api.urgent.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class UrgentCharacterRequestConsumer(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${external-api.urgent.not-found-topic}")
    private val notFoundTopic: String,
    @Value("\${external-api.urgent.chunk-ready-topic}")
    private val urgentChunkReadyTopic: String,
    @Value("\${external-api.store.base-path}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${external-api.urgent.request-topic}"],
        groupId = "\${external-api.urgent.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val request = objectMapper.readValue(message, UrgentCharacterRequest::class.java)
        log.info("[Urgent] received: userIgn={}", maskIgn(request.userIgn))

        processUrgentCharacter(request)

        acknowledgment.acknowledge()
        log.info("[Urgent] completed: userIgn={}", maskIgn(request.userIgn))
    }

    private fun processUrgentCharacter(request: UrgentCharacterRequest) {
        val ocidData = clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.OCID_LOOKUP,
            request.userIgn,
        ).join()

        val ocidNode = objectMapper.readTree(ocidData).get("ocid")
        if (ocidNode == null || ocidNode.isNull) {
            log.info("[Urgent] OCID not found: userIgn={}", maskIgn(request.userIgn))
            publishNotFound(request.userIgn)
            return
        }

        val ocid = ocidNode.asText()
        log.info("[Urgent] OCID resolved: userIgn={}", maskIgn(request.userIgn))

        val basicFuture = clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.CHARACTER_BASIC,
            ocid,
        )
        val equipmentFuture = clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.ITEM_EQUIPMENT,
            ocid,
        )

        val basicData = basicFuture.join()
        val equipmentData = equipmentFuture.join()

        val runId = "urgent-${UUID.randomUUID()}"

        publishUrgentChunk(
            runId = runId,
            endpoint = ExternalApiEndpoint.CHARACTER_BASIC,
            userIgn = request.userIgn,
            data = basicData,
            keyType = "OCID",
        )
        publishUrgentChunk(
            runId = runId,
            endpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
            userIgn = request.userIgn,
            data = equipmentData,
            keyType = "OCID",
        )

        log.info(
            "[Urgent] data fetch complete: userIgn={}, runId={}",
            maskIgn(request.userIgn),
            runId,
        )
    }

    private fun publishUrgentChunk(
        runId: String,
        endpoint: ExternalApiEndpoint,
        userIgn: String,
        data: ByteArray,
        keyType: String,
    ) {
        val endpointDir = endpoint.storageSubDir()
        val chunksDir = Path.of(storeBasePath, "runs", runId, endpointDir, "chunks")
        Files.createDirectories(chunksDir)

        val writer = GzipJsonlChunkWriter(chunksDir, 1, 1, Long.MAX_VALUE, objectMapper)
        writer.append(
            SnapshotChunkRecord.Success(
                key = userIgn,
                endpoint = endpointDir,
                keyType = keyType,
                httpStatus = 200,
                fetchedAt = Instant.now(),
                bodyBytes = data,
            ),
        )
        val stats = writer.close()

        val objectKey = "runs/$runId/$endpointDir/${stats.path}"
        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            endpoint = endpointDir,
            chunkId = "part-000001",
            objectKey = objectKey,
            recordCount = stats.recordCount,
            uncompressedBytes = stats.uncompressedBytes,
            compressedBytes = stats.compressedBytes,
            createdAt = Instant.now(),
        )

        val eventJson = objectMapper.writeValueAsString(event)
        val key = "${event.runId}:${event.endpoint}:${event.chunkId}"
        kafkaTemplate.send(urgentChunkReadyTopic, key, eventJson).get(30, TimeUnit.SECONDS)

        log.info(
            "[Urgent] published chunk: endpoint={}, userIgn={}, objectKey={}",
            endpointDir,
            maskIgn(userIgn),
            objectKey,
        )
    }

    private fun publishNotFound(userIgn: String) {
        val event = mapOf(
            "userIgn" to userIgn,
            "reason" to "OCID_NOT_FOUND",
            "occurredAt" to Instant.now().toString(),
        )
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(notFoundTopic, userIgn, json)
        log.info("[Urgent] published not-found: userIgn={}", maskIgn(userIgn))
    }
}

data class UrgentCharacterRequest(
    val eventId: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: Instant,
)
