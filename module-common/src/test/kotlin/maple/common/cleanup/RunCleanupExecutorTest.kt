package maple.common.cleanup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class RunCleanupExecutorTest {

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val executor = RunCleanupExecutor("TestCleanup")

    @Test
    fun `dry run reports candidates without deleting`() {
        val runs = oldRuns(3)
        val dryRunCandidates = mutableListOf<String>()
        var deleteCalls = 0

        val result = executor.cleanup(
            runs = runs,
            dryRun = true,
            keepRecent = 0,
            keepWithinHours = 48,
            maxDeleteRunsPerCycle = 10,
            maxDeleteBytesPerCycle = Long.MAX_VALUE,
            maxRuntimeSeconds = 60,
            startedAt = now,
            now = now,
            deleteRun = {
                deleteCalls++
                it.sizeBytes
            },
            onDryRunCandidate = { dryRunCandidates.add(it.runId) },
        )

        assertThat(result.runsDeleted).isEqualTo(3)
        assertThat(result.bytesDeleted).isEqualTo(3_000L)
        assertThat(deleteCalls).isZero()
        assertThat(dryRunCandidates).containsExactly("run-0", "run-1", "run-2")
    }

    @Test
    fun `delete run limit throttles candidates`() {
        val deleted = mutableListOf<String>()
        var throttled = 0

        val result = executor.cleanup(
            runs = oldRuns(5),
            dryRun = false,
            keepRecent = 0,
            keepWithinHours = 48,
            maxDeleteRunsPerCycle = 2,
            maxDeleteBytesPerCycle = Long.MAX_VALUE,
            maxRuntimeSeconds = 60,
            startedAt = Instant.now(),
            now = now,
            deleteRun = {
                deleted.add(it.runId)
                it.sizeBytes
            },
            onThrottled = { throttled = it },
        )

        assertThat(result.runsDeleted).isEqualTo(2)
        assertThat(result.throttled).isEqualTo(3)
        assertThat(throttled).isEqualTo(3)
        assertThat(deleted).containsExactly("run-0", "run-1")
    }

    @Test
    fun `delete errors are counted and do not stop remaining runs`() {
        val errors = mutableListOf<String>()

        val result = executor.cleanup(
            runs = oldRuns(3),
            dryRun = false,
            keepRecent = 0,
            keepWithinHours = 48,
            maxDeleteRunsPerCycle = 10,
            maxDeleteBytesPerCycle = Long.MAX_VALUE,
            maxRuntimeSeconds = 60,
            startedAt = Instant.now(),
            now = now,
            deleteRun = { if (it.runId == "run-1") -1L else it.sizeBytes },
            onDeleteError = { errors.add(it.runId) },
        )

        assertThat(result.runsDeleted).isEqualTo(2)
        assertThat(result.bytesDeleted).isEqualTo(2_000L)
        assertThat(result.errors).isEqualTo(1)
        assertThat(errors).containsExactly("run-1")
    }

    private fun oldRuns(count: Int): List<RunInfo> =
        (0 until count).map { i ->
            RunInfo(
                runId = "run-$i",
                createdAt = now.minus(72, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1_000L,
            )
        }
}
