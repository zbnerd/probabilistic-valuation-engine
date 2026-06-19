package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.future
import maple.calculator.model.CalculationResult
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CalculationResultWriter(
    private val objectStorage: ObjectStorage,
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
        val etag: String?,
    )

    /**
     * Stream calculation results through gzip -> [ObjectStorage.putStreamMultipart].
     *
     * Producer (on [producerScope], IO dispatcher) collects the Flow and
     * writes to a pipe. Consumer is the S3 async client (or LocalFs
     * virtual-thread executor). The 8MB pipe provides natural backpressure:
     * when the consumer stalls, the pipe fills, the producer's
     * `pipeOutput.write()` blocks, the gzip blocks, the JsonGenerator
     * blocks, and `Flow.collect` suspends - no unbounded heap growth.
     *
     * Returns [CompletableFuture]. Callers MUST chain via
     * `thenApply` / `thenAccept` - never `.join()` / `.get()` in
     * production. The legacy suspend caller in `SnapshotChunkProcessor`
     * will be migrated in Task 10 to bridge through
     * `kotlinx.coroutines.future.await`.
     */
    fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): CompletableFuture<WriteResult> {
        val pipeInput = java.io.PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOutput = java.io.PipedOutputStream(pipeInput)
        val counters = WriteCounters()

        // Wrap pipeOutput in CountingOutputStream to track compressed bytes
        // (gzip output -> pipe). Minio's putStreamMultipart reports
        // size=-1L for chunked transfer, so this counter is the only
        // source of truth for compressed bytes in the Minio path.
        val compressedCounter = CountingOutputStream(pipeOutput)

        // Producer: collect the Flow into the pipe via gzip.
        val producerFuture: CompletableFuture<Unit> = producerScope.future {
            try {
                GZIPOutputStream(compressedCounter).use { gz ->
                    CountingOutputStream(gz).use { cgz ->
                        objectMapper.factory.createGenerator(cgz).use { gen ->
                            results.collect { result ->
                                counters.records.incrementAndGet()
                                gen.writeObject(result)
                                gen.writeRaw('\n')
                            }
                            counters.uncompressedBytes.set(cgz.count)
                        }
                    }
                }
                counters.compressedBytes.set(compressedCounter.count)
            } finally {
                runCatching { pipeOutput.close() }  // signal EOF to consumer
            }
            Unit
        }

        // Consumer: pipe -> ObjectStorage.putStreamMultipart (chunked transfer).
        val uploadFuture = objectStorage.putStreamMultipart(objectKey, pipeInput)

        // Deadlock guard: if the upload fails before draining the pipe,
        // the producer blocks on pipeOut.write() forever. Closing
        // pipeInput makes the next pipeOut.write() throw IOException,
        // which unblocks the producer's coroutine. The pipe becomes
        // effectively a one-shot channel with explicit error propagation.
        uploadFuture.whenComplete { _, err ->
            if (err != null) {
                runCatching { pipeInput.close() }
            }
        }

        // Compose: producer done + upload done -> WriteResult.
        val composed = producerFuture.thenCombine(uploadFuture) { _, putResult ->
            WriteResult(
                objectKey = putResult.key,
                resultCount = counters.records.get(),
                uncompressedBytes = counters.uncompressedBytes.get(),
                compressedBytes = if (putResult.size >= 0) {
                    putResult.size  // LocalFs: real size from putFile
                } else {
                    counters.compressedBytes.get()  // Minio: chunked transfer, size=-1L
                },
                etag = putResult.checksum,
            )
        }

        // Cleanup the pipe on any path (success or failure).
        return composed.whenComplete { _, _ ->
            runCatching { pipeInput.close() }
        }.exceptionally { err ->
            log.error(
                "[CalculationResultWriter] write failed for key={}",
                objectKey,
                err,
            )
            throw RuntimeException("streaming write failed for key=$objectKey", err)
        }
    }

    companion object {
        /** Pipe buffer = 8MB. Backs pressure the producer when S3 stalls. */
        private const val PIPE_BUFFER_BYTES: Int = 8 * 1024 * 1024
    }
}
