package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val coordinator: CalculatorChunkProcessingCoordinator,
    @Value("\${calculator.kafka.consumer-max-retries:3}") private val maxRetries: Int,
    @Value("\${calculator.kafka.consumer-retry-backoff-ms:500}") private val retryBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)

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
        processWithRetry(event, acknowledgment, label = "Consumer")
    }

    @KafkaListener(
        topics = ["\${calculator.kafka.urgent-snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.urgent-consumer-group-id}",
    )
    fun consumeUrgent(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[URGENT] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )
        processWithRetry(event, acknowledgment, label = "URGENT")
    }

    /**
     * Processes the event with bounded retry. On exhaustion, rethrows so Spring Kafka
     * DefaultErrorHandler routes the message to DLT via DeadLetterPublishingRecoverer
     * (configured in module-infra/.../config/KafkaConsumerConfig.kt).
     *
     * runBlocking bridges the suspend `coordinator.handle` call from this non-suspend
     * listener method. We keep the listener non-suspend for consistency with the
     * existing pattern; migrating to a `suspend` `@KafkaListener` (Spring Kafka 2.8+
     * CoroutineKafkaListener) is a separate refactor. Blocking is bounded by
     * maxRetries × retryBackoffMs.
     */
    private fun processWithRetry(event: SnapshotChunkReadyEvent, acknowledgment: Acknowledgment, label: String) {
        // If runBlocking throws (retries exhausted), the exception propagates to Spring Kafka
        // DefaultErrorHandler → DLT. ACK is intentionally skipped in that case.
        runBlocking {
            var attempt = 0
            while (true) {
                try {
                    coordinator.handle(event)
                    return@runBlocking
                } catch (e: CancellationException) {
                    // Per Kotlin coroutine convention: never swallow CancellationException.
                    // Propagates to runBlocking → Spring (redelivery, not DLT).
                    throw e
                } catch (e: Exception) {
                    attempt++
                    if (attempt > maxRetries) {
                        log.error(
                            "[{}] Exhausted {} retries, propagating to DLT: runId={} chunkId={}",
                            label, maxRetries, event.runId, event.chunkId, e,
                        )
                        throw e
                    }
                    log.warn(
                        "[{}] Retry {}/{}: runId={} chunkId={}",
                        label, attempt, maxRetries, event.runId, event.chunkId, e,
                    )
                    delay(retryBackoffMs)
                }
            }
        }
        runCatching { acknowledgment.acknowledge() }
            .onFailure { log.warn("[{}] ACK failed: runId={} chunkId={}", label, event.runId, event.chunkId) }
    }
}
