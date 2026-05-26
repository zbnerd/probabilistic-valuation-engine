package maple.common.cleanup

import java.time.Duration
import java.time.Instant

object RunRetentionPolicy {

    fun selectForDeletion(
        runs: List<RunInfo>,
        keepRecentCount: Int,
        keepWithinHours: Long,
        now: Instant,
    ): List<RunInfo> {
        if (runs.isEmpty()) return emptyList()
        require(keepRecentCount >= 0) { "keepRecentCount must be non-negative: $keepRecentCount" }
        require(keepWithinHours >= 0) { "keepWithinHours must be non-negative: $keepWithinHours" }

        val cutoff = now.minus(Duration.ofHours(keepWithinHours))
        val recentRunIds = runs.sortedByDescending { it.createdAt }
            .take(keepRecentCount)
            .map { it.runId }
            .toSet()

        return runs.filter { run ->
            !run.isRunning && run.createdAt.isBefore(cutoff) && run.runId !in recentRunIds
        }
    }
}
