package maple.expectation.core.probability

import maple.expectation.core.domain.flame.FlameEquipCategory
import maple.expectation.core.domain.flame.FlameOptionType
import maple.expectation.core.domain.flame.FlameStageProbability
import maple.expectation.core.domain.flame.FlameStatTable
import maple.expectation.core.domain.flame.FlameType

/**
 * 환생의 불꽃 환산치 계산 컴포넌트
 *
 * <p>각 옵션 종류별 "1줄 환산치 PMF"를 생성한다. 스케일 팩터 10을 적용하여 모든 환산치를 정수로 처리.
 */
class FlameScoreCalculator {

  companion object {
    private const val SCALE = 10
  }

  /** 직업 가중치 Record 모든 값은 SCALE(10)이 적용된 정수 */
  data class JobWeights(
      val wStr: Int,
      val wDex: Int,
      val wInt: Int,
      val wLuk: Int,
      val wHp: Int,
      val wMp: Int,
      val wAllstatPct: Int,
      val wAtt: Int,
      val wMag: Int,
      val wDmgPct: Int,
      val wBossDmgPct: Int
  ) {
    companion object {
      /** 일반 직업 (주스탯=10, 부스탯=1, 올스탯%=100, 공=40, 뎀/보공=140) */
      @JvmStatic
      fun of(mainStat: String, subStat: String?): JobWeights {
        var wStr = 0
        var wDex = 0
        var wInt = 0
        var wLuk = 0

        when (mainStat) {
          "STR" -> wStr = SCALE
          "DEX" -> wDex = SCALE
          "INT" -> wInt = SCALE
          "LUK" -> wLuk = SCALE
        }

        if (subStat != null) {
          when (subStat) {
            "STR" -> wStr = 1
            "DEX" -> wDex = 1
            "INT" -> wInt = 1
            "LUK" -> wLuk = 1
          }
        }

        return JobWeights(
            wStr, wDex, wInt, wLuk, 0, 0, 10 * SCALE, 4 * SCALE, 4 * SCALE, 14 * SCALE, 14 * SCALE)
      }

      /** 부스탯 2개 (섀도어, 듀얼블레이드, 카데나: LUK 주스탯, STR+DEX 부스탯) */
      @JvmStatic
      fun of(mainStat: String, subStat1: String, subStat2: String): JobWeights {
        val stats = buildStatWeights(mainStat, subStat1, subStat2)
        return JobWeights(
            stats[0],
            stats[1],
            stats[2],
            stats[3],
            0,
            0,
            10 * SCALE,
            4 * SCALE,
            4 * SCALE,
            14 * SCALE,
            14 * SCALE)
      }

      /** 제논 (STR+DEX+LUK 3주스탯) */
      @JvmStatic
      fun xenon(): JobWeights {
        return JobWeights(
            SCALE, SCALE, 0, SCALE, 0, 0, 10 * SCALE, 4 * SCALE, 4 * SCALE, 14 * SCALE, 14 * SCALE)
      }

      /** 데몬어벤져 (HP=10, 공=1500) */
      @JvmStatic
      fun demonAvenger(): JobWeights {
        return JobWeights(0, 0, 0, 0, SCALE, 0, 0, 150 * SCALE, 0, 0, 0)
      }

      private fun buildStatWeights(mainStat: String, vararg subStats: String): IntArray {
        val w = IntArray(4) // [STR, DEX, INT, LUK]
        applyWeight(w, mainStat, SCALE)
        for (sub in subStats) {
          applyWeight(w, sub, 1)
        }
        return w
      }

      private fun applyWeight(w: IntArray, stat: String, value: Int) {
        when (stat) {
          "STR" -> w[0] = value
          "DEX" -> w[1] = value
          "INT" -> w[2] = value
          "LUK" -> w[3] = value
        }
      }
    }
  }

