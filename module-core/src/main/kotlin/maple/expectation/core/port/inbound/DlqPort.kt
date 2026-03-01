package maple.expectation.core.port.inbound

/**
 * DLQ 관리 Port 인터페이스 (ADR-005)
 *
 * <p>책임: Dead Letter Queue 관리
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/DlqPortAdapter - DlqAdminService에 위임
 * </ul>
 */
interface DlqPort {

    /**
     * DLQ 목록 조회 (페이징)
     */
    fun findAll(page: Int, size: Int): Any

    /**
     * DLQ 상세 조회
     */
    fun findById(id: Long): Any

    /**
     * DLQ 재처리
     */
    fun reprocess(id: Long): Any

    /**
     * DLQ 폐기
     */
    fun discard(id: Long)

    /**
     * DLQ 총 건수
     */
    fun count(): Long

    /**
     * DLQ 목록 조회 (Cursor-based Pagination)
     *
     * @param cursor 마지막 ID (null이면 첫 페이지)
     * @param size 페이지 크기
     * @return Cursor 기반 페이지 응답
     */
    fun findAllByCursor(cursor: Long?, size: Int): Any
}
