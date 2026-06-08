package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.adapter.chunk.ChunkPipelineOrchestrator
import org.springframework.stereotype.Component

/**
 * Thin delegate to [ChunkPipelineOrchestrator]. Retained for backward compatibility with
 * consumers that depend on the [ChunkProcessor] interface (e.g. `KafkaResultChunkConsumer`).
 * New stage composition should happen in the orchestrator. To be removed in a follow-up
 * issue once the consumer migrates to inject `ChunkPipelineOrchestrator` directly.
 */
@Deprecated(
    message = "Use ChunkPipelineOrchestrator directly. This delegate will be removed.",
    replaceWith = ReplaceWith("ChunkPipelineOrchestrator", "maple.synchronizer.adapter.chunk.ChunkPipelineOrchestrator"),
)
@Component
class DefaultChunkProcessor(
    private val orchestrator: ChunkPipelineOrchestrator,
) : ChunkProcessor {

    override fun process(input: ChunkProcessInput): ChunkProcessResult = orchestrator.execute(input)
}
