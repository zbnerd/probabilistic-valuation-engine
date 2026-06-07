package maple.cleanup.controller

data class InboxCleanupResponse(
    val drained: Int,
    val deleted: Int,
    val failed: Int,
)
