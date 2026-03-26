package maple.expectation.core.calculator.port

/**
 * Outbound Port: 스타포스 기대값 조회 인터페이스
 *
 * Port-Based Architecture (ADR-004):
 * - Core 도메인이 필요로 하는 스타포스 기대값 조회 기능을 정의
 * - module-app가 이 인터페이스를 구현하는 Adapter 제공
 * - 의존성 방향: app → core ← infra
 *
 * 성능 최적화 (2026-03-23):
 * - BigDecimal → Double로 변경하여 계산 비용 절감
 * - 조회 결과는 Double로 반환
 *
 * @see maple.expectation.service.v2.starforce.StarforceLookupTable Java 인터페이스 (module-app)
 */
interface StarforceLookupPort {
    /**
     * 스타포스 기대 비용 조회 (기본 옵션: 스타캐치 O, 썬데이 O, 할인 O, 파괴방지 X)
     * @param currentStar 현재 스타포스 (0~30)
     * @param targetStar 목표 스타포스 (currentStar ~ 30)
     * @param itemLevel 아이템 레벨
     * @return 기대 비용 (메소)
     */
    fun getExpectedCost(currentStar: Int, targetStar: Int, itemLevel: Int): Double

    /**
     * 레벨별 최대 스타포스 조회
     * @param itemLevel 아이템 레벨
     * @return 최대 스타포스 수
     */
    fun getMaxStarForLevel(itemLevel: Int): Int

    /**
     * 특정 스타에서 성공 확률 조회
     * @param currentStar 현재 스타포스 (0~29)
     * @return 성공 확률 (0.0 ~ 1.0)
     */
    fun getSuccessProbability(currentStar: Int): Double

    /**
     * 특정 스타에서 파괴 확률 조회
     * @param currentStar 현재 스타포스 (0~29)
     * @return 파괴 확률 (0.0 ~ 1.0), 파괴 없으면 0
     */
    fun getDestroyProbability(currentStar: Int): Double

    /**
     * 단일 스타 강화 비용 조회
     * @param currentStar 현재 스타포스 (0~29)
     * @param itemLevel 아이템 레벨
     * @return 1회 강화 비용 (메소)
     */
    fun getSingleEnhanceCost(currentStar: Int, itemLevel: Int): Double

    /**
     * 옵션별 기대 비용 계산
     * @param currentStar 현재 스타
     * @param targetStar 목표 스타
     * @param itemLevel 아이템 레벨
     * @param useStarCatch 스타캐치 사용 여부 (성공률 1.05배)
     * @param useSundayMaple 썬데이메이플 적용 여부 (파괴율 30% 감소, 21성 미만만)
     * @param useDiscount 30% 할인 적용 여부
     * @param useDestroyPrevention 파괴방지 사용 여부 (15-17성만, 비용 200% 추가)
     * @return 기대 비용
     */
    fun getExpectedCost(
        currentStar: Int,
        targetStar: Int,
        itemLevel: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDiscount: Boolean,
        useDestroyPrevention: Boolean,
    ): Double

    /**
     * 기대 파괴 횟수 계산
     * @param currentStar 현재 스타포스
     * @param targetStar 목표 스타포스
     * @param useStarCatch 스타캐치 사용 여부
     * @param useSundayMaple 썬데이메이플 적용 여부
     * @param useDestroyPrevention 파괴방지 사용 여부 (15-17성)
     * @return 기대 파괴 횟수
     */
    fun getExpectedDestroyCount(
        currentStar: Int,
        targetStar: Int,
        useStarCatch: Boolean,
        useSundayMaple: Boolean,
        useDestroyPrevention: Boolean,
    ): Double

    /**
     * 초기화 (서버 시작 시 호출)
     * Pre-compute all starforce expected values for faster lookup.
     */
    fun initialize()

    /**
     * 초기화 완료 여부 (Health Check용)
     * @return true if initialized
     */
    fun isInitialized(): Boolean
}
