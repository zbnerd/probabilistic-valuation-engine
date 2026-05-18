package maple.externalapi.snapshot.event

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class NoOpSnapshotChunkEventPublisher : SnapshotChunkEventPublisher {
    private val log = LoggerFactory.getLogger(NoOpSnapshotChunkEventPublisher::class.java)

    override fun publishChunkReady(event: SnapshotChunkReadyEvent): CompletableFuture<Void> {
        log.debug("[Event] NoOp: chunk ready runId={} endpoint={} chunkId={}", event.runId, event.endpoint, event.chunkId)
        return CompletableFuture.completedFuture(null)
    }

    override fun publishRunCompleted(event: SnapshotRunCompletedEvent): CompletableFuture<Void> {
        log.debug("[Event] NoOp: run completed runId={} endpoint={}", event.runId, event.endpoint)
        return CompletableFuture.completedFuture(null)
    }

    override fun publishRunFailed(event: SnapshotRunFailedEvent): CompletableFuture<Void> {
        log.debug("[Event] NoOp: run failed runId={} endpoint={}", event.runId, event.endpoint)
        return CompletableFuture.completedFuture(null)
    }
}
