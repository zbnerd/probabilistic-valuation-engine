package maple.pipeline.artifact.lifecycle

import java.io.InputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.SourceArtifactLayout
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RunLifecycleTest {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread.ofPlatform().name("artifact-lifecycle-test").unstarted(task)
    }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC)

    @AfterEach
    fun closeExecutor() {
        executor.shutdownNow()
    }

    @Test
    fun `manifest write failure keeps running marker and skips success and publication`() {
        val storage = RecordingObjectStorage()
        val lifecycle = RunLifecycle(storage, executor, clock)
        awaitSuccess(lifecycle.startEndpoint(RUN_ID, ENDPOINT))
        storage.failPutKey = SourceArtifactLayout.manifest(RUN_ID, ENDPOINT).value
        val published = AtomicBoolean(false)

        val outcome = awaitOutcome(
            lifecycle.finalizeEndpoint(RUN_ID, ENDPOINT, MANIFEST_BYTES) {
                published.set(true)
                CompletableFuture.completedFuture(null)
            },
        )

        assertThat(outcome.failure).hasRootCauseMessage("put failed for runs/run-1/item-equipment/manifest.json")
        assertThat(storage.exists(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)).isTrue()
        assertThat(storage.exists(SourceArtifactLayout.endpointSuccess(RUN_ID, ENDPOINT).value)).isFalse()
        assertThat(published.get()).isFalse()
    }

    @Test
    fun `success marker failure keeps running marker and does not publish`() {
        val storage = RecordingObjectStorage()
        val lifecycle = RunLifecycle(storage, executor, clock)
        awaitSuccess(lifecycle.startEndpoint(RUN_ID, ENDPOINT))
        storage.failPutKey = SourceArtifactLayout.endpointSuccess(RUN_ID, ENDPOINT).value
        val published = AtomicBoolean(false)

        val outcome = awaitOutcome(
            lifecycle.finalizeEndpoint(RUN_ID, ENDPOINT, MANIFEST_BYTES) {
                published.set(true)
                CompletableFuture.completedFuture(null)
            },
        )

        assertThat(outcome.failure).hasRootCauseMessage("put failed for runs/run-1/item-equipment/_SUCCESS")
        assertThat(storage.exists(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)).isTrue()
        assertThat(storage.exists(SourceArtifactLayout.endpointSuccess(RUN_ID, ENDPOINT).value)).isFalse()
        assertThat(published.get()).isFalse()
    }

    @Test
    fun `required publication failure retains success and running markers`() {
        val storage = RecordingObjectStorage()
        val lifecycle = RunLifecycle(storage, executor, clock)
        awaitSuccess(lifecycle.startEndpoint(RUN_ID, ENDPOINT))

        val outcome = awaitOutcome(
            lifecycle.finalizeEndpoint(RUN_ID, ENDPOINT, MANIFEST_BYTES) {
                storage.operations.add("publish")
                CompletableFuture.failedFuture(IllegalStateException("broker unavailable"))
            },
        )

        assertThat(outcome.failure).hasRootCauseMessage("broker unavailable")
        assertThat(storage.exists(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)).isTrue()
        assertThat(storage.exists(SourceArtifactLayout.endpointSuccess(RUN_ID, ENDPOINT).value)).isTrue()
        assertThat(storage.operations).endsWith(
            "put:runs/run-1/item-equipment/manifest.json",
            "put:runs/run-1/item-equipment/_SUCCESS",
            "publish",
        )
    }

    @Test
    fun `successful publication with marker delete failure returns orphan marker state`() {
        val storage = RecordingObjectStorage()
        val lifecycle = RunLifecycle(storage, executor, clock)
        awaitSuccess(lifecycle.startEndpoint(RUN_ID, ENDPOINT))
        storage.failDeleteKey = SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value

        val state = awaitSuccess(
            lifecycle.finalizeEndpoint(RUN_ID, ENDPOINT, MANIFEST_BYTES) {
                storage.operations.add("publish")
                CompletableFuture.completedFuture(null)
            },
        )

        assertThat(state).isEqualTo(RunState.PublishedWithOrphanMarker)
        assertThat(storage.exists(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)).isTrue()
        assertThat(storage.operations).endsWith(
            "put:runs/run-1/item-equipment/manifest.json",
            "put:runs/run-1/item-equipment/_SUCCESS",
            "publish",
            "delete:runs/run-1/item-equipment/_RUNNING",
        )
    }

    @Test
    fun `replay reads manifest republishes and then removes matching running marker`() {
        val storage = RecordingObjectStorage().apply {
            seed(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value, RUNNING_BYTES)
            seed(SourceArtifactLayout.endpointSuccess(RUN_ID, ENDPOINT).value, ByteArray(0))
            seed(SourceArtifactLayout.manifest(RUN_ID, ENDPOINT).value, MANIFEST_BYTES)
        }
        val lifecycle = RunLifecycle(storage, executor, clock)
        val replayedBytes = AtomicReference<ByteArray>()

        val state = awaitSuccess(
            lifecycle.replayPublicationPending(RUN_ID, ENDPOINT) { bytes ->
                replayedBytes.set(bytes)
                storage.operations.add("publish")
                CompletableFuture.completedFuture(null)
            },
        )

        assertThat(state).isEqualTo(RunState.Published)
        assertThat(replayedBytes.get()).containsExactly(*MANIFEST_BYTES)
        assertThat(storage.exists(SourceArtifactLayout.endpointRunning(RUN_ID, ENDPOINT).value)).isFalse()
        assertThat(storage.operations).containsExactly(
            "get:runs/run-1/item-equipment/manifest.json",
            "publish",
            "delete:runs/run-1/item-equipment/_RUNNING",
        )
    }

    @Test
    fun `ranking uses legacy root marker without creating endpoint running marker`() {
        val storage = RecordingObjectStorage()
        val lifecycle = RunLifecycle(storage, executor, clock)

        assertThat(awaitSuccess(lifecycle.startEndpoint(RUN_ID, RANKING_ENDPOINT))).isEqualTo(RunState.Running)
        assertThat(storage.exists(SourceArtifactLayout.legacyRankingRunning(RUN_ID).value)).isTrue()
        assertThat(storage.exists(SourceArtifactLayout.endpointRunning(RUN_ID, RANKING_ENDPOINT).value)).isFalse()

        val state = awaitSuccess(
            lifecycle.finalizeEndpoint(RUN_ID, RANKING_ENDPOINT, MANIFEST_BYTES) {
                storage.operations.add("publish")
                CompletableFuture.completedFuture(null)
            },
        )

        assertThat(state).isEqualTo(RunState.Published)
        assertThat(storage.exists(SourceArtifactLayout.legacyRankingRunning(RUN_ID).value)).isFalse()
        assertThat(storage.operations).containsExactly(
            "put:runs/run-1/_RUNNING",
            "put:runs/run-1/ranking-overall/manifest.json",
            "put:runs/run-1/ranking-overall/_SUCCESS",
            "publish",
            "delete:runs/run-1/_RUNNING",
        )
    }

    private fun <T> awaitSuccess(future: CompletableFuture<T>): T {
        val outcome = awaitOutcome(future)
        assertThat(outcome.failure).isNull()
        return requireNotNull(outcome.value)
    }

    private fun <T> awaitOutcome(future: CompletableFuture<T>): FutureOutcome<T> {
        val captured = AtomicReference<FutureOutcome<T>>()
        future.whenComplete { value, failure -> captured.set(FutureOutcome(value, failure)) }
        await().atMost(Duration.ofSeconds(5)).until { captured.get() != null }
        return requireNotNull(captured.get())
    }

    private data class FutureOutcome<T>(val value: T?, val failure: Throwable?)

    private class RecordingObjectStorage : ObjectStorage {
        private val objects = ConcurrentHashMap<String, ByteArray>()
        val operations = CopyOnWriteArrayList<String>()
        var failPutKey: String? = null
        var failDeleteKey: String? = null

        fun seed(key: String, bytes: ByteArray) {
            objects[key] = bytes.copyOf()
        }

        override fun put(key: String, data: ByteArray): PutResult {
            operations.add("put:$key")
            check(key != failPutKey) { "put failed for $key" }
            objects[key] = data.copyOf()
            return PutResult(key, data.size.toLong(), null)
        }

        override fun get(key: String): ByteArray {
            operations.add("get:$key")
            return requireNotNull(objects[key]) { "missing object $key" }.copyOf()
        }

        override fun delete(key: String) {
            operations.add("delete:$key")
            check(key != failDeleteKey) { "delete failed for $key" }
            objects.remove(key)
        }

        override fun exists(key: String): Boolean = objects.containsKey(key)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun putStream(key: String, input: InputStream): PutResult = unsupported()

        override fun putFile(key: String, path: Path): PutResult = unsupported()

        override fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult> = unsupported()

        override fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult> = unsupported()

        override fun getStream(key: String): InputStream = unsupported()

        override fun listByPrefix(prefix: String): List<ObjectInfo> = unsupported()

        override fun deleteByPrefix(prefix: String): Long = unsupported()

        override fun calculatePrefixSize(prefix: String): Long = unsupported()

        override fun getLastModified(key: String): Instant? = unsupported()

        private fun <T> unsupported(): T = error("operation not used by RunLifecycleTest")
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val ENDPOINT = "item-equipment"
        const val RANKING_ENDPOINT = "ranking-overall"
        val MANIFEST_BYTES: ByteArray = "{\"runId\":\"run-1\"}".toByteArray()
        val RUNNING_BYTES: ByteArray = "2026-07-19T12:00:00Z".toByteArray()
    }
}
