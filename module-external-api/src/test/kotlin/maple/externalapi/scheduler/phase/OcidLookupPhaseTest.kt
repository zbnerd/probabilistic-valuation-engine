package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.port.out.ExternalApiClientPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class OcidLookupPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var objectMapper: ObjectMapper
    private lateinit var phase: OcidLookupPhase

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        phase = OcidLookupPhase(
            clientPort = mock(),
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 400,
            batchSize = 1000,
            storeBasePath = tempDir.resolve("store").toString(),
            eventPublisher = maple.externalapi.snapshot.event.NoOpSnapshotChunkEventPublisher(),
        )
    }

    private fun writeGzipJsonl(chunkFilePath: Path, keys: List<String>) {
        Files.createDirectories(chunkFilePath.parent)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(chunkFilePath.toFile()))).use { gzip ->
            for (key in keys) {
                val line = """{"endpoint":"ranking-overall","keyType":"DATE_PAGE","key":"$key","status":"SUCCESS","httpStatus":200,"fetchedAt":"2026-05-20T02:00:00Z","body":{"character_name":"$key"}}"""
                gzip.write((line + "\n").toByteArray())
            }
        }
    }

    @Test
    fun `readCharacterNamesFromChunks extracts distinct keys from gzip JSONL`() {
        val chunksDir = tempDir.resolve("runs").resolve("20260520-030000-123").resolve("ranking-overall").resolve("chunks")
        writeGzipJsonl(chunksDir.resolve("part-000001.jsonl.gz"), listOf("PlayerA", "PlayerB", "PlayerC"))
        writeGzipJsonl(chunksDir.resolve("part-000002.jsonl.gz"), listOf("PlayerC", "PlayerD"))

        val names = phase.readCharacterNamesFromChunks(tempDir.resolve("runs").resolve("20260520-030000-123"))

        assertThat(names).containsExactlyInAnyOrder("PlayerA", "PlayerB", "PlayerC", "PlayerD")
    }

    @Test
    fun `readCharacterNamesFromChunks returns empty list when no chunk files`() {
        val runDir = tempDir.resolve("runs").resolve("empty-run")

        val names = phase.readCharacterNamesFromChunks(runDir)

        assertThat(names).isEmpty()
    }
}
