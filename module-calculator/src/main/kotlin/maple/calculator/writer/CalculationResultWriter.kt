package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.channels.ReceiveChannel
import maple.calculator.processor.CalculationResult
import maple.calculator.storage.ObjectStorage
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
        results: ReceiveChannel<CalculationResult>,
    ): WriteResult {
        val compressedCounter = CountingOutputStream(objectStorage.openOutputStream(objectKey))
        val gzipStream = GZIPOutputStream(compressedCounter)
        val uncompressedCounter = CountingOutputStream(gzipStream)
        var resultCount = 0

        objectMapper.factory.createGenerator(uncompressedCounter).use { generator ->
            for (result in results) {
                generator.writeObject(result)
                generator.writeRaw('\n')
                resultCount += 1
            }
        }

        log.info(
            "[Writer] wrote calculator result chunk: objectKey={} results={} uncompressedBytes={} compressedBytes={}",
            objectKey,
            resultCount,
            uncompressedCounter.bytesWritten,
            compressedCounter.bytesWritten,
        )
        return WriteResult(objectKey, resultCount, uncompressedCounter.bytesWritten, compressedCounter.bytesWritten)
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
