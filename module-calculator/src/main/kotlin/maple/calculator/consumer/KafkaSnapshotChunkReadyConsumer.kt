package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val coordinator: CalculatorChunkProcessingCoordinator,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        scope.launch {
            try {
                coordinator.handle(event)
                // ACK only on success — on failure, Kafka redelivers via DefaultErrorHandler → retry/DLQ
                runCatching { acknowledgment.acknowledge() }
                    .onFailure { log.warn("[Consumer] ACK failed: runId={} chunkId={}", event.runId, event.chunkId) }
            } catch (e: Exception) {
                log.error(
                    "[Consumer] chunk processing failed: runId={} chunkId={}",
                    event.runId, event.chunkId, e,
                )
                // Intentionally NOT ACKing — Kafka will redeliver. Coordinator is idempotent.
            }
        }
    }

    @KafkaListener(
        topics = ["\${calculator.kafka.urgent-snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.urgent-consumer-group-id}",
    )
    fun consumeUrgent(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received URGENT chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        scope.launch {
            try {
                coordinator.handle(event)
                runCatching { acknowledgment.acknowledge() }
                    .onFailure { log.warn("[Consumer] URGENT ACK failed: runId={} chunkId={}", event.runId, event.chunkId) }
            } catch (e: Exception) {
                log.error(
                    "[Consumer] URGENT chunk processing failed: runId={} chunkId={}",
                    event.runId, event.chunkId, e,
                )
                // Intentionally NOT ACKing — Kafka will redeliver. Coordinator is idempotent.
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        // Note: does NOT drain in-flight coroutines. Trade-off accepted because:
        // 1. coordinator.handle() is idempotent (checks existing results)
        // 2. Un-ACKed messages → Kafka redelivery on next startup
        log.info("[Consumer] Coroutine scope cancelled")
    }
}
