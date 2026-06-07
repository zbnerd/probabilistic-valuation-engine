package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.parser.OcidResponseParser
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.reader.CharacterNameReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class OcidLookupPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var objectMapper: ObjectMapper
    private lateinit var clientPort: ExternalApiClientPort
    private lateinit var phase: OcidLookupPhase
    private lateinit var executor: java.util.concurrent.ExecutorService

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        clientPort = mock()
        phase = OcidLookupPhase(
            clientPort = clientPort,
            ocidResponseParser = OcidResponseParser(objectMapper),
            characterNameReader = CharacterNameReader(objectMapper),
            ocidLookupPermitsPerSecond = 400,
            batchSize = 1000,
            storeBasePath = tempDir.resolve("store").toString(),
            eventPublisher = maple.externalapi.snapshot.event.NoOpSnapshotChunkEventPublisher(),
            maxInFlight = 100,
            runIdGenerator = RunIdGenerator(java.time.Clock.systemDefaultZone()),
            schedulerRateLimiter = SchedulerRateLimiter(),
            schedulerProgressLogger = SchedulerProgressLogger(java.time.Clock.systemDefaultZone()),
        )
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    }

    @AfterEach
    fun tearDown() {
        executor.close()
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

    @Test
    fun `execute writes only valid ocids when one Nexon response lacks ocid field`() {
        val runDir = tempDir.resolve("runs").resolve("20260604-140000-001")
        val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
        writeGzipJsonl(chunksDir.resolve("part-000001.jsonl.gz"), listOf("PlayerValid", "PlayerNullOcid"))

        whenever(
            clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "PlayerValid"),
        ).thenReturn(CompletableFuture.completedFuture("""{"ocid":"abc123"}""".toByteArray()))
        whenever(
            clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "PlayerNullOcid"),
        ).thenReturn(CompletableFuture.completedFuture("""{"character_name":"x"}""".toByteArray()))

        val outputPath = requireNotNull(phase.execute(executor, runDir).get()) { "execute returned null path" }

        assertThat(Files.exists(outputPath)).isTrue

        val lines = GZIPInputStream(BufferedInputStream(Files.newInputStream(outputPath)))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("\"ocid\":\"abc123\"").contains("\"userIgn\":\"PlayerValid\"")
    }
}
