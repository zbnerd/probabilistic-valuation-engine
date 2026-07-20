package maple.cleanup.service

import java.time.Instant
import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupResult
import maple.pipeline.artifact.identity.ArtifactPrefix
import maple.pipeline.artifact.identity.CalculatorArtifactLayout
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.lifecycle.RunState
import maple.pipeline.artifact.retention.ArtifactRetentionService
import maple.pipeline.artifact.retention.ArtifactRunCatalog
import maple.pipeline.artifact.retention.ArtifactRunInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Airflow-triggered whole-run retention for source and calculator artifacts.
 * The typed catalog owns exhaustive listing and classification; the retention
 * service owns policy bounds and exact-prefix deletion.
 */
@Service
class RunCleanupService(
    private val properties: CleanupProperties,
    private val artifactRunCatalog: ArtifactRunCatalog,
    private val artifactRetentionService: ArtifactRetentionService,
) {
    private val log = LoggerFactory.getLogger(RunCleanupService::class.java)

    fun cleanupRuns(): RunCleanupResult = cleanup(SourceArtifactLayout.runPrefix)

    fun cleanupCalculatorRuns(): RunCleanupResult = cleanup(CalculatorArtifactLayout.runPrefix)

    internal fun cleanup(root: ArtifactPrefix, now: Instant = Instant.now()): RunCleanupResult {
        val startedAt = Instant.now()
        log.info("[Cleanup] started prefix={} dryRun={}", root.value, properties.dryRun)
        val runs = artifactRunCatalog.list(root)
        emitClassificationCounts(root, runs)
        return artifactRetentionService.cleanup(
            runs = runs,
            dryRun = properties.dryRun,
            keepRecent = properties.runs.keepRecent,
            keepWithinHours = properties.runs.keepWithinHours,
            maxDeleteRunsPerCycle = properties.maxDeleteRunsPerCycle,
            maxDeleteBytesPerCycle = properties.maxDeleteBytesPerCycle,
            maxRuntimeSeconds = properties.maxRuntimeSeconds,
            startedAt = startedAt,
            now = now,
        )
    }

    private fun emitClassificationCounts(root: ArtifactPrefix, runs: List<ArtifactRunInfo>) {
        val invalid = runs.count { run -> run.state is RunState.Invalid }
        val protected = runs.count(::isProtected)
        log.info(
            "[Cleanup] catalog prefix={} scanned={} protected={} invalid={}",
            root.value,
            runs.size,
            protected,
            invalid,
        )
    }

    private fun isProtected(run: ArtifactRunInfo): Boolean = when (run.state) {
        RunState.Published, is RunState.Incomplete, is RunState.Invalid -> false
        else -> true
    }
}
