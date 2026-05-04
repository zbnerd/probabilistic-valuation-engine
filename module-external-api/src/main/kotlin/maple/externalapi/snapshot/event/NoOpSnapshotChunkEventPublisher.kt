package maple.externalapi.snapshot.event

import org.slf4j.LoggerFactory

class NoOpSnapshotChunkEventPublisher : SnapshotChunkEventPublisher {
    private val log = LoggerFactory.getLogger(NoOpSnapshotChunkEventPublisher::class.java)

    override fun publishChunkReady(event: SnapshotChunkReadyEvent) {
        log.debug("[Event] NoOp: chunk ready runId={} endpoint={} chunkId={}", event.runId, event.endpoint, event.chunkId)
    }

    override fun publishRunCompleted(event: SnapshotRunCompletedEvent) {
        log.debug("[Event] NoOp: run completed runId={} endpoint={}", event.runId, event.endpoint)
    }

    override fun publishRunFailed(event: SnapshotRunFailedEvent) {
        log.debug("[Event] NoOp: run failed runId={} endpoint={}", event.runId, event.endpoint)
    }
}
