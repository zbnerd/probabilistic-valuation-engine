package maple.common.cleanup

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

class RunCleanupExecutor(
    private val logPrefix: String,
) {
    private val log = KotlinLogging.logger {}

    fun cleanup(
        runs: List<RunInfo>,
        dryRun: Boolean,
        keepRecent: Int,
        keepWithinHours: Long,
        maxDeleteRunsPerCycle: Int,
        maxDeleteBytesPerCycle: Long,
        maxRuntimeSeconds: Long,
        startedAt: Instant,
        now: Instant = Instant.now(),
        deleteRun: (RunInfo) -> Long,
        onThrottled: (Int) -> Unit = {},
        onDeletedBytes: (Long) -> Unit = {},
        onDeletedRuns: (Int) -> Unit = {},
        onDeleteError: (RunInfo) -> Unit = {},
        onDryRunCandidate: (RunInfo) -> Unit = {},
    ): RunCleanupResult {
        if (runs.isEmpty()) {
            log.info { "[$logPrefix] no runs found" }
            return RunCleanupResult.ZERO
        }

        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runs,
            keepRecentCount = keepRecent,
            keepWithinHours = keepWithinHours,
            now = now,
        )

        if (toDelete.isEmpty()) {
            log.info { "[$logPrefix] no runs to delete" }
            return RunCleanupResult.ZERO
        }

        val throttled = maxOf(0, toDelete.size - maxDeleteRunsPerCycle)
        val limited = if (toDelete.size > maxDeleteRunsPerCycle) {
            log.info { "[$logPrefix] throttling: ${toDelete.size} candidates, limit $maxDeleteRunsPerCycle" }
            onThrottled(throttled)
            toDelete.take(maxDeleteRunsPerCycle)
        } else {
            toDelete
        }

        log.info {
            "[$logPrefix] candidates: ${limited.size} of ${runs.size} scanned " +
                "(throttled=$throttled, dryRun=$dryRun)"
        }

        if (dryRun) {
            limited.forEach(onDryRunCandidate)
            return RunCleanupResult(limited.size, limited.sumOf { it.sizeBytes }, 0, throttled)
        }

        return deleteRunWithLimits(
            runs = limited,
            startedAt = startedAt,
            maxDeleteBytesPerCycle = maxDeleteBytesPerCycle,
            maxRuntimeSeconds = maxRuntimeSeconds,
            throttled = throttled,
            deleteRun = deleteRun,
            onDeletedBytes = onDeletedBytes,
            onDeleteError = onDeleteError,
        ).also { onDeletedRuns(it.runsDeleted) }
    }

    private fun deleteRunWithLimits(
        runs: List<RunInfo>,
        startedAt: Instant,
        maxDeleteBytesPerCycle: Long,
        maxRuntimeSeconds: Long,
        throttled: Int,
        deleteRun: (RunInfo) -> Long,
        onDeletedBytes: (Long) -> Unit,
        onDeleteError: (RunInfo) -> Unit,
    ): RunCleanupResult {
        var deletedRuns = 0
        var deletedBytes = 0L
        var errors = 0

        for (run in runs) {
            val elapsed = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            if (elapsed > maxRuntimeSeconds * 1000) {
                log.info { "[$logPrefix] runtime limit reached: ${elapsed}ms > ${maxRuntimeSeconds * 1000}ms, stopping" }
                break
            }
            if (deletedBytes >= maxDeleteBytesPerCycle) {
                log.info { "[$logPrefix] byte limit reached: $deletedBytes >= $maxDeleteBytesPerCycle, stopping" }
                break
            }

            val bytes = deleteRun(run)
            if (bytes >= 0) {
                deletedRuns++
                deletedBytes += bytes
                onDeletedBytes(bytes)
            } else {
                errors++
                onDeleteError(run)
                log.warn { "[$logPrefix] failed to delete run: ${run.runId} (pipeline NOT affected)" }
            }
        }

        return RunCleanupResult(deletedRuns, deletedBytes, errors, throttled)
    }
}

data class RunCleanupResult(
    val runsDeleted: Int,
    val bytesDeleted: Long,
    val errors: Int,
    val throttled: Int,
) {
    companion object {
        val ZERO = RunCleanupResult(0, 0L, 0, 0)
    }
}
