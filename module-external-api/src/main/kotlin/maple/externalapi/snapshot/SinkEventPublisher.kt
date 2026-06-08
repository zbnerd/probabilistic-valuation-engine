package maple.externalapi.snapshot

import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory

/**
 * Wraps [SnapshotChunkEventPublisher] with a fire-and-forget pattern.
 * Send failures are logged but do not propagate — the sink must finish
 * draining its queue even if the broker is down.
 */
class SinkEventPublisher(
    private val publisher: SnapshotChunkEventPublisher,
) {
    private val log = LoggerFactory.getLogger(SinkEventPublisher::class.java)

    fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<*> = publishSafely("SnapshotChunkReady") { publisher.publishChunkReady(event) }

    fun publishRunCompleted(event: SnapshotRunCompletedEvent): CompletableFuture<*> = publishSafely("RunCompleted") { publisher.publishRunCompleted(event) }

    fun publishRunFailed(event: SnapshotRunFailedEvent): CompletableFuture<*> = publishSafely("RunFailed") { publisher.publishRunFailed(event) }

    private fun publishSafely(
        name: String,
        send: () -> CompletableFuture<*>,
    ): CompletableFuture<*> = try {
        send().exceptionally { ex ->
            log.warn("[SinkEventPublisher] {} send failed: {}", name, ex.message, ex)
            null
        }
    } catch (ex: Exception) {
        log.warn("[SinkEventPublisher] {} send threw synchronously: {}", name, ex.message, ex)
        CompletableFuture.completedFuture(null)
    }
}
