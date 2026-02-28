package maple.expectation.core.port.out

/**
 * Outbox Processor Port - Outbox 패턴 처리를 위한 인터페이스
 *
 * <h3>역할</h3>
 * <p>Outbox 패턴의 폴링 및 처리, 복구 작업을 정의합니다.
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>module-app/service/v2/donation/outbox/OutboxProcessor
 * </ul>
 *
 * @see OutboxMetricsPort
 */
interface OutboxProcessorPort {
    /**
     * Outbox 폴링 및 처리
     *
     * <p>PENDING 상태의 메시지를 조회하여 처리 후 COMPLETED로 변경
     */
    fun pollAndProcess()

    /**
     * Stalled 상태 복구
     *
     * <p>JVM 크래시 등으로 PROCESSING 상태에서 멈춘 항목을 PENDING으로 복원
     */
    fun recoverStalled()
}
