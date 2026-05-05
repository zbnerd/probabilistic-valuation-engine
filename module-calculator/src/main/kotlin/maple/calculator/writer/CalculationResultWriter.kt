package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
        val countingStream = CountingOutputStream(objectStorage.openOutputStream(objectKey))
        var resultCount = 0
        var uncompressedBytes = 0L

        BufferedWriter(OutputStreamWriter(GZIPOutputStream(countingStream), StandardCharsets.UTF_8)).use { writer ->
            for (result in results) {
                val line = objectMapper.writeValueAsString(result)
                writer.write(line)
                writer.newLine()
                resultCount += 1
                uncompressedBytes += line.toByteArray(StandardCharsets.UTF_8).size + 1
            }
        }

        log.info(
            "[Writer] wrote calculator result chunk: objectKey={} results={} uncompressedBytes={} compressedBytes={}",
            objectKey,
            resultCount,
            uncompressedBytes,
            countingStream.bytesWritten,
        )
        return WriteResult(objectKey, resultCount, uncompressedBytes, countingStream.bytesWritten)
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
