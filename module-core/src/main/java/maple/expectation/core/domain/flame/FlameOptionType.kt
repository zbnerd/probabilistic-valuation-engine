package maple.expectation.core.domain.flame

/**
 * 환생의 불꽃 추가옵션 종류
 */
enum class FlameOptionType {
    STR,
    DEX,
    INT,
    LUK,
    STR_DEX,
    STR_INT,
    STR_LUK,
    DEX_INT,
    DEX_LUK,
    INT_LUK,
    MAX_HP,
    MAX_MP,
    LEVEL_REDUCE,
    DEF,
    ATT,
    MAG,
    BOSS_DMG_PCT,
    // weapon only
    DMG_PCT,
    // weapon only
    ALLSTAT_PCT,
    SPEED,
    // armor only
    JUMP; // armor only

    /**
     * Check if this is a composite stat (combination of two primary stats)
     */
    @JvmName("isCompositeStat")
    fun isCompositeStat(): Boolean {
        return this == STR_DEX ||
                this == STR_INT ||
                this == STR_LUK ||
                this == DEX_INT ||
                this == DEX_LUK ||
                this == INT_LUK
    }

    companion object {
        // Weapon option pool (19 types)
        @JvmField
        val WEAPON_OPTIONS = arrayOf(
            STR,
            DEX,
            INT,
            LUK,
            STR_DEX,
            STR_INT,
            STR_LUK,
            DEX_INT,
            DEX_LUK,
            INT_LUK,
            MAX_HP,
            MAX_MP,
            LEVEL_REDUCE,
            DEF,
            ATT,
            MAG,
            BOSS_DMG_PCT,
            DMG_PCT,
            ALLSTAT_PCT
        )

        // Armor+Accessory option pool (21 types)
        @JvmField
        val ARMOR_OPTIONS = arrayOf(
            STR,
            DEX,
            INT,
            LUK,
            STR_DEX,
            STR_INT,
            STR_LUK,
            DEX_INT,
            DEX_LUK,
            INT_LUK,
            MAX_HP,
            MAX_MP,
            LEVEL_REDUCE,
            DEF,
            ATT,
            MAG,
            BOSS_DMG_PCT,
            DMG_PCT,
            ALLSTAT_PCT,
            SPEED,
            JUMP
        )
    }
}
