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

        val sortedByNewest = runs.sortedByDescending { it.createdAt }
        val recentRunIds = sortedByNewest.take(keepRecentCount).map { it.runId }.toSet()
        val cutoff = now.minus(Duration.ofHours(keepWithinHours))

        return runs.filter { run ->
            !run.isRunning
                && run.runId !in recentRunIds
                && run.createdAt.isBefore(cutoff)
        }
    }
}
