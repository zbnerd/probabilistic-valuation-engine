package maple.core.domain.chunk

data class Chunk<T>(
    val input: ChunkProcessInput,
    val data: T,
    val metadata: Map<String, String> = emptyMap(),
)
