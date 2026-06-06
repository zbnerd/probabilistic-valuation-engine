package maple.core.domain.chunk

interface ChunkWriter<T> {
    suspend fun write(chunk: Chunk<T>): Chunk<Unit>
}
