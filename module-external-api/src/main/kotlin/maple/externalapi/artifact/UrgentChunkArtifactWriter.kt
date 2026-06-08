package maple.externalapi.artifact

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import maple.externalapi.snapshot.GzipJsonlChunkWriter
import maple.externalapi.snapshot.SnapshotChunkRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Writes a single urgent chunk record as a GZIP JSONL file under the artifact
 * store base path. Encapsulates `Files.createDirectories` and
 * `GzipJsonlChunkWriter` so the urgent consumer never touches `Files` or
 * `GZIP` primitives directly.
 */
@Component
class UrgentChunkArtifactWriter(
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Appends [record] to a fresh chunk file under
     * `runs/{runId}/{endpointDir}/chunks/` and returns the relative
     * `runs/{runId}/{endpointDir}/chunks/part-000001.jsonl.gz` object key.
     */
    fun writeChunk(
        runId: String,
        endpointDir: String,
        record: SnapshotChunkRecord.Success,
    ): String {
        val chunksDir = Path.of(storeBasePath, "runs", runId, endpointDir, "chunks")
        Files.createDirectories(chunksDir)
        val writer = GzipJsonlChunkWriter(chunksDir, 1, 1, Long.MAX_VALUE, objectMapper)
        writer.append(record)
        val stats = writer.close()
        return "runs/$runId/$endpointDir/${stats.path}"
    }
}
