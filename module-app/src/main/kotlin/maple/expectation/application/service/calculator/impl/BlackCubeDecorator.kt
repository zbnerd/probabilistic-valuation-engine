package maple.expectation.application.service.calculator.impl

import maple.expectation.application.service.calculator.ExpectationCalculator
import maple.expectation.application.service.cube.AbstractCubeDecoratorV2
import maple.expectation.application.service.cube.CubeTrialsProvider
import maple.expectation.application.service.cube.policy.CubeCostPolicy
import maple.expectation.domain.v2.CubeType
import maple.expectation.core.dto.cube.CubeCalculationInput

/**
 * V2 블랙큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV2 사용)
 *
 * 리팩토링 내역:
 * - 중복 로직 제거: AbstractCubeDecoratorV2 템플릿 사용
 * - 코드 감소: ~50% (58 → 30 라인)
 * - 단일 책임: 큐브 타입과 경로 접미사만 정의
 *
 * 블랙큐브 특성:
 * - 윗잠재(메인 잠재능력) 재설정
 * - 레어 → 에픽 → 유니크 → 레전드리 등급 상승
 * - 큐브 가격: 레벨 × 등급 계수
 *
 * @see AbstractCubeDecoratorV2 공통 로직 템플릿
 */
class BlackCubeDecorator(
    target: ExpectationCalculator,
    trialsProvider: CubeTrialsProvider,
    costPolicy: CubeCostPolicy,
    input: CubeCalculationInput,
) : AbstractCubeDecoratorV2(target, trialsProvider, costPolicy, input) {

    override fun getCubeType(): CubeType = CubeType.BLACK

    override fun getCubePathSuffix(): String = " > 블랙큐브(윗잠)"
}
