package maple.synchronizer.consumer

sealed interface ChunkLifecycleEvent {
    data class Accepted(
        val runId: String,
        val chunkId: String,
    ) : ChunkLifecycleEvent

    data class Succeeded(
        val runId: String,
        val chunkId: String,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        val resultCount: Long,
        val durationNanos: Long,
    ) : ChunkLifecycleEvent

    data class Failed(
        val runId: String,
        val chunkId: String,
    ) : ChunkLifecycleEvent

    data class Finally(
        val runId: String,
        val chunkId: String,
    ) : ChunkLifecycleEvent
}
