package maple.externalapi.snapshot.event

import java.time.Instant

data class SnapshotRunCompletedEvent(
    val eventId: String,
    val eventType: String = "SNAPSHOT_RUN_COMPLETED",
    val schemaVersion: Int = 1,
    val runId: String,
    val endpoint: String,
    val manifestPath: String,
    val totalRecords: Int,
    val totalFailed: Int,
    val chunkCount: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val createdAt: Instant,
)
