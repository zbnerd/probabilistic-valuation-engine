package maple.expectation.core.domain.flame

import java.util.EnumMap
import java.util.NavigableMap
import java.util.TreeMap

/**
 * 환생의 불꽃 추가옵션 수치 테이블
 *
 * 장비 레벨과 단계(stage 1-7)에 따른 추가옵션 수치를 정의한다. 방어구/악세서리와 무기는 서로 다른 테이블을 사용하며, 무기의 ATT/MAG는 기본 공격력 기반
 * 공식으로 계산한다.
 *
 * 레벨 버킷 매핑: 130/135 -> 130, 140/145 -> 140, 나머지는 동일
 */
object FlameStatTable {

    private const val STAGES = 7

    /** armor[level][optionType] = Integer[7] (stage 1-7, 0-indexed) null element = 해당 단계 미존재 */
    private val ARMOR_TABLE: NavigableMap<Int, Map<FlameOptionType, Array<Int?>>> = TreeMap()

    init {
        initLevel100()
        initLevel110()
        initLevel120()
        initLevel130()
        initLevel140()
        initLevel150()
        initLevel160()
        initLevel170()
        initLevel180()
        initLevel200()
        initLevel250()
    }

    /**
     * 방어구/악세서리 추가옵션 수치 조회
     *
     * @param option 옵션 타입
     * @param level 장비 레벨
     * @param stage 단계 (1-7)
     * @return 수치 (null = 해당 단계/옵션 미존재)
     */
    @JvmStatic
    fun getArmorValue(option: FlameOptionType, level: Int, stage: Int): Int? {
        val bucket = toBucket(level)
        val levelTable = ARMOR_TABLE[bucket] ?: return null
        val stages = levelTable[option] ?: return null

        val idx = stage - 1
        if (idx < 0 || idx >= STAGES) {
            return null
        }
        return stages[idx]
    }

    /**
     * 무기 공격력/마력 보너스 계산
     *
     * 공식: floor((level / 40 + 1) * stage * (1 + 0.1 * (stage - 3)) * baseAtt)
     *
     * @param level 장비 레벨
     * @param stage 단계 (1-7)
     * @param baseAtt 무기 기본 공격력
     * @return 공격력/마력 보너스
     */
    @JvmStatic
    fun weaponAttBonus(level: Int, stage: Int, baseAtt: Int): Int {
        val factor = (level / 40 + 1) * stage * (1.0 + 0.1 * (stage - 3))
        return Math.floor(factor * baseAtt).toInt()
    }

    /**
     * 무기 보스 데미지% 조회
     *
     * @param stage 단계 (1-7)
     * @return 보스 데미지% 수치
     */
    @JvmStatic
    fun weaponBossDmgPct(stage: Int): Int = stage * 2

    /**
     * 무기 추가옵션 수치 조회 (ATT/MAG, BOSS_DMG_PCT 제외)
     *
     * 무기의 ATT/MAG는 [weaponAttBonus]를 사용하고, BOSS_DMG_PCT는 [weaponBossDmgPct]를 사용한다.
     * 나머지 옵션은 방어구 테이블과 동일하다.
     *
     * @param option 옵션 타입
     * @param level 장비 레벨
     * @param stage 단계 (1-7)
     * @return 수치 (null = 해당 단계/옵션 미존재)
     */
    @JvmStatic
    fun getWeaponValue(option: FlameOptionType, level: Int, stage: Int): Int? = getArmorValue(option, level, stage)

    // ------------------------------------------------------------------
    // Level bucket mapping
    // ------------------------------------------------------------------

    private fun toBucket(level: Int): Int {
        if (level >= 250) return 250
        if (level >= 200) return 200
        if (level >= 180) return 180
        if (level >= 170) return 170
        if (level >= 160) return 160
        if (level >= 150) return 150
        if (level >= 140) return 140
        if (level >= 130) return 130
        if (level >= 120) return 120
        if (level >= 110) return 110
        return 100
    }

    // ------------------------------------------------------------------
    // Table initialization helpers
    // ------------------------------------------------------------------

    private fun stages(vararg values: Int?): Array<Int?> {
        require(values.size == STAGES) {
            "Expected $STAGES stage values, got ${values.size}"
        }
        return arrayOf(*values)
    }

    private fun linearStages(base: Int): Array<Int?> = stages(base, base * 2, base * 3, base * 4, base * 5, base * 6, base * 7)

    private fun newOptionMap(): EnumMap<FlameOptionType, Array<Int?>> = EnumMap(FlameOptionType::class.java)

