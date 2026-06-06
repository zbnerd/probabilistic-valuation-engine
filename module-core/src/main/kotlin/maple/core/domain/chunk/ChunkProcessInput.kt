package maple.core.domain.chunk

data class ChunkProcessInput(
    val objectKey: String,
    val sourceRunId: String,
    val sourceChunkId: String,
    val resultCount: Int,
)
