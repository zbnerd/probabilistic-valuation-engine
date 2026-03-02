package maple.expectation.core.dto.like

/**
 * 원자적 fetch 결과 DTO (Immutable Data Class)
 *
 * 금융수준 안전 설계:
 * - tempKey 보존으로 JVM 크래시 시 복구 가능
 * - 불변 Map으로 Thread-Safe 보장
 * - empty() 팩토리 메서드로 null 회피
 *
 * @param tempKey 임시 키 (복구용 - Hash Tag 패턴: {buffer:likes}:sync:{uuid})
 * @param data fetch된 데이터 (userIgn -> count)
 */
data class FetchResult(
    @get:JvmName("tempKey")
    val tempKey: String?,
    @get:JvmName("data")
    val data: Map<String, Long> = emptyMap()
) {
    companion object {
        /** 빈 결과 생성 (Empty Object Pattern) */
        @JvmStatic
        fun empty(): FetchResult = FetchResult(null, emptyMap())
    }

    /** 데이터 존재 여부 확인 */
    fun isEmpty(): Boolean = data.isEmpty()

    /** 데이터 건수 */
    fun size(): Int = data.size

    /** 총 count 합계 (메트릭용) */
    fun totalCount(): Long = data.values.sum()
}