    private fun putSingleStats(map: MutableMap<FlameOptionType, Array<Int?>>, values: Array<Int?>) {
        map[FlameOptionType.STR] = values
        map[FlameOptionType.DEX] = values
        map[FlameOptionType.INT] = values
        map[FlameOptionType.LUK] = values
    }

    private fun putCompositeStats(map: MutableMap<FlameOptionType, Array<Int?>>, values: Array<Int?>) {
        map[FlameOptionType.STR_DEX] = values
        map[FlameOptionType.STR_INT] = values
        map[FlameOptionType.STR_LUK] = values
        map[FlameOptionType.DEX_INT] = values
        map[FlameOptionType.DEX_LUK] = values
        map[FlameOptionType.INT_LUK] = values
    }

    private fun putHpMp(map: MutableMap<FlameOptionType, Array<Int?>>, values: Array<Int?>) {
        map[FlameOptionType.MAX_HP] = values
        map[FlameOptionType.MAX_MP] = values
    }

    private fun putAttMag(map: MutableMap<FlameOptionType, Array<Int?>>, values: Array<Int?>) {
        map[FlameOptionType.ATT] = values
        map[FlameOptionType.MAG] = values
    }

    /** 100~150제 공통 옵션 (ATT/MAG = 1~7 선형, ALLSTAT/DMG/BOSS_DMG/LEVEL_REDUCE 동일) */
    private fun putStandardOptions(map: MutableMap<FlameOptionType, Array<Int?>>) {
        putAttMag(map, linearStages(1))
        map[FlameOptionType.ALLSTAT_PCT] = linearStages(1)
        map[FlameOptionType.DMG_PCT] = linearStages(1)
        map[FlameOptionType.BOSS_DMG_PCT] = stages(2, 4, 6, 8, 10, 6, 7)
        map[FlameOptionType.LEVEL_REDUCE] = linearStages(5)
        putSpeedJump(map)
    }

    private fun putSpeedJump(map: MutableMap<FlameOptionType, Array<Int?>>) {
        map[FlameOptionType.SPEED] = linearStages(1)
        map[FlameOptionType.JUMP] = linearStages(1)
    }

    // ------------------------------------------------------------------
    // Level-specific initialization
    // ------------------------------------------------------------------

