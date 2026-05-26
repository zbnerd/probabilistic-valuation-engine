package maple.expectation.common.event

enum class ChunkExecutionStatus {
    PENDING,
    PROCESSING,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    SUCCEEDED,
}
