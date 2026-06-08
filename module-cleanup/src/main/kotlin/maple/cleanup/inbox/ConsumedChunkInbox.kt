package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.ChunkConsumedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Event-driven inbox for synchronizer's CHUNK_CONSUMED events.
 * Replaces the old ConsumedChunkCleanupScheduler in module-external-api.
 *
 * The @KafkaListener populates the in-memory queue. The @Scheduled drain is
 * gone — Airflow now triggers /api/internal/cleanup/inbox every 1h.
 *
 * Bound: maxPending (default 10,000). Overflow drops oldest, increments dropped counter.
 * `autoStart=false` makes consume() a no-op (used during migration).
 */
@Component
class ConsumedChunkInbox(
    private val objectMapper: ObjectMapper,
    private val properties: InboxProperties,
) {
    private val log = LoggerFactory.getLogger(ConsumedChunkInbox::class.java)
    private val queue = ConcurrentLinkedQueue<ChunkConsumedEvent>()
    private val pendingCount = AtomicInteger(0)
    private val dropped = AtomicLong(0)
    private val skipped = AtomicLong(0)

    @KafkaListener(
        topics = ["\${cleanup-inbox.topic}"],
        groupId = "\${cleanup-inbox.consumer-group}",
        autoStartup = "\${cleanup-inbox.auto-start:true}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        if (!properties.autoStart) {
            acknowledgment.acknowledge()
            return
        }
        val event = runCatching {
            objectMapper.readValue(message, ChunkConsumedEvent::class.java)
        }.getOrElse { ex ->
            log.warn("[Inbox] failed to parse event: {}", ex.message)
            skipped.incrementAndGet()
            acknowledgment.acknowledge()
            return
        }
        // O(1) bound check via AtomicInteger
        if (pendingCount.incrementAndGet() > properties.maxPending) {
            queue.poll()
            pendingCount.decrementAndGet()
            dropped.incrementAndGet()
            log.warn("[Inbox] pending queue at capacity ({}), dropped oldest", properties.maxPending)
        }
        queue.add(event)
        log.debug("[Inbox] queued: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
        acknowledgment.acknowledge()
    }

    fun size(): Int = pendingCount.get()
    fun dropped(): Long = dropped.get()
    fun skipped(): Long = skipped.get()

    fun drain(): List<ChunkConsumedEvent> {
        val out = mutableListOf<ChunkConsumedEvent>()
        while (true) {
            val event = queue.poll() ?: break
            pendingCount.decrementAndGet()
            out.add(event)
        }
        return out
    }
}
