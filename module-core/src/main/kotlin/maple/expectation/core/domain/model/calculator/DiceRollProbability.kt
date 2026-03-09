package maple.expectation.core.domain.model.calculator

/**
 * 주사위 굴림 확률 분포
 *
 * <p>순수 도메인 모델 - JPA/인프라 의존 없음
 *
 * <h3>용도</h3>
 *
 * <ul>
 *   <li>슬롯별 주사위 확률 분포 표현
 *   <li>SparsePmf로 변환하여 합성곱 계산
 * </ul>
 */
data class DiceRollProbability(val successValue: Int, val successProbability: Double) {

    init {
        require(successValue >= 0) { "successValue must be non-negative" }
        require(successProbability in 0.0..1.0) {
            "successProbability must be between 0 and 1: $successProbability"
        }
    }

    /** 성공 확률로 SparsePmf 생성 */
    fun toSparsePmf(): SparsePmf {
        val values = intArrayOf(successValue, 0)
        val probs = doubleArrayOf(successProbability, 1.0 - successProbability)
        return SparsePmf(values, probs)
    }
}
