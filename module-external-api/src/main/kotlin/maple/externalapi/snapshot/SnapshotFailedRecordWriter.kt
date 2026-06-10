package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import java.io.ByteArrayOutputStream

/**
 * Read-modify-write of `runs/$runKey/failed.jsonl`. S3 has no native append,
 * so we read the existing object, append a line, and put it back. Acceptable
 * for low volume (failures are rare in healthy runs).
 */
class SnapshotFailedRecordWriter(
    private val runKey: String,
    private val objectMapper: ObjectMapper,
    private val objectStorage: ObjectStorage,
) {
    private val key = "$runKey/failed.jsonl"
    private var count: Int = 0

    fun append(record: SnapshotChunkRecord.Failure) {
        val existing = runCatching { objectStorage.get(key) }.getOrDefault(ByteArray(0))
        val out = ByteArrayOutputStream(existing.size + 256)
        out.write(existing)
        if (existing.isNotEmpty() && existing.last() != '\n'.code.toByte()) out.write('\n'.code)
        out.write(objectMapper.writeValueAsBytes(record))
        out.write('\n'.code)
        objectStorage.put(key, out.toByteArray())
        count++
    }

    fun count(): Int = count
}
