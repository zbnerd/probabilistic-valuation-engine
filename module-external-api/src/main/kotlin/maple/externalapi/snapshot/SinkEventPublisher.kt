package maple.externalapi.snapshot

import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher

/**
 * Adapts every publisher call to one required [CompletableFuture]. Both a
 * synchronous send exception and an asynchronously failed send remain
 * exceptional so lifecycle finalization cannot mistake either for success.
 */
class SinkEventPublisher(
    private val publisher: SnapshotChunkEventPublisher,
) {
    fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<Void> =
        publish { publisher.publishChunkReady(event) }

    fun publishRunCompleted(event: SnapshotRunCompletedEvent): CompletableFuture<Void> =
        publish { publisher.publishRunCompleted(event) }

    fun publishRunFailed(event: SnapshotRunFailedEvent): CompletableFuture<Void> =
        publish { publisher.publishRunFailed(event) }

    private fun publish(send: () -> CompletableFuture<*>): CompletableFuture<Void> =
        runCatching { send().thenApply<Void> { null } }
            .getOrElse { failure -> CompletableFuture.failedFuture(failure) }
}
