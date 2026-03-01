package maple.expectation.web.dto.common

import org.springframework.data.domain.Slice

/**
 * Cursor-based Pagination response (#233)
 */
data class CursorPageResponse<T>(val content: List<T>, val nextCursor: Long?, val hasNext: Boolean, val size: Int) {
    companion object {
        @JvmStatic
        fun <T> from(slice: Slice<T>, idExtractor: (T) -> Long): CursorPageResponse<T> {
            val content = slice.content
            val nextCursor = if (content.isEmpty()) null else idExtractor(content.last())
            return CursorPageResponse(content, nextCursor, slice.hasNext(), content.size)
        }

        @JvmStatic
        fun <E, D> fromWithMapping(slice: Slice<E>, mapper: (E) -> D, idExtractor: (E) -> Long): CursorPageResponse<D> {
            val entities = slice.content
            val content = entities.map(mapper)
            val nextCursor = if (entities.isEmpty()) null else idExtractor(entities.last())
            return CursorPageResponse(content, nextCursor, slice.hasNext(), content.size)
        }

        @JvmStatic
        fun <T> empty(): CursorPageResponse<T> = CursorPageResponse(emptyList(), null, false, 0)
    }
}
