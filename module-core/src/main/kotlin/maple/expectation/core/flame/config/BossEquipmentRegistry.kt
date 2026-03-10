package maple.expectation.core.flame.config

/**
 * 보스 드랍 장비 레지스트리
 *
 * FLAME_LOGIC.md의 보스 장비 목록을 코드로 관리합니다. 무기는 접두어(prefix) 매칭, 장신구/방어구는 접두어 또는 정확한 이름 매칭으로 판별합니다.
 */
object BossEquipmentRegistry {

    /** 보스 드랍 무기 접두어 (자쿰의 포이즈닉, 네크로, 로얄 반 레온, 루타비스 4셋, 파프니르, 앱솔랩스, 아케인셰이드, 제네시스, 데스티니) */
    private val BOSS_WEAPON_PREFIXES = setOf(
        "자쿰의 포이즈닉",
        "네크로",
        "로얄 반 레온",
        "라이온하트",
        "드래곤테일",
        "팔콘윙",
        "레이븐혼",
        "샤크투스",
        "파프니르",
        "앱솔랩스",
        "아케인셰이드",
        "제네시스",
        "데스티니",
    )

    /** 보스 드랍 방어구 접두어 (세트 장비) */
    private val BOSS_ARMOR_PREFIXES = setOf(
        "네크로", "로얄 반 레온", "라이온하트", "드래곤테일", "팔콘윙", "레이븐혼", "샤크투스", "이글아이", "트릭스터", "하이네스", "앱솔랩스",
        "아케인셰이드", "에테르넬",
    )

    /** 보스 드랍 장신구 (정확한 이름) */
    private val BOSS_ACCESSORY_NAMES = setOf(
        "아쿠아틱 레터 눈장식",
        "블랙빈 마크",
        "파풀라투스 마크",
        "응축된 힘의 결정석",
        "골든 클로버 벨트",
        "분노한 자쿰의 벨트",
        "매커네이터 펜던트",
        "도미네이터 펜던트",
        "데아 시두스 이어링",
        "지옥의 불꽃",
        "핑크빛 성배",
        "트와일라이트 마크",
        "에스텔라 이어링",
        "데이브레이크 펜던트",
        "루즈 컨트롤 머신 마크",
        "마력이 깃든 안대",
        "몽환의 벨트",
        "고통의 근원",
        "커맨더 포스 이어링",
        "저주받은 적의 마도서",
        "죽음의 맹세",
        "불멸의 유산",
        "오만의 원죄",
    )

    /**
     * 보스 드랍 장비 여부 판별
     *
     * @param itemName 장비 이름
     * @param isWeapon 무기 여부
     * @return 보스 드랍 장비이면 true
     */
    fun isBossEquipment(itemName: String?, isWeapon: Boolean): Boolean {
        if (itemName.isNullOrBlank()) {
            return false
        }

        return if (isWeapon) {
            matchesAnyPrefix(itemName, BOSS_WEAPON_PREFIXES)
        } else {
            matchesAnyPrefix(itemName, BOSS_ARMOR_PREFIXES) || BOSS_ACCESSORY_NAMES.contains(itemName)
        }
    }

    /**
     * 제로 무기 여부 판별
     *
     * 제로 직업군의 무기는 보스 드랍에서 제외됩니다.
     *
     * @param characterClass 직업명
     * @param isWeapon 무기 여부
     * @return 제로 무기이면 true (보스 드랍 제외 대상)
     */
    fun isZeroWeapon(characterClass: String?, isWeapon: Boolean): Boolean = isWeapon && characterClass == "제로"

    private fun matchesAnyPrefix(itemName: String, prefixes: Set<String>): Boolean = prefixes.any { itemName.startsWith(it) }
}
