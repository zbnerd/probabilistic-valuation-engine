package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.lifecycle.RunState
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingPublicationRecoveryTest {
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `recovery deterministically republishes every manifest event before deleting marker`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        val manifest = validManifest()
        seedPending(objects, objectMapper.writeValueAsBytes(manifest))
        val characterPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        whenever(characterPublisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))
        whenever(characterPublisher.publishRunCompleted(any())).thenReturn(CompletableFuture.completedFuture(null))
        val recovery = recovery(storage, characterPublisher)

        assertThat(awaitSuccess(recovery.recover(RUN_ID, ENDPOINT))).isEqualTo(RunState.Published)
        objects[SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value] = RUNNING_BYTES
        assertThat(awaitSuccess(recovery.recover(RUN_ID, ENDPOINT))).isEqualTo(RunState.Published)

        val chunkCaptor = argumentCaptor<SnapshotChunkReadyEvent>()
        verify(characterPublisher, times(2)).publishChunkReady(chunkCaptor.capture())
        assertThat(chunkCaptor.allValues[0]).isEqualTo(chunkCaptor.allValues[1])
        assertThat(chunkCaptor.firstValue).isEqualTo(
            SnapshotChunkReadyEvent(
                eventId = "89656389-43bb-5b93-b042-8cd4e66290fc",
                runId = RUN_ID,
                endpoint = ENDPOINT,
                chunkId = "part-000001",
                objectKey = "runs/r1/item-equipment/chunks/part-000001.jsonl.gz",
                recordCount = 3,
                uncompressedBytes = 300L,
                compressedBytes = 100L,
                sha256 = null,
                createdAt = CHUNK_FINISHED_AT,
            ),
        )

        val runCaptor = argumentCaptor<SnapshotRunCompletedEvent>()
        verify(characterPublisher, times(2)).publishRunCompleted(runCaptor.capture())
        assertThat(runCaptor.allValues[0]).isEqualTo(runCaptor.allValues[1])
        assertThat(runCaptor.firstValue).isEqualTo(
            SnapshotRunCompletedEvent(
                eventId = "e1b7bcf0-1246-543c-926b-ab91ef37a635",
                runId = RUN_ID,
                endpoint = ENDPOINT,
                manifestPath = "runs/r1/item-equipment/manifest.json",
                totalRecords = 3,
                totalFailed = 1,
                chunkCount = 1,
                startedAt = STARTED_AT,
                finishedAt = FINISHED_AT,
                createdAt = FINISHED_AT,
            ),
        )
        assertThat(objects).doesNotContainKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
    }

    @Test
    fun `invalid later chunk path rejects whole manifest before any send and keeps marker`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        val manifest = validManifest().apply {
            chunks.add(
                ChunkEntry(
                    path = "chunks/part-000002.jsonl.gz",
                    recordCount = 0,
                    uncompressedBytes = 0,
                    compressedBytes = 0,
                    startedAt = STARTED_AT,
                    finishedAt = CHUNK_FINISHED_AT,
                ),
            )
        }
        seedPending(objects, objectMapper.writeValueAsBytes(manifest))
        val characterPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        val recovery = recovery(storage, characterPublisher)

        val state = awaitSuccess(recovery.recover(RUN_ID, ENDPOINT))

        assertThat(state).isInstanceOfSatisfying(RunState.Incomplete::class.java) { incomplete ->
            assertThat(incomplete.reason).contains("path")
        }
        verify(characterPublisher, never()).publishChunkReady(any())
        verify(characterPublisher, never()).publishRunCompleted(any())
        assertThat(objects).containsKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
    }

    @Test
    fun `epoch timestamp is incomplete and is never replaced with current time`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        seedPending(
            objects,
            objectMapper.writeValueAsBytes(validManifest().copy(finishedAt = Instant.EPOCH)),
        )
        val characterPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        val recovery = recovery(storage, characterPublisher)

        val state = awaitSuccess(recovery.recover(RUN_ID, ENDPOINT))

        assertThat(state).isInstanceOfSatisfying(RunState.Incomplete::class.java) { incomplete ->
            assertThat(incomplete.reason).contains("finishedAt")
        }
        verify(characterPublisher, never()).publishChunkReady(any())
        verify(characterPublisher, never()).publishRunCompleted(any())
        assertThat(objects).containsKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
    }

    @Test
    fun `malformed timestamp is incomplete and keeps pending marker`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        val malformed = objectMapper.writeValueAsString(validManifest())
            .replace(FINISHED_AT.toString(), "not-an-instant")
            .toByteArray()
        seedPending(objects, malformed)
        val characterPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        val recovery = recovery(storage, characterPublisher)

        val state = awaitSuccess(recovery.recover(RUN_ID, ENDPOINT))

        assertThat(state).isInstanceOf(RunState.Incomplete::class.java)
        verify(characterPublisher, never()).publishChunkReady(any())
        verify(characterPublisher, never()).publishRunCompleted(any())
        assertThat(objects).containsKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
    }

    private fun recovery(
        storage: ObjectStorage,
        characterPublisher: maple.externalapi.snapshot.event.SnapshotChunkEventPublisher,
    ): PendingPublicationRecovery = PendingPublicationRecovery(
        runLifecycle = RunLifecycle(storage, Executor(Runnable::run)),
        objectMapper = objectMapper,
        characterBasicPublisher = characterPublisher,
        rankingPublisher = mock(),
    )

    private fun storageObjects(storage: ObjectStorage): ConcurrentHashMap<String, ByteArray> {
        val objects = ConcurrentHashMap<String, ByteArray>()
        whenever(storage.get(any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            requireNotNull(objects[key]) { "missing object $key" }.copyOf()
        }
        whenever(storage.delete(any())).thenAnswer { invocation ->
            objects.remove(invocation.getArgument<String>(0))
            Unit
        }
        return objects
    }

    private fun seedPending(objects: ConcurrentHashMap<String, ByteArray>, manifestBytes: ByteArray) {
        objects[SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value] = RUNNING_BYTES
        objects[SourceArtifactLayout.endpointSuccess(RUN_ID, ENDPOINT).value] = ByteArray(0)
        objects[SourceArtifactLayout.manifest(RUN_ID, ENDPOINT).value] = manifestBytes
    }

    private fun validManifest(): SnapshotChunkManifest = SnapshotChunkManifest(
        runId = RUN_ID,
        endpoint = ENDPOINT,
        startedAt = STARTED_AT,
        finishedAt = FINISHED_AT,
        chunks = mutableListOf(
            ChunkEntry(
                path = "part-000001.jsonl.gz",
                recordCount = 3,
                uncompressedBytes = 300,
                compressedBytes = 100,
                startedAt = STARTED_AT,
                finishedAt = CHUNK_FINISHED_AT,
            ),
        ),
        totalRecords = 3,
        totalFailed = 1,
    )

    private fun <T> awaitSuccess(future: CompletableFuture<T>): T {
        val captured = AtomicReference<FutureOutcome<T>>()
        future.whenComplete { value, failure -> captured.set(FutureOutcome(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { captured.get() != null }
        val outcome = requireNotNull(captured.get())
        assertThat(outcome.failure).isNull()
        return requireNotNull(outcome.value)
    }

    private data class FutureOutcome<T>(val value: T?, val failure: Throwable?)

    private companion object {
        const val RUN_ID = "r1"
        const val ENDPOINT = "item-equipment"
        val STARTED_AT: Instant = Instant.parse("2026-07-19T12:00:00Z")
        val CHUNK_FINISHED_AT: Instant = Instant.parse("2026-07-19T12:05:00Z")
        val FINISHED_AT: Instant = Instant.parse("2026-07-19T12:10:00Z")
        val RUNNING_BYTES: ByteArray = STARTED_AT.toString().toByteArray()
    }
}
