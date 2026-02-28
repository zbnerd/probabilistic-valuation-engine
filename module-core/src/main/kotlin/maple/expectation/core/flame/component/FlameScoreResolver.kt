package maple.expectation.core.flame.component

import maple.expectation.core.probability.FlameScoreCalculator.JobWeights

/**
 * 장비 추가옵션(item_add_option)의 환산치 계산기
 *
 * 장비에 현재 붙어 있는 추옵 스탯을 직업 가중치로 환산하여 불꽃 DP 계산의 {@code target} 파라미터로 사용합니다.
 *
 * 환산치 = str*wStr + dex*wDex + int*wInt + luk*wLuk + hp*wHp + allstat%*wAllstatPct + att*wAtt +
 * mag*wMag + dmg%*wDmgPct + bossDmg%*wBossDmgPct
 */
object FlameScoreResolver {

    /**
     * 추옵 스탯으로부터 환산치 계산
     *
     * @param addStr 추옵 STR
     * @param addDex 추옵 DEX
     * @param addInt 추옵 INT
     * @param addLuk 추옵 LUK
     * @param addHp 추옵 최대 HP
     * @param addAllStat 추옵 올스탯%
     * @param addAtt 추옵 공격력
     * @param addMag 추옵 마력
     * @param addDmg 추옵 데미지%
     * @param addBossDmg 추옵 보스 데미지%
     * @param weights 직업 가중치 (스케일 10 적용)
     * @return 환산치 (스케일 10 적용된 정수)
     */
    fun calculate(
        addStr: Int,
        addDex: Int,
        addInt: Int,
        addLuk: Int,
        addHp: Int,
        addAllStat: Int,
        addAtt: Int,
        addMag: Int,
        addDmg: Int,
        addBossDmg: Int,
        weights: JobWeights
    ): Int {
        return addStr * weights.wStr +
            addDex * weights.wDex +
            addInt * weights.wInt +
            addLuk * weights.wLuk +
            addHp * weights.wHp +
            addAllStat * weights.wAllstatPct +
            addAtt * weights.wAtt +
            addMag * weights.wMag +
            addDmg * weights.wDmgPct +
            addBossDmg * weights.wBossDmgPct
    }
}
