package maple.externalapi.dataflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.RunMarkerWriter
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.pipeline.artifact.storage.LocalFsObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Layer 3 contract test for the raw-path → MinIO migration (Tasks 1-9).
 *
 * <p>Drives `RankingFetchPhase` → `OcidLookupPhase` end-to-end through a real
 * [LocalFsObjectStorage] backed by a temp dir, and asserts that every byte put
 * to storage round-trips through `get()` and parses to the expected schema.
 *
 * <p>Purpose: catch the kind of bug we already had (producer/consumer key
 * prefix mismatch — see commit 2d9222680). The three boundaries exercised
 * are:
 *  1. chunk bytes (`runs/{runId}/ranking-overall/chunks/part-NNNNNN.jsonl.gz`)
 *  2. manifest (`runs/{runId}/ranking-overall/manifest.json`)
 *  3. OCID mapping (`ocid-mapping/ocid-mapping-{runId}.jsonl.gz`)
 */
class DataflowContractTest {

    @TempDir
    lateinit var tempDir: Path

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `ranking fetch writes chunks and manifest, ocid lookup reads chunks and writes ocid mapping`() {
        // arrange: a real LocalFsObjectStorage in a temp dir.
        val objectStorage: ObjectStorage = LocalFsObjectStorage(
            basePath = tempDir.toString(),
            uploadExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
            meterRegistry = null,
        )

        // arrange: mock ExternalApiClientPort — returns a fixed 3-entry ranking on page 1,
        // an empty ranking on page 2 to terminate the loop, and a fixed OCID for any IGN.
        // Each stub uses distinct matchers so Mockito does not collapse them into one rule.
        val clientPort = mock<ExternalApiClientPort>()
        val rankingJson = """
            {
              "ranking": [
                { "character_name": "TestChar1", "world_name": "Crono", "class_name": "Archer", "sub_class_name": "Bowmaster", "character_level": 270 },
                { "character_name": "TestChar2", "world_name": "Crono", "class_name": "Thief", "sub_class_name": "Shadower", "character_level": 275 },
                { "character_name": "TestChar3", "world_name": "Crono", "class_name": "Pirate", "sub_class_name": "Viper", "character_level": 260 }
              ]
            }
        """.trimIndent().toByteArray()
        val emptyRankingJson = """{"ranking": []}""".toByteArray()
        val ocidJson = """{"ocid":"0123456789abcdef0123456789abcdef01234567"}""".toByteArray()

        val rankingCalls = java.util.concurrent.atomic.AtomicInteger(0)
        whenever(
            clientPort.fetch(
                eq(ExternalApiProvider.NEXON),
                eq(ExternalApiEndpoint.RANKING_OVERALL),
                any(),
            ),
        ).thenAnswer { _ ->
            val pageIdx = rankingCalls.incrementAndGet()
            val payload = if (pageIdx == 1) rankingJson else emptyRankingJson
            // Simulate the small network latency the production code expects. Without this,
            // page 1's fire-and-forget submitRankingEntriesAsync loses the race against the
            // outer .whenComplete's sink.close() (test mocks are too fast; real I/O masks it).
            CompletableFuture.supplyAsync(
                { payload },
                CompletableFuture.delayedExecutor(200L, TimeUnit.MILLISECONDS),
            )
        }

        whenever(
            clientPort.fetch(
                eq(ExternalApiProvider.NEXON),
                eq(ExternalApiEndpoint.OCID_LOOKUP),
                any(),
            ),
        ).thenReturn(CompletableFuture.completedFuture(ocidJson))

        // arrange: no-op event publisher
        val rankingPublisher = mock<SnapshotChunkEventPublisher>()
        whenever(rankingPublisher.publishChunkReady(any<SnapshotChunkReadyEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))
        whenever(rankingPublisher.publishRunCompleted(any<SnapshotRunCompletedEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))
        whenever(rankingPublisher.publishRunFailed(any<SnapshotRunFailedEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val ocidPublisher = mock<SnapshotChunkEventPublisher>()
        whenever(ocidPublisher.publishChunkReady(any<SnapshotChunkReadyEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))
        whenever(ocidPublisher.publishRunCompleted(any<SnapshotRunCompletedEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))
        whenever(ocidPublisher.publishRunFailed(any<SnapshotRunFailedEvent>()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val volumeMetrics = mock<SnapshotVolumeMetrics>()
        val externalApiMetrics = mock<ExternalApiMetrics>()
        val chunkingProperties = SnapshotChunkingProperties()
        val runMarkerWriter = RunMarkerWriter(Clock.systemUTC(), objectStorage)
        val nexonAuthClient = mock<NexonAuthClient>()

        val rankingPhase = RankingFetchPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            chunkingProperties = chunkingProperties,
            volumeMetrics = volumeMetrics,
            metrics = externalApiMetrics,
            rankingPublisher = rankingPublisher,
            maxPages = 2,
            permitsPerSecond = 1000,
            runMarkerWriter = runMarkerWriter,
            objectStorage = objectStorage,
            stopSignal = maple.externalapi.scheduler.PhaseStopSignal(),
        )

        val ocidPhase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 1000,
            batchSize = 10,
            eventPublisher = ocidPublisher,
            objectStorage = objectStorage,
            nexonAuthClient = nexonAuthClient,
            stopSignal = maple.externalapi.scheduler.PhaseStopSignal(),
            streamingChunkParser = maple.common.parser.StreamingChunkParser(objectMapper),
            chunkParserMetrics = maple.externalapi.metrics.ChunkParserMetrics(
                io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            ),
        )

        // act: run ranking
        // Use a single-threaded worker executor so the per-page .thenAcceptAsync callback
        // (which is fire-and-forget to the outer chain) lands in the sink queue before the
        // next page's callback runs. With a virtual-thread pool the fire-and-forget chain
        // can complete before sink.submit has run, racing the outer .whenComplete's
        // sink.close() (production I/O masks this race; in tests the mocks are too fast).
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread.ofPlatform().name("ranking-worker").unstarted(runnable)
        }
        val runKey: String = rankingPhase.execute(executor, "20260610-xyz").get(30, TimeUnit.SECONDS)
        assertThat(runKey).startsWith("runs/")

        // act: run OCID lookup — it's suspend, so wrap in runBlocking
        val ocidRunId = "20260610-xyz"
        runBlocking { ocidPhase.execute(executor, runKey, ocidRunId) }

        // assert: chunk schema
        val chunkKeys = objectStorage.listByPrefix("$runKey/ranking-overall/chunks")
            .map { it.key }
            .filter { it.endsWith(".jsonl.gz") }
        assertThat(chunkKeys).withFailMessage("expected at least one chunk under $runKey/ranking-overall/chunks")
            .isNotEmpty

        for (key in chunkKeys) {
            val gzBytes = objectStorage.get(key)
            val lines = GZIPInputStream(gzBytes.inputStream()).bufferedReader().use { it.readLines() }
            assertThat(lines).withFailMessage("chunk $key should have at least one line")
                .isNotEmpty
            for (line in lines) {
                val node: JsonNode = objectMapper.readTree(line)
                // Each line is a serialized SnapshotChunkRecord.Success. The original ranking
                // entry lives in the base64-encoded `bodyBytes` field. The producer's `key`
                // is the character_name, which is what OcidLookupPhase uses downstream.
                val keyField = node.get("key")
                assertThat(keyField)
                    .withFailMessage("chunk $key line should contain 'key' (=character_name): $line")
                    .isNotNull
                assertThat(keyField.asText())
                    .withFailMessage("chunk $key 'key' should not be empty: $line")
                    .isNotEmpty
                val bodyBytes = node.get("bodyBytes")
                assertThat(bodyBytes)
                    .withFailMessage("chunk $key line should contain 'bodyBytes': $line")
                    .isNotNull
                val bodyJson = objectMapper.readTree(java.util.Base64.getDecoder().decode(bodyBytes.asText()))
                val characterName = bodyJson.get("character_name")
                assertThat(characterName)
                    .withFailMessage("decoded bodyBytes should contain 'character_name': $bodyJson")
                    .isNotNull
                assertThat(characterName.asText())
                    .withFailMessage("decoded 'character_name' should not be empty: $bodyJson")
                    .isNotEmpty
            }
        }

        // assert: manifest schema
        val manifestKey = "$runKey/ranking-overall/manifest.json"
        assertThat(objectStorage.exists(manifestKey)).withFailMessage("manifest should exist at $manifestKey")
            .isTrue
        val manifest = objectMapper.readTree(objectStorage.get(manifestKey))
        assertThat(manifest.get("runId").asText()).isNotEmpty
        assertThat(manifest.get("totalRecords").asInt()).isGreaterThan(0)
        val chunksNode = manifest.get("chunks")
        assertThat(chunksNode).isNotNull
        assertThat(chunksNode.isArray).isTrue
        assertThat(manifest.get("startedAt").asText()).isNotEmpty
        assertThat(manifest.get("finishedAt").asText()).isNotEmpty
        assertThat(chunksNode.size()).isGreaterThan(0)

        // assert: each chunk path in manifest is reachable in storage.
        // The producer stores chunks under `runs/{runId}/{endpoint}/chunks/part-N.jsonl.gz`,
        // but ChunkStats.path is the filename only (see GzipJsonlChunkWriter.close()).
        // The contract: chunk.path in the manifest points under the `chunks/` subdir.
        val chunkPaths = chunksNode.map { it.get("path").asText() }
        for (path in chunkPaths) {
            val absoluteKey = "$runKey/ranking-overall/chunks/$path"
            assertThat(objectStorage.exists(absoluteKey))
                .withFailMessage("manifest chunk path $path should resolve to $absoluteKey")
                .isTrue
        }

        // assert: OCID mapping schema
        val runId = runKey.removePrefix("runs/")
        val mappingKey = "ocid-mapping/ocid-mapping-$runId.jsonl.gz"
        assertThat(objectStorage.exists(mappingKey))
            .withFailMessage("OCID mapping should exist at $mappingKey")
            .isTrue
        val mappingBytes = objectStorage.get(mappingKey)
        val mappingLines = GZIPInputStream(mappingBytes.inputStream()).bufferedReader().use { it.readLines() }
        assertThat(mappingLines).withFailMessage("OCID mapping should have at least one line")
            .isNotEmpty
        val ocidRegex = Regex("^[a-f0-9]{40}$")
        for (line in mappingLines) {
            val node: JsonNode = objectMapper.readTree(line)
            val userIgn = node.get("userIgn")
            val ocid = node.get("ocid")
            assertThat(userIgn)
                .withFailMessage("OCID mapping line should contain 'userIgn': $line")
                .isNotNull
            assertThat(userIgn.asText())
                .withFailMessage("OCID mapping 'userIgn' should not be empty: $line")
                .isNotEmpty
            assertThat(ocid)
                .withFailMessage("OCID mapping line should contain 'ocid': $line")
                .isNotNull
            val ocidText = ocid.asText()
            assertThat(ocidRegex.matches(ocidText))
                .withFailMessage("OCID '$ocidText' should match ^[a-f0-9]{40}$ in: $line")
                .isTrue
        }
    }
}
