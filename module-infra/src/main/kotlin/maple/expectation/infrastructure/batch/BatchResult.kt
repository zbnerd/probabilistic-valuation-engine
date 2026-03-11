package maple.expectation.infrastructure.batch

/**
 * 배치 실행 결과 메트릭
 *
 * @param totalRequests 총 요청 수
 * @param uniqueKeys 고유 키 수 (중복 제거)
 * @param dbQueries DB 쿼리 실행 횟수 (Chunk 단위)
 * @param cacheHits 캐시 적중 수
 * @param durationMs 실행 시간 (ms)
 */
data class BatchResult(
    val totalRequests: Int,
    val uniqueKeys: Int,
    val dbQueries: Int,
    val cacheHits: Int,
    val durationMs: Long,
) {
    companion object {
        val EMPTY = BatchResult(
            totalRequests = 0,
            uniqueKeys = 0,
            dbQueries = 0,
            cacheHits = 0,
            durationMs = 0,
        )
    }
}
