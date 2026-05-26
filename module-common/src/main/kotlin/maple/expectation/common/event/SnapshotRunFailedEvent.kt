package maple.expectation.common.event

import java.time.Instant

data class SnapshotRunFailedEvent(
    val eventId: String,
    val eventType: String = "SNAPSHOT_RUN_FAILED",
    val schemaVersion: Int = 1,
    val runId: String,
    val endpoint: String,
    val errorMessage: String,
    val createdAt: Instant,
) {
    fun kafkaKey(): String = "$runId:$endpoint"
}
