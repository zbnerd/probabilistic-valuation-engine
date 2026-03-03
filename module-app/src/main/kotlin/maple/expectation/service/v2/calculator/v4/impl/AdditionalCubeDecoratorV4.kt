package maple.expectation.service.v2.calculator.v4.impl

import maple.expectation.domain.v2.CubeType
import maple.expectation.web.dto.CubeCalculationInput
import maple.expectation.service.v2.CubeTrialsProvider
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator.CostBreakdown
import maple.expectation.service.v2.cube.AbstractCubeDecoratorV4
import maple.expectation.service.v2.policy.CubeCostPolicy
import java.math.BigDecimal

/**
 * V4 에디셔널큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV4 사용)
 *
 * 리팩토링 내역:
 * - 중복 로직 제거: AbstractCubeDecoratorV4 템플릿 사용
 * - 코드 감소: ~60% (102 → 40 라인)
 * - 단일 책임: 큐브 타입과 경로 접미사만 정의
 *
 * 에디셔널큐브 특성:
 * - 아랫잠재(에디셔널 잠재능력) 재설정
 * - 에픽 → 유니크 → 레전드리 등급 상승
 * - 메이플스토리: 에디셔널 옵션은 주력 스탯에 추가 버프 제공
 *
 * @see AbstractCubeDecoratorV4 공통 로직 템플릿
 */
class AdditionalCubeDecoratorV4(
    target: EquipmentExpectationCalculator,
    trialsProvider: CubeTrialsProvider,
    costPolicy: CubeCostPolicy,
    input: CubeCalculationInput,
) : AbstractCubeDecoratorV4(target, trialsProvider, costPolicy, input) {

    override fun getCubeType(): CubeType = CubeType.ADDITIONAL

    override fun getCubePathSuffix(): String = " > 에디셔널큐브(아랫잠)"

    override fun updateCostBreakdown(base: CostBreakdown, cubeCost: BigDecimal, trials: BigDecimal): CostBreakdown =
        base.withAdditionalCube(base.additionalCubeCost.add(cubeCost), trials)
}
