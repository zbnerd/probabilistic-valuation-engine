package maple.expectation.core.port.out

/**
 * Popular Character Tracker Port - 인기 캐릭터 추적을 위한 인터페이스
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>module-app/service/v4/warmup/PopularCharacterTracker - 인기 캐릭터 트래커 구현
 * </ul>
 */
interface PopularCharacterTrackerPort {
    /**
     * 전날 인기 캐릭터 조회 (웜업용)
     *
     * @param limit 상위 N개
     * @return 전날 인기 캐릭터 목록
     */
    fun getYesterdayTopCharacters(limit: Int): List<String>

    /**
     * 캐릭터 접근 기록 (Auto Warmup)
     *
     * @param userIgn 캐릭터 IGN
     */
    fun recordAccess(userIgn: String)
}
