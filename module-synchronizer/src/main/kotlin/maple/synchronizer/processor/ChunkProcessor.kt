package maple.synchronizer.processor

interface ChunkProcessor {
    fun process(input: ChunkProcessInput): ChunkProcessResult
}
