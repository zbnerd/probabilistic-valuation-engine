package maple.expectation.core.port.out

import java.util.UUID

data class OutboxEvent(
    val eventId: UUID,
    val eventType: String,
    val jobId: UUID,
    val payload: String?,
    val published: Boolean,
    val publishAttempts: Int,
)

interface OutboxEventPort {
    fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean
    fun findUnpublished(limit: Int): List<OutboxEvent>
    fun markPublished(eventId: UUID)
    fun incrementPublishAttempts(eventId: UUID)
}
