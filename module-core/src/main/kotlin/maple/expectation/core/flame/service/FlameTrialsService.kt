package maple.expectation.core.flame.service

import maple.expectation.core.domain.flame.FlameEquipCategory
import maple.expectation.core.domain.flame.FlameType
import maple.expectation.core.flame.port.FlameTrialsPort
import maple.expectation.core.probability.FlameDpCalculator
import maple.expectation.core.probability.FlameScoreCalculator

/**
 * 환생의 불꽃 기대 시도 횟수 서비스
 *
 * ## 역할
 *
 * FlameDpCalculator에 위임하여 환생의 불꽃 기대 시도 횟수를 계산합니다.
 *
 * ## 설계 근거
 *
 * DIP 준수를 위해 FlameTrialsPort 인터페이스를 구현합니다. V4 PresetCalculationHelper는 구체 클래스가 아닌
 * 인터페이스에 의존합니다.
 */
class FlameTrialsService(
    private val dpCalculator: FlameDpCalculator,
    private val scoreCalculator: FlameScoreCalculator,
) : FlameTrialsPort {

    override fun calculateExpectedTrials(
        category: FlameEquipCategory,
        flameType: FlameType,
        level: Int,
        weights: FlameScoreCalculator.JobWeights,
        target: Int,
        baseAtt: Int,
        baseMag: Int,
    ): Double? {
        // Build option PMFs first using scoreCalculator
        val optionPmfs = scoreCalculator.buildOptionPmfs(
            category,
            flameType,
            level,
            weights,
            baseAtt,
            baseMag,
        )
        return dpCalculator.calculateExpectedTrials(
            category,
            flameType,
            level,
            weights,
            target,
            baseAtt,
            baseMag,
            optionPmfs,
        )
    }
}
