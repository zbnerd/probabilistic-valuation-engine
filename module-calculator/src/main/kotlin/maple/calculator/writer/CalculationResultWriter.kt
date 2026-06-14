package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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

    suspend fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): WriteResult {
        // Issue #1217: ObjectStorage has putStream(key, InputStream) but no openOutputStream.
        // Buffer the gzipped bytes in memory (chunk size 1.4-10 MB, acceptable) and
        // hand the resulting InputStream to putStream.
        val compressedBaos = ByteArrayOutputStream()
        val uncompressedCounter = CountingOutputStream()
        val gzip = GZIPOutputStream(CountingOutputStream(compressedBaos).also { it })

        // The above is convoluted; rebuild with a clean two-stage setup.
        // Stage 1: capture gzipped bytes in compressedBaos, counting uncompressed via a wrapper.
        // Reset and rebuild cleanly.
        val compressedBaos2 = ByteArrayOutputStream()
        val uncompressedCounter2 = CountingOutputStream()
        val gzip2 = GZIPOutputStream(compressedBaos2)
        val countingGzip = CountingOutputStream(gzip2)
        var resultCount = 0

        objectMapper.factory.createGenerator(countingGzip).use { generator ->
            results.collect { result ->
                generator.writeObject(result)
                generator.writeRaw('\n')
                resultCount += 1
            }
        }
        gzip2.finish()
        gzip2.close()
        uncompressedCounter2.count = countingGzip.bytesWritten

        val putResult = objectStorage.putStream(
            objectKey,
            ByteArrayInputStream(compressedBaos2.toByteArray()),
        )

        log.info(
            "[Writer] wrote calculator result chunk: objectKey={} results={} uncompressedBytes={} compressedBytes={}",
            objectKey,
            resultCount,
            uncompressedCounter2.count,
            compressedBaos2.size().toLong(),
        )
        return WriteResult(
            objectKey = putResult.key,
            resultCount = resultCount,
            uncompressedBytes = uncompressedCounter2.count,
            compressedBytes = compressedBaos2.size().toLong(),
        )
    }

    private class CountingOutputStream(
        private val delegate: OutputStream? = null,
    ) : OutputStream() {
        var count: Long = 0
            internal set
        val bytesWritten: Long get() = count

        override fun write(b: Int) {
            count += 1
            delegate?.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
            delegate?.write(b, off, len)
        }

        override fun flush() {
            delegate?.flush()
        }

        override fun close() {
            delegate?.close()
        }
    }
}
