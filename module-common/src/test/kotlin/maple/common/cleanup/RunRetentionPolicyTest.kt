package maple.common.cleanup

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunRetentionPolicyTest {

    @Test
    fun `should delete run when not active, not recent 5, and older than 48h`() {
        val now = Instant.now()
        val runs = (0..9).map { i ->
            RunInfo(
                runId = "run-$i",
                createdAt = now.minus(49, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1024L,
            )
        }
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runs,
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete.map { it.runId }).containsExactly(
            "run-0",
            "run-1",
            "run-2",
            "run-3",
            "run-4",
        )
    }

    @Test
    fun `should keep active run even if not recent 5 and older than 48h`() {
        val now = Instant.now()
        val oldActive = RunInfo(
            runId = "active-old",
            createdAt = now.minus(72, ChronoUnit.HOURS),
            isRunning = true,
            sizeBytes = 1024L,
        )
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = listOf(oldActive),
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete).isEmpty()
    }

    @Test
    fun `should keep run within 48h even if not recent 5`() {
        val now = Instant.now()
        val recentButOld = RunInfo(
            runId = "recent-24h",
            createdAt = now.minus(24, ChronoUnit.HOURS),
            isRunning = false,
            sizeBytes = 1024L,
        )
        val newer = (0..5).map { i ->
            RunInfo(
                runId = "newer-$i",
                createdAt = now.minus(1, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1024L,
            )
        }
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = newer + recentButOld,
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete.map { it.runId }).doesNotContain("recent-24h")
    }

    @Test
    fun `should return empty when no runs`() {
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = emptyList(),
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = Instant.now(),
        )
        assertThat(toDelete).isEmpty()
    }

    @Test
    fun `should handle exactly keepRecentCount runs`() {
        val now = Instant.now()
        val runs = (0..4).map { i ->
            RunInfo(
                runId = "run-$i",
                createdAt = now.minus(49, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1024L,
            )
        }
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runs,
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete).isEmpty()
    }
}
