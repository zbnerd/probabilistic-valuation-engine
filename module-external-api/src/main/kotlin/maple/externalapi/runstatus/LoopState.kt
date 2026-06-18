package maple.externalapi.runstatus

import java.time.Instant

/**
 * Mutable per-loop state held by PhaseLoopController. Stored under
 * AtomicReference<LoopState> for lock-free updates from iteration
 * whenComplete callbacks.
 *
 * iterationCount and lastRunId reflect the most recent completed iteration
 * (see PhaseLoopController.handleIterationEnd); they are advisory, not
 * transactional.
 */
data class LoopState(
    val loopId: String,
    val phase: PipelinePhase,
    val startedAt: Instant,
    var status: LoopStatus = LoopStatus.RUNNING,
    var iterationCount: Int = 0,
    var lastRunId: String? = null,
    var lastError: String? = null,
)