  /** 특정 옵션의 특정 단계에서의 환산치 계산 */
  fun calculateScore(
      option: FlameOptionType,
      level: Int,
      stage: Int,
      weights: JobWeights,
      isWeapon: Boolean,
      baseAtt: Int,
      baseMag: Int
  ): Int? {
    if (option.isCompositeStat()) {
      return calculateCompositeScore(option, level, stage, weights)
    }

    if (isWeapon) {
      return when (option) {
        FlameOptionType.ATT -> {
          val bonus = FlameStatTable.weaponAttBonus(level, stage, baseAtt)
          bonus * weights.wAtt
        }
        FlameOptionType.MAG -> {
          val bonus = FlameStatTable.weaponAttBonus(level, stage, baseMag)
          bonus * weights.wMag
        }
        FlameOptionType.BOSS_DMG_PCT -> FlameStatTable.weaponBossDmgPct(stage) * weights.wBossDmgPct
        else -> calculateArmorScore(option, level, stage, weights)
      }
    }

    return calculateArmorScore(option, level, stage, weights)
  }

  /**
   * 옵션 종류별 1줄 환산치 PMF 생성
   *
   * @return List of PMFs (Map&lt;score, probability&gt;), one per valid option
   */
  fun buildOptionPmfs(
      category: FlameEquipCategory,
      flameType: FlameType,
      level: Int,
      weights: JobWeights,
      baseAtt: Int,
      baseMag: Int
  ): List<Map<Int, Double>> {
    val stageProbs = FlameStageProbability.getStageProbs(category.bossDrop, flameType)
    val optionPool =
        if (category.weapon) FlameOptionType.WEAPON_OPTIONS else FlameOptionType.ARMOR_OPTIONS

    val pmfs = mutableListOf<Map<Int, Double>>()
    for (option in optionPool) {
      val pmf =
          buildSingleOptionPmf(option, level, stageProbs, weights, category.weapon, baseAtt, baseMag)
      if (pmf != null) {
        pmfs.add(pmf)
      }
    }
    return pmfs
  }

  private fun calculateArmorScore(
      option: FlameOptionType,
      level: Int,
      stage: Int,
      weights: JobWeights
  ): Int? {
    val value = FlameStatTable.getArmorValue(option, level, stage) ?: return null

    return when (option) {
      FlameOptionType.STR -> value * weights.wStr
      FlameOptionType.DEX -> value * weights.wDex
      FlameOptionType.INT -> value * weights.wInt
      FlameOptionType.LUK -> value * weights.wLuk
      FlameOptionType.MAX_HP -> value * weights.wHp
      FlameOptionType.MAX_MP -> value * weights.wMp
      FlameOptionType.ATT -> value * weights.wAtt
      FlameOptionType.MAG -> value * weights.wMag
      FlameOptionType.ALLSTAT_PCT -> value * weights.wAllstatPct
      FlameOptionType.DMG_PCT -> value * weights.wDmgPct
      FlameOptionType.BOSS_DMG_PCT -> value * weights.wBossDmgPct
      else -> 0 // DEF, LEVEL_REDUCE, SPEED, JUMP -> weight 0
    }
  }

  private fun calculateCompositeScore(
      option: FlameOptionType,
      level: Int,
      stage: Int,
      weights: JobWeights
  ): Int? {
    val value = FlameStatTable.getArmorValue(option, level, stage) ?: return null

    return when (option) {
      FlameOptionType.STR_DEX -> value * weights.wStr + value * weights.wDex
      FlameOptionType.STR_INT -> value * weights.wStr + value * weights.wInt
      FlameOptionType.STR_LUK -> value * weights.wStr + value * weights.wLuk
      FlameOptionType.DEX_INT -> value * weights.wDex + value * weights.wInt
      FlameOptionType.DEX_LUK -> value * weights.wDex + value * weights.wLuk
      FlameOptionType.INT_LUK -> value * weights.wInt + value * weights.wLuk
      else -> 0
    }
  }

  private fun buildSingleOptionPmf(
      option: FlameOptionType,
      level: Int,
      stageProbs: Map<Int, Double>,
      weights: JobWeights,
      isWeapon: Boolean,
      baseAtt: Int,
      baseMag: Int
  ): Map<Int, Double>? {
    val pmf = mutableMapOf<Int, Double>()

    for ((stage, prob) in stageProbs) {
      val score = calculateScore(option, level, stage, weights, isWeapon, baseAtt, baseMag)
      if (score == null) {
        return null // stage not available -> option invalid
      }

      pmf[score] = pmf.getOrDefault(score, 0.0) + prob
    }

    return if (pmf.isEmpty()) null else pmf
  }
}
