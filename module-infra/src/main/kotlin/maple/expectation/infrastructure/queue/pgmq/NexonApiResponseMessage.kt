package maple.expectation.infrastructure.queue.pgmq

data class NexonApiResponseMessage(
    val eventType: String = "SNAPSHOT_READY",
    val jobId: java.util.UUID,
    val snapshotId: java.util.UUID,
    val objectKey: String,
    val characterId: String,
    val presetNo: Int = 1
)
