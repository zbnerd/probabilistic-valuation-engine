package maple.synchronizer.processor

data class ChunkProcessInput(
    val objectKey: String,
    val sourceRunId: String,
    val sourceChunkId: String,
    val resultCount: Int,
)
