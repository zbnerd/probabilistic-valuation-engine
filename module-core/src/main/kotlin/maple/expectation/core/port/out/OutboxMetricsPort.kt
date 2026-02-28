package maple.expectation.core.port.out

/**
 * Outbox Metrics Port - Outbox 메트릭 조회를 위한 인터페이스
 *
 * <h3>역할</h3>
 * <p>Outbox 상태 모니터링을 위한 메트릭 조회를 정의합니다.
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>module-app/service/v2/donation/outbox/OutboxMetrics
 * </ul>
 *
 * @see OutboxProcessorPort
 */
interface OutboxMetricsPort {
    /**
     * Pending 카운트 업데이트
     */
    fun updatePendingCount()

    /**
     * 전체 카운트 업데이트
     */
    fun updateTotalCount()

    /**
     * 현재 Outbox 크기 조회
     *
     * @return 현재 처리 대기 중인 메시지 수
     */
    fun getCurrentSize(): Long
}
