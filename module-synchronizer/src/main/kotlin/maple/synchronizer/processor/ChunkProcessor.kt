package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput

interface ChunkProcessor {
    fun process(input: ChunkProcessInput): ChunkProcessResult
}