    private fun initLevel100() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(6))
        putCompositeStats(map, linearStages(3))
        putHpMp(map, linearStages(300))
        map[FlameOptionType.DEF] = linearStages(6)
        putStandardOptions(map)
        ARMOR_TABLE[100] = map
    }

    private fun initLevel110() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(6))
        putCompositeStats(map, linearStages(3))
        putHpMp(map, linearStages(330))
        map[FlameOptionType.DEF] = linearStages(6)
        putStandardOptions(map)
        ARMOR_TABLE[110] = map
    }

    private fun initLevel120() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(7))
        putCompositeStats(map, linearStages(4))
        putHpMp(map, linearStages(360))
        map[FlameOptionType.DEF] = linearStages(7)
        putStandardOptions(map)
        ARMOR_TABLE[120] = map
    }

    private fun initLevel130() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(7))
        putCompositeStats(map, linearStages(4))
        putHpMp(map, linearStages(390))
        map[FlameOptionType.DEF] = linearStages(7)
        putStandardOptions(map)
        ARMOR_TABLE[130] = map
    }

    private fun initLevel140() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(8))
        putCompositeStats(map, linearStages(4))
        putHpMp(map, linearStages(420))
        map[FlameOptionType.DEF] = linearStages(8)
        putStandardOptions(map)
        ARMOR_TABLE[140] = map
    }

    private fun initLevel150() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(8))
        putCompositeStats(map, linearStages(4))
        putHpMp(map, linearStages(450))
        map[FlameOptionType.DEF] = linearStages(8)
        putStandardOptions(map)
        ARMOR_TABLE[150] = map
    }

    private fun initLevel160() {
        val map = newOptionMap()
        putSingleStats(map, stages(null, null, 27, 36, 45, 54, 63))
        putCompositeStats(map, stages(null, null, 15, 20, 25, 30, 35))
        putHpMp(map, stages(null, null, 1440, 1920, 2400, 2880, 3360))
        putAttMag(map, stages(null, null, 3, 4, 5, 6, 7))
        map[FlameOptionType.DEF] = stages(null, null, 27, 36, 45, 54, 63)
        map[FlameOptionType.ALLSTAT_PCT] = stages(null, null, 3, 4, 5, 6, 7)
        map[FlameOptionType.SPEED] = stages(null, null, 3, 4, 5, 6, 7)
        map[FlameOptionType.JUMP] = stages(null, null, 3, 4, 5, 6, 7)
        map[FlameOptionType.LEVEL_REDUCE] = stages(null, null, 15, 20, 25, 30, 35)
        // 160제: DMG_PCT, BOSS_DMG_PCT 없음
        ARMOR_TABLE[160] = map
    }

    private fun initLevel170() {
        val map = newOptionMap()
        putSingleStats(map, stages(9, 18, 27, 36, 45, null, null))
        putCompositeStats(map, stages(5, 10, 15, 20, 25, null, null))
        putHpMp(map, stages(510, 1020, 1530, 2040, 2550, null, null))
        map[FlameOptionType.ATT] = stages(9, 20, 32, 47, 64, null, null)
        map[FlameOptionType.MAG] = stages(9, 20, 32, 47, 64, null, null)
        map[FlameOptionType.DEF] = stages(9, 18, 27, 36, 45, null, null)
        map[FlameOptionType.ALLSTAT_PCT] = stages(1, 2, 3, 4, 5, null, null)
        map[FlameOptionType.DMG_PCT] = stages(1, 2, 3, 4, 5, null, null)
        map[FlameOptionType.BOSS_DMG_PCT] = stages(2, 4, 6, 8, 10, null, null)
        map[FlameOptionType.LEVEL_REDUCE] = stages(5, 10, 15, 20, 25, null, null)
        map[FlameOptionType.SPEED] = stages(1, 2, 3, 4, 5, null, null)
        map[FlameOptionType.JUMP] = stages(1, 2, 3, 4, 5, null, null)
        ARMOR_TABLE[170] = map
    }

    private fun initLevel180() {
        val map = newOptionMap()
        putSingleStats(map, stages(10, 20, 30, 40, 50, null, null))
        putCompositeStats(map, stages(5, 10, 15, 20, 25, null, null))
        putHpMp(map, stages(540, 1080, 1620, 2160, 2700, null, null))
        map[FlameOptionType.ATT] = stages(11, 23, 38, 56, 76, null, null)
        map[FlameOptionType.MAG] = stages(11, 23, 38, 56, 76, null, null)
        map[FlameOptionType.DEF] = stages(10, 20, 30, 40, 50, null, null)
        map[FlameOptionType.ALLSTAT_PCT] = stages(1, 2, 3, 4, 5, null, null)
        map[FlameOptionType.DMG_PCT] = stages(1, 2, 3, 4, 5, null, null)
        map[FlameOptionType.BOSS_DMG_PCT] = stages(2, 4, 6, 8, 10, null, null)
        map[FlameOptionType.LEVEL_REDUCE] = stages(5, 10, 15, 20, 25, null, null)
        map[FlameOptionType.SPEED] = stages(1, 2, 3, 4, 5, null, null)
        map[FlameOptionType.JUMP] = stages(1, 2, 3, 4, 5, null, null)
        ARMOR_TABLE[180] = map
    }

    private fun initLevel200() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(11))
        putCompositeStats(map, linearStages(6))
        putHpMp(map, linearStages(600))
        map[FlameOptionType.DEF] = linearStages(11)
        putAttMag(map, linearStages(1))
        map[FlameOptionType.ALLSTAT_PCT] = linearStages(1)
        map[FlameOptionType.DMG_PCT] = linearStages(1)
        map[FlameOptionType.BOSS_DMG_PCT] = stages(2, 4, 6, 8, 10, 12, 14)
        map[FlameOptionType.LEVEL_REDUCE] = linearStages(5)
        putSpeedJump(map)
        ARMOR_TABLE[200] = map
    }

    private fun initLevel250() {
        val map = newOptionMap()
        putSingleStats(map, linearStages(12))
        putCompositeStats(map, linearStages(7))
        putHpMp(map, linearStages(700))
        map[FlameOptionType.DEF] = linearStages(12)
        putAttMag(map, linearStages(1))
        map[FlameOptionType.ALLSTAT_PCT] = linearStages(1)
        map[FlameOptionType.DMG_PCT] = linearStages(1)
        map[FlameOptionType.BOSS_DMG_PCT] = stages(2, 4, 6, 8, 10, 6, 7)
        map[FlameOptionType.LEVEL_REDUCE] = linearStages(5)
        putSpeedJump(map)
        ARMOR_TABLE[250] = map
    }
}
