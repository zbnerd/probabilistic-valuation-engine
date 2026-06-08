package maple.externalapi.snapshot.event

import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent

interface SnapshotChunkEventPublisher {
    fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<Void>
    fun publishRunCompleted(event: SnapshotRunCompletedEvent): CompletableFuture<Void>
    fun publishRunFailed(event: SnapshotRunFailedEvent): CompletableFuture<Void>
}
