package maple.expectation.infrastructure.nexon.outbox

import maple.expectation.domain.v2.NexonApiOutbox
import maple.expectation.domain.v2.NexonApiOutbox.OutboxStatus
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Nexon API Outbox 조회 Facade (내부 호출 AOP 문제 해결용)
 *
 * <p>NexonApiOutboxProcessor의 fetchAndLock()를 분리하여 Spring AOP 프록시가 정상 작동하도록 함.
 *
 * <h3>분리 사유</h3>
 *
 * <ul>
 *   <li>동일 클래스 내부 메서드 호출 시 @Transactional 무시 문제 해결
 *   <li>Facade 패턴으로 트랜잭션 경계 명확화
 *   <li>단일 책임 원칙: 조회 로직과 처리 로직 분리
 * </ul>
 *
 * @see maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
 * @see NexonApiOutboxRepository
 */
@Service
class NexonApiOutboxFetchFacade(
    private val outboxRepository: NexonApiOutboxRepository,
    private val properties: OutboxProperties
) {

    private val log = LoggerFactory.getLogger(NexonApiOutboxFetchFacade::class.java)

    /**
     * Phase 1: SKIP LOCKED 조회 + markProcessing (단일 트랜잭션)
     *
     * <p>트랜잭션 종료와 함께 SKIP LOCKED 해제되지만, 상태가 PROCESSING으로 변경되어 다른 인스턴스가 재조회하지 않음
     *
     * <h4>인덱스 활용</h4>
     *
     * <p>idx_pending_poll (status, next_retry_at, id) 복합 인덱스 사용
     *
     * @return 잠긴 Outbox 항목 목록
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun fetchAndLock(): List<NexonApiOutbox> {
        val statuses = listOf(OutboxStatus.PENDING, OutboxStatus.FAILED).toMutableList() as java.util.List<OutboxStatus>
        val pending = outboxRepository.findPendingWithLock(
            statuses,
            LocalDateTime.now(),
            PageRequest.of(0, properties.batchSize)
        )

        for (entry in pending) {
            entry.markProcessing(properties.instanceId)
        }

        return outboxRepository.saveAll(pending)
    }
}
