package maple.externalapi.loop

import jakarta.annotation.PreDestroy
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import maple.externalapi.runstatus.LoopState
import maple.externalapi.runstatus.LoopStatus
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.ExternalApiScheduler
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.stereotype.Component

private const val MILLIS_PER_SECOND = 1000L

/**
 * Owns per-phase infinite-loop state. One loop per phase; phases in
 * [loopablePhases]. Each iteration gets a fresh `runId`; the loopId
 * is shared across iterations and recorded on each `RunStatus` for
 * /run-status correlation.
 *
 * Iteration chaining:
 *   startLoop -> submitIteration -> scheduler.triggerPhase (with loopId)
 *                          -> handleIterationEnd -> submit next submitIteration
 *                          -> finalize (status=STOPPED, stopSignal.clear)
 *
 * Stop flow: /stop/loop/phase/{name} -> PhaseStopSignal.requestStop ->
 * current iteration throws PhaseStoppedException at chunk boundary ->
 * handleIterationEnd sets status=STOPPING -> finalize.
 */
@Component
class PhaseLoopController(
    private val externalApiScheduler: ExternalApiScheduler,
    private val runStatusTracker: RunStatusTracker,
    private val runIdGenerator: RunIdGenerator,
    private val stopSignal: PhaseStopSignal,
    private val loopExecutor: AsyncTaskExecutor,
    @Value("\${external-api.loop.upstream-retry-interval-seconds:30}")
    private val upstreamRetryIntervalSeconds: Int = 30,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(PhaseLoopController::class.java)

    private val loopablePhases: Set<PipelinePhase> = setOf(
        PipelinePhase.ITEM_EQUIPMENT,
        PipelinePhase.CHARACTER_BASIC,
    )

    private val loops = ConcurrentHashMap<PipelinePhase, AtomicReference<LoopState>>()

    fun startLoop(phase: PipelinePhase): LoopState {
        require(phase in loopablePhases) { "phase $phase is not loopable; allowed: $loopablePhases" }

        val ref = loops.computeIfAbsent(phase) { AtomicReference(null) }
        val newLoopId = UUID.randomUUID().toString()

        val created = ref.updateAndGet { current ->
            // Only block a fresh startLoop if a loop is truly running.
            // STOPPING/STOPPED allow a fresh loop with a new loopId — the
            // previous iteration's finalization happens out-of-band and
            // may briefly race the new iteration's acquirePhaseSlot.
            if (current != null && current.status == LoopStatus.RUNNING) {
                current
            } else {
                LoopState(
                    loopId = newLoopId,
                    phase = phase,
                    startedAt = java.time.Instant.now(clock),
                )
            }
        }

        if (created.loopId == newLoopId) {
            log.info("[Loop] startLoop phase={} loopId={}", phase, created.loopId)
            // lastRunId remains null until the first iteration completes.
            // iterationCount defaults to 0 (matches spec §5.1 LOOP_STARTED response).
            submitIteration(phase, created.loopId, runIdGenerator.newRunId(), n = 1)
        } else {
            log.info("[Loop] startLoop reused existing loop phase={} loopId={}", phase, created.loopId)
        }
        return created
    }

    fun hasActiveLoop(phase: PipelinePhase): Boolean {
        val state = loops[phase]?.get() ?: return false
        return state.status != LoopStatus.STOPPED
    }

    fun getLoopState(phase: PipelinePhase): LoopState? = loops[phase]?.get()

    fun activeLoops(): List<LoopState> =
        loops.values.mapNotNull { it.get() }.filter { it.status != LoopStatus.STOPPED }

    fun stopLoop(phase: PipelinePhase): LoopState? {
        val state = loops[phase]?.get() ?: return null
        if (state.status == LoopStatus.STOPPED) return null
        stopSignal.requestStop(phase)
        state.status = LoopStatus.STOPPING
        log.info("[Loop] stop requested phase={} loopId={} iterations={}",
            phase, state.loopId, state.iterationCount)
        return state
    }

    @PreDestroy
    fun shutdown() {
        val active = activeLoops()
        if (active.isEmpty()) {
            log.info("[Loop] shutdown: no active loops")
            return
        }
        log.info("[Loop] shutdown: stopping {} active loops", active.size)
        for (state in active) {
            stopSignal.requestStop(state.phase)
            finalize(state.phase, state.loopId)
        }
    }

    private fun submitIteration(phase: PipelinePhase, loopId: String, runId: String, n: Int) {
        val upstream = latestUpstreamRunId(phase)
        try {
            externalApiScheduler.triggerPhase(phase, runId, upstream, loopId)
                .whenComplete { _, ex -> handleIterationEnd(phase, loopId, runId, ex, n) }
        } catch (ex: Throwable) {
            // Upstream (OCID_LOOKUP) is transiently absent — the morning_chain
            // 03:00 KST run refreshes OCID_LOOKUP, leaving its slot non-terminal
            // so latestUpstreamRunId() returns null. runItemEquipmentPhase
            // rejects a null upstream synchronously (require, before slot
            // acquire), which previously killed the loop via the fatal path
            // below. Defer instead: re-submit on the loopExecutor after a short
            // backoff (virtual-thread sleep, no carrier pinning). The loop stays
            // RUNNING and resumes once OCID_LOOKUP reaches a terminal state.
            // See ADR-742.
            val state = loops[phase]?.get()
            if (upstream == null && state != null && state.loopId == loopId && state.status == LoopStatus.RUNNING) {
                log.info(
                    "[Loop] upstream not ready phase={} loopId={} iter={} deferring retry in {}s",
                    phase, loopId, n, upstreamRetryIntervalSeconds,
                )
                val delayMillis = upstreamRetryIntervalSeconds.toLong() * MILLIS_PER_SECOND
                loopExecutor.execute {
                    sleepInterruptibly(delayMillis)
                    submitIteration(phase, loopId, runId, n)
                }
                return
            }
            log.error("[Loop] iteration submit failed phase={} loopId={} iter={}", phase, loopId, n, ex)
            val current = state ?: return
            if (current.loopId != loopId) return
            current.lastError = ex.message
            current.status = LoopStatus.STOPPING
            finalize(phase, loopId)
        }
    }

    private fun sleepInterruptibly(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Both loopable phases (ITEM_EQUIPMENT, CHARACTER_BASIC) consume
     * OCID_LOOKUP's last completed runId as upstream. OCID_LOOKUP itself
     * is not loopable (no upstream source; runOcidPhase requires upstreamRunId).
     */
    private fun latestUpstreamRunId(phase: PipelinePhase): String? =
        runStatusTracker.getLastCompletedForPhase(PipelinePhase.OCID_LOOKUP)?.runId

    private fun handleIterationEnd(phase: PipelinePhase, loopId: String, runId: String, ex: Throwable?, n: Int) {
        val state = loops[phase]?.get() ?: return
        if (state.loopId != loopId) return
        // lastRunId = "most recent **completed** iteration's runId". Stable.
        // Set unconditionally on terminal — success, stop, and fail all set it.
        state.lastRunId = runId
        when {
            ex is maple.externalapi.scheduler.PhaseStoppedException -> {
                log.info("[Loop] iteration stopped phase={} loopId={} iter={}", phase, loopId, n)
                state.status = LoopStatus.STOPPING
            }
            ex != null -> {
                log.error("[Loop] iteration failed phase={} loopId={} iter={}", phase, loopId, n, ex)
                state.lastError = ex.message ?: "unknown"
                state.status = LoopStatus.STOPPING
            }
            else -> {
                log.info("[Loop] iteration done phase={} loopId={} iter={}", phase, loopId, n)
            }
        }
        // Count this iteration as completed (terminal or successful). Submitted-but-not-finished
        // iterations do not count. Spec §5.1 LOOP_STARTED response shows iterationCount:0.
        state.iterationCount += 1
        if (state.status == LoopStatus.STOPPING) {
            finalize(phase, loopId)
        } else {
            val nextN = n + 1
            val nextRunId = runIdGenerator.newRunId()
            // lastRunId is NOT updated to nextRunId here. It reflects the most
            // recent *completed* iteration; the next iteration's runId will
            // be set when it completes (or fails/stops).
            loopExecutor.execute { submitIteration(phase, loopId, nextRunId, nextN) }
        }
    }

    private fun finalize(phase: PipelinePhase, loopId: String) {
        val state = loops[phase]?.get() ?: return
        if (state.loopId != loopId) return
        state.status = LoopStatus.STOPPED
        stopSignal.clear(phase)
        log.info("[Loop] stopped loopId={} phase={} iterations={} lastError={}",
            loopId, phase, state.iterationCount, state.lastError ?: "none")
    }
}
