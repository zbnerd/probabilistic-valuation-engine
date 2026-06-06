package maple.externalapi.urgent

import maple.externalapi.artifact.UrgentChunkArtifactWriter
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.event.UrgentEventPublisher
import maple.externalapi.parser.UrgentOcidResponseParser
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
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
    private val ocidResponseParser: UrgentOcidResponseParser,
    private val chunkArtifactWriter: UrgentChunkArtifactWriter,
    private val eventPublisher: UrgentEventPublisher,
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
        val request = ocidResponseParser.parseRequest(message)
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
            val ocid = ocidResponseParser.extractOcid(ocidData)
            if (ocid == null) {
                log.info("[Urgent] OCID not found: userIgn={}", maskIgn(request.userIgn))
                return@thenComposeAsync publishNotFoundAsync(request.userIgn)
            }

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
            val objectKey = chunkArtifactWriter.writeChunk(
                runId = runId,
                endpointDir = endpointDir,
                record = SnapshotChunkRecord.Success(
                    key = key,
                    endpoint = endpointDir,
                    keyType = keyType,
                    httpStatus = 200,
                    fetchedAt = Instant.now(),
                    bodyBytes = data,
                ),
            )
            SnapshotChunkReadyEvent(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                endpoint = endpointDir,
                chunkId = "$endpointDir-part-000001",
                objectKey = objectKey,
                recordCount = 1,
                uncompressedBytes = data.size.toLong(),
                compressedBytes = -1L,
                createdAt = Instant.now(),
            )
        }, executor).thenCompose { event ->
            eventPublisher.publishChunkReady(event)
        }

    private fun publishNotFoundAsync(userIgn: String): CompletableFuture<Void> =
        eventPublisher.publishNotFound(
            userIgn = userIgn,
            reason = "OCID_NOT_FOUND",
            occurredAt = Instant.now(),
        )
}

data class UrgentCharacterRequest(
    val eventId: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: Instant,
)
