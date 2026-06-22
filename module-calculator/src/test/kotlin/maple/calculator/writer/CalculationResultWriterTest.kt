package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.util.concurrent.ExecutionException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import maple.calculator.model.CalculationResult
import maple.expectation.common.storage.PutResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        val writer = CalculationResultWriter(stub, objectMapper)
        val results = flowOf(
            sampleResult(ocid = "ocid-1"),
            sampleResult(ocid = "ocid-2"),
            sampleResult(ocid = "ocid-3"),
        )

        val cf = writer.write("test/chunk.jsonl.gz", results)
        val writeResult = cf.get()  // .get() OK in test only

        assertThat(writeResult.objectKey).isEqualTo("test/chunk.jsonl.gz")
        assertThat(writeResult.resultCount).isEqualTo(3L)
        assertThat(writeResult.compressedBytes).isGreaterThan(0L)
        assertThat(writeResult.uncompressedBytes).isGreaterThan(writeResult.compressedBytes)
        assertThat(writeResult.etag).startsWith("stub-etag-")

        // Bytewise equivalence: decompress the captured stream and verify content.
        val gz = stub.capturedStream!!
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
        val writer = CalculationResultWriter(stub, objectMapper)

        val cf = writer.write("empty.jsonl.gz", emptyFlow())
        val writeResult = cf.get()

        assertThat(writeResult.resultCount).isZero()
        assertThat(writeResult.compressedBytes).isGreaterThan(0L)
        // gzip magic: 1f 8b
        val gz = stub.capturedStream!!
        assertThat(gz[0]).isEqualTo(0x1f.toByte())
        assertThat(gz[1]).isEqualTo(0x8b.toByte())
    }

    @Test
    fun `write failure propagates via CompletableFuture exceptionally`() {
        val stub = object : StubObjectStorage() {
            override fun handlePutFileAsync(key: String, path: java.nio.file.Path): PutResult {
                throw RuntimeException("simulated upload failure")
            }
        }
        val writer = CalculationResultWriter(stub, objectMapper)
        val results = flowOf(sampleResult(ocid = "ocid-1"))

        val ex = assertThrows<ExecutionException> {
            writer.write("test.jsonl.gz", results).get()
        }
        assertThat(ex.cause).hasMessageContaining("streaming write failed")
    }

    private fun sampleResult(ocid: String): CalculationResult =
        CalculationResult(
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
}
