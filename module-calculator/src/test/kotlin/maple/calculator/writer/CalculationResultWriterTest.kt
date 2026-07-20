package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import maple.calculator.model.CalculationResult
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * Verifies the CF-chain streaming write: the gzipped bytes captured by
 * [StubObjectStorage] decompress back to the original JSONL with no data
 * loss. Also exercises the error path (ObjectStorage failure) and the
 * counter accuracy.
 *
 * Plain unit test (no @SpringBootTest) — uses Spring's
 * [Jackson2ObjectMapperBuilder] directly to satisfy the code-style rule
 * forbidding `new ObjectMapper()`. ~10ms startup vs ~1500ms for a
 * SpringBootTest.
 */
class CalculationResultWriterTest {

    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder()
        .modules(KotlinModule.Builder().build(), JavaTimeModule())
        .build()

    @Test
    fun `streaming gzip output decompresses to expected JSONL`() {
        val stub = StubObjectStorage()
        val writer = writer(stub)
        val results = flowOf(
            sampleResult(ocid = "ocid-1"),
            sampleResult(ocid = "ocid-2"),
            sampleResult(ocid = "ocid-3"),
        )

        val cf = writer.write("test/chunk.jsonl.gz", results)
        val writeResult = awaitSuccess(cf)

        assertThat(writeResult.objectKey).isEqualTo("test/chunk.jsonl.gz")
        assertThat(writeResult.resultCount).isEqualTo(3L)
        assertThat(writeResult.compressedBytes).isGreaterThan(0L)
        assertThat(writeResult.uncompressedBytes).isGreaterThan(writeResult.compressedBytes)
        assertThat(writeResult.contentSha256).isEqualTo(sha256(requireNotNull(stub.capturedStream)))
        assertThat(writeResult.backendTag).startsWith("stub-etag-")

        // Bytewise equivalence: decompress the captured stream and verify content.
        val gz = requireNotNull(stub.capturedStream)
        val decompressed = GZIPInputStream(gz.inputStream()).bufferedReader().readText()
        assertThat(decompressed).contains("\"ocid\":\"ocid-1\"")
        assertThat(decompressed).contains("\"ocid\":\"ocid-2\"")
        assertThat(decompressed).contains("\"ocid\":\"ocid-3\"")
        // Each record terminated with newline
        assertThat(decompressed.lines().filter { it.isNotBlank() }).hasSize(3)
    }

    @Test
    fun `streaming write with empty flow produces gzip header only`() {
        val stub = StubObjectStorage()
        val writer = writer(stub)

        val cf = writer.write("empty.jsonl.gz", emptyFlow())
        val writeResult = awaitSuccess(cf)

        assertThat(writeResult.resultCount).isZero()
        assertThat(writeResult.compressedBytes).isGreaterThan(0L)
        // gzip magic: 1f 8b
        val gz = requireNotNull(stub.capturedStream)
        assertThat(gz[0]).isEqualTo(0x1f.toByte())
        assertThat(gz[1]).isEqualTo(0x8b.toByte())
    }

    @Test
    fun `write failure propagates via CompletableFuture exceptionally`() {
        val stub = object : StubObjectStorage() {
            override fun handlePutFileAsync(key: String, path: java.nio.file.Path): PutResult = throw RuntimeException("simulated upload failure")
        }
        val writer = writer(stub)
        val results = flowOf(sampleResult(ocid = "ocid-1"))
        val before = resultTempFiles()

        val outcome = awaitCompletion(writer.write("test.jsonl.gz", results))

        assertThat(rootCause(requireNotNull(outcome.failure))).hasMessageContaining("simulated upload failure")
        assertThat(outcome.value).isNull()
        assertThat(resultTempFiles()).isEqualTo(before)
    }

    @Test
    fun `cancelled producer scope does not open an artifact session`() {
        val stub = StubObjectStorage()
        val cancelledScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).also(CoroutineScope::cancel)
        val writer = CalculationResultWriter(
            DefaultArtifactWriter(stub, java.util.concurrent.Executor { command -> command.run() }),
            objectMapper,
            cancelledScope,
        )
        val before = resultTempFiles()

        try {
            val outcome = awaitCompletion(writer.write("cancelled.jsonl.gz", emptyFlow()))

            assertThat(outcome.failure).isNotNull()
            assertThat(resultTempFiles()).isEqualTo(before)
        } finally {
            (resultTempFiles() - before).forEach(Files::deleteIfExists)
        }
    }

    private fun sampleResult(ocid: String): CalculationResult = CalculationResult(
        ocid = ocid,
        presetNo = 0,
        itemName = "Test Item",
        itemLevel = 200,
        itemPart = null,
        itemEquipmentPart = null,
        potentialGrade = null,
        potentialOptions = emptyList(),
        additionalGrade = null,
        additionalOptions = emptyList(),
        currentStar = 0,
        targetStar = 0,
        status = "SUCCESS",
        totalCost = 1000.0,
        blackCubeCost = null,
        additionalCubeCost = null,
        starforceCost = null,
        errorMessage = null,
    )

    private fun writer(storage: StubObjectStorage): CalculationResultWriter = CalculationResultWriter(
        DefaultArtifactWriter(storage, java.util.concurrent.Executor { command -> command.run() }),
        objectMapper,
    )

    private fun <T> awaitSuccess(future: CompletableFuture<T>): T {
        val outcome = awaitCompletion(future)
        assertThat(outcome.failure).isNull()
        return requireNotNull(outcome.value)
    }

    private fun <T> awaitCompletion(future: CompletableFuture<T>): Completion<T> {
        val observed = AtomicReference<Completion<T>?>()
        future.whenComplete { value, failure -> observed.set(Completion(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { observed.get() != null }
        return requireNotNull(observed.get())
    }

    private fun resultTempFiles(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { path ->
            val name = path.fileName.toString()
            name.startsWith("calc-result-") || name.startsWith("artifact-gzip-")
        }.map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toList()
            .toSet()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun rootCause(failure: Throwable): Throwable = generateSequence(failure) { current -> current.cause }.last()

    private data class Completion<T>(
        val value: T?,
        val failure: Throwable?,
    )
}
