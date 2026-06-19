package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import maple.common.parser.StreamingChunkParser
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.externalapi.metrics.ChunkParserMetrics
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class OcidLookupPhaseTest {

    @Test
    fun `execute streams OCID mapping gzipped to ObjectStorage under ocid-mapping key`() {
        val storage = mock<ObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        whenever(storage.listByPrefix(any())).thenReturn(listOf(
            ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, Instant.now())
        ))
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

        // Collect streamed bytes (replaces the previous put() call). This
        // is exactly the path MinioObjectStorage.putStreamMultipart takes: it
        // streams the input through the S3AsyncClient chunked transfer. The
        // mock drains it to bytes for assertion.
        val collectedBytes = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        whenever(storage.putStreamMultipart(any(), any())).thenAnswer { invocation ->
            val input = invocation.getArgument<InputStream>(1)
            val bytes = input.readBytes()
            collectedBytes.set(bytes)
            CompletableFuture.completedFuture(
                PutResult(invocation.getArgument<String>(0), bytes.size.toLong(), null),
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
        )

        kotlinx.coroutines.runBlocking {
            phase.execute(
                workerExecutor = Executors.newSingleThreadExecutor(),
                runKey = "runs/abc",
                runId = "abc",
            )
        }

        // Verify the streaming putStreamMultipart was called with the right key.
        val mappingKeyCaptor = argumentCaptor<String>()
        verify(storage).putStreamMultipart(mappingKeyCaptor.capture(), any())
        val mappingKey = mappingKeyCaptor.firstValue
        assertNotNull(mappingKey)
        assertTrue(
            mappingKey.startsWith("ocid-mapping/ocid-mapping-"),
            "expected mapping key to start with 'ocid-mapping/ocid-mapping-' but was '$mappingKey'",
        )
        assertTrue(
            mappingKey.endsWith(".jsonl.gz"),
            "expected mapping key to end with '.jsonl.gz' but was '$mappingKey'",
        )

        // The streamed bytes should be a non-empty valid gzip containing the
        // expected userIgn/ocid pairs.
        val bytes = collectedBytes.get()
        assertNotNull(bytes, "putStreamMultipart should have been called")
        assertTrue(bytes!!.isNotEmpty(), "streamed bytes should not be empty")
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
        val storage = mock<ObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        val now = Instant.now()
        // Pre-existing OCID mappings: one for the current runId + two for prior runs
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(listOf(
            ObjectInfo("ocid-mapping/ocid-mapping-abc.jsonl.gz", 100, now),        // current runId
            ObjectInfo("ocid-mapping/ocid-mapping-old1.jsonl.gz", 100, now),
            ObjectInfo("ocid-mapping/ocid-mapping-old2.jsonl.gz", 100, now),
        ))
        // Ranking chunks from the upstream run
        whenever(storage.listByPrefix("runs/abc/ranking-overall/chunks")).thenReturn(listOf(
            ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, now)
        ))
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
        whenever(storage.putStreamMultipart(any(), any())).thenAnswer { invocation ->
            val input = invocation.getArgument<InputStream>(1)
            val bytes = input.readBytes()
            CompletableFuture.completedFuture(
                PutResult(invocation.getArgument<String>(0), bytes.size.toLong(), null),
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
        val storage = mock<ObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())
        val stopSignal = PhaseStopSignal()
        stopSignal.requestStop(PipelinePhase.OCID_LOOKUP)

        val now = Instant.now()
        whenever(storage.listByPrefix("runs/abc/ranking-overall/chunks")).thenReturn(listOf(
            ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, now)
        ))
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
        )

        assertThrows(PhaseStoppedException::class.java) {
            kotlinx.coroutines.runBlocking {
                phase.execute(Executors.newSingleThreadExecutor(), "runs/abc", "abc")
            }
        }
    }
}
