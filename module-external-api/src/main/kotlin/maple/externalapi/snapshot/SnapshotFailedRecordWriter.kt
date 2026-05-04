package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class SnapshotFailedRecordWriter(
    private val filePath: Path,
    private val objectMapper: ObjectMapper,
) {
    private var count = 0

    fun append(record: SnapshotChunkRecord.Failure) {
        val line = buildFailureLine(record)
        Files.write(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        count++
    }

    fun count(): Int = count

    private fun buildFailureLine(record: SnapshotChunkRecord.Failure): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        buf.write("{\"endpoint\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.endpoint))
        buf.write(",\"keyType\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.keyType))
        buf.write(",\"key\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.key))
        buf.write(",\"status\":\"FAILURE\",\"httpStatus\":".toByteArray())
        buf.write(record.httpStatus.toString().toByteArray())
        buf.write(",\"fetchedAt\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.fetchedAt.toString()))
        buf.write(",\"error\":".toByteArray())
        buf.write(objectMapper.writeValueAsBytes(record.errorMessage))
        buf.write("}\n".toByteArray())
        return buf.toByteArray()
    }
}
