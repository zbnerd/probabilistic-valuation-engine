package maple.expectation.application.service.calculator.v4.impl

import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator.CostBreakdown
import maple.expectation.application.service.cube.AbstractCubeDecoratorV4
import maple.expectation.application.service.cube.CubeTrialsProvider
import maple.expectation.application.service.cube.policy.CubeCostPolicy
import maple.expectation.domain.v2.CubeType
import maple.expectation.web.dto.CubeCalculationInput

/**
 * V4 블랙큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV4 사용)
 *
 * 리팩토링 내역:
 * - 중복 로직 제거: AbstractCubeDecoratorV4 템플릿 사용
 * - 코드 감소: ~60% (119 → 47 라인)
 * - 단일 책임: 큐브 타입과 경로 접미사만 정의
 * - 성능 최적화: BigDecimal → Double로 변경 (2026-03-23)
 *
 * 블랙큐브 특성:
 * - 윗잠재(메인 잠재능력) 재설정
 * - 레어 → 에픽 → 유니크 → 레전드리 등급 상승
 * - 큐브 가격: 레벨 × 등급 계수
 *
 * @see AbstractCubeDecoratorV4 공통 로직 템플릿
 */
class BlackCubeDecoratorV4(
    target: EquipmentExpectationCalculator,
    trialsProvider: CubeTrialsProvider,
    costPolicy: CubeCostPolicy,
    input: CubeCalculationInput,
) : AbstractCubeDecoratorV4(target, trialsProvider, costPolicy, input) {

    override fun getCubeType(): CubeType = CubeType.BLACK

    override fun getCubePathSuffix(): String = " > 블랙큐브(윗잠)"

    override fun updateCostBreakdown(base: CostBreakdown, cubeCost: Double, trials: Double): CostBreakdown =
        base.withBlackCube(base.blackCubeCost + cubeCost, trials)
}
