package maple.expectation.core.domain.flame

/**
 * 환생의 불꽃 장비 분류
 *
 * 보스 드랍 장비: 4줄 고정 그외 장비: 1~4줄 균등
 */
enum class FlameEquipCategory(
    @get:JvmName("isBossDrop") val bossDrop: Boolean,
    @get:JvmName("isWeapon") val weapon: Boolean,
    val fixedLineCount: Int // 4 for boss, -1 for other (1~4 uniform)
) {
    BOSS_WEAPON(true, true, 4),
    BOSS_ARMOR(true, false, 4),
    OTHER_WEAPON(false, true, -1), // -1 = 1~4 uniform
    OTHER_ARMOR(false, false, -1);

    companion object {
        @JvmStatic
        fun of(bossDrop: Boolean, isWeapon: Boolean): FlameEquipCategory {
            return if (bossDrop) {
                if (isWeapon) BOSS_WEAPON else BOSS_ARMOR
            } else {
                if (isWeapon) OTHER_WEAPON else OTHER_ARMOR
            }
        }
    }
}
