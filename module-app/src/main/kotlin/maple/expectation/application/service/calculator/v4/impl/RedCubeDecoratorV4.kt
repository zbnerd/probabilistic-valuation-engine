package maple.expectation.application.service.calculator.v4.impl

import java.math.BigDecimal
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator.CostBreakdown
import maple.expectation.application.service.cube.AbstractCubeDecoratorV4
import maple.expectation.application.service.cube.CubeTrialsProvider
import maple.expectation.application.service.cube.policy.CubeCostPolicy
import maple.expectation.domain.v2.CubeType
import maple.expectation.web.dto.CubeCalculationInput

/**
 * V4 레드큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV4 사용)
 *
 * 리팩토링 내역:
 * - 중복 로직 제거: AbstractCubeDecoratorV4 템플릿 사용
 * - 코드 감소: ~60% (97 → 40 라인)
 * - 단일 책임: 큐브 타입과 경로 접미사만 정의
 *
 * 레드큐브 특성:
 * - 윗잠재(메인 잠재능력) 재설정
 * - 블랙큐브보다 저렴하지만 등급 상승 확률이 낮음
 * - 주로 중간 단계 옵션 작업에 사용
 *
 * @see AbstractCubeDecoratorV4 공통 로직 템플릿
 */
class RedCubeDecoratorV4(
    target: EquipmentExpectationCalculator,
    trialsProvider: CubeTrialsProvider,
    costPolicy: CubeCostPolicy,
    input: CubeCalculationInput,
) : AbstractCubeDecoratorV4(target, trialsProvider, costPolicy, input) {

    override fun getCubeType(): CubeType = CubeType.RED

    override fun getCubePathSuffix(): String = " > 레드큐브(윗잠)"

    override fun updateCostBreakdown(base: CostBreakdown, cubeCost: BigDecimal, trials: BigDecimal): CostBreakdown = base.withRedCube(base.redCubeCost.add(cubeCost), trials)
}
