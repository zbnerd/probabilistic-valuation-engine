package maple.expectation.core.domain.model

/**
 * Potential grade enum for cube potential options.
 *
 * <p>Domain model - no external dependencies.
 */
enum class PotentialGrade(val koreanName: String) {
    RARE("레어"),
    EPIC("에픽"),
    UNIQUE("유니크"),
    LEGENDARY("레전드리"),
    ;

    companion object {
        private val KOREAN_MAP = entries.associateBy { it.koreanName }

        /**
         * Lookup PotentialGrade by Korean name.
         *
         * @param korean Korean grade name (e.g., "레어", "에픽", "유니크", "레전드리")
         * @return Matching PotentialGrade
         * @throws IllegalArgumentException if Korean name is invalid
         */
        fun fromKorean(korean: String?): PotentialGrade {
            if (korean == null) {
                throw IllegalArgumentException("Potential grade cannot be null")
            }
            return KOREAN_MAP[korean.trim()]
                ?: throw IllegalArgumentException("Invalid potential grade: $korean")
        }
    }
}
