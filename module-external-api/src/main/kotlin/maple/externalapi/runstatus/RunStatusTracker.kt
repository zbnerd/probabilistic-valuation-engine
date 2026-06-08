package maple.externalapi.runstatus

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RunStatusTracker(
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val currentRun = AtomicReference<RunStatus>(null)
    private val lastCompletedRun = AtomicReference<RunStatus>(null)

    fun startRun(runId: String) {
        val status = RunStatus(
            runId = runId,
            phase = PipelinePhase.RANKING_FETCH,
            startedAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
        )
        currentRun.set(status)
        log.info("[RunStatus] started run={}", runId)
    }

    fun transitionPhase(phase: PipelinePhase) {
        currentRun.updateAndGet { current ->
            current?.copy(phase = phase, updatedAt = Instant.now(clock))
        }
        log.info("[RunStatus] phase={}", phase)
    }

    fun completeRun(runId: String, chunksProcessed: Int, recordsProcessed: Long) {
        val now = Instant.now(clock)
        val completed = currentRun.updateAndGet { current ->
            if (current?.runId != runId) return@updateAndGet current
            current.copy(
                phase = PipelinePhase.COMPLETED,
                updatedAt = now,
                completedAt = now,
                chunksProcessed = chunksProcessed,
                recordsProcessed = recordsProcessed,
            )
        }
        lastCompletedRun.set(completed)
        log.info("[RunStatus] completed run={} chunks={} records={}", completed?.runId, chunksProcessed, recordsProcessed)
    }

    fun failRun(runId: String, errorMessage: String) {
        val now = Instant.now(clock)
        val failed = currentRun.updateAndGet { current ->
            if (current?.runId != runId) return@updateAndGet current
            current.copy(
                phase = PipelinePhase.FAILED,
                updatedAt = now,
                completedAt = now,
                errorMessage = errorMessage,
            )
        }
        lastCompletedRun.set(failed)
        log.error("[RunStatus] failed run={}: {}", failed?.runId, errorMessage)
    }

    fun getCurrentStatus(): RunStatus? = currentRun.get()

    fun getLastCompletedRun(): RunStatus? = lastCompletedRun.get()
}
