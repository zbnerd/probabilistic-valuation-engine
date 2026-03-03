package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.v2.NexonApiDlq
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional

/**
 * Dead Letter Queue Repository for Nexon API (Issue #333)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 *
 * <p>NexonApiOutbox 처리 실패 시 데이터를 저장하여 영구 손실 방지.
 *
 * <h3>Design Pattern</h3>
 *
 * <p>DonationDlqRepository 패턴을 따르며 Nexon API 특화
 *
 * @see maple.expectation.domain.v2.NexonApiDlq
 * @see maple.expectation.infrastructure.nexon.dlq.NexonApiDlqHandler
 */
interface NexonApiDlqRepository : JpaRepository<NexonApiDlq, Long> {

    /**
     * DLQ 목록 조회 (최신순 정렬, 페이징)
     *
     * @param pageable 페이징 정보
     * @return DLQ 목록
     */
    fun findAllByOrderByMovedAtDesc(pageable: Pageable): Page<NexonApiDlq>

    /**
     * requestId로 DLQ 조회
     *
     * @param requestId 요청 ID
     * @return DLQ 엔티티
     */
    fun findByRequestId(requestId: String): Optional<NexonApiDlq>

    /**
     * DLQ 총 건수
     *
     * @return 전체 DLQ 건수
     */
    @Query("SELECT COUNT(d) FROM NexonApiDlq d")
    fun countAll(): Long
}
