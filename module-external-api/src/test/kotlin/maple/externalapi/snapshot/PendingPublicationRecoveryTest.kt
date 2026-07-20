package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.ArtifactReplayEventId
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.lifecycle.RunState
import maple.pipeline.artifact.retention.ArtifactEndpointInfo
import maple.pipeline.artifact.retention.ArtifactRunCatalog
import maple.pipeline.artifact.retention.ArtifactRunInfo
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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

    @Test
    fun `first ready event recovers legacy and endpoint markers in background exactly once`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        val rankingRunId = "ranking-run"
        seedPending(objects, RUN_ID, ENDPOINT, objectMapper.writeValueAsBytes(validManifest()))
        seedPending(
            objects,
            rankingRunId,
            RANKING_ENDPOINT,
            objectMapper.writeValueAsBytes(validManifest(rankingRunId, RANKING_ENDPOINT)),
        )
        objects[SourceArtifactLayout.legacyRankingRunning(RUN_ID).value] = RUNNING_BYTES
        objects[SourceArtifactLayout.endpointRunning(rankingRunId, RANKING_ENDPOINT).value] = RUNNING_BYTES
        val catalog = mock<ArtifactRunCatalog>()
        whenever(catalog.list(SourceArtifactLayout.runPrefix)).thenReturn(
            listOf(
                pendingRun(RUN_ID, ENDPOINT),
                pendingRun(rankingRunId, RANKING_ENDPOINT),
            ),
        )
        val characterPublisher = successfulPublisher()
        val rankingPublisher = successfulPublisher()
        val executor = ManualExecutor()
        val registry = SimpleMeterRegistry()
        val recovery = recovery(
            storage = storage,
            characterPublisher = characterPublisher,
            catalog = catalog,
            executor = executor,
            registry = registry,
            rankingPublisher = rankingPublisher,
        )

        recovery.onApplicationReady()
        recovery.onApplicationReady()

        verifyNoInteractions(catalog)
        assertThat(executor.pendingCount).isEqualTo(1)
        assertThat(objects).containsKeys(
            SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value,
            SourceArtifactLayout.legacyRankingRunning(rankingRunId).value,
        )

        executor.runAll()
        recovery.onApplicationReady()

        verify(catalog).list(SourceArtifactLayout.runPrefix)
        assertThat(executor.pendingCount).isZero()
        assertThat(objects).doesNotContainKeys(
            SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value,
            SourceArtifactLayout.legacyRankingRunning(rankingRunId).value,
        )
        assertThat(objects).containsKeys(
            SourceArtifactLayout.legacyRankingRunning(RUN_ID).value,
            SourceArtifactLayout.endpointRunning(rankingRunId, RANKING_ENDPOINT).value,
        )

        val itemChunk = argumentCaptor<SnapshotChunkReadyEvent>()
        val itemRun = argumentCaptor<SnapshotRunCompletedEvent>()
        verify(characterPublisher).publishChunkReady(itemChunk.capture())
        verify(characterPublisher).publishRunCompleted(itemRun.capture())
        assertThat(itemChunk.firstValue.eventId).isEqualTo("89656389-43bb-5b93-b042-8cd4e66290fc")
        assertThat(itemRun.firstValue.eventId).isEqualTo("e1b7bcf0-1246-543c-926b-ab91ef37a635")

        val rankingChunk = argumentCaptor<SnapshotChunkReadyEvent>()
        val rankingRun = argumentCaptor<SnapshotRunCompletedEvent>()
        verify(rankingPublisher).publishChunkReady(rankingChunk.capture())
        verify(rankingPublisher).publishRunCompleted(rankingRun.capture())
        assertThat(rankingChunk.firstValue.eventId).isEqualTo(
            ArtifactReplayEventId.forChunk(
                "SNAPSHOT_CHUNK_READY",
                rankingRunId,
                RANKING_ENDPOINT,
                "part-000001",
            ).toString(),
        )
        assertThat(rankingRun.firstValue.eventId).isEqualTo(
            ArtifactReplayEventId.forRun("SNAPSHOT_RUN_COMPLETED", rankingRunId, RANKING_ENDPOINT).toString(),
        )
        assertThat(recoveredCount(registry)).isEqualTo(2.0)
        assertThat(failureCount(registry, "list")).isZero()
        assertThat(failureCount(registry, "replay")).isZero()
        assertStaticMetricTags(registry)
    }

    @Test
    fun `listing failure is observable and never blocks readiness or touches markers`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        seedPending(objects, objectMapper.writeValueAsBytes(validManifest()))
        val catalog = mock<ArtifactRunCatalog>()
        whenever(catalog.list(SourceArtifactLayout.runPrefix)).thenThrow(IllegalStateException("list page failed"))
        val publisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        val executor = ManualExecutor()
        val registry = SimpleMeterRegistry()
        val recovery = recovery(storage, publisher, catalog, executor, registry)

        recovery.onApplicationReady()

        assertThat(executor.pendingCount).isEqualTo(1)
        assertThat(objects).containsKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
        executor.runAll()
        assertThat(objects).containsKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
        verifyNoInteractions(publisher)
        assertThat(failureCount(registry, "list")).isEqualTo(1.0)
        assertThat(failureCount(registry, "replay")).isZero()
        assertThat(recoveredCount(registry)).isZero()
    }

    @Test
    fun `replay failure is observable and retains its pending marker`() {
        val storage = mock<ObjectStorage>()
        val objects = storageObjects(storage)
        seedPending(objects, objectMapper.writeValueAsBytes(validManifest()))
        val catalog = mock<ArtifactRunCatalog>()
        whenever(catalog.list(SourceArtifactLayout.runPrefix)).thenReturn(listOf(pendingRun(RUN_ID, ENDPOINT)))
        val publisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>()
        whenever(publisher.publishChunkReady(any())).thenReturn(
            CompletableFuture.failedFuture(IllegalStateException("broker unavailable")),
        )
        val executor = ManualExecutor()
        val registry = SimpleMeterRegistry()
        val recovery = recovery(storage, publisher, catalog, executor, registry)

        recovery.onApplicationReady()
        executor.runAll()

        assertThat(objects).containsKey(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)
        verify(publisher, never()).publishRunCompleted(any())
        assertThat(failureCount(registry, "list")).isZero()
        assertThat(failureCount(registry, "replay")).isEqualTo(1.0)
        assertThat(recoveredCount(registry)).isZero()
    }

    private fun recovery(
        storage: ObjectStorage,
        characterPublisher: maple.externalapi.snapshot.event.SnapshotChunkEventPublisher,
        catalog: ArtifactRunCatalog = mock(),
        executor: Executor = Executor(Runnable::run),
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        rankingPublisher: maple.externalapi.snapshot.event.SnapshotChunkEventPublisher = mock(),
    ): PendingPublicationRecovery = PendingPublicationRecovery(
        runLifecycle = RunLifecycle(storage, executor),
        objectMapper = objectMapper,
        characterBasicPublisher = characterPublisher,
        rankingPublisher = rankingPublisher,
        artifactRunCatalog = catalog,
        artifactUploadExecutor = executor,
        metrics = PendingPublicationRecoveryMetrics(registry),
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
        seedPending(objects, RUN_ID, ENDPOINT, manifestBytes)
    }

    private fun seedPending(
        objects: ConcurrentHashMap<String, ByteArray>,
        runId: String,
        endpoint: String,
        manifestBytes: ByteArray,
    ) {
        val runningKey = if (endpoint == RANKING_ENDPOINT) {
            SourceArtifactLayout.legacyRankingRunning(runId)
        } else {
            SourceArtifactLayout.endpointRunning(runId, endpoint)
        }
        objects[runningKey.value] = RUNNING_BYTES
        objects[SourceArtifactLayout.endpointSuccess(runId, endpoint).value] = ByteArray(0)
        objects[SourceArtifactLayout.manifest(runId, endpoint).value] = manifestBytes
    }

    private fun validManifest(
        runId: String = RUN_ID,
        endpoint: String = ENDPOINT,
    ): SnapshotChunkManifest = SnapshotChunkManifest(
        runId = runId,
        endpoint = endpoint,
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

    private fun pendingRun(runId: String, endpoint: String): ArtifactRunInfo = ArtifactRunInfo(
        runId = runId,
        prefix = SourceArtifactLayout.runRoot(runId),
        createdAt = STARTED_AT,
        sizeBytes = 1L,
        state = RunState.ArtifactSucceededPublicationPending,
        endpoints = listOf(
            ArtifactEndpointInfo(
                endpoint = endpoint,
                manifestKey = SourceArtifactLayout.manifest(runId, endpoint),
                state = RunState.ArtifactSucceededPublicationPending,
            ),
        ),
    )

    private fun successfulPublisher(): maple.externalapi.snapshot.event.SnapshotChunkEventPublisher =
        mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>().also { publisher ->
            whenever(publisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))
            whenever(publisher.publishRunCompleted(any())).thenReturn(CompletableFuture.completedFuture(null))
        }

    private fun failureCount(registry: SimpleMeterRegistry, stage: String): Double =
        registry.find("artifact_publication_recovery_failures_total").tag("stage", stage).counter()?.count() ?: 0.0

    private fun recoveredCount(registry: SimpleMeterRegistry): Double =
        registry.find("artifact_publication_recovered_endpoints_total").counter()?.count() ?: 0.0

    private fun assertStaticMetricTags(registry: SimpleMeterRegistry) {
        val failureMeters = registry.meters.filter { meter ->
            meter.id.name == "artifact_publication_recovery_failures_total"
        }
        assertThat(failureMeters).hasSize(2)
        assertThat(failureMeters.flatMap { meter -> meter.id.tags }.map { tag -> tag.key }.distinct())
            .containsExactly("stage")
        assertThat(failureMeters.flatMap { meter -> meter.id.tags }.map { tag -> tag.value })
            .containsExactlyInAnyOrder("list", "replay")
        assertThat(registry.find("artifact_publication_recovered_endpoints_total").counter()?.id?.tags)
            .isEmpty()
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private companion object {
        const val RUN_ID = "r1"
        const val ENDPOINT = "item-equipment"
        const val RANKING_ENDPOINT = "ranking-overall"
        val STARTED_AT: Instant = Instant.parse("2026-07-19T12:00:00Z")
        val CHUNK_FINISHED_AT: Instant = Instant.parse("2026-07-19T12:05:00Z")
        val FINISHED_AT: Instant = Instant.parse("2026-07-19T12:10:00Z")
        val RUNNING_BYTES: ByteArray = STARTED_AT.toString().toByteArray()
    }
}
