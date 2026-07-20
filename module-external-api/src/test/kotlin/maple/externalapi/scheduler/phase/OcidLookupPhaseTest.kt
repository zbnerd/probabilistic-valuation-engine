package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import maple.common.parser.StreamingChunkParser
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.PutResult
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.externalapi.artifact.OcidMappingArtifactWriter
import maple.externalapi.metrics.ChunkParserMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.write.DefaultArtifactWriter
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OcidLookupPhaseTest {

    @Test
    fun `execute streams OCID mapping gzipped to ObjectStorage under ocid-mapping key`() {
        val storage = mock<ConditionalObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        whenever(storage.listByPrefix(any())).thenReturn(
            listOf(
                ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, Instant.now()),
            ),
        )
        val chunkBytes = run {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gz ->
                gz.write("{\"key\":\"user1\"}\n{\"key\":\"user2\"}\n".toByteArray())
            }
            out.toByteArray()
        }
        whenever(storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"))
            .thenReturn(chunkBytes.inputStream())

        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenAnswer { invocation ->
            val ign = invocation.getArgument<String>(2)
            val payload = "{\"ocid\":\"ocid-for-$ign\"}"
            CompletableFuture.completedFuture(payload.toByteArray())
        }

        // The shared artifact writer uploads its finalized gzip path. The
        // mock reads those bytes and captures the key for downstream assertions.
        // Since B4 (#1423) the phase also emits a side-by-side Parquet upload;
        // collect all putFileAsync calls so we can pick the JSONL.gz one out.
        val allKeys = ConcurrentLinkedQueue<String>()
        val keyToBytes = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val file = invocation.getArgument<java.nio.file.Path>(1)
            val bytes = java.nio.file.Files.readAllBytes(file)
            allKeys.add(key)
            keyToBytes[key] = bytes
            CompletableFuture.completedFuture(
                PutResult(key, bytes.size.toLong(), null),
            )
        }

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>(),
            objectStorage = storage,
            nexonAuthClient = nexonClient,
            stopSignal = PhaseStopSignal(),
            streamingChunkParser = StreamingChunkParser(objectMapper),
            chunkParserMetrics = ChunkParserMetrics(SimpleMeterRegistry()),
            ocidMappingArtifactWriter = mappingWriter(storage),
        )

        kotlinx.coroutines.runBlocking {
            phase.execute(
                workerExecutor = Executors.newSingleThreadExecutor(),
                runKey = "runs/abc",
                runId = "abc",
            )
        }

        // The production JSONL.gz upload MUST still happen — pull that key out
        // of the captured calls. (B4 also adds a side-by-side Parquet upload.)
        val mappingKey = requireNotNull(allKeys.firstOrNull { it.endsWith(".jsonl.gz") }) {
            "expected JSONL.gz putFileAsync call among $allKeys"
        }
        assertTrue(
            mappingKey.startsWith("ocid-mapping/ocid-mapping-"),
            "expected mapping key to start with 'ocid-mapping/ocid-mapping-' but was '$mappingKey'",
        )
        assertTrue(
            mappingKey.endsWith(".jsonl.gz"),
            "expected mapping key to end with '.jsonl.gz' but was '$mappingKey'",
        )

        // The temp file bytes should be a non-empty valid gzip containing
        // the expected userIgn/ocid pairs. Pull the JSONL.gz bytes (not the
        // side-by-side Parquet PoC bytes) out of the captured calls.
        val bytes = requireNotNull(keyToBytes[mappingKey]) { "putFileAsync should have been called" }
        assertTrue(bytes.isNotEmpty(), "streamed bytes should not be empty")
        val decompressed = GZIPInputStream(bytes.inputStream())
            .bufferedReader().readText()
        assertTrue(
            decompressed.contains("user1") && decompressed.contains("ocid-for-user1"),
            "expected mapping to contain user1/ocid pair, got: $decompressed",
        )
        assertTrue(
            decompressed.contains("user2") && decompressed.contains("ocid-for-user2"),
            "expected mapping to contain user2/ocid pair, got: $decompressed",
        )
    }

    @Test
    fun `execute preserves current runId's OCID mapping while deleting others`() {
        val storage = mock<ConditionalObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        val now = Instant.now()
        // Pre-existing OCID mappings: one for the current runId + two for prior runs
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(
            listOf(
                ObjectInfo("ocid-mapping/ocid-mapping-abc.jsonl.gz", 100, now), // current runId
                ObjectInfo("ocid-mapping/ocid-mapping-old1.jsonl.gz", 100, now),
                ObjectInfo("ocid-mapping/ocid-mapping-old2.jsonl.gz", 100, now),
            ),
        )
        // Ranking chunks from the upstream run
        whenever(storage.listByPrefix("runs/abc/ranking-overall/chunks")).thenReturn(
            listOf(
                ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, now),
            ),
        )
        val chunkBytes = run {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gz ->
                gz.write("{\"key\":\"user1\"}\n".toByteArray())
            }
            out.toByteArray()
        }
        whenever(storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"))
            .thenReturn(chunkBytes.inputStream())

        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenAnswer { invocation ->
            val ign = invocation.getArgument<String>(2)
            CompletableFuture.completedFuture("{\"ocid\":\"ocid-for-$ign\"}".toByteArray())
        }
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val file = invocation.getArgument<java.nio.file.Path>(1)
            val bytes = java.nio.file.Files.readAllBytes(file)
            CompletableFuture.completedFuture(
                PutResult(key, bytes.size.toLong(), null),
            )
        }

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>(),
            objectStorage = storage,
            nexonAuthClient = nexonClient,
            stopSignal = PhaseStopSignal(),
            streamingChunkParser = StreamingChunkParser(objectMapper),
            chunkParserMetrics = ChunkParserMetrics(SimpleMeterRegistry()),
            ocidMappingArtifactWriter = mappingWriter(storage),
        )

        kotlinx.coroutines.runBlocking {
            phase.execute(
                workerExecutor = Executors.newSingleThreadExecutor(),
                runKey = "runs/abc",
                runId = "abc",
            )
        }

        // deleteOldMappingFiles: per-key delete for old runs, current run preserved
        verify(storage).delete("ocid-mapping/ocid-mapping-old1.jsonl.gz")
        verify(storage).delete("ocid-mapping/ocid-mapping-old2.jsonl.gz")
        verify(storage, never()).delete("ocid-mapping/ocid-mapping-abc.jsonl.gz")
    }

    @Test
    fun `execute throws PhaseStoppedException when stop requested before processBatch`() {
        val storage = mock<ConditionalObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())
        val stopSignal = PhaseStopSignal()
        stopSignal.requestStop(PipelinePhase.OCID_LOOKUP)

        val now = Instant.now()
        whenever(storage.listByPrefix("runs/abc/ranking-overall/chunks")).thenReturn(
            listOf(
                ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, now),
            ),
        )
        val chunkBytes = run {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gz ->
                gz.write("{\"key\":\"user1\"}\n".toByteArray())
            }
            out.toByteArray()
        }
        whenever(storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"))
            .thenReturn(chunkBytes.inputStream())
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(emptyList())

        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(ByteArray(0)))

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>(),
            objectStorage = storage,
            nexonAuthClient = nexonClient,
            stopSignal = stopSignal,
            streamingChunkParser = StreamingChunkParser(objectMapper),
            chunkParserMetrics = ChunkParserMetrics(SimpleMeterRegistry()),
            ocidMappingArtifactWriter = mappingWriter(storage),
        )

        assertThrows(PhaseStoppedException::class.java) {
            kotlinx.coroutines.runBlocking {
                phase.execute(Executors.newSingleThreadExecutor(), "runs/abc", "abc")
            }
        }
    }

    @Test
    fun `execute writes side-by-side Parquet output alongside gzip JSONL (1423 PoC)`() {
        val storage = mock<ConditionalObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        whenever(storage.listByPrefix(any())).thenReturn(
            listOf(
                ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, Instant.now()),
            ),
        )
        val chunkBytes = run {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gz ->
                gz.write("{\"key\":\"user1\"}\n{\"key\":\"user2\"}\n".toByteArray())
            }
            out.toByteArray()
        }
        whenever(storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"))
            .thenReturn(chunkBytes.inputStream())

        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenAnswer { invocation ->
            val ign = invocation.getArgument<String>(2)
            CompletableFuture.completedFuture("{\"ocid\":\"ocid-for-$ign\"}".toByteArray())
        }

        // Capture every putFileAsync call so we can assert both keys are present.
        val capturedKeys = ConcurrentLinkedQueue<String>()
        val capturedBytes = ConcurrentLinkedQueue<ByteArray>()
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val file = invocation.getArgument<java.nio.file.Path>(1)
            capturedKeys.add(key)
            capturedBytes.add(java.nio.file.Files.readAllBytes(file))
            CompletableFuture.completedFuture(PutResult(key, 0L, null))
        }

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>(),
            objectStorage = storage,
            nexonAuthClient = nexonClient,
            stopSignal = PhaseStopSignal(),
            streamingChunkParser = StreamingChunkParser(objectMapper),
            chunkParserMetrics = ChunkParserMetrics(SimpleMeterRegistry()),
            ocidMappingArtifactWriter = mappingWriter(storage),
        )

        kotlinx.coroutines.runBlocking {
            phase.execute(
                workerExecutor = Executors.newSingleThreadExecutor(),
                runKey = "runs/abc",
                runId = "abc",
            )
        }

        val keys = capturedKeys.toList()
        // BOTH the production JSONL.gz AND the side-by-side Parquet must be uploaded.
        assertThat(keys).anyMatch { it == "ocid-mapping/ocid-mapping-abc.jsonl.gz" }
        assertThat(keys).anyMatch { it == "ocid-mapping-parquet/ocid-mapping-abc.parquet" }

        // Verify the Parquet upload actually carries a valid Parquet file (PAR1 magic bytes).
        val parquetBytes = keys
            .zip(capturedBytes.toList())
            .first { it.first == "ocid-mapping-parquet/ocid-mapping-abc.parquet" }
            .second
        assertThat(parquetBytes.size).isGreaterThan(4)
        assertThat(String(parquetBytes.copyOfRange(0, 4))).isEqualTo("PAR1")
    }

    @Test
    fun `cancellation during Parquet upload still deletes the borrowed path and publishes no event`() {
        val storage = mock<ConditionalObjectStorage>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())
        val eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        val parquetUpload = CompletableFuture<PutResult>()
        val borrowedParquet = AtomicReference<Path?>()
        val rankingKey = "runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"

        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(emptyList())
        whenever(storage.listByPrefix("runs/abc/ranking-overall/chunks")).thenReturn(
            listOf(ObjectInfo(rankingKey, 100, Instant.now())),
        )
        val chunkBytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { gzip -> gzip.write("{\"key\":\"user1\"}\n".toByteArray()) }
            out.toByteArray()
        }
        whenever(storage.getStream(rankingKey)).thenReturn(chunkBytes.inputStream())

        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture("{\"ocid\":\"ocid-for-user1\"}".toByteArray()))
        whenever(storage.putFileAsync(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val path = invocation.getArgument<Path>(1)
            if (key.endsWith(".parquet")) {
                borrowedParquet.set(path)
                parquetUpload
            } else {
                CompletableFuture.completedFuture(PutResult(key, Files.size(path), null))
            }
        }

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = eventPublisher,
            objectStorage = storage,
            nexonAuthClient = mock(),
            stopSignal = PhaseStopSignal(),
            streamingChunkParser = StreamingChunkParser(objectMapper),
            chunkParserMetrics = ChunkParserMetrics(SimpleMeterRegistry()),
            ocidMappingArtifactWriter = mappingWriter(storage),
        )
        val workerExecutor = Executors.newSingleThreadExecutor()

        try {
            runBlocking {
                val execution = async(Dispatchers.Default) {
                    phase.execute(workerExecutor, "runs/abc", "abc")
                }
                await().atMost(Duration.ofSeconds(10)).until { borrowedParquet.get() != null }
                val path = requireNotNull(borrowedParquet.get())
                assertThat(path).exists()

                execution.cancelAndJoin()
                parquetUpload.complete(PutResult("ocid-mapping-parquet/ocid-mapping-abc.parquet", Files.size(path), null))
                await().atMost(Duration.ofSeconds(5)).until {
                    Files.notExists(path) && Files.notExists(path.parent)
                }
            }
            verify(eventPublisher, never()).publishRunCompleted(any())
        } finally {
            parquetUpload.completeExceptionally(IllegalStateException("test cleanup"))
            workerExecutor.shutdownNow()
            borrowedParquet.get()?.let { path ->
                Files.deleteIfExists(path)
                Files.deleteIfExists(path.parent)
            }
        }
    }

    private fun mappingWriter(storage: ConditionalObjectStorage): OcidMappingArtifactWriter = OcidMappingArtifactWriter(
        DefaultArtifactWriter(storage, java.util.concurrent.Executor { command -> command.run() }),
    )
}
