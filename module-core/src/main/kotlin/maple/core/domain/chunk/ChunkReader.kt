package maple.core.domain.chunk

interface ChunkReader<T> {
    suspend fun read(chunk: Chunk<Unit>): Chunk<T>
}
