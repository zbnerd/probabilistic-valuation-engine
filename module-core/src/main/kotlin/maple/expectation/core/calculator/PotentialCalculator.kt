package maple.expectation.core.calculator

import maple.expectation.core.domain.stat.StatParser
import maple.expectation.core.domain.stat.StatType
import java.util.EnumMap

/** 잠재능력 수치 계산기 (Pure Business Logic) */
class PotentialCalculator(private val statParser: StatParser) {

  /** "윗잠(잠재능력)" 합산 결과 반환 */
  fun calculateMainPotential(option1: String, option2: String, option3: String): Map<StatType, Int> {
    return sumOptions(listOfNotNull(option1, option2, option3))
  }

  /** "에디(에디셔널)" 합산 결과 반환 */
  fun calculateAdditionalPotential(
      option1: String,
      option2: String,
      option3: String
  ): Map<StatType, Int> {
    return sumOptions(listOfNotNull(option1, option2, option3))
  }

  /** 특정 스탯의 "최종 수치" 계산 (올스탯 포함) */
  fun getEffectiveStat(stats: Map<StatType, Int>, type: StatType): Int {
    return if (type == StatType.ALL_STAT) {
      stats.getOrDefault(StatType.ALL_STAT, 0)
    } else {
      stats.getOrDefault(type, 0) + stats.getOrDefault(StatType.ALL_STAT, 0)
    }
  }

  /** 🚀 평탄화: 반복적인 accumulateStat 호출을 Stream으로 통합 */
  private fun sumOptions(options: List<String>): Map<StatType, Int> {
    val result = EnumMap<StatType, Int>(StatType::class.java)

    options
        .filter { !it.isNullOrEmpty() }
        .forEach { accumulateStat(result, it) }

    return result
  }

  private fun accumulateStat(map: MutableMap<StatType, Int>, optionStr: String) {
    // findTypeWithUnit()을 사용하여 퍼센트 스탯도 올바르게 매칭
    val type = StatType.findTypeWithUnit(optionStr)

    // 퍼센트 스탯 타입을 기본 타입으로 변환 (STR_PERCENT -> STR, ALLSTAT_PERCENT -> ALL_STAT)
    // 이렇게 하면 getEffectiveStat()에서 올바르게 합산 가능
    val baseType = convertToBaseType(type)

    val value = statParser.parseNum(optionStr)

    if (baseType != StatType.UNKNOWN && value != 0) {
      map.merge(baseType, value, Integer::sum)
    }
  }

  /**
   * 퍼센트 스탯 타입을 기본 타입으로 변환
   *
   * <p>STR_PERCENT -> STR, DEX_PERCENT -> DEX, ALLSTAT_PERCENT -> ALL_STAT
   *
   * <p>이렇게 하면 잠재능력 계산 시 퍼센트/플랫 구분 없이 합산 가능
   */
  private fun convertToBaseType(type: StatType): StatType {
    if (type == StatType.UNKNOWN) {
      return StatType.UNKNOWN
    }

    // 퍼센트 타입을 기본 타입으로 변환
    return when (type) {
      StatType.STR_PERCENT,
      StatType.DEX_PERCENT,
      StatType.INT_PERCENT,
      StatType.LUK_PERCENT -> {
        val keyword = type.keyword
        // 같은 키워드를 가진 기본 타입 찾기 (STR, DEX, INT, LUK)
        StatType.entries
            .filter { t -> t.keyword == keyword && !t.percent }
            .firstOrNull() ?: StatType.UNKNOWN
      }
      StatType.ALLSTAT_PERCENT -> StatType.ALL_STAT
      StatType.ATTACK_POWER_PERCENT -> StatType.ATTACK_POWER
      StatType.MAGIC_POWER_PERCENT -> StatType.MAGIC_POWER
      StatType.HP_PERCENT -> StatType.HP
      else -> type // 그 외는 그대로 반환 (BOSS_DAMAGE, IGNORE_DEFENSE 등)
    }
  }
}
