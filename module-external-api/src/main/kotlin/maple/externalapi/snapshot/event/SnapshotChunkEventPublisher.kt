package maple.externalapi.snapshot.event

interface SnapshotChunkEventPublisher {
    fun publishChunkReady(event: SnapshotChunkReadyEvent)
    fun publishRunCompleted(event: SnapshotRunCompletedEvent)
    fun publishRunFailed(event: SnapshotRunFailedEvent)
}
