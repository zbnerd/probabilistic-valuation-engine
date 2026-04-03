package maple.expectation.core.port.inbound

/**
 * Task 상태 Enum (ADR-355)
 */
enum class TaskStatus {
    /** PGMQ에 대기 중 */
    PENDING,

    /** Worker가 처리 중 */
    PROCESSING,

    /** 완료 (PostgreSQL에 결과 존재) */
    COMPLETED,

    /** 실패 (DLQ 또는 max retry 초과) */
    FAILED,

    /** 알 수 없는 taskId */
    NOT_FOUND,
}
