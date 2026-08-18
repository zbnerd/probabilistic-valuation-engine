package maple.pipeline.artifact.lifecycle

import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.SourceArtifactLayout

class RunLifecycle(
    private val objectStorage: ObjectStorage,
    private val artifactUploadExecutor: Executor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun startEndpoint(runId: String, endpoint: String): CompletableFuture<RunState> {
        val runningKey = runningKey(runId, endpoint)
        val markerBytes = Instant.now(clock).toString().toByteArray()
        return put(runningKey, markerBytes).thenApply { RunState.Running }
    }

    fun finalizeEndpoint(
        runId: String,
        endpoint: String,
        manifestBytes: ByteArray,
        requiredPublish: () -> CompletionStage<Void>,
    ): CompletableFuture<RunState> {
        val manifestKey = SourceArtifactLayout.manifest(runId, endpoint)
        val successKey = SourceArtifactLayout.endpointSuccess(runId, endpoint)
        val runningKey = runningKey(runId, endpoint)
        return put(manifestKey, manifestBytes)
            .thenCompose { put(successKey, ByteArray(0)) }
            .thenCompose { invokeRequiredPublish(requiredPublish) }
            .thenCompose { deletePublishedMarker(runningKey) }
    }

    fun replayPublicationPending(
        runId: String,
        endpoint: String,
        requiredPublishFromManifest: (ByteArray) -> CompletionStage<Void>,
    ): CompletableFuture<RunState> {
        val manifestKey = SourceArtifactLayout.manifest(runId, endpoint)
        val runningKey = runningKey(runId, endpoint)
        return get(manifestKey)
            .thenCompose { manifestBytes -> invokeRequiredPublish { requiredPublishFromManifest(manifestBytes) } }
            .thenCompose { deletePublishedMarker(runningKey) }
    }

    private fun put(key: ArtifactKey, bytes: ByteArray): CompletableFuture<Void> = CompletableFuture.runAsync(
        { objectStorage.put(key.value, bytes) },
        artifactUploadExecutor,
    )

    private fun get(key: ArtifactKey): CompletableFuture<ByteArray> = CompletableFuture.supplyAsync(
        { objectStorage.get(key.value) },
        artifactUploadExecutor,
    )

    private fun invokeRequiredPublish(requiredPublish: () -> CompletionStage<Void>): CompletableFuture<Void> = runCatching { requiredPublish().toCompletableFuture() }
        .getOrElse { failure -> CompletableFuture.failedFuture(failure) }

    private fun deletePublishedMarker(runningKey: ArtifactKey): CompletableFuture<RunState> = CompletableFuture.supplyAsync(
        {
            runCatching { objectStorage.delete(runningKey.value) }
                .fold(
                    onSuccess = { RunState.Published },
                    onFailure = { RunState.PublishedWithOrphanMarker },
                )
        },
        artifactUploadExecutor,
    )

    private fun runningKey(runId: String, endpoint: String): ArtifactKey = if (endpoint == RANKING_ENDPOINT) {
        SourceArtifactLayout.legacyRankingRunning(runId)
    } else {
        SourceArtifactLayout.endpointRunning(runId, endpoint)
    }

    private companion object {
        const val RANKING_ENDPOINT: String = "ranking-overall"
    }
}
