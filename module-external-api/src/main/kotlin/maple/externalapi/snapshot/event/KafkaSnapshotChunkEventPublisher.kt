package maple.externalapi.snapshot.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate

class KafkaSnapshotChunkEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val chunkReadyTopic: String,
    private val runCompletedTopic: String,
    private val runFailedTopic: String,
) : SnapshotChunkEventPublisher {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkEventPublisher::class.java)

    override fun publishChunkReady(event: SnapshotChunkReadyEvent) {
        val key = "${event.runId}:${event.endpoint}:${event.chunkId}"
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(chunkReadyTopic, key, payload).whenComplete { _, ex ->
            if (ex != null) {
                log.warn("[Event] failed chunk-ready: runId={} endpoint={} chunkId={} error={}", event.runId, event.endpoint, event.chunkId, ex.message)
            } else {
                log.info("[Event] published chunk-ready: runId={} endpoint={} chunkId={}", event.runId, event.endpoint, event.chunkId)
            }
        }
    }

    override fun publishRunCompleted(event: SnapshotRunCompletedEvent) {
        val key = "${event.runId}:${event.endpoint}"
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(runCompletedTopic, key, payload).whenComplete { _, ex ->
            if (ex != null) {
                log.warn("[Event] failed run-completed: runId={} endpoint={} error={}", event.runId, event.endpoint, ex.message)
            } else {
                log.info("[Event] published run-completed: runId={} endpoint={} chunks={}", event.runId, event.endpoint, event.chunkCount)
            }
        }
    }

    override fun publishRunFailed(event: SnapshotRunFailedEvent) {
        val key = "${event.runId}:${event.endpoint}"
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(runFailedTopic, key, payload).whenComplete { _, ex ->
            if (ex != null) {
                log.warn("[Event] failed run-failed: runId={} endpoint={} error={}", event.runId, event.endpoint, ex.message)
            } else {
                log.info("[Event] published run-failed: runId={} endpoint={}", event.runId, event.endpoint)
            }
        }
    }
}
