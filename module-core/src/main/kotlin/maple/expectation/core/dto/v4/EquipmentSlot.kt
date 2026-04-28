package maple.expectation.core.dto.v4

enum class EquipmentSlot(val koreanName: String) {
    HAT("모자"),
    TOP("상의"),
    BOTTOM("하의"),
    SHOES("신발"),
    GLOVES("장갑"),
    CAPE("망토"),
    WEAPON("무기"),
    SECONDARY_WEAPON("보조무기"),
    EARRING("귀고리"),
    RING1("반지1"),
    RING2("반지2"),
    RING3("반지3"),
    RING4("반지4"),
    PENDANT1("펜던트1"),
    PENDANT2("펜던트2"),
    BELT("벨트"),
    MEDAL("훈장"),
    BADGE("뱃지"),
    EMBLEM("엠블렘"),
    POCKET("포켓 아이템"),
    SHOULDER("어깨장식"),
    HEART("기계심장"),
    FACE("얼굴장식"),
    EYE("눈장식"),
    POWER_SOURCE("파워 소스"),
    UNKNOWN("알 수 없음");

    companion object {
        fun fromKorean(name: String): EquipmentSlot =
            entries.find { it.koreanName == name } ?: UNKNOWN
    }
}
