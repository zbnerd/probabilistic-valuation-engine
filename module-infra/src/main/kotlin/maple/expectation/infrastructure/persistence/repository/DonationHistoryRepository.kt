package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.v2.DonationHistory
import org.springframework.data.jpa.repository.JpaRepository

interface DonationHistoryRepository : JpaRepository<DonationHistory, Long> {

    // 🔍 멱등성 검사: 이미 처리된 요청인지 확인하는 메서드
    fun existsByRequestId(requestId: String): Boolean
}
