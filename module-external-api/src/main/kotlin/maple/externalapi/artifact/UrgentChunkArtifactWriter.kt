package maple.externalapi.artifact

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletionStage
import maple.externalapi.snapshot.GzipJsonlChunkWriter
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.ArtifactWriter
import org.springframework.stereotype.Component

/**
 * Writes a single urgent chunk record through the shared artifact lifetime.
 * Encapsulates [GzipJsonlChunkWriter] so the urgent consumer never touches
 * file or gzip primitives directly.
 */
@Component
class UrgentChunkArtifactWriter(
    private val objectMapper: ObjectMapper,
    private val artifactWriter: ArtifactWriter,
) {
    /**
     * Appends [record] to a fresh chunk object under
     * `runs/{runId}/{endpointDir}/chunks/part-{uuid}.jsonl.gz` and returns its
     * receipt after upload. The UUID suffix prevents concurrent urgent writes
     * for one run and endpoint from clobbering each other.
     */
    fun writeChunk(
        runId: String,
        endpointDir: String,
        record: SnapshotChunkRecord.Success,
    ): CompletionStage<ArtifactReceipt> {
        val chunkKey = SourceArtifactLayout.chunk(runId, endpointDir, "part-${UUID.randomUUID()}")
        val writer = GzipJsonlChunkWriter(
            chunkKey = chunkKey,
            partIndex = 1,
            maxRecords = 1,
            maxUncompressedBytes = Long.MAX_VALUE,
            objectMapper = objectMapper,
            artifactWriter = artifactWriter,
        )
        writer.append(record)
        return writer.close().uploadFuture
    }
}
