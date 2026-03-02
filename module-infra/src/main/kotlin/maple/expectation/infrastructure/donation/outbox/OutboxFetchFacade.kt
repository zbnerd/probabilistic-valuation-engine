package maple.expectation.infrastructure.donation.outbox

import maple.expectation.domain.v2.DonationOutbox
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * Outbox 조회 Facade (내부 호출 AOP 문제 해결용)
 *
 * OutboxProcessor의 fetchAndLock()를 분리하여 Spring AOP 프록시가 정상 작동하도록 함.
 *
 * <h3>분리 사유</h3>
 * - 동일 클래스 내부 메서드 호출 시 @Transactional 무시 문제 해결
 * - Facade 패턴으로 트랜잭션 경계 명확화
 * - 단일 책임 원칙: 조회 로직과 처리 로직 분리
 *
 * @see OutboxProcessor
 * @see DonationOutboxRepository
 */
@Service
class OutboxFetchFacade(
    private val outboxRepository: DonationOutboxRepository,
    private val properties: OutboxProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Phase 1: SKIP LOCKED 조회 + markProcessing (단일 트랜잭션)
     *
     * 트랜잭션 종료와 함께 SKIP LOCKED 해제되지만, 상태가 PROCESSING으로 변경되어
     * 다른 인스턴스가 재조회하지 않음
     *
     * @return 잠긴 Outbox 항목 목록
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun fetchAndLock(): List<DonationOutbox> {
        val pending = outboxRepository.findPendingWithLock(
            listOf(OutboxStatus.PENDING, OutboxStatus.FAILED),
            java.time.LocalDateTime.now(),
            PageRequest.of(0, properties.batchSize)
        )

        pending.forEach { entry ->
            entry.markProcessing(properties.instanceId)
        }

        val locked = outboxRepository.saveAll(pending)

        if (locked.isNotEmpty()) {
            log.info("[OutboxFetchFacade] SKIP LOCKED 조회 완료: {}건", locked.size)
        }

        return locked
    }
}
