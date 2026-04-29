package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.core.port.out.OutboxEvent
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class OutboxEventPortAdapter(
    private val repo: OutboxEventRepository,
) : OutboxEventPort {

    override fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean = repo.insertIfAbsent(UUID.randomUUID(), eventType, jobId, payload) > 0

    override fun findUnpublished(limit: Int): List<OutboxEvent> = repo.findUnpublished(limit, PageRequest.of(0, limit)).map {
        OutboxEvent(it.eventId, it.eventType, it.jobId, it.payload, it.published, it.publishAttempts)
    }

    override fun markPublished(eventId: UUID) {
        repo.markPublished(eventId)
    }

    override fun incrementPublishAttempts(eventId: UUID) {
        repo.incrementPublishAttempts(eventId)
    }
}
