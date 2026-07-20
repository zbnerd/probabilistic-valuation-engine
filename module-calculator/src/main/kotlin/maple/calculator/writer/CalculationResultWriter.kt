package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import maple.calculator.model.CalculationResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.write.ArtifactReceipt
import maple.pipeline.artifact.write.ArtifactWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CalculationResultWriter(
    private val artifactWriter: ArtifactWriter,
    private val objectMapper: ObjectMapper,
    // Nullable so tests can inject a TestScope without conflicting with
    // @PreDestroy. If null, we create + own a default scope and cancel
    // it on bean destroy. If injected, the caller owns the lifecycle.
    private val injectedProducerScope: CoroutineScope? = null,
) {
    private val log = LoggerFactory.getLogger(CalculationResultWriter::class.java)

    private val producerScope: CoroutineScope =
        injectedProducerScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val ownsProducerScope: Boolean = injectedProducerScope == null

    @PreDestroy
    fun close() {
        if (ownsProducerScope) {
            producerScope.cancel()
        }
    }

    data class WriteResult(
        val objectKey: String,
        val resultCount: Long,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
        val contentSha256: String,
        val backendTag: String?,
    )

    /**
     * Drain calculation results through one [ArtifactWriter]-owned gzip
     * session. The receipt is mapped to workload counters only after upload
     * and writer-owned cleanup complete.
     *
     * Returns [CompletableFuture]. Callers MUST chain via
     * `thenApply` / `thenAccept` / `.await()` - never `.join()` / `.get()`
     * in production.
     */
    fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): CompletableFuture<WriteResult> {
        val counters = WriteCounters()
        val writeFuture = runCatching {
            producerScope.future {
                val session = artifactWriter.openGzip(ArtifactKey.require(objectKey))
                session.use { openSession ->
                    val uncompressedBytes = writeResults(openSession.output, results, counters)
                    val receipt = openSession.complete(uncompressedBytes).await()
                    receipt.toWriteResult(counters.records.get())
                }
            }
        }.getOrElse { failure ->
            CompletableFuture.failedFuture(failure)
        }
        writeFuture.whenComplete { _, failure -> logFailure(objectKey, failure) }
        return writeFuture
    }

    private suspend fun writeResults(
        output: OutputStream,
        results: Flow<CalculationResult>,
        counters: WriteCounters,
    ): Long {
        val countingOutput = CountingOutputStream(output)
        objectMapper.factory.createGenerator(countingOutput).use { generator ->
            results.collect { result -> writeResult(generator, result, counters) }
        }
        return countingOutput.count
    }

    private fun writeResult(
        generator: com.fasterxml.jackson.core.JsonGenerator,
        result: CalculationResult,
        counters: WriteCounters,
    ) {
        counters.records.incrementAndGet()
        generator.writeObject(result)
        generator.writeRaw('\n')
    }

    private fun ArtifactReceipt.toWriteResult(resultCount: Long): WriteResult = WriteResult(
        objectKey = key.value,
        resultCount = resultCount,
        uncompressedBytes = uncompressedBytes,
        compressedBytes = compressedBytes,
        contentSha256 = contentSha256,
        backendTag = backendTag,
    )

    private fun logFailure(objectKey: String, failure: Throwable?) {
        if (failure != null) {
            log.error("[CalculationResultWriter] write failed for key={}", objectKey, failure)
        }
    }
}
