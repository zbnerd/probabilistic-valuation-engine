package maple.cleanup.controller

data class InboxCleanupResponse(
    val scanned: Int,
    val completed: Int,
    val retainedForRetry: Int,
    val deletedTargets: Int,
)
