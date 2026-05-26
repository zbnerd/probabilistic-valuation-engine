package maple.externalapi.snapshot.event

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import java.util.concurrent.CompletableFuture

interface SnapshotChunkEventPublisher {
    fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<Void>
    fun publishRunCompleted(event: SnapshotRunCompletedEvent): CompletableFuture<Void>
    fun publishRunFailed(event: SnapshotRunFailedEvent): CompletableFuture<Void>
}
