package maple.externalapi.artifact

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import maple.externalapi.snapshot.GzipJsonlChunkWriter
import maple.externalapi.snapshot.SnapshotChunkRecord
import org.springframework.stereotype.Component

/**
 * Writes a single urgent chunk record as a GZIP JSONL object to [ObjectStorage].
 * Encapsulates [GzipJsonlChunkWriter] so the urgent consumer never touches
 * `Files` or `GZIP` primitives directly.
 */
@Component
class UrgentChunkArtifactWriter(
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
) {
    /**
     * Appends [record] to a fresh chunk object under
     * `runs/{runId}/{endpointDir}/chunks/part-000001.jsonl.gz` and returns
     * the object key `runs/{runId}/{endpointDir}/chunks/part-000001.jsonl.gz`.
     */
    fun writeChunk(
        runId: String,
        endpointDir: String,
        record: SnapshotChunkRecord.Success,
    ): String {
        val chunkKey = "runs/$runId/$endpointDir/chunks/part-000001"
        val writer = GzipJsonlChunkWriter(
            chunkKey = chunkKey,
            partIndex = 1,
            maxRecords = 1,
            maxUncompressedBytes = Long.MAX_VALUE,
            objectMapper = objectMapper,
            objectStorage = objectStorage,
        )
        writer.append(record)
        val stats = writer.close()
        return "runs/$runId/$endpointDir/chunks/${stats.path}"
    }
}
