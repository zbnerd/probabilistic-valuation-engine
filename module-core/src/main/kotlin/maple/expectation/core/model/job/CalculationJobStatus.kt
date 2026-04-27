package maple.expectation.core.model.job

enum class CalculationJobStatus {
    REQUESTED,
    OCID_RESOLVING,
    OCID_RESOLVED,
    API_REQUESTED,
    SNAPSHOT_READY,
    CALCULATING,
    COMPLETED,
    FAILED,
    OCID_RETRY_WAIT,
    API_RETRY_WAIT,
    RETRYING
}
