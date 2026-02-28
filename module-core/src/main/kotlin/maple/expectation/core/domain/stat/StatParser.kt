package maple.expectation.core.domain.stat

/**
 * 스탯 파싱 유틸리티 (Pure Domain - Spring/Infrastructure 의존 없음)
 */
class StatParser {

    /**
     * 문자열에서 숫자만 추출
     *
     * @param value 파싱할 문자열
     * @return 추출된 숫자 (실패 시 0)
     */
    fun parseNum(value: String?): Int {
        if (value.isNullOrEmpty()) {
            return 0
        }

        return try {
            val cleanStr = value.replace("[^0-9\\-]".toRegex(), "")
            if (cleanStr.isEmpty()) 0 else cleanStr.toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * 퍼센트(%) 옵션인지 확인
     */
    fun isPercent(value: String?): Boolean {
        return value != null && value.contains("%")
    }
}
