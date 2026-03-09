package maple.expectation.core.domain.stat

import java.util.ArrayList

/**
 * 스탯 타입 Enum
 *
 * 메이플스토리 잠재력/에디셔널 옵션의 스탯 타입을 정의합니다.
 */
enum class StatType(
    /** 키워드 */
    val keyword: String,

    /** 퍼센트 스탯 여부 */
    @get:JvmName("isPercent") val percent: Boolean = false,

    /** 개별 스탯 여부 (STR, DEX, INT, LUK) ALLSTAT은 개별 스탯이 아님 (복합) */
    @get:JvmName("isIndividualStat") val individualStat: Boolean = false,
) {
    // 1. 핵심 스탯 (기존 - 하위호환)
    STR("STR", false, true),
    DEX("DEX", false, true),
    INT("INT", false, true),
    LUK("LUK", false, true),
    ALL_STAT("올스탯", false, false),
    // 올스탯은 계산 시 STR, DEX, INT, LUK 모두에 더해져야 함

    // 1-1. 퍼센트 스탯 (DP 엔진용 - 단위 포함)
    STR_PERCENT("STR", true, true),
    DEX_PERCENT("DEX", true, true),
    INT_PERCENT("INT", true, true),
    LUK_PERCENT("LUK", true, true),
    ALLSTAT_PERCENT("올스탯", true, false),

    // 2. 공격력/마력
    ATTACK_POWER("공격력", false, false),
    MAGIC_POWER("마력", false, false),
    ATTACK_POWER_PERCENT("공격력", true, false),
    MAGIC_POWER_PERCENT("마력", true, false),

    // 3. 특수 옵션
    BOSS_DAMAGE("보스 몬스터 공격 시 데미지", true, false),

    // 보공 (항상 %)
    IGNORE_DEFENSE("몬스터 방어율 무시", true, false),

    // 방무 (항상 %)
    DAMAGE("데미지", true, false),
    CRITICAL_DAMAGE("크리티컬 데미지", true, false),

    // 4. 유틸 옵션
    COOLDOWN_REDUCTION("재사용 대기시간", false, false),

    // 쿨감 (초 단위)
    ITEM_DROP("아이템 드롭률", true, false),
    MESO_DROP("메소 획득량", true, false),
    HP("HP", false, false),
    HP_PERCENT("HP", true, false),

    // 5. 레벨당 스탯 (에디셔널 핵심 옵션) (#240 V4)
    // longest-first 매칭으로 "STR"보다 먼저 감지됨
    LEVEL_STR("캐릭터 기준 9레벨 당 STR", false, true),
    LEVEL_DEX("캐릭터 기준 9레벨 당 DEX", false, true),
    LEVEL_INT("캐릭터 기준 9레벨 당 INT", false, true),
    LEVEL_LUK("캐릭터 기준 9레벨 당 LUK", false, true),

    // 6. 기타 (판별 불가)
    UNKNOWN("기타", false, false),
    ;

    /**
     * 옵션 카테고리 Enum (#240 V4)
     *
     * 복합 옵션 감지용: 서로 다른 카테고리의 옵션이 2개 이상이면 복합 옵션
     */
    enum class OptionCategory {
        STAT,

        // STR, DEX, INT, LUK, 올스탯
        BOSS_IED,

        // 보공, 방무
        ATK_MAG,

        // 공격력, 마력
        CRIT_DMG,

        // 크리티컬 데미지
        COOLDOWN,

        // 쿨감
        OTHER, // 기타
    }

    /**
     * 해당 StatType의 옵션 카테고리 반환
     */
    fun getCategory(): OptionCategory = when (this) {
        STR, DEX, INT, LUK, ALL_STAT,
        STR_PERCENT, DEX_PERCENT, INT_PERCENT, LUK_PERCENT, ALLSTAT_PERCENT,
        LEVEL_STR, LEVEL_DEX, LEVEL_INT, LEVEL_LUK,
        -> OptionCategory.STAT // #240 V4: 레벨당 스탯 추가
        BOSS_DAMAGE, IGNORE_DEFENSE -> OptionCategory.BOSS_IED
        ATTACK_POWER, MAGIC_POWER, ATTACK_POWER_PERCENT, MAGIC_POWER_PERCENT -> OptionCategory.ATK_MAG
        CRITICAL_DAMAGE -> OptionCategory.CRIT_DMG
        COOLDOWN_REDUCTION -> OptionCategory.COOLDOWN
        else -> OptionCategory.OTHER
    }

    /**
     * 유효 옵션 카테고리 여부 (복합 옵션 감지용)
     *
     * OTHER 카테고리는 복합 옵션 판정에서 제외
     */
    @JvmName("isValidCategory")
    fun isValidCategory(): Boolean = getCategory() != OptionCategory.OTHER

    companion object {
        /**
         * 문자열을 분석해서 어떤 스탯인지 찾아냅니다. (기존 방식 - 하위호환)
         * 예: "STR +12%" -> StatType.STR
         * 예: "스킬 재사용 대기시간 -2초" -> StatType.COOLDOWN_REDUCTION
         */
        @JvmStatic
        fun findType(option: String?): StatType {
            if (option.isNullOrEmpty()) {
                return UNKNOWN
            }

            if (option.contains("피격 시") ||
                // 피격 시 10% 확률로 데미지 무시 등
                option.contains("오토스틸")
            ) { // 공격 시 x% 확률로 오토스틸
                return UNKNOWN
            }

            // 기존 방식: 퍼센트 스탯은 UNKNOWN 반환 (findTypeWithUnit 사용 필요)
            if (option.contains("%")) {
                return UNKNOWN
            }

            return values()
                .filter { it != UNKNOWN } // 기타 제외하고 검색
                .filter { !it.percent } // 기존 방식: non-percent 타입만
                .filter { option.contains(it.keyword) } // 키워드 포함 여부 확인
                .firstOrNull() ?: UNKNOWN
        }

        /**
         * 문자열을 분석해서 단위 포함 스탯 타입을 찾아냅니다. (DP 엔진용)
         * 예: "STR +12%" -> StatType.STR_PERCENT
         * 예: "올스탯 +9%" -> StatType.ALLSTAT_PERCENT
         * 예: "STR +30" -> StatType.STR (플랫)
         *
         * @param option 옵션 문자열
         * @return 단위 포함 StatType (퍼센트 여부 자동 판별)
         */
        @JvmStatic
        fun findTypeWithUnit(option: String?): StatType {
            if (option.isNullOrEmpty()) {
                return UNKNOWN
            }

            if (option.contains("피격 시") || option.contains("오토스틸")) {
                return UNKNOWN
            }

            val isPercent = option.contains("%")

            return values()
                .filter { it != UNKNOWN }
                .filter { it.percent == isPercent }
                .filter { option.contains(it.keyword) }
                .firstOrNull() ?: UNKNOWN
        }

        /**
         * 문자열에서 모든 매칭되는 스탯 타입을 찾아냅니다. (복합 옵션 대응)
         * 예: "STR/DEX +6%" -> [STR_PERCENT, DEX_PERCENT]
         * 예: "올스탯 +9%" -> [ALLSTAT_PERCENT]
         *
         * P0: 키워드 오탐 방지 - longest-first 매칭 적용
         *
         * 예: "보스 몬스터 공격 시 데미지" → BOSS_DAMAGE만 (DAMAGE 오탐 방지)
         *
         * 하위호환: 매칭 실패 시 UNKNOWN 반환 (기존 동작 유지)
         *
         * @param option 옵션 문자열
         * @return 매칭된 StatType 리스트 (없으면 [UNKNOWN])
         */
        @JvmStatic
        fun findAllTypes(option: String?): List<StatType> {
            val results = findAllTypesOrEmpty(option)
            return if (results.isEmpty()) listOf(UNKNOWN) else results
        }

        /**
         * Strict 버전: 매칭 실패 시 예외 (DP 엔진 Fail-Fast 전용)
         *
         * @param option 옵션 문자열
         * @return 매칭된 StatType 리스트
         * @throws IllegalArgumentException 매칭 결과 없을 시
         */
        @JvmStatic
        fun findAllTypesStrict(option: String?): List<StatType> {
            val results = findAllTypesOrEmpty(option)
            if (results.isEmpty()) {
                throw IllegalArgumentException("스탯 타입 매칭 실패: $option")
            }
            return results
        }

        /**
         * 문자열에서 모든 매칭되는 스탯 타입을 찾습니다. (빈 리스트 허용)
         *
         * 비-핵심 경로용 (프록 옵션 등 무시 가능한 경우)
         *
         * P0: 키워드 오탐 방지 - longest-first 매칭 적용
         *
         * #240 V4: 레벨당 스탯 최우선 감지
         *
         * @param option 옵션 문자열
         * @return 매칭된 StatType 리스트 (없으면 빈 리스트)
         */
        @JvmStatic
        fun findAllTypesOrEmpty(option: String?): List<StatType> {
            if (option.isNullOrEmpty()) {
                return emptyList()
            }

            // 프록 옵션: 빈 리스트 (기여도 0으로 처리)
            if (option.contains("피격 시") || option.contains("오토스틸")) {
                return emptyList()
            }

            // #240 V4: 레벨당 스탯 최우선 감지 (STR +21 같은 깡스탯보다 먼저)
            // "캐릭터 기준 9레벨 당 STR +1" → LEVEL_STR로 감지 (STR 아님)
            val levelStat = detectLevelBasedStat(option)
            if (levelStat != null) {
                return listOf(levelStat)
            }

            val isPercent = option!!.contains("%")
            val results = ArrayList<StatType>()

            // P0: 키워드 길이 내림차순 정렬 (longest-first)
            // "보스 몬스터 공격 시 데미지"가 "데미지"보다 먼저 매칭
            val candidates = values()
                .filter { it != UNKNOWN }
                .filter { !isLevelBasedStat(it) } // 레벨당 스탯은 위에서 처리됨
                .filter { it.percent == isPercent }
                .sortedWith(compareByDescending<StatType> { it.keyword.length })

            // 매칭된 키워드 위치 추적 (오탐 방지)
            var remaining = option
            for (type in candidates) {
                if (remaining!!.contains(type.keyword)) {
                    results.add(type)
                    // 매칭된 키워드 제거 (동일 위치 재매칭 방지)
                    remaining = remaining.replace(type.keyword, "")
                }
            }

            return results
        }

        /**
         * 레벨당 스탯 감지 (#240 V4)
         *
         * "캐릭터 기준 9레벨 당" 패턴을 최우선으로 감지하여 "STR +21" 같은 깡스탯으로 오인식되는 것을 방지합니다.
         *
         * @param option 옵션 문자열
         * @return 레벨당 스탯 타입 (없으면 null)
         */
        private fun detectLevelBasedStat(option: String): StatType? {
            if (!option.contains("캐릭터 기준") || !option.contains("레벨 당")) {
                return null
            }

            return when {
                option.contains("STR") -> LEVEL_STR
                option.contains("DEX") -> LEVEL_DEX
                option.contains("INT") -> LEVEL_INT
                option.contains("LUK") -> LEVEL_LUK
                else -> null
            }
        }

        /**
         * 레벨당 스탯 타입 여부 (#240 V4)
         */
        private fun isLevelBasedStat(type: StatType): Boolean = type == LEVEL_STR ||
            type == LEVEL_DEX ||
            type == LEVEL_INT ||
            type == LEVEL_LUK

        /**
         * Primary stat 계열 키워드 포함 여부 (Drift 감지용)
         *
         * STR, DEX, INT, LUK, 올스탯 계열이 포함되어 있는지 휴리스틱 판별
         *
         * @param option 옵션 문자열
         * @return primary stat 계열로 보이면 true
         */
        @JvmStatic
        fun looksLikePrimaryStat(option: String?): Boolean {
            if (option.isNullOrEmpty()) {
                return false
            }
            // 주 스탯 키워드 패턴 (대소문자 무관)
            val upper = option.uppercase()
            return upper.contains("STR") ||
                upper.contains("DEX") ||
                upper.contains("INT") ||
                upper.contains("LUK") ||
                option.contains("올스탯") ||
                option.contains("올 스탯")
        }
    }
}
