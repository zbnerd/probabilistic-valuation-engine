package maple.expectation.core.model.job

enum class CalculationJobStatus {
    REQUESTED,
    OCID_RESOLVING,
    API_REQUESTED,
    SNAPSHOT_READY,
    CALCULATING,
    COMPLETED,
    FAILED,
    RETRYING,
}
