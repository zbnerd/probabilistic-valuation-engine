package maple.expectation.core.dto.v4

enum class StarforceScrollFlag(val koreanValue: String) {
    USED("사용"),
    NOT_USED("미사용"),
    UNKNOWN("알 수 없음");

    companion object {
        fun fromKorean(value: String?): StarforceScrollFlag =
            if (value == null) NOT_USED
            else entries.find { it.koreanValue == value } ?: UNKNOWN
    }
}
