package maple.externalapi.cleanup

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Component
@ConditionalOnProperty(name = ["external-api.cleanup.consumed.enabled"], havingValue = "true")
class ConsumedChunkCleanupScheduler(
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.store.base-path:../data}") private val basePath: String,
    @Value("\${external-api.cleanup.consumed.max-pending:10000}") private val maxPending: Int, // 10,000 pending threshold
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pendingDeletions = ConcurrentLinkedQueue<ChunkConsumedEvent>()
    private val pendingCount = AtomicInteger(0)

    @KafkaListener(
        topics = ["\${external-api.kafka.chunk-consumed-topic}"],
        groupId = "\${external-api.cleanup.consumed.consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
    ) {
        val event = runCatching {
            objectMapper.readValue(message, ChunkConsumedEvent::class.java)
        }.getOrElse { ex ->
            log.warn("[ConsumedChunkCleanup] failed to parse event: {}", ex.message)
            acknowledgment.acknowledge()
            return
        }

        // O(1) bound check via AtomicInteger
        if (pendingCount.incrementAndGet() > maxPending) {
            pendingDeletions.poll()
            pendingCount.decrementAndGet()
            log.warn("[ConsumedChunkCleanup] pending queue at capacity ({}), dropped oldest", maxPending)
        }
        pendingDeletions.add(event)
        log.debug("[ConsumedChunkCleanup] queued: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)

        acknowledgment.acknowledge()
    }

    @Scheduled(fixedDelayString = "\${external-api.cleanup.consumed.interval-ms:3600000}") // 3,600,000 ms = 1 hour
    fun scheduledCleanup() {
        cleanup()
    }

    fun cleanup() {
        val batch = mutableListOf<ChunkConsumedEvent>()
        while (true) {
            val event = pendingDeletions.poll() ?: break
            pendingCount.decrementAndGet()
            batch.add(event)
        }
        if (batch.isEmpty()) return

        val start = System.nanoTime()
        var deletedCount = 0
        var failedCount = 0

        batch.forEach { event ->
            if (deleteFile(event.objectKey)) deletedCount++ else failedCount++
            event.sourceObjectKey?.let {
                if (deleteFile(it)) deletedCount++ else failedCount++
            }
        }

        val durationMs = (System.nanoTime() - start) / 1_000_000
        log.info(
            "[ConsumedChunkCleanup] batch complete: chunks={} deleted={} failed={} durationMs={}",
            batch.size, deletedCount, failedCount, durationMs,
        )
    }

    internal fun deleteFile(objectKey: String): Boolean {
        val path = Paths.get(basePath, objectKey)
        return try {
            val deleted = Files.deleteIfExists(path)
            if (deleted) {
                log.info("[ConsumedChunkCleanup] deleted: {}", objectKey)
            } else {
                log.debug("[ConsumedChunkCleanup] already gone: {}", objectKey)
            }
            deleted
        } catch (ex: IOException) {
            log.error("[ConsumedChunkCleanup] delete failed (IO): {} - {}", objectKey, ex.message, ex)
            throw ex
        } catch (ex: SecurityException) {
            log.error("[ConsumedChunkCleanup] delete failed (security): {} - {}", objectKey, ex.message, ex)
            throw ex
        }
    }

    override val lifecyclePhase: Int = 200

    override fun stopLifecycle() {
        // No executor to close — cleanup is synchronous
    }

    @PreDestroy
    fun shutdown() {
        cleanup()
    }
}
