package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import java.io.BufferedOutputStream
import java.nio.file.Files
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
     * Drain calculation results through gzip into a temp file, then upload
     * the file via [ObjectStorage.putFileAsync].
     *
     * Producer (on [producerScope], IO dispatcher) collects the Flow and
     * writes JSONL rows through gzip into the temp file. When the producer
     * completes (file flushed + closed), [putFileAsync] uploads it — MinIO
     * uses S3TransferManager multipart (file-backed, no pipe), LocalFs uses
     * an atomic rename. The temp file is deleted in a `whenComplete`
     * safety net on both success and failure.
     *
     * Why not the previous `PipedInputStream`/`PipedOutputStream` +
     * `putStreamMultipart` path: the AWS SDK reads the InputStream on a
     * background thread that races the producer coroutine. After the
     * producer exits, `PipedInputStream` throws "Read end dead"
     * (`readSide.isAlive()` check) → truncated/empty gzip files (0-row
     * result parts, data loss). This is the same bug `OcidLookupPhase`
     * hit and fixed in commit `c7b20f4c3`; see ADR-730.
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
        val tempFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX)

        // Producer: collect the Flow into the temp file via gzip.
        val producerFuture: CompletableFuture<Unit> = producerScope.future {
            try {
                GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(tempFile))).use { gz ->
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
                // Real compressed size from the finalized file. putFileAsync
                // reports size=-1L for MinIO chunked transfer, so this is the
                // source of truth for compressed bytes in that path.
                counters.compressedBytes.set(Files.size(tempFile))
            } catch (e: Throwable) {
                // Producer failed before the file was fully written — clean up
                // here so no stray temp file leaks. The upload stage below is
                // skipped because producerFuture completes exceptionally and
                // thenCompose short-circuits.
                runCatching { Files.deleteIfExists(tempFile) }
                throw e
            }
            Unit
        }

        // After the producer writes the file, upload it via putFileAsync
        // (MinIO S3TransferManager multipart | LocalFs atomic move). No pipe
        // — the upload reads from the file, eliminating the reader/writer
        // thread race.
        return producerFuture
            .thenCompose { objectStorage.putFileAsync(objectKey, tempFile) }
            .thenApply { putResult ->
                WriteResult(
                    objectKey = putResult.key,
                    resultCount = counters.records.get(),
                    uncompressedBytes = counters.uncompressedBytes.get(),
                    compressedBytes = if (putResult.size >= 0) {
                        putResult.size  // LocalFs: real size from putFile
                    } else {
                        counters.compressedBytes.get()  // MinIO: chunked transfer, size=-1L
                    },
                    etag = putResult.checksum,
                )
            }
            .whenComplete { _, _ ->
                // Safety net: delete the temp file after the upload resolves.
                // MinIO's putFileAsync may already delete on success; LocalFs
                // putFile atomically moves it into place. deleteIfExists is
                // idempotent either way.
                runCatching { Files.deleteIfExists(tempFile) }
            }
            .exceptionally { err ->
                log.error(
                    "[CalculationResultWriter] write failed for key={}",
                    objectKey,
                    err,
                )
                throw RuntimeException("streaming write failed for key=$objectKey", err)
            }
    }

    companion object {
        private const val TEMP_FILE_PREFIX: String = "calc-result-"
        private const val TEMP_FILE_SUFFIX: String = ".jsonl.gz.tmp"
    }
}
