package maple.expectation.core.model.job

enum class CalculationJobStatus {
    REQUESTED,
    API_REQUESTED,
    SNAPSHOT_READY,
    CALCULATING,
    COMPLETED,
    FAILED,
    RETRYING
}
