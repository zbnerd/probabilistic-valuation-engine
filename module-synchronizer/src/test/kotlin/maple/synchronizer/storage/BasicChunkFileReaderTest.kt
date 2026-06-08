package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class BasicChunkFileReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var reader: BasicChunkFileReader
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        reader = BasicChunkFileReader(
            basePath = tempDir.toString(),
            objectMapper = objectMapper,
            readerMetrics = SynchronizerReaderMetrics(SimpleMeterRegistry()),
            missingFieldThreshold = 100,
        )
    }

    @Test
    fun `read parses normal records`() {
        val gz = tempDir.resolve("ok.jsonl.gz")
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            basicLine(ocid = "ocid-2", ign = "PlayerB", status = "SUCCESS", endpoint = "character-basic"),
        ))

        val records = reader.read("ok.jsonl.gz")
        assertThat(records).hasSize(2)
        assertThat(records.map { it.userIgn }).containsExactly("PlayerA", "PlayerB")
    }

    @Test
    fun `read filters non-SUCCESS and non-character-basic records`() {
        val gz = tempDir.resolve("mixed.jsonl.gz")
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            basicLine(ocid = "ocid-2", ign = "PlayerB", status = "FAILED", endpoint = "character-basic"),
            basicLine(ocid = "ocid-3", ign = "PlayerC", status = "SUCCESS", endpoint = "item-equipment"),
        ))

        val records = reader.read("mixed.jsonl.gz")
        assertThat(records).hasSize(1)
        assertThat(records[0].userIgn).isEqualTo("PlayerA")
    }

    @Test
    fun `read throws JsonProcessingException on malformed line`() {
        val gz = tempDir.resolve("bad.jsonl.gz")
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            """{not valid""",
        ))

        assertThatThrownBy { reader.read("bad.jsonl.gz") }
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException::class.java)
    }

    @Test
    fun `read throws IllegalStateException when missing-field threshold exceeded`() {
        val smallThresholdReader = BasicChunkFileReader(
            basePath = tempDir.toString(),
            objectMapper = objectMapper,
            readerMetrics = SynchronizerReaderMetrics(SimpleMeterRegistry()),
            missingFieldThreshold = 2,
        )
        val gz = tempDir.resolve("threshold.jsonl.gz")
        // 1 success record, then 3 records with missing key/body (missing-field). Threshold=2, so the 3rd missing-field triggers throw.
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            """{"status":"SUCCESS","endpoint":"character-basic","body":{"character_name":"PlayerB"}}""",
            """{"status":"SUCCESS","endpoint":"character-basic","body":{"character_name":"PlayerC"}}""",
            """{"status":"SUCCESS","endpoint":"character-basic","body":{"character_name":"PlayerD"}}""",
        ))

        assertThatThrownBy { smallThresholdReader.read("threshold.jsonl.gz") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing-field threshold exceeded")
    }

    private fun basicLine(ocid: String, ign: String, status: String, endpoint: String): String =
        """{"status":"$status","endpoint":"$endpoint","key":"$ocid","body":{"character_name":"$ign"}}"""

    private fun writeGzipJsonl(path: Path, lines: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(path.toFile()))).use { gzip ->
            for (line in lines) {
                gzip.write((line + "\n").toByteArray())
            }
        }
    }
}
