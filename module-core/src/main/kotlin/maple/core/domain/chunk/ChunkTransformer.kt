package maple.core.domain.chunk

interface ChunkTransformer<T, R> {
    suspend fun transform(chunk: Chunk<T>): Chunk<R>
}
