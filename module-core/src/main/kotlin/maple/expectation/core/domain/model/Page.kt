package maple.expectation.core.domain.model

/**
 * 간단한 페이지네이션 결과 컨테이너
 *
 * <p>Spring Data의 Page/Pageable 의존성을 제거하기 위한 순수 도메인 타입입니다.
 * module-core는 Spring Framework에 의존하지 않도록 설계되었습니다 (ADR-017).
 *
 * @property T 페이지에 포함될 요소의 타입
 * @property content 현재 페이지의 컨텐츠 목록
 * @property pageNumber 현재 페이지 번호 (0-based)
 * @property pageSize 페이지 크기
 * @property totalElements 전체 요소 개수
 * @property hasNext 다음 페이지가 존재하는지 여부
 */
data class Page<T>(
    val content: List<T>,
    val pageNumber: Int,
    val pageSize: Int,
    val totalElements: Long,
    val hasNext: Boolean
) {
    /**
     * 전체 페이지 수 계산
     */
    val totalPages: Int
        get() = if (pageSize == 0) 0 else ((totalElements / pageSize).toInt() + if (totalElements % pageSize != 0L) 1 else 0)

    /**
     * 현재 페이지가 첫 번째 페이지인지 여부
     */
    val isFirst: Boolean
        get() = pageNumber == 0

    /**
     * 현재 페이지가 마지막 페이지인지 여부
     */
    val isLast: Boolean
        get() = !hasNext

    /**
     * 빈 페이지 생성 (결과가 없는 경우)
     */
    companion object {
        fun <T> empty(): Page<T> = Page(
            content = emptyList(),
            pageNumber = 0,
            pageSize = 0,
            totalElements = 0,
            hasNext = false
        )
    }

    /**
     * 현재 페이지에 요소가 없는지 확인
     */
    fun isEmpty(): Boolean = content.isEmpty()

    /**
     * 현재 페이지에 요소가 있는지 확인
     */
    fun isNotEmpty(): Boolean = content.isNotEmpty()
}

/**
 * 페이지네이션 요청 파라미터
 *
 * @property page 페이지 번호 (0-based)
 * @property size 페이지 크기
 */
data class PageRequest(
    val page: Int = 0,
    val size: Int = 10
) {
    init {
        require(page >= 0) { "Page index must not be less than zero" }
        require(size > 0) { "Page size must be greater than zero" }
    }

    /**
     * 첫 번째 페이지 요청 생성
     */
    fun first(): PageRequest = PageRequest(page = 0, size = size)

    /**
     * 다음 페이지 요청 생성
     */
    fun next(): PageRequest = PageRequest(page = page + 1, size = size)

    companion object {
        /**
         * 기본 페이지 요청 생성 (page=0, size=10)
         */
        fun of(page: Int = 0, size: Int = 10): PageRequest = PageRequest(page, size)
    }
}
