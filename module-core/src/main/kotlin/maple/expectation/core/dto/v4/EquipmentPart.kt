package maple.expectation.core.dto.v4

enum class EquipmentPart(val koreanName: String) {
    WEAPON("무기"),
    SECONDARY_WEAPON("보조무기"),
    ARMOR("방어구"),
    ACCESSORY("장신구"),
    ETC("기타"),
    UNKNOWN("알 수 없음");

    companion object {
        fun fromKorean(name: String): EquipmentPart =
            entries.find { it.koreanName == name } ?: UNKNOWN
    }
}
