package maple.pipeline.artifact.retention

import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.config.ArtifactStorageAutoConfiguration
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix
import maple.pipeline.artifact.identity.CalculatorArtifactLayout
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunState
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.storage.PutIfAbsentResult
import maple.pipeline.artifact.storage.StorageObjectPage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean

class ArtifactRunCatalogTest {
    @Test
    fun `catalog classifies both marker topologies and keeps the most protective aggregate`() {
        val storage = CatalogStorage(
            objects = listOf(
                objectInfo("runs/20260701-010101-1/_RUNNING"),
                objectInfo("runs/20260701-010102-2/item-equipment/_RUNNING"),
                objectInfo("runs/20260701-010103-3/item-equipment/chunks/_RUNNING"),
                objectInfo("runs/20260701-010104-4/_RUNNING"),
                objectInfo("runs/20260701-010104-4/ranking-overall/manifest.json"),
                objectInfo("runs/20260701-010104-4/ranking-overall/_SUCCESS"),
                objectInfo("runs/20260701-010105-5/item-equipment/_RUNNING"),
                objectInfo("runs/20260701-010105-5/item-equipment/manifest.json"),
                objectInfo("runs/20260701-010105-5/item-equipment/_SUCCESS"),
                objectInfo("runs/20260701-010106-6/character-basic/manifest.json"),
                objectInfo("runs/20260701-010106-6/character-basic/_SUCCESS"),
                objectInfo("runs/20260701-010107-7/item-equipment/manifest.json"),
                objectInfo("runs/20260701-010108-8/item-equipment/_RUNNING"),
                objectInfo("runs/20260701-010108-8/item-equipment/manifest.json"),
                objectInfo("runs/20260701-010108-8/item-equipment/_SUCCESS"),
                objectInfo("runs/20260701-010108-8/../manifest.json"),
                objectInfo("runs/not-a-run/character-basic/manifest.json"),
                objectInfo("runs/not-a-run/character-basic/_SUCCESS"),
            ),
        )

        val runs = ArtifactRunCatalog(storage, ZoneOffset.UTC).list(SourceArtifactLayout.runPrefix)

        assertThat(runs.associate { it.runId to it.state }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "20260701-010101-1" to RunState.Running,
                "20260701-010102-2" to RunState.Running,
                "20260701-010103-3" to RunState.Running,
                "20260701-010104-4" to RunState.ArtifactSucceededPublicationPending,
                "20260701-010105-5" to RunState.ArtifactSucceededPublicationPending,
                "20260701-010106-6" to RunState.Published,
                "20260701-010107-7" to RunState.Incomplete("endpoint artifacts are incomplete"),
                "20260701-010108-8" to RunState.Invalid("artifact key or endpoint is invalid"),
                "not-a-run" to RunState.Invalid("run ID is invalid"),
            ),
        )
        assertThat(endpoint(runs, "20260701-010101-1", "ranking-overall").state)
            .isEqualTo(RunState.Running)
        assertThat(endpoint(runs, "20260701-010104-4", "ranking-overall").manifestKey)
            .isEqualTo(ArtifactKey.require("runs/20260701-010104-4/ranking-overall/manifest.json"))
        assertThat(endpoint(runs, "20260701-010105-5", "item-equipment").state)
            .isEqualTo(RunState.ArtifactSucceededPublicationPending)
    }

    @Test
    fun `catalog exhausts every page before classifying 1001 objects`() {
        val runId = "20260701-020000-1"
        val objects = buildList {
            add(objectInfo("runs/$runId/item-equipment/manifest.json"))
            add(objectInfo("runs/$runId/item-equipment/_SUCCESS"))
            repeat(999) { index ->
                add(objectInfo("runs/$runId/item-equipment/chunks/part-${index.toString().padStart(6, '0')}.jsonl.gz"))
            }
        }
        val storage = CatalogStorage(objects)

        val run = ArtifactRunCatalog(storage, ZoneOffset.UTC)
            .list(SourceArtifactLayout.runPrefix)
            .single()

        assertThat(run.state).isEqualTo(RunState.Published)
        assertThat(run.sizeBytes).isEqualTo(1_001L)
        assertThat(storage.pageRequests).hasSize(2)
        assertThat(storage.pageRequests.map { it.afterKey }).containsExactly(null, storage.pageRequests.first().lastKey)
    }

    @Test
    fun `catalog accepts only the two typed artifact roots`() {
        val storage = CatalogStorage(emptyList())
        val catalog = ArtifactRunCatalog(storage, ZoneOffset.UTC)

        assertThat(catalog.list(SourceArtifactLayout.runPrefix)).isEmpty()
        assertThat(catalog.list(CalculatorArtifactLayout.runPrefix)).isEmpty()
        assertThatThrownBy { catalog.list(ArtifactPrefix.require("other/runs/")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(storage.pageRequests).hasSize(2)
    }

    @Test
    fun `retention delegates bounds and deletes only stale safe exact run prefixes`() {
        val objects = listOf(
            objectInfo("calculator/runs/20260701-010101-1/item-equipment/chunks/result-a.jsonl.gz", size = 10L),
            objectInfo("calculator/runs/20260701-010102-2/item-equipment/chunks/result-b.jsonl.gz", size = 20L),
            objectInfo("calculator/runs/20260701-010103-3/item-equipment/_RUNNING", size = 1L),
            objectInfo("calculator/runs/not-a-run/item-equipment/chunks/result-c.jsonl.gz", size = 30L),
        )
        val storage = CatalogStorage(objects)
        val catalog = ArtifactRunCatalog(storage, ZoneOffset.UTC)
        val retention = ArtifactRetentionService(storage)
        val runs = catalog.list(CalculatorArtifactLayout.runPrefix)

        val result = retention.cleanup(
            runs = runs,
            dryRun = false,
            keepRecent = 0,
            keepWithinHours = 0,
            maxDeleteRunsPerCycle = 1,
            maxDeleteBytesPerCycle = 100L,
            maxRuntimeSeconds = 60,
            startedAt = Instant.now(),
            now = Instant.parse("2026-07-20T00:00:00Z"),
        )

        assertThat(result.runsDeleted).isEqualTo(1)
        assertThat(result.throttled).isEqualTo(1)
        assertThat(storage.deletedPrefixes).containsExactly("calculator/runs/20260701-010101-1/")
    }

    @Test
    fun `auto configuration declares exactly one catalog and retention bean`() {
        val beanTypes = ArtifactStorageAutoConfiguration::class.java.declaredMethods
            .filter { method -> method.getAnnotation(Bean::class.java) != null }
            .groupingBy { method -> method.returnType }
            .eachCount()

        assertThat(beanTypes[ArtifactRunCatalog::class.java]).isEqualTo(1)
        assertThat(beanTypes[ArtifactRetentionService::class.java]).isEqualTo(1)
    }

    private fun endpoint(
        runs: List<ArtifactRunInfo>,
        runId: String,
        endpoint: String,
    ): ArtifactEndpointInfo = runs.single { it.runId == runId }.endpoints.single { it.endpoint == endpoint }

    private class CatalogStorage(objects: List<ObjectInfo>) : ConditionalObjectStorage {
        private val objects = objects.sortedBy(ObjectInfo::key)
        val pageRequests = mutableListOf<PageRequest>()
        val deletedPrefixes = mutableListOf<String>()

        override fun listPage(
            prefix: ArtifactPrefix,
            afterKey: ArtifactKey?,
            limit: Int,
        ): StorageObjectPage {
            val candidates = objects.filter { objectInfo ->
                objectInfo.key.startsWith(prefix.value) && (afterKey == null || objectInfo.key > afterKey.value)
            }
            val pageObjects = candidates.take(limit)
            val next = if (candidates.size > limit) ArtifactKey.require(pageObjects.last().key) else null
            pageRequests += PageRequest(afterKey, pageObjects.lastOrNull()?.let { ArtifactKey.parse(it.key).getOrNull() })
            return StorageObjectPage(pageObjects, next)
        }

        override fun deleteByPrefix(prefix: String): Long {
            deletedPrefixes += prefix
            return objects.filter { it.key.startsWith(prefix) }.sumOf(ObjectInfo::size)
        }

        override fun putIfAbsent(key: String, data: ByteArray): CompletionStage<PutIfAbsentResult> = unsupported()
        override fun put(key: String, data: ByteArray): PutResult = unsupported()

        @Suppress("OVERRIDE_DEPRECATION")
        override fun putStream(key: String, input: InputStream): PutResult = unsupported()

        override fun putFile(key: String, path: Path): PutResult = unsupported()
        override fun putFileAsync(key: String, path: Path): CompletableFuture<PutResult> = unsupported()
        override fun putStreamMultipart(key: String, input: InputStream): CompletableFuture<PutResult> = unsupported()
        override fun get(key: String): ByteArray = unsupported()
        override fun getStream(key: String): InputStream = unsupported()
        override fun delete(key: String) = unsupported<Unit>()
        override fun exists(key: String): Boolean = unsupported()
        override fun listByPrefix(prefix: String): List<ObjectInfo> = unsupported()
        override fun calculatePrefixSize(prefix: String): Long = unsupported()
        override fun getLastModified(key: String): Instant? = unsupported()

        private fun <T> unsupported(): T = error("operation not used by ArtifactRunCatalogTest")
    }

    private data class PageRequest(val afterKey: ArtifactKey?, val lastKey: ArtifactKey?)

    private companion object {
        val MODIFIED_AT: Instant = Instant.parse("2026-07-01T00:00:00Z")

        fun objectInfo(key: String, size: Long = 1L): ObjectInfo = ObjectInfo(key, size, MODIFIED_AT)
    }
}
