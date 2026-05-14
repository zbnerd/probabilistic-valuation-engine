package maple.synchronizer.processor

import maple.expectation.common.event.CalculatorResultChunkReadyEvent

interface ChunkProcessor {
    fun process(event: CalculatorResultChunkReadyEvent): ChunkProcessResult
}
