package maple.pipeline.artifact.inbox

import java.time.Instant
import maple.expectation.common.event.ChunkConsumedEvent

data class CleanupInboxEntry(
    val eventId: String,
    val topic: String,
    val partition: Int,
    val offset: Long,
    val receivedAt: Instant,
    val event: ChunkConsumedEvent,
)

sealed interface InboxPutResult {
    data object Created : InboxPutResult

    data object Replay : InboxPutResult

    data class IntegrityConflict(val eventId: String) : InboxPutResult
}
