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
     * `runs/{runId}/{endpointDir}/chunks/part-{uuid}.jsonl.gz` and returns
     * the object key. UUID suffix prevents concurrent urgent writes for the
     * same runId/endpoint from clobbering each other on the same key.
     */
    fun writeChunk(
        runId: String,
        endpointDir: String,
        record: SnapshotChunkRecord.Success,
    ): String {
        val chunkKey = "runs/$runId/$endpointDir/chunks/part-${java.util.UUID.randomUUID()}.jsonl.gz"
        val writer = GzipJsonlChunkWriter(
            chunkKey = chunkKey,
            partIndex = 1,
            maxRecords = 1,
            maxUncompressedBytes = Long.MAX_VALUE,
            objectMapper = objectMapper,
            objectStorage = objectStorage,
        )
        writer.append(record)
        writer.close()
        return chunkKey
    }
}
