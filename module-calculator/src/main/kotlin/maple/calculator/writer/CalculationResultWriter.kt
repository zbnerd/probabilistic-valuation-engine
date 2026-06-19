package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.Flow
import maple.calculator.model.CalculationResult
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CalculationResultWriter(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CalculationResultWriter::class.java)

    data class WriteResult(
        val objectKey: String,
        val resultCount: Int,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
    )

    @Suppress("RedundantSuspendModifier")
    suspend fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): WriteResult {
        // Legacy implementation using the new public CountingOutputStream
        // (replaces the buggy nested impl dropped in Task 1 of issue #1312).
        // Task 9 will rewrite this as a CF chain (Flow → gzip → 8MB pipe
        // → ObjectStorage.putStreamMultipart). For now, this preserves the
        // original behavior (full-chunk ByteArrayOutputStream buffering +
        // putStream upload) using the AtomicLong-backed counter.
        val compressedBaos = ByteArrayOutputStream()
        val countingGzip = CountingOutputStream(GZIPOutputStream(compressedBaos))
        var resultCount = 0

        objectMapper.factory.createGenerator(countingGzip).use { generator ->
            results.collect { result ->
                generator.writeObject(result)
                generator.writeRaw('\n')
                resultCount += 1
            }
        }
        val uncompressedBytes = countingGzip.count
        countingGzip.close()

        val putResult = objectStorage.putStream(
            objectKey,
            ByteArrayInputStream(compressedBaos.toByteArray()),
        )

        log.info(
            "[Writer] wrote calculator result chunk: objectKey={} results={} uncompressedBytes={} compressedBytes={}",
            objectKey,
            resultCount,
            uncompressedBytes,
            compressedBaos.size().toLong(),
        )
        return WriteResult(
            objectKey = putResult.key,
            resultCount = resultCount,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBaos.size().toLong(),
        )
    }
}
