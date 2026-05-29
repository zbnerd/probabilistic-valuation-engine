package maple.externalapi.runstatus

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Component
class RunStatusTracker {

    private val log = LoggerFactory.getLogger(javaClass)
    private val currentRun = AtomicReference<RunStatus>(null)
    private val lastCompletedRun = AtomicReference<RunStatus>(null)

    fun startRun(runId: String) {
        val status = RunStatus(
            runId = runId,
            phase = PipelinePhase.RANKING_FETCH,
            startedAt = Instant.now(),
        )
        currentRun.set(status)
        log.info("[RunStatus] started run={}", runId)
    }

    fun transitionPhase(phase: PipelinePhase) {
        currentRun.updateAndGet { current ->
            current?.copy(phase = phase, updatedAt = Instant.now())
        }
        log.info("[RunStatus] phase={}", phase)
    }

    fun completeRun(chunksProcessed: Int, recordsProcessed: Long) {
        val now = Instant.now()
        currentRun.updateAndGet { current ->
            current?.copy(
                phase = PipelinePhase.COMPLETED,
                updatedAt = now,
                completedAt = now,
                chunksProcessed = chunksProcessed,
                recordsProcessed = recordsProcessed,
            )
        }
        lastCompletedRun.set(currentRun.get())
        log.info("[RunStatus] completed chunks={} records={}", chunksProcessed, recordsProcessed)
    }

    fun failRun(errorMessage: String) {
        val now = Instant.now()
        currentRun.updateAndGet { current ->
            current?.copy(
                phase = PipelinePhase.FAILED,
                updatedAt = now,
                completedAt = now,
                errorMessage = errorMessage,
            )
        }
        lastCompletedRun.set(currentRun.get())
        log.error("[RunStatus] failed: {}", errorMessage)
    }

    fun getCurrentStatus(): RunStatus? = currentRun.get()

    fun getLastCompletedRun(): RunStatus? = lastCompletedRun.get()
}
