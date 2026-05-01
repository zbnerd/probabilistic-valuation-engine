package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.core.port.out.OutboxEvent
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxEventPortAdapter(
    private val repo: OutboxEventRepository,
) : OutboxEventPort {

    @Transactional(value = "transactionManager", readOnly = false)
    override fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean = repo.insertIfAbsent(UUID.randomUUID(), eventType, jobId, payload) > 0

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findUnpublished(limit: Int): List<OutboxEvent> = repo.findUnpublished(limit, PageRequest.of(0, limit)).map {
        OutboxEvent(it.eventId, it.eventType, it.jobId, it.payload, it.published, it.publishAttempts)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun markPublished(eventId: UUID) {
        repo.markPublished(eventId)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun markAllPublished(eventIds: List<UUID>) {
        if (eventIds.isEmpty()) return
        repo.markAllPublished(eventIds)
    }

    @Transactional(value = "transactionManager", readOnly = false)
    override fun incrementPublishAttempts(eventId: UUID) {
        repo.incrementPublishAttempts(eventId)
    }
}
