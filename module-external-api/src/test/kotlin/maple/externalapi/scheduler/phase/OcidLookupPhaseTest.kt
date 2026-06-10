package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.infrastructure.external.NexonAuthClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class OcidLookupPhaseTest {

    @Test
    fun `execute writes OCID mapping gzipped to ObjectStorage under ocid-mapping key`() {
        val storage = mock<ObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())

        whenever(storage.listByPrefix(any())).thenReturn(listOf(
            ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, Instant.now())
        ))
        // Chunk files are gzipped JSONL on disk; produce a gzipped fixture.
        val chunkBytes = run {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gz ->
                gz.write("{\"key\":\"user1\"}\n{\"key\":\"user2\"}\n".toByteArray())
            }
            out.toByteArray()
        }
        whenever(storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"))
            .thenReturn(chunkBytes.inputStream())

        // clientPort is used for the per-IGN OCID lookup. Stub it to return an ocid JSON for each IGN.
        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenAnswer { invocation ->
            val ign = invocation.getArgument<String>(2)
            val payload = "{\"ocid\":\"ocid-for-$ign\"}"
            CompletableFuture.completedFuture(payload.toByteArray())
        }

        val mappingKeyCaptor = argumentCaptor<String>()
        val mappingBytesCaptor = argumentCaptor<ByteArray>()
        whenever(storage.put(mappingKeyCaptor.capture(), mappingBytesCaptor.capture()))
            .thenReturn(PutResult("k", 0, null))

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>(),
            objectStorage = storage,
            nexonAuthClient = nexonClient,
        )

        kotlinx.coroutines.runBlocking {
            phase.execute(
                workerExecutor = Executors.newSingleThreadExecutor(),
                runKey = "runs/abc",
            )
        }

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

        // Verify the captured bytes are a valid gzip stream
        val captured = mappingBytesCaptor.firstValue
        assertTrue(captured.isNotEmpty(), "mapping bytes should not be empty")
        val decompressed = java.util.zip.GZIPInputStream(captured.inputStream())
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
}
