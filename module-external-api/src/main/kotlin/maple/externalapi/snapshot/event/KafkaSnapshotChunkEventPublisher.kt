package maple.externalapi.snapshot.event

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.metrics.SchedulerMetrics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.CompletableFuture

class KafkaSnapshotChunkEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val chunkReadyTopic: String,
    private val runCompletedTopic: String,
    private val runFailedTopic: String,
    private val schedulerMetrics: SchedulerMetrics,
) : SnapshotChunkEventPublisher {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkEventPublisher::class.java)

    override fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<Void> {
        val payload = objectMapper.writeValueAsString(event)
        return kafkaTemplate.send(chunkReadyTopic, event.kafkaKey(), payload)
            .thenAccept {
                schedulerMetrics.recordChunkPublished(event.recordCount)
                log.info(
                    "[Event] published chunk-ready: runId={} endpoint={} chunkId={}",
                    event.runId,
                    event.endpoint,
                    event.chunkId,
                )
            }
            .whenComplete { _, ex -> logPublishFailure(ex, "chunk-ready", event.runId, event.endpoint) }
    }

    override fun publishRunCompleted(event: SnapshotRunCompletedEvent): CompletableFuture<Void> {
        val payload = objectMapper.writeValueAsString(event)
        return kafkaTemplate.send(runCompletedTopic, event.kafkaKey(), payload)
            .thenAccept {
                log.info(
                    "[Event] published run-completed: runId={} endpoint={} chunks={}",
                    event.runId,
                    event.endpoint,
                    event.chunkCount,
                )
            }
            .whenComplete { _, ex -> logPublishFailure(ex, "run-completed", event.runId, event.endpoint) }
    }

    override fun publishRunFailed(event: SnapshotRunFailedEvent): CompletableFuture<Void> {
        val payload = objectMapper.writeValueAsString(event)
        return kafkaTemplate.send(runFailedTopic, event.kafkaKey(), payload)
            .thenAccept {
                log.info("[Event] published run-failed: runId={} endpoint={}", event.runId, event.endpoint)
            }
            .whenComplete { _, ex -> logPublishFailure(ex, "run-failed", event.runId, event.endpoint) }
    }

    private fun logPublishFailure(ex: Throwable?, eventName: String, runId: String, endpoint: String) {
        if (ex != null) {
            log.warn("[Event] failed to publish {}: runId={} endpoint={}: {}", eventName, runId, endpoint, ex.message)
        }
    }
}
