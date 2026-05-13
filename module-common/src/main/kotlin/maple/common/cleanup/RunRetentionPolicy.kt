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

        val cutoff = now.minus(Duration.ofHours(keepWithinHours))

        return runs.filter { run ->
            !run.isRunning && run.createdAt.isBefore(cutoff)
        }
    }
}
