package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

data class ChunkStats(
    val partIndex: Int,
    val path: String,
    val recordCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val startedAt: Instant,
    val finishedAt: Instant,
)

class GzipJsonlChunkWriter(
    private val chunksDir: Path,
    private val partIndex: Int,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val startedAt = Instant.now(clock)
    private val tmpFile: Path = chunksDir.resolve(String.format("part-%06d.jsonl.gz.tmp", partIndex))
    private val finalFile: Path = chunksDir.resolve(String.format("part-%06d.jsonl.gz", partIndex))

    private val fos = FileOutputStream(tmpFile.toFile())
    private val bos = BufferedOutputStream(fos)
    private val gzip = java.util.zip.GZIPOutputStream(bos)

    private var recordCount = 0
    private var uncompressedBytes = 0L

    fun append(record: SnapshotChunkRecord.Success) {
        require(record.bodyBytes.isNotEmpty()) { "bodyBytes must not be empty for key=${record.key}" }

        val line = buildRecordLine(record)
        gzip.write(line)
        gzip.flush()

        recordCount++
        uncompressedBytes += line.size
    }

    fun shouldRotate(): Boolean =
        recordCount >= maxRecords || uncompressedBytes >= maxUncompressedBytes

    fun close(): ChunkStats {
        gzip.finish()
        bos.flush()
        fos.fd.sync()
        fos.close()

        if (recordCount == 0) {
            Files.deleteIfExists(tmpFile)
            return ChunkStats(
                partIndex = partIndex,
                path = "chunks/${finalFile.fileName}",
                recordCount = 0,
                uncompressedBytes = 0,
                compressedBytes = 0,
                startedAt = startedAt,
                finishedAt = Instant.now(clock),
            )
        }

        Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val compressedBytes = Files.size(finalFile)

        return ChunkStats(
            partIndex = partIndex,
            path = "chunks/${finalFile.fileName}",
            recordCount = recordCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBytes,
            startedAt = startedAt,
            finishedAt = Instant.now(clock),
        )
    }

    fun deleteTmp() {
        try {
            gzip.close()
        } catch (_: Exception) {
        }
        Files.deleteIfExists(tmpFile)
    }

    private fun buildRecordLine(record: SnapshotChunkRecord.Success): ByteArray {
        val buf = java.io.ByteArrayOutputStream()

        // metadata prefix
        buf.write("{\"endpoint\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.endpoint))
        buf.write(",\"keyType\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.keyType))
        buf.write(",\"key\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.key))
        buf.write(",\"status\":\"SUCCESS\",\"httpStatus\":".toByteArray())
        buf.write(record.httpStatus.toString().toByteArray())
        buf.write(",\"fetchedAt\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.fetchedAt.toString()))
        buf.write(",\"body\":".toByteArray())

        // body: raw JSON bytes directly (no parse/re-serialize)
        buf.write(record.bodyBytes)

        // closing
        buf.write("}\n".toByteArray())

        return buf.toByteArray()
    }
}
