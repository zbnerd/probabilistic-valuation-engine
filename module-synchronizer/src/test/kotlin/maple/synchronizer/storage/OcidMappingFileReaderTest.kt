package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.error.exception.ArtifactNotFoundException
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

class OcidMappingFileReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var reader: OcidMappingFileReader
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        val registry = SimpleMeterRegistry()
        reader = OcidMappingFileReader(
            storeBasePath = tempDir.toString(),
            objectMapper = objectMapper,
            readerMetrics = SynchronizerReaderMetrics(registry),
        )
    }

    @Test
    fun `read parses gzip JSONL into OcidMapping list`() {
        val gzPath = tempDir.resolve("ocid-mapping").resolve("test.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            """{"userIgn":"PlayerB","ocid":"ocid-b"}""",
        ))

        val mappings = reader.read("ocid-mapping/test.jsonl.gz")

        assertThat(mappings).hasSize(2)
        assertThat(mappings[0].userIgn).isEqualTo("PlayerA")
        assertThat(mappings[0].ocid).isEqualTo("ocid-a")
        assertThat(mappings[1].userIgn).isEqualTo("PlayerB")
        assertThat(mappings[1].ocid).isEqualTo("ocid-b")
    }

    @Test
    fun `read throws ArtifactNotFoundException when file not found`() {
        assertThatThrownBy { reader.read("nonexistent/path.jsonl.gz") }
            .isInstanceOf(ArtifactNotFoundException::class.java)
            .hasMessageContaining("OcidMappingFileReader")
            .hasMessageContaining("nonexistent/path.jsonl.gz")
    }

    @Test
    fun `read skips blank lines and counts records with missing fields`() {
        val gzPath = tempDir.resolve("test-mixed.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            "",
            "   ",
            """{"userIgn":"PlayerB","ocid":"ocid-b"}""",
            """{"missing":"fields"}""",
        ))

        val mappings = reader.read("test-mixed.jsonl.gz")

        assertThat(mappings).hasSize(2)
        assertThat(mappings[0].userIgn).isEqualTo("PlayerA")
        assertThat(mappings[1].userIgn).isEqualTo("PlayerB")
    }

    @Test
    fun `read throws JsonProcessingException on malformed line`() {
        val gzPath = tempDir.resolve("test-malformed.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            """{not valid json""",
        ))

        assertThatThrownBy { reader.read("test-malformed.jsonl.gz") }
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException::class.java)
    }

    private fun writeGzipJsonl(path: Path, lines: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(path.toFile()))).use { gzip ->
            for (line in lines) {
                gzip.write((line + "\n").toByteArray())
            }
        }
    }
}
