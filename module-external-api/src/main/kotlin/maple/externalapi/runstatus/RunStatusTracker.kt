package maple.externalapi.runstatus

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RunStatusTracker(
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val slots = ConcurrentHashMap<PipelinePhase, AtomicReference<RunStatus>>()

    fun getPhaseStatus(phase: PipelinePhase): RunStatus? = slots[phase]?.get()

    fun hasNonTerminalRun(phase: PipelinePhase): RunStatus? {
        val slot = slots[phase]?.get() ?: return null
        return if (slot.isTerminal) null else slot
    }

    /**
     * Atomic acquire. Returns the new RunStatus if acquired (slot was null OR terminal);
     * returns null if slot occupied by a non-terminal run.
     * Used by ExternalApiScheduler.triggerPhase and the /api/internal/trigger/phase controller.
     */
    fun acquirePhaseSlot(phase: PipelinePhase, runId: String): RunStatus? {
        val slot = slots.computeIfAbsent(phase) { AtomicReference(null) }
        val now = Instant.now(clock)
        val candidate = RunStatus(
            runId = runId,
            phase = phase,
            triggeredPhase = phase,
            startedAt = now,
            updatedAt = now,
        )
        val result = slot.updateAndGet { current ->
            if (current == null || current.isTerminal) candidate else current
        }
        return if (result.runId == runId) {
            log.info("[RunStatus] phase-slot acquired phase={} runId={}", phase, runId)
            result
        } else {
            log.warn("[RunStatus] phase-slot occupied phase={} existingRunId={}", phase, result.runId)
            null
        }
    }

    /**
     * Transition the run in [phase] slot to a new phase. When [runId] is null,
     * the slot's current runId is trusted (legacy single-slot pattern — used
     * by code paths that haven't migrated to per-phase slots yet). When [runId]
     * is provided, the transition is a no-op if the slot's current runId differs.
     * No-op entirely if the slot is empty.
     */
    fun transitionPhase(phase: PipelinePhase, runId: String? = null) {
        val updated = slots[phase]?.updateAndGet { current ->
            if (current == null) return@updateAndGet null
            if (runId != null && current.runId != runId) return@updateAndGet current
            current.copy(phase = phase, updatedAt = Instant.now(clock))
        }
        if (updated != null && updated.phase == phase) {
            log.info("[RunStatus] phase-slot transition phase={} runId={}", phase, updated.runId)
        }
    }

    /**
     * Mark phase slot's run as COMPLETED with chunks/records. Slot record persists
     * (NOT cleared). Next acquire on the same phase will overwrite the terminal record.
     */
    fun completeRun(phase: PipelinePhase, runId: String, chunksProcessed: Int, recordsProcessed: Long) {
        val now = Instant.now(clock)
        slots[phase]?.updateAndGet { current ->
            if (current == null || current.runId != runId) return@updateAndGet current
            current.copy(
                phase = PipelinePhase.COMPLETED,
                updatedAt = now,
                completedAt = now,
                chunksProcessed = chunksProcessed,
                recordsProcessed = recordsProcessed,
            )
        }
        log.info("[RunStatus] phase-slot completed phase={} runId={} chunks={} records={}",
            phase, runId, chunksProcessed, recordsProcessed)
    }

    /**
     * Mark phase slot's run as FAILED with errorMessage. Caller should follow with
     * [releasePhaseSlot] to clear the slot for re-acquire.
     */
    fun failRun(phase: PipelinePhase, runId: String, errorMessage: String) {
        val now = Instant.now(clock)
        slots[phase]?.updateAndGet { current ->
            if (current == null || current.runId != runId) return@updateAndGet current
            current.copy(
                phase = PipelinePhase.FAILED,
                updatedAt = now,
                completedAt = now,
                errorMessage = errorMessage,
            )
        }
        log.error("[RunStatus] phase-slot failed phase={} runId={} error={}", phase, runId, errorMessage)
    }

    /**
     * Clear slot if runId matches. Idempotent. Call only on FAILED (or operator override).
     * Successful runs keep their COMPLETED record in the slot until next acquire.
     */
    fun releasePhaseSlot(phase: PipelinePhase, runId: String) {
        val prev = slots[phase]?.get()
        val updated = slots[phase]?.updateAndGet { current ->
            if (current?.runId == runId) null else current
        }
        if (prev != null && updated == null) {
            log.info("[RunStatus] phase-slot released phase={} runId={}", phase, runId)
        }
    }

    /**
     * Legacy single-slot compatibility. Returns the most-recently-started non-terminal
     * run across all phases, or null. Used by /run-status API consumers that haven't
     * migrated to the slot-based shape.
     */
    fun getCurrentStatus(): RunStatus? {
        return slots.values
            .mapNotNull { it.get() }
            .filterNot { it.isTerminal }
            .maxByOrNull { it.startedAt }
    }

    fun getLastCompletedRun(): RunStatus? {
        return slots.values
            .mapNotNull { it.get() }
            .filter { it.isTerminal }
            .maxByOrNull { it.completedAt ?: it.startedAt }
    }

    /**
     * Return the most recent terminal RunStatus for [phase] (across cycles/runs).
     * Looks at the slot's current value if terminal; null if slot is empty or non-terminal.
     * Used by /run-status API to surface per-phase completion history.
     */
    fun getLastCompletedForPhase(phase: PipelinePhase): RunStatus? {
        val slot = slots[phase]?.get() ?: return null
        return if (slot.isTerminal) slot else null
    }
}
