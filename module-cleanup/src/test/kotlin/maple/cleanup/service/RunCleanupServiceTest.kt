package maple.cleanup.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupResult
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.PutResult
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix
import maple.pipeline.artifact.identity.CalculatorArtifactLayout
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.retention.ArtifactRetentionService
import maple.pipeline.artifact.retention.ArtifactRunCatalog
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.storage.PutIfAbsentResult
import maple.pipeline.artifact.storage.StorageObjectPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class RunCleanupServiceTest {
    private val serviceLogger = requireNotNull(LoggerFactory.getLogger(RunCleanupService::class.java) as? Logger)
    private lateinit var logAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun captureLogs() {
        logAppender = ListAppender<ILoggingEvent>().apply { start() }
        serviceLogger.addAppender(logAppender)
    }

    @AfterEach
    fun stopCapturingLogs() {
        serviceLogger.detachAppender(logAppender)
        logAppender.stop()
    }

    @Test
    fun `cleanup excludes descendant active pending and invalid runs and emits their counts`() {
        val storage = CleanupStorage(
            listOf(
                objectInfo("runs/20260701-010101-1/item-equipment/manifest.json", 10L),
                objectInfo("runs/20260701-010101-1/item-equipment/_SUCCESS"),
                objectInfo("runs/20260701-010102-2/item-equipment/manifest.json", 20L),
                objectInfo("runs/20260701-010103-3/item-equipment/manifest.json", 30L),
                objectInfo("runs/20260701-010103-3/item-equipment/chunks/_RUNNING"),
                objectInfo("runs/20260701-010104-4/item-equipment/manifest.json", 40L),
                objectInfo("runs/20260701-010104-4/item-equipment/_SUCCESS"),
                objectInfo("runs/20260701-010104-4/item-equipment/_RUNNING"),
                objectInfo("runs/not-a-run/item-equipment/manifest.json", 50L),
                objectInfo("runs/not-a-run/item-equipment/_SUCCESS"),
            ),
        )
        val service = service(storage, dryRun = false)

        val result = service.cleanup(SourceArtifactLayout.runPrefix, NOW)

        assertThat(result.runsDeleted).isEqualTo(2)
        assertThat(storage.deletedPrefixes).containsExactly(
            "runs/20260701-010101-1/",
            "runs/20260701-010102-2/",
        )
        assertThat(storage.deletedPrefixes).noneMatch { prefix ->
            prefix.contains("20260701-010103-3") ||
                prefix.contains("20260701-010104-4") ||
                prefix.contains("not-a-run")
        }
        assertThat(logAppender.list.map(ILoggingEvent::getFormattedMessage))
            .anyMatch { message -> message.contains("scanned=5 protected=2 invalid=1") }
    }

    @Test
    fun `cleanupRuns returns zero through the typed source root when no runs exist`() {
        val storage = CleanupStorage(emptyList())

        val result = service(storage, dryRun = false).cleanupRuns()

        assertThat(result).isEqualTo(RunCleanupResult.ZERO)
        assertThat(storage.listedRoots).containsExactly(SourceArtifactLayout.runPrefix)
        assertThat(storage.deletedPrefixes).isEmpty()
    }

    @Test
    fun `cleanupCalculatorRuns targets the typed calculator root`() {
        val storage = CleanupStorage(
            listOf(
                objectInfo("calculator/runs/20260701-010101-1/item-equipment/chunks/result-a.jsonl.gz", 10L),
            ),
        )

        service(storage, dryRun = false).cleanup(CalculatorArtifactLayout.runPrefix, NOW)

        assertThat(storage.listedRoots).containsExactly(CalculatorArtifactLayout.runPrefix)
        assertThat(storage.deletedPrefixes).containsExactly("calculator/runs/20260701-010101-1/")
    }

    @Test
    fun `dry run reports candidates without deleting their typed prefixes`() {
        val storage = CleanupStorage(
            listOf(
                objectInfo("runs/20260701-010101-1/item-equipment/manifest.json", 10L),
                objectInfo("runs/20260701-010101-1/item-equipment/_SUCCESS"),
            ),
        )

        val result = service(storage, dryRun = true).cleanup(SourceArtifactLayout.runPrefix, NOW)

        assertThat(result.runsDeleted).isEqualTo(1)
        assertThat(storage.deletedPrefixes).isEmpty()
    }

    private fun service(storage: ConditionalObjectStorage, dryRun: Boolean): RunCleanupService = RunCleanupService(
        properties = props(dryRun),
        artifactRunCatalog = ArtifactRunCatalog(storage, ZoneOffset.UTC),
        artifactRetentionService = ArtifactRetentionService(storage),
    )

    private fun props(dryRun: Boolean) = CleanupProperties(
        dryRun = dryRun,
        runs = CleanupProperties.Runs(keepRecent = 0, keepWithinHours = 0),
        maxDeleteRunsPerCycle = 100,
        maxDeleteBytesPerCycle = 100_000_000L,
        maxRuntimeSeconds = 60,
    )

    private class CleanupStorage(objects: List<ObjectInfo>) : ConditionalObjectStorage {
        private val objects = objects.sortedBy(ObjectInfo::key)
        val listedRoots = mutableListOf<ArtifactPrefix>()
        val deletedPrefixes = mutableListOf<String>()

        override fun listPage(
            prefix: ArtifactPrefix,
            afterKey: ArtifactKey?,
            limit: Int,
        ): StorageObjectPage {
            listedRoots += prefix
            val candidates = objects.filter { objectInfo ->
                objectInfo.key.startsWith(prefix.value) && (afterKey == null || objectInfo.key > afterKey.value)
            }
            val pageObjects = candidates.take(limit)
            val next = if (candidates.size > limit) ArtifactKey.require(pageObjects.last().key) else null
            return StorageObjectPage(pageObjects, next)
        }

        override fun deleteByPrefix(prefix: String): Long {
            deletedPrefixes += prefix
            return objects.filter { objectInfo -> objectInfo.key.startsWith(prefix) }.sumOf(ObjectInfo::size)
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

        private fun <T> unsupported(): T = error("operation not used by RunCleanupServiceTest")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-20T00:00:00Z")
        val MODIFIED_AT: Instant = Instant.parse("2026-07-01T00:00:00Z")

        fun objectInfo(key: String, size: Long = 1L): ObjectInfo = ObjectInfo(key, size, MODIFIED_AT)
    }
}
