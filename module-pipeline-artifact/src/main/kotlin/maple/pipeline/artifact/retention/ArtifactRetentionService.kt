package maple.pipeline.artifact.retention

import java.time.Instant
import maple.common.cleanup.RunCleanupExecutor
import maple.common.cleanup.RunCleanupResult
import maple.common.cleanup.RunInfo
import maple.pipeline.artifact.identity.CalculatorArtifactLayout
import maple.pipeline.artifact.identity.SourceArtifactLayout
import maple.pipeline.artifact.identity.asPrefix
import maple.pipeline.artifact.lifecycle.RunState
import maple.pipeline.artifact.storage.ConditionalObjectStorage

class ArtifactRetentionService(
    private val objectStorage: ConditionalObjectStorage,
) {
    private val cleanupExecutor = RunCleanupExecutor("ArtifactRetention")

    fun cleanup(
        runs: List<ArtifactRunInfo>,
        dryRun: Boolean,
        keepRecent: Int,
        keepWithinHours: Long,
        maxDeleteRunsPerCycle: Int,
        maxDeleteBytesPerCycle: Long,
        maxRuntimeSeconds: Long,
        startedAt: Instant,
        now: Instant = Instant.now(),
    ): RunCleanupResult {
        val safeRuns = runs.filter(::isRetentionCandidate)
        val artifactsByRunId = safeRuns.associateBy(ArtifactRunInfo::runId)
        return cleanupExecutor.cleanup(
            runs = safeRuns.map(::toRunInfo),
            dryRun = dryRun,
            keepRecent = keepRecent,
            keepWithinHours = keepWithinHours,
            maxDeleteRunsPerCycle = maxDeleteRunsPerCycle,
            maxDeleteBytesPerCycle = maxDeleteBytesPerCycle,
            maxRuntimeSeconds = maxRuntimeSeconds,
            startedAt = startedAt,
            now = now,
            deleteRun = { run -> deleteExactRun(requireNotNull(artifactsByRunId[run.runId])) },
        )
    }

    private fun isRetentionCandidate(run: ArtifactRunInfo): Boolean = when (run.state) {
        RunState.Published, is RunState.Incomplete -> true
        else -> false
    }

    private fun toRunInfo(run: ArtifactRunInfo): RunInfo = RunInfo(
        runId = run.runId,
        createdAt = run.createdAt,
        isRunning = false,
        sizeBytes = run.sizeBytes,
    )

    private fun deleteExactRun(run: ArtifactRunInfo): Long {
        val sourcePrefix = runCatching { SourceArtifactLayout.runRoot(run.runId) }.getOrNull()
        val calculatorPrefix = runCatching { CalculatorArtifactLayout.runRoot(run.runId) }.getOrNull()
        require(run.prefix == sourcePrefix || run.prefix == calculatorPrefix) {
            "retention deletion requires an exact typed run prefix"
        }
        return objectStorage.deleteByPrefix(run.prefix.asPrefix().value)
    }
}
