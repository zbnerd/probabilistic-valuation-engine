package maple.calculator.consumer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service

/**
 * Owns ACK/retry decisions for incoming snapshot chunks. The Kafka consumer
 * delegates here after deserialization so transport and policy are separate.
 *
 * `runBlocking` bridges the suspend `coordinator.handle` call from this
 * non-suspend dispatcher method. Blocking is bounded by
 * maxRetries × retryBackoffMs.
 */
@Service
class SnapshotDispatchService(
    private val coordinator: CalculatorChunkProcessingCoordinator,
    @Value("\${calculator.kafka.consumer-max-retries:3}") private val maxRetries: Int,
    @Value("\${calculator.kafka.consumer-retry-backoff-ms:500}") private val retryBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(SnapshotDispatchService::class.java)

    /**
     * Dispatches the chunk with bounded retry. On exhaustion, rethrows so Spring Kafka
     * DefaultErrorHandler routes the message to DLT via DeadLetterPublishingRecoverer
     * (configured in module-infra/.../config/KafkaConsumerConfig.kt).
     *
     * If dispatch throws (retries exhausted), the exception propagates to Spring Kafka
     * DefaultErrorHandler → DLT. ACK is intentionally skipped in that case.
     */
    fun dispatch(event: SnapshotChunkReadyEvent, ack: Acknowledgment?, label: String) {
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
        runCatching { ack?.acknowledge() }
            .onFailure { log.warn("[{}] ACK failed: runId={} chunkId={}", label, event.runId, event.chunkId) }
    }
}
