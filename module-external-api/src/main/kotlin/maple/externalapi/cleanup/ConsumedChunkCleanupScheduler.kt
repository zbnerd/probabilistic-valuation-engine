package maple.externalapi.cleanup

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

@Component
@ConditionalOnProperty(name = ["external-api.cleanup.consumed.enabled"], havingValue = "true")
class ConsumedChunkCleanupScheduler(
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.store.base-path:../data}")
    private val basePath: String,
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pendingDeletions = ConcurrentLinkedQueue<ChunkConsumedEvent>()
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @KafkaListener(
        topics = ["\${external-api.kafka.chunk-consumed-topic}"],
        groupId = "\${external-api.cleanup.consumed.consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
    ) {
        runCatching {
            val event = objectMapper.readValue(message, ChunkConsumedEvent::class.java)
            pendingDeletions.add(event)
            log.debug("[ConsumedChunkCleanup] queued: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
        }.onFailure { ex ->
            log.warn("[ConsumedChunkCleanup] failed to parse event: {}", ex.message)
        }
        acknowledgment.acknowledge()
    }

    @Scheduled(fixedDelayString = "\${external-api.cleanup.consumed.interval-ms:3600000}")
    fun cleanup() {
        val batch = mutableListOf<ChunkConsumedEvent>()
        while (true) {
            val event = pendingDeletions.poll() ?: break
            batch.add(event)
        }
        if (batch.isEmpty()) return

        val start = System.nanoTime()
        var deletedCount = 0
        var failedCount = 0

        batch.forEach { event ->
            vtExecutor.submit {
                if (deleteFile(event.objectKey)) deletedCount++ else failedCount++
                event.sourceObjectKey?.let {
                    if (deleteFile(it)) deletedCount++ else failedCount++
                }
            }
        }

        val durationMs = (System.nanoTime() - start) / 1_000_000
        log.info("[ConsumedChunkCleanup] batch complete: chunks={} deleted={} failed={} durationMs={}", batch.size, deletedCount, failedCount, durationMs)
    }

    private fun deleteFile(objectKey: String): Boolean {
        val path = Paths.get(basePath, objectKey)
        return runCatching {
            val deleted = Files.deleteIfExists(path)
            if (deleted) {
                log.info("[ConsumedChunkCleanup] deleted: {}", objectKey)
            } else {
                log.debug("[ConsumedChunkCleanup] already gone: {}", objectKey)
            }
            deleted
        }.onFailure { ex ->
            log.warn("[ConsumedChunkCleanup] delete failed: {} - {}", objectKey, ex.message)
        }.getOrDefault(false)
    }

    override val lifecyclePhase: Int = 200

    override fun stopLifecycle() {
        vtExecutor.close()
    }
}
