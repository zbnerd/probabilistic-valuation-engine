package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.time.Instant

data class SnapshotChunkManifest(
    val runId: String,
    val endpoint: String,
    val startedAt: Instant,
    var finishedAt: Instant = Instant.EPOCH,
    val chunks: MutableList<ChunkEntry> = mutableListOf(),
    var totalRecords: Int = 0,
    var totalFailed: Int = 0,
)

data class ChunkEntry(
    val path: String,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val startedAt: Instant,
    val finishedAt: Instant,
)

class SnapshotChunkManifestWriter(
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
) {
    fun write(runKey: String, manifest: SnapshotChunkManifest) {
        val manifestKey = "$runKey/manifest.json"
        val bytes = objectMapper.writeValueAsBytes(manifest)
        objectStorage.put(manifestKey, bytes)
    }
}
