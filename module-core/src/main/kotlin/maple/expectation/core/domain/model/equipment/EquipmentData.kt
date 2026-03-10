package maple.expectation.core.domain.model.equipment

/**
 * 장비 데이터 도메인 모델
 *
 * <p>순수 도메인 - JPA 의존 없음
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 장비 JSON 데이터 표현만 담당
 *   <li>OCP: 불변 data class로 안전한 상태 보장
 * </ul>
 */
data class EquipmentData(private val json: String) {

    init {
        require(json.isNotBlank()) { "json cannot be null or blank" }
    }

    /** JSON 컨텐츠 반환 */
    fun jsonContent(): String = json

    /** 비어있는지 여부 확인 */
    fun isEmpty(): Boolean = json.isBlank() || json.trim() == "{}"

    /** 비어있지 않은지 여부 확인 */
    fun isNotEmpty(): Boolean = !isEmpty()

    /** JSON 길이 반환 */
    fun length(): Int = json.length

    companion object {
        /** 빈 장비 데이터 생성 */
        @JvmStatic
        fun empty(): EquipmentData = EquipmentData("{}")

        /** JSON으로부터 생성 */
        @JvmStatic
        fun of(json: String): EquipmentData = EquipmentData(json)
    }
}
