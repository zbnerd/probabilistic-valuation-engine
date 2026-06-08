package maple.calculator.event

sealed interface ChunkProcessingEvent {
    val runId: String
    val chunkId: String

    data class Skipped(
        override val runId: String,
        override val chunkId: String,
        val reason: String,
    ) : ChunkProcessingEvent

    data class Failed(
        override val runId: String,
        override val chunkId: String,
    ) : ChunkProcessingEvent

    data class Completed(
        override val runId: String,
        override val chunkId: String,
        val recordCount: Int,
        val totalItems: Int,
        val resultCount: Int,
        val errorCount: Int,
        val inputCompressedBytes: Long,
        val inputUncompressedBytes: Long,
        val resultCompressedBytes: Long,
        val resultUncompressedBytes: Long,
        val durationNanos: Long,
    ) : ChunkProcessingEvent
}
