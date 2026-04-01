package maple.expectation.core.port.out

/**
 * Nexon API Outbox Metrics Port - Nexon API Outbox 메트릭 조회를 위한 인터페이스
 *
 * <h3>역할</h3>
 * <p>Nexon API Outbox 상태 모니터링을 위한 메트릭 조회를 정의합니다.
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>module-infra/nexon/pgmq/NexonApiPgmqMetrics (PGMQ 기반)
 * </ul>
 *
 * @see NexonApiOutboxProcessorPort
 */
@Deprecated("Outbox 제거 완료. PGMQ Worker로 대체됨. 다음 릴리즈에서 삭제 예정.")
interface NexonApiOutboxMetricsPort {
    /**
     * Pending 카운트 업데이트
     */
    fun updatePendingCount()
}
