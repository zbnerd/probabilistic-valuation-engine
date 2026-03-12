package maple.expectation.infrastructure.adapter

import maple.expectation.core.port.out.PopularCharacterTrackerPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * No-op PopularCharacterTrackerPort 구현체
 *
 * Redis 제거 후 인기 캐릭터 추적 기능 비활성화 상태.
 * V4 컨트롤러 정상 시작을 위해 빈 등록만 수행.
 */
@Component
class PopularCharacterTrackerAdapter : PopularCharacterTrackerPort {

    override fun getYesterdayTopCharacters(limit: Int): List<String> {
        log.debug("[PopularCharacterTracker] No-op implementation - returning empty list")
        return emptyList()
    }

    override fun recordAccess(userIgn: String) {
        log.debug("[PopularCharacterTracker] No-op implementation - ignoring access record for: {}", userIgn)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PopularCharacterTrackerAdapter::class.java)
    }
}
