package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.NoOpSnapshotChunkEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class RankingFetchPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var clientPort: ExternalApiClientPort
    private lateinit var objectMapper: ObjectMapper
    private lateinit var phase: RankingFetchPhase
    private lateinit var csvPath: Path
    private lateinit var executor: java.util.concurrent.ExecutorService

    @BeforeEach
    fun setUp() {
        clientPort = mock()
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        csvPath = tempDir.resolve("userIgn_List.csv")
        val storeBasePath = tempDir.resolve("store").toString()

        val registry = SimpleMeterRegistry()
        phase = RankingFetchPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            chunkingProperties = SnapshotChunkingProperties(),
            volumeMetrics = SnapshotVolumeMetrics(registry),
            metrics = ExternalApiMetrics(registry),
            rankingPublisher = NoOpSnapshotChunkEventPublisher(),
            maxPages = 3,
            permitsPerSecond = 100,
            storeBasePath = storeBasePath,
            csvPath = csvPath.toString(),
        )
        executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    @AfterEach
    fun tearDown() {
        executor.close()
    }

    private fun rankingJson(vararg names: String): ByteArray {
        val entries = names.mapIndexed { i, name ->
            """{"ranking":${i + 1},"character_name":"$name","world_name":"크로아","class_name":"전사"}"""
        }.joinToString(",", prefix = """{"ranking":[""", postfix = "]}")
        return entries.toByteArray()
    }

    @Test
    fun `execute fetches all pages and writes character names to CSV`() {
        // Given - 3 pages with different character names
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerA", "PlayerB")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerC", "PlayerD")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerE")))

        // When
        val future = phase.execute(executor)
        future.join()

        // Then - CSV contains all character names
        val csvLines = Files.readAllLines(csvPath).filter { it.isNotBlank() }
        assertThat(csvLines).containsExactly("PlayerA", "PlayerB", "PlayerC", "PlayerD", "PlayerE")

        // And - gzip chunk files created in store
        val chunksDir = tempDir.resolve("store").resolve("runs")
        val gzFiles = Files.walk(chunksDir).filter { it.toString().endsWith(".gz") }.toList()
        assertThat(gzFiles).isNotEmpty

        // And - _SUCCESS marker exists
        val successMarkers = Files.walk(chunksDir).filter { it.fileName.toString() == "_SUCCESS" }.toList()
        assertThat(successMarkers).hasSize(1)
    }

    @Test
    fun `execute continues on page failure and writes successful names to CSV`() {
        // Given - page 1 succeeds, page 2 fails, page 3 succeeds
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerA")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("API error")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerC")))

        // When
        val future = phase.execute(executor)
        future.join()

        // Then - CSV contains only names from successful pages
        val csvLines = Files.readAllLines(csvPath).filter { it.isNotBlank() }
        assertThat(csvLines).containsExactly("PlayerA", "PlayerC")
    }

    @Test
    fun `execute skips entries without character_name`() {
        // Given - page with entries where one has no character_name
        val json = """{"ranking":[{"ranking":1,"character_name":"ValidName","world_name":"크로아"},{"ranking":2,"world_name":"크로아"}]}""".toByteArray()
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
            .thenReturn(CompletableFuture.completedFuture(json))
        // Return empty for remaining pages to end quickly
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
            .thenReturn(CompletableFuture.completedFuture("""{"ranking":[]}""".toByteArray()))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
            .thenReturn(CompletableFuture.completedFuture("""{"ranking":[]}""".toByteArray()))

        // When
        val future = phase.execute(executor)
        future.join()

        // Then - only valid entry in CSV
        val csvLines = Files.readAllLines(csvPath).filter { it.isNotBlank() }
        assertThat(csvLines).containsExactly("ValidName")
    }
}
