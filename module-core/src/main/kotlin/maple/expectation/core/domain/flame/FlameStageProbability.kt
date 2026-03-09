package maple.expectation.core.domain.flame

/**
 * 환생의 불꽃 단계별 확률 분포
 *
 * 보스 장비와 일반 장비의 단계(stage) 확률이 상이하며, 불꽃 종류(POWERFUL / ETERNAL / ABYSS)에 따라 분포가 달라진다.
 */
object FlameStageProbability {

    // Boss equipment stage probabilities
    private val BOSS_POWERFUL = mapOf(3 to 0.20, 4 to 0.30, 5 to 0.36, 6 to 0.14)
    private val BOSS_ETERNAL = mapOf(4 to 0.29, 5 to 0.45, 6 to 0.25, 7 to 0.01)
    private val BOSS_ABYSS = mapOf(5 to 0.63, 6 to 0.34, 7 to 0.03)

    // Other equipment stage probabilities
    private val OTHER_POWERFUL = mapOf(1 to 0.20, 2 to 0.30, 3 to 0.36, 4 to 0.14)
    private val OTHER_ETERNAL = mapOf(2 to 0.29, 3 to 0.45, 4 to 0.25, 5 to 0.01)
    private val OTHER_ABYSS = mapOf(3 to 0.63, 4 to 0.34, 5 to 0.03)

    /**
     * Get stage probabilities based on equipment type and flame type
     *
     * @param bossDrop whether the equipment is boss drop
     * @param flameType the type of flame
     * @return map of stage to probability
     */
    @JvmStatic
    fun getStageProbs(bossDrop: Boolean, flameType: FlameType): Map<Int, Double> = when (flameType) {
        FlameType.POWERFUL -> if (bossDrop) BOSS_POWERFUL else OTHER_POWERFUL
        FlameType.ETERNAL -> if (bossDrop) BOSS_ETERNAL else OTHER_ETERNAL
        FlameType.ABYSS -> if (bossDrop) BOSS_ABYSS else OTHER_ABYSS
    }
}
