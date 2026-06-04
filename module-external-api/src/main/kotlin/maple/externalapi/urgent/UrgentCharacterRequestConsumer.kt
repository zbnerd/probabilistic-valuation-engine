package maple.externalapi.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.GzipJsonlChunkWriter
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore

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
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    @Value("\${external-api.concurrency.urgent-max-concurrent:30}")
    maxConcurrent: Int,
    @Qualifier("urgentCharacterRequestExecutor") private val executor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val semaphore = Semaphore(maxConcurrent)

    @KafkaListener(
        topics = ["\${external-api.urgent.request-topic}"],
        groupId = "\${external-api.urgent.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val request = objectMapper.readValue(message, UrgentCharacterRequest::class.java)
        log.info("[Urgent] received: userIgn={}", maskIgn(request.userIgn))

        if (!semaphore.tryAcquire()) {
            log.warn("[Urgent] backpressure: semaphore exhausted, skipping userIgn={}", maskIgn(request.userIgn))
            acknowledgment.acknowledge()
            return
        }

        processUrgentCharacterAsync(request)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Urgent] failed: userIgn={}", maskIgn(request.userIgn), ex)
                } else {
                    log.info("[Urgent] completed: userIgn={}", maskIgn(request.userIgn))
                }
                semaphore.release()
                runCatching { acknowledgment.acknowledge() }
                    .onFailure { log.warn("[Urgent] ACK failed: userIgn={}", maskIgn(request.userIgn)) }
            }
    }

    private fun processUrgentCharacterAsync(request: UrgentCharacterRequest): CompletableFuture<Void> =
        clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.OCID_LOOKUP,
            request.userIgn,
        ).thenComposeAsync({ ocidData ->
            val ocidNode = objectMapper.readTree(ocidData).get("ocid")
            if (ocidNode == null || ocidNode.isNull) {
                log.info("[Urgent] OCID not found: userIgn={}", maskIgn(request.userIgn))
                return@thenComposeAsync publishNotFoundAsync(request.userIgn)
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

            CompletableFuture.allOf(basicFuture, equipmentFuture)
                .thenComposeAsync({
                    publishUrgentChunksAsync(
                        request = request,
                        ocid = ocid,
                        basicData = basicFuture.resultNow(),
                        equipmentData = equipmentFuture.resultNow(),
                    )
                }, executor)
        }, executor)

    private fun publishUrgentChunksAsync(
        request: UrgentCharacterRequest,
        ocid: String,
        basicData: ByteArray,
        equipmentData: ByteArray,
    ): CompletableFuture<Void> {
        val runId = "urgent-${UUID.randomUUID()}"

        return publishUrgentChunkAsync(runId, ExternalApiEndpoint.CHARACTER_BASIC, request.userIgn, ocid, basicData, "OCID")
            .thenCompose {
                publishUrgentChunkAsync(runId, ExternalApiEndpoint.ITEM_EQUIPMENT, request.userIgn, ocid, equipmentData, "OCID")
            }
            .thenAccept {
                log.info(
                    "[Urgent] data fetch complete: userIgn={}, runId={}",
                    maskIgn(request.userIgn),
                    runId,
                )
            }
    }

    private fun publishUrgentChunkAsync(
        runId: String,
        endpoint: ExternalApiEndpoint,
        userIgn: String,
        key: String,
        data: ByteArray,
        keyType: String,
    ): CompletableFuture<Void> =
        CompletableFuture.supplyAsync({
            val endpointDir = endpoint.storageSubDir()
            val chunksDir = Path.of(storeBasePath, "runs", runId, endpointDir, "chunks")
            Files.createDirectories(chunksDir)

            val writer = GzipJsonlChunkWriter(chunksDir, 1, 1, Long.MAX_VALUE, objectMapper)
            writer.append(
                SnapshotChunkRecord.Success(
                    key = key,
                    endpoint = endpointDir,
                    keyType = keyType,
                    httpStatus = 200,
                    fetchedAt = Instant.now(),
                    bodyBytes = data,
                ),
            )
            val stats = writer.close()

            val objectKey = "runs/$runId/$endpointDir/${stats.path}"
            SnapshotChunkReadyEvent(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                endpoint = endpointDir,
                chunkId = "$endpointDir-part-000001",
                objectKey = objectKey,
                recordCount = stats.recordCount,
                uncompressedBytes = stats.uncompressedBytes,
                compressedBytes = stats.compressedBytes,
                createdAt = Instant.now(),
            )
        }, executor).thenCompose { event ->
            val eventJson = objectMapper.writeValueAsString(event)
            kafkaTemplate.send(urgentChunkReadyTopic, event.kafkaKey(), eventJson).thenAccept {
                log.info(
                    "[Urgent] published chunk: endpoint={}, userIgn={}, objectKey={}",
                    event.endpoint,
                    maskIgn(userIgn),
                    event.objectKey,
                )
            }
        }

    private fun publishNotFoundAsync(userIgn: String): CompletableFuture<Void> {
        val event = mapOf(
            "userIgn" to userIgn,
            "reason" to "OCID_NOT_FOUND",
            "occurredAt" to Instant.now().toString(),
        )
        val json = objectMapper.writeValueAsString(event)
        return kafkaTemplate.send(notFoundTopic, userIgn, json)
            .thenAccept {
                log.info("[Urgent] published not-found: userIgn={}", maskIgn(userIgn))
            }
    }
}

data class UrgentCharacterRequest(
    val eventId: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: Instant,
)
