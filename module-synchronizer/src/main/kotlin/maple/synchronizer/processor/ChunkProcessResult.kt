package maple.synchronizer.processor

data class ChunkProcessResult(
    val documentCount: Int,
    val itemCount: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val jsonRowCount: Long,
)
