package maple.synchronizer.processor

data class ChunkProcessResult(
    val documentCount: Int,
    val itemCount: Long,
    val jsonRowCount: Long,
)
