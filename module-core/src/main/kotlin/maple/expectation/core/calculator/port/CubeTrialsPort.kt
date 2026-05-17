package maple.expectation.core.calculator.port

import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.dto.cube.CubeCalculationInput

/**
 * Port: 큐브 기대 시도 횟수 계산
 *
 * module-app의 CubeTrialsProvider와 동일한 계약을 core CubeType으로 정의.
 * module-app, module-calculator 모두 이 port에 의존.
 */
interface CubeTrialsPort {
    fun calculateExpectedTrials(input: CubeCalculationInput, type: CubeType): Double
}
