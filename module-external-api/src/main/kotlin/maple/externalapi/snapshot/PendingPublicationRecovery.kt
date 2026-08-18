package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.pipeline.artifact.identity.ArtifactReplayEventId
import maple.pipeline.artifact.identity.ArtifactSegment
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunLifecycle
import maple.pipeline.artifact.lifecycle.RunState
import maple.pipeline.artifact.retention.ArtifactRunCatalog
import maple.pipeline.artifact.retention.ArtifactRunInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class PendingPublicationRecovery(
    private val runLifecycle: RunLifecycle,
    private val objectMapper: ObjectMapper,
    @Qualifier("characterBasicSnapshotPublisher")
    characterBasicPublisher: SnapshotChunkEventPublisher,
    @Qualifier("rankingSnapshotPublisher")
    rankingPublisher: SnapshotChunkEventPublisher,
    private val artifactRunCatalog: ArtifactRunCatalog,
    @Qualifier("artifactUploadExecutor")
    private val artifactUploadExecutor: Executor,
    private val metrics: PendingPublicationRecoveryMetrics,
) {
    private val log = LoggerFactory.getLogger(PendingPublicationRecovery::class.java)
    private val characterBasicPublisher = SinkEventPublisher(characterBasicPublisher)
    private val rankingPublisher = SinkEventPublisher(rankingPublisher)
    private val startupRecoveryStarted = AtomicBoolean(false)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!startupRecoveryStarted.compareAndSet(false, true)) return
        CompletableFuture.supplyAsync(::listSourceRuns, artifactUploadExecutor)
            .handle(::handleSourceListing)
            .thenCompose { completion -> completion }
    }

    fun recover(runId: String, endpoint: String): CompletableFuture<RunState> {
        val replay = runLifecycle.replayPublicationPending(runId, endpoint) { manifestBytes ->
            publishValidatedManifest(runId, endpoint, manifestBytes)
        }
        return replay.handle { state, failure -> classifyReplayResult(state, failure) }
            .thenCompose { result -> result }
    }

    private fun listSourceRuns(): List<ArtifactRunInfo> = artifactRunCatalog.list(SourceArtifactLayout.runPrefix)

    private fun handleSourceListing(
        runs: List<ArtifactRunInfo>?,
        failure: Throwable?,
    ): CompletableFuture<Void> {
        if (failure == null) return recoverPendingEndpoints(requireNotNull(runs))
        val cause = unwrapCompletionFailure(failure)
        metrics.recordListFailure()
        log.error("[ArtifactRecovery] source artifact listing failed", cause)
        return CompletableFuture.completedFuture(null)
    }

    private fun recoverPendingEndpoints(runs: List<ArtifactRunInfo>): CompletableFuture<Void> {
        val recoveries = runs.flatMap(::recoverPendingEndpoints)
        return CompletableFuture.allOf(*recoveries.toTypedArray())
    }

    private fun recoverPendingEndpoints(run: ArtifactRunInfo): List<CompletableFuture<Void>> =
        run.endpoints
            .filter { endpoint -> endpoint.state == RunState.ArtifactSucceededPublicationPending }
            .map { endpoint -> recoverPendingEndpoint(run.runId, endpoint.endpoint) }

    private fun recoverPendingEndpoint(runId: String, endpoint: String): CompletableFuture<Void> {
        val observed = recover(runId, endpoint).handle { state, failure ->
            observeReplay(runId, endpoint, state, failure)
        }
        return observed.thenApply<Void> { null }
    }

    private fun observeReplay(
        runId: String,
        endpoint: String,
        state: RunState?,
        failure: Throwable?,
    ) {
        if (failure != null) {
            metrics.recordReplayFailure()
            log.error(
                "[ArtifactRecovery] publication replay failed runId={} endpoint={}",
                runId,
                endpoint,
                failure,
            )
        } else if (state == RunState.Published) {
            metrics.recordRecoveredEndpoint()
            log.info("[ArtifactRecovery] publication replay completed runId={} endpoint={}", runId, endpoint)
        } else {
            metrics.recordReplayFailure()
            log.warn(
                "[ArtifactRecovery] publication replay retained marker runId={} endpoint={} state={}",
                runId,
                endpoint,
                state,
            )
        }
    }

    private fun publishValidatedManifest(
        runId: String,
        endpoint: String,
        manifestBytes: ByteArray,
    ): CompletableFuture<Void> = runCatching {
        val publication = validateManifest(runId, endpoint, manifestBytes)
        publish(publication, publisherFor(endpoint))
    }.getOrElse { failure ->
        CompletableFuture.failedFuture(IncompleteManifestException(failure.message ?: "invalid manifest", failure))
    }

    private fun validateManifest(
        runId: String,
        endpoint: String,
        manifestBytes: ByteArray,
    ): RecoveryPublication {
        ArtifactSegment.require(runId)
        ArtifactSegment.require(endpoint)
        val manifest = objectMapper.readValue(manifestBytes, SnapshotChunkManifest::class.java)
        require(manifest.runId == runId) { "manifest runId does not match recovery runId" }
        require(manifest.endpoint == endpoint) { "manifest endpoint does not match recovery endpoint" }
        require(manifest.startedAt != Instant.EPOCH) { "manifest startedAt must not be epoch" }
        require(manifest.finishedAt != Instant.EPOCH) { "manifest finishedAt must not be epoch" }
        require(!manifest.finishedAt.isBefore(manifest.startedAt)) {
            "manifest finishedAt must not precede startedAt"
        }
        require(manifest.totalRecords >= 0) { "manifest totalRecords must not be negative" }
        require(manifest.totalFailed >= 0) { "manifest totalFailed must not be negative" }

        val chunkEvents = manifest.chunks.map { entry -> toChunkEvent(runId, endpoint, entry) }
        require(chunkEvents.map(SnapshotChunkReadyEvent::chunkId).distinct().size == chunkEvents.size) {
            "manifest chunk paths must be unique"
        }
        require(chunkEvents.sumOf { event -> event.recordCount.toLong() } == manifest.totalRecords.toLong()) {
            "manifest totalRecords must equal chunk record count"
        }

        return RecoveryPublication(
            chunkEvents = chunkEvents,
            runCompleted = SnapshotRunCompletedEvent(
                eventId = ArtifactReplayEventId.forRun(RUN_COMPLETED_EVENT_TYPE, runId, endpoint).toString(),
                runId = runId,
                endpoint = endpoint,
                manifestPath = SourceArtifactLayout.manifest(runId, endpoint).value,
                totalRecords = manifest.totalRecords,
                totalFailed = manifest.totalFailed,
                chunkCount = manifest.chunks.size,
                startedAt = manifest.startedAt,
                finishedAt = manifest.finishedAt,
                createdAt = manifest.finishedAt,
            ),
        )
    }

    private fun toChunkEvent(
        runId: String,
        endpoint: String,
        entry: ChunkEntry,
    ): SnapshotChunkReadyEvent {
        val fileName = ArtifactSegment.require(entry.path).value
        require(fileName.endsWith(CHUNK_SUFFIX)) { "manifest chunk path must end with $CHUNK_SUFFIX" }
        val chunkId = ArtifactSegment.require(fileName.removeSuffix(CHUNK_SUFFIX)).value
        require(entry.recordCount >= 0) { "manifest chunk recordCount must not be negative" }
        require(entry.uncompressedBytes >= 0) { "manifest chunk uncompressedBytes must not be negative" }
        require(entry.compressedBytes >= 0) { "manifest chunk compressedBytes must not be negative" }
        require(entry.startedAt != Instant.EPOCH) { "manifest chunk startedAt must not be epoch" }
        require(entry.finishedAt != Instant.EPOCH) { "manifest chunk finishedAt must not be epoch" }
        require(!entry.finishedAt.isBefore(entry.startedAt)) {
            "manifest chunk finishedAt must not precede startedAt"
        }

        return SnapshotChunkReadyEvent(
            eventId = ArtifactReplayEventId.forChunk(CHUNK_READY_EVENT_TYPE, runId, endpoint, chunkId).toString(),
            runId = runId,
            endpoint = endpoint,
            chunkId = chunkId,
            objectKey = SourceArtifactLayout.chunk(runId, endpoint, chunkId).value,
            recordCount = entry.recordCount,
            uncompressedBytes = entry.uncompressedBytes,
            compressedBytes = entry.compressedBytes,
            sha256 = null,
            createdAt = entry.finishedAt,
        )
    }

    private fun publish(
        publication: RecoveryPublication,
        publisher: SinkEventPublisher,
    ): CompletableFuture<Void> {
        val chunkPublishes = publication.chunkEvents
            .map(publisher::publishChunkReady)
            .toTypedArray()
        return CompletableFuture.allOf(*chunkPublishes)
            .thenCompose { publisher.publishRunCompleted(publication.runCompleted) }
    }

    private fun publisherFor(endpoint: String): SinkEventPublisher = when (endpoint) {
        RANKING_ENDPOINT -> rankingPublisher
        CHARACTER_BASIC_ENDPOINT, ITEM_EQUIPMENT_ENDPOINT -> characterBasicPublisher
        else -> throw IllegalArgumentException("unsupported publication endpoint: $endpoint")
    }

    private fun classifyReplayResult(
        state: RunState?,
        failure: Throwable?,
    ): CompletableFuture<RunState> {
        if (failure == null) return CompletableFuture.completedFuture(requireNotNull(state))
        val cause = unwrapCompletionFailure(failure)
        return if (cause is IncompleteManifestException) {
            CompletableFuture.completedFuture(RunState.Incomplete(cause.message ?: "invalid manifest"))
        } else {
            CompletableFuture.failedFuture(cause)
        }
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable = when (failure) {
        is java.util.concurrent.CompletionException,
        is java.util.concurrent.ExecutionException,
        -> failure.cause?.let(::unwrapCompletionFailure) ?: failure

        else -> failure
    }

    private data class RecoveryPublication(
        val chunkEvents: List<SnapshotChunkReadyEvent>,
        val runCompleted: SnapshotRunCompletedEvent,
    )

    private class IncompleteManifestException(message: String, cause: Throwable) :
        IllegalArgumentException(message, cause)

    private companion object {
        const val CHUNK_READY_EVENT_TYPE: String = "SNAPSHOT_CHUNK_READY"
        const val RUN_COMPLETED_EVENT_TYPE: String = "SNAPSHOT_RUN_COMPLETED"
        const val CHUNK_SUFFIX: String = ".jsonl.gz"
        const val RANKING_ENDPOINT: String = "ranking-overall"
        const val CHARACTER_BASIC_ENDPOINT: String = "character-basic"
        const val ITEM_EQUIPMENT_ENDPOINT: String = "item-equipment"
    }
}
