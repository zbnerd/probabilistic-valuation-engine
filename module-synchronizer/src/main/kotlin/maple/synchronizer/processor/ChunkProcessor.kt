package maple.synchronizer.processor

import maple.synchronizer.event.CalculatorResultChunkReadyEvent

interface ChunkProcessor {
    fun process(event: CalculatorResultChunkReadyEvent): ChunkProcessResult
}
