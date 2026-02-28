package maple.expectation.core.port.out

/**
 * Cache Warmup Port - 캐시 웜업을 위한 인터페이스
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>module-app/service/v4/EquipmentExpectationServiceV4 - 장비 기대값 서비스 구현
 * </ul>
 */
interface CacheWarmupPort {
    /**
     * 캐시 웜업 수행
     *
     * @param userIgn 캐릭터 닉네임
     * @param force 캐시 무시 여부
     */
    fun warmup(userIgn: String, force: Boolean)
}
