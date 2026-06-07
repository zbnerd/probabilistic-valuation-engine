package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.ChunkConsumedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Event-driven inbox for synchronizer's CHUNK_CONSUMED events.
 * Tracer bullet: consume + queue + ack only. Overflow, drain, autoStart gate
 * added in subsequent TDD cycles.
 */
@Component
class ConsumedChunkInbox(
    private val objectMapper: ObjectMapper,
    private val properties: InboxProperties,
) {
    private val log = LoggerFactory.getLogger(ConsumedChunkInbox::class.java)
    private val queue = ConcurrentLinkedQueue<ChunkConsumedEvent>()

    @KafkaListener(
        topics = ["\${cleanup-inbox.topic}"],
        groupId = "\${cleanup-inbox.consumer-group}",
        autoStartup = "\${cleanup-inbox.auto-start:true}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, ChunkConsumedEvent::class.java)
        queue.add(event)
        log.debug("[Inbox] queued: runId={} chunkId={}", event.runId, event.chunkId)
        acknowledgment.acknowledge()
    }

    fun size(): Int = queue.size

    fun drain(): List<ChunkConsumedEvent> {
        val out = mutableListOf<ChunkConsumedEvent>()
        while (true) {
            val event = queue.poll() ?: break
            out.add(event)
        }
        return out
    }
}
