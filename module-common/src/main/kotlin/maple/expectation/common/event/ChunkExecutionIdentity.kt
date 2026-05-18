package maple.expectation.common.event

data class ChunkExecutionIdentity(
    val executionType: ChunkExecutionType,
    val runId: String,
    val endpoint: String,
    val chunkId: String,
)
