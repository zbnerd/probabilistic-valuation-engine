package maple.expectation.web.dto.page

/**
 * Cursor-based Pagination 요청 (#233)
 *
 * @param cursor 마지막으로 조회한 커서 (ID)
 * @param size 페이지 크기
 */
data class CursorPageRequest(
    val cursor: Long?,
    val size: Int
) {
    companion object {
        private const val DEFAULT_SIZE = 20
        private const val MAX_SIZE = 100

        @JvmStatic
        fun of(cursor: Long?, size: Int): CursorPageRequest {
            val validSize = if (size <= 0) DEFAULT_SIZE else minOf(size, MAX_SIZE)
            return CursorPageRequest(cursor, validSize)
        }

        @JvmStatic
        fun firstPage(): CursorPageRequest {
            return CursorPageRequest(null, DEFAULT_SIZE)
        }

        fun firstPage(size: Int): CursorPageRequest {
            return of(null, size)
        }
    }
}
