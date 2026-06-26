package maple.externalapi.runstatus

import java.time.Instant

/**
 * Mutable per-loop state held by PhaseLoopController. Stored under
 * AtomicReference<LoopState> for the startLoop CAS race.
 *
 * The mutable fields are annotated `@Volatile` for cross-thread visibility
 * (status written from request thread via stopLoop/shutdown; iterationCount
 * and lastRunId written from the loopExecutor thread via handleIterationEnd).
 *
 * Concurrency invariant: handleIterationEnd is single-threaded per phase
 * (iterations chain serially through loopExecutor). stopLoop/shutdown only
 * write `status`, not `iterationCount`. So `iterationCount += 1` is safe by
 * construction — the only contended write is on `status`, where the final
 * value is idempotent (STOPPING in both writers).
 */
data class LoopState(
    val loopId: String,
    val phase: PipelinePhase,
    val startedAt: Instant,
    @Volatile var status: LoopStatus = LoopStatus.RUNNING,
    @Volatile var iterationCount: Int = 0,
    @Volatile var lastRunId: String? = null,
    @Volatile var lastError: String? = null,
)
