package maple.expectation.core.flame.config

import maple.expectation.core.probability.FlameScoreCalculator.JobWeights

/**
 * 직업명 → 주스탯/부스탯 매핑 유틸리티
 *
 * Nexon API의 {@code character_class} 문자열로부터 직업 가중치(JobWeights)를 결정합니다.
 */
object JobStatMapping {

    /** STR 주스탯 + DEX 부스탯 */
    private val STR_DEX_JOBS = setOf(
        "히어로", "팔라딘", "다크나이트", "소울마스터", "미하일", "블래스터", "데몬슬레이어", "아란", "아델", "카이저", "제로", "바이퍼",
        "캐논슈터", "스트라이커", "아크", "은월", "제트",
    )

    /** DEX 주스탯 + STR 부스탯 */
    private val DEX_STR_JOBS = setOf("보우마스터", "신궁", "패스파인더", "윈드브레이커", "와일드헌터", "카인", "메르세데스", "캡틴", "메카닉", "엔젤릭버스터")

    /** INT 주스탯 + LUK 부스탯 */
    private val INT_LUK_JOBS = setOf(
        "아크메이지(불,독)",
        "아크메이지(썬,콜)",
        "비숍",
        "플레임위자드",
        "에반",
        "루미너스",
        "키네시스",
        "일리움",
        "라라",
        "이벤져",
        "리엔",
    )

    /** LUK 주스탯 + DEX 부스탯 */
    private val LUK_DEX_JOBS = setOf("나이트로드", "나이트워커", "팬텀", "칼리", "호영")

    /** LUK 주스탯 + STR,DEX 부스탯 2개 */
    private val LUK_STR_DEX_JOBS = setOf("섀도어", "듀얼블레이드", "카데나")

    /** 직업명 → JobWeights 캐시 (불변 Map) */
    private val SPECIAL_JOBS = mapOf(
        "제논" to JobWeights.xenon(),
        "데몬어벤져" to JobWeights.demonAvenger(),
    )

    /**
     * 직업명으로 JobWeights 결정
     *
     * @param characterClass Nexon API의 character_class 문자열
     * @return 해당 직업의 가중치, 매핑 실패 시 STR/DEX 기본값
     */
    fun resolve(characterClass: String?): JobWeights {
        if (characterClass.isNullOrBlank()) {
            return JobWeights.of("STR", "DEX")
        }

        return SPECIAL_JOBS[characterClass]
            ?: when (characterClass) {
                in STR_DEX_JOBS -> JobWeights.of("STR", "DEX")
                in DEX_STR_JOBS -> JobWeights.of("DEX", "STR")
                in INT_LUK_JOBS -> JobWeights.of("INT", "LUK")
                in LUK_DEX_JOBS -> JobWeights.of("LUK", "DEX")
                in LUK_STR_DEX_JOBS -> JobWeights.of("LUK", "STR", "DEX")
                else -> JobWeights.of("STR", "DEX")
            }
    }
}
