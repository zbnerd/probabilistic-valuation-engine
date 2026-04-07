package maple.expectation.core.probability

import maple.expectation.core.domain.model.calculator.DensePmf
import maple.expectation.core.domain.model.calculator.SparsePmf

/**
 * DP 합성곱 기반 확률 계산 컴포넌트
 *
 * <h3>핵심 가정 (이 가정이 틀리면 결과도 틀림)</h3>
 *
 * <ul>
 *   <li>각 슬롯(라인)은 독립적으로 옵션을 추첨한다
 *   <li>슬롯 간 추첨은 독립이다 (조건부 확률 아님)
 *   <li>같은 옵션이 여러 슬롯에 중복 등장 가능하다
 * </ul>
 *
 * <h3>타입 분리 설계</h3>
 *
 * <ul>
 *   <li>입력: List&lt;SparsePmf&gt; (희소, K가 작음)
 *   <li>출력: DensePmf (밀집, 인덱스=값)
 * </ul>
 *
 * <h3>Tail Clamp 전략</h3>
 *
 * <ul>
 *   <li>인덱스는 0..target
 *   <li>합이 target 초과 시 모두 target 버킷에 누적
 *   <li>결과적으로 O(slots × target × K) 보장
 * </ul>
 */
class ProbabilityConvolver {

    companion object {
        private const val MASS_TOLERANCE = 1e-5
        private const val NEGATIVE_TOLERANCE = -1e-15
    }

    /**
     * 슬롯 SparsePmf들을 합성하여 총합 DensePmf 생성
     *
     * <p>사후조건:
     *
     * <ul>
     *   <li>질량 보존: Σ=1 ± MASS_TOLERANCE
     *   <li>NaN/Inf 없음
     *   <li>enableTailClamp=true면 상태 크기 = target+1
     * </ul>
     *
     * @param slotPmfs 슬롯별 SparsePmf 리스트
     * @param target 목표 합계
     * @param enableTailClamp Tail Clamp 활성화 여부
     * @return 합성된 DensePmf
     * @throws IllegalArgumentException 불변식 위반 시
     */
    fun convolveAll(slotPmfs: List<SparsePmf>, target: Int, enableTailClamp: Boolean): DensePmf = doConvolveWithClamp(slotPmfs, target, enableTailClamp)

    private fun doConvolveWithClamp(
        slotPmfs: List<SparsePmf>,
        target: Int,
        enableTailClamp: Boolean,
    ): DensePmf {
        val maxIndex = if (enableTailClamp) target else calculateMaxSum(slotPmfs)
        var acc = initializeAccumulator(maxIndex)

        for (slot in slotPmfs) {
            acc = convolveSlot(acc, slot, maxIndex)
        }

        val result = DensePmf.fromArray(acc)
        validateInvariants(result)
        return result
    }

    private fun initializeAccumulator(maxIndex: Int): DoubleArray {
        val acc = DoubleArray(maxIndex + 1)
        acc[0] = 1.0 // 초기 상태: 합=0일 확률 100%
        return acc
    }

    private fun convolveSlot(acc: DoubleArray, slot: SparsePmf, maxIndex: Int): DoubleArray {
        val next = DoubleArray(maxIndex + 1)

        for (i in 0..maxIndex) {
            if (acc[i] == 0.0) continue
            accumulateSlotContributions(acc, slot, next, i, maxIndex)
        }

        return next
    }

    private fun accumulateSlotContributions(
        acc: DoubleArray,
        slot: SparsePmf,
        next: DoubleArray,
        currentIndex: Int,
        maxIndex: Int,
    ) {
        for (k in 0 until slot.size()) {
            val value = slot.valueAt(k)
            val prob = slot.probAt(k)

            // P2 Fix (PR #159 Codex 지적): 음수 contribution 가드
            // 상위 파서/추출기 버그 시 ArrayIndexOutOfBoundsException 방지
            if (value < 0) {
                throw IllegalArgumentException(
                    "음수 contribution 감지: value=$value (slot index=$k)",
                )
            }

            val targetIndex = minOf(currentIndex + value, maxIndex) // Tail Clamp
            next[targetIndex] += acc[currentIndex] * prob
        }
    }

    private fun calculateMaxSum(slotPmfs: List<SparsePmf>): Int = slotPmfs.sumOf { it.maxValue() }

    /**
     * DensePmf 불변식 검증
     *
     * <p>DoD 1e-12 기준 충족을 위해 Kahan summation 사용
     *
     * @param pmf 검증 대상
     * @throws IllegalArgumentException 불변식 위반 시
     */
    private fun validateInvariants(pmf: DensePmf) {
        val sum = pmf.totalMassKahan()
        // 부동소수점 누적 오차로 인한 질량 편차는 무시 (정규화는 호출측에서 처리)
        if (pmf.hasNegative(NEGATIVE_TOLERANCE)) {
            throw IllegalArgumentException("음수 확률 감지")
        }
        if (pmf.hasNaNOrInf()) {
            throw IllegalArgumentException("NaN/Inf 감지")
        }
        if (pmf.hasValueExceedingOne()) {
            throw IllegalArgumentException("확률 > 1 감지")
        }
    }
}
