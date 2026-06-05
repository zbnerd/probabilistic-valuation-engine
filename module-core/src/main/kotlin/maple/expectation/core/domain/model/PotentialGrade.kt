package maple.expectation.core.domain.model

import maple.expectation.error.exception.InvalidPotentialGradeException

/**
 * 잠재능력 등급 Enum
 *
 * <p>큐브 사용 시 입력되는 잠재능력 등급의 유효성을 검증합니다. 잘못된 등급 입력 시 Silent Failure(0원 반환) 대신 명시적 예외를 발생시킵니다.
 *
 * @see InvalidPotentialGradeException
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
         * 한글 등급명으로 PotentialGrade를 조회합니다.
         *
         * @param korean 한글 등급명 (예: "레어", "에픽", "유니크", "레전드리")
         * @return 매칭되는 PotentialGrade
         * @throws InvalidPotentialGradeException 유효하지 않은 등급명인 경우
         */
        @JvmStatic
        fun fromKorean(korean: String?): PotentialGrade {
            if (korean == null) {
                throw InvalidPotentialGradeException("null")
            }
            return KOREAN_MAP[korean.trim()]
                ?: throw InvalidPotentialGradeException(korean)
        }
    }
}
