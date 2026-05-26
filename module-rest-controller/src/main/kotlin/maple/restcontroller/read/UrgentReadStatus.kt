package maple.restcontroller.read

enum class UrgentReadState {
    PENDING,
    READY,
    NOT_FOUND,
    UNKNOWN,
}

data class UrgentReadStatusResponse(
    val state: UrgentReadState,
    val userIgn: String,
    val statusUrl: String,
    val queuePositionApprox: Long?,
    val estimatedWaitSeconds: Long?,
    val retryAfterSeconds: Long,
)
