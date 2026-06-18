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
        return if (result.runId == runId && result.startedAt == now) {
            log.info("[RunStatus] phase-slot acquired phase={} runId={}", phase, runId)
            result
        } else {
            log.warn("[RunStatus] phase-slot occupied phase={} existingRunId={}", phase, result.runId)
            null
        }
    }

    /**
     * Transition the run in [phase] slot. No-op if slot empty or runId mismatch.
     */
    fun transitionPhase(phase: PipelinePhase, runId: String? = null) {
        slots[phase]?.updateAndGet { current ->
            if (current == null) return@updateAndGet null
            if (runId != null && current.runId != runId) return@updateAndGet current
            current.copy(phase = phase, updatedAt = Instant.now(clock))
        }
        log.info("[RunStatus] phase-slot transition phase={} runId={}", phase, runId)
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
        slots[phase]?.updateAndGet { current ->
            if (current?.runId == runId) null else current
        }
        log.info("[RunStatus] phase-slot released phase={} runId={}", phase, runId)
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

    // Legacy methods retained for code paths not yet migrated (Task 3+ will replace).
    @Deprecated("Use acquirePhaseSlot(phase, runId) instead")
    fun startRun(runId: String) {
        acquirePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
    }

    @Deprecated("Use acquirePhaseSlot(ITEM_EQUIPMENT, runId) instead")
    fun startItemEquipmentCycle(runId: String) {
        acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
    }

    @Deprecated("Use completeRun(phase, runId, chunks, records) instead")
    fun completeRun(runId: String, chunksProcessed: Int, recordsProcessed: Long) {
        val phase = inferPhaseFromCurrentRun(runId) ?: PipelinePhase.ITEM_EQUIPMENT
        completeRun(phase, runId, chunksProcessed, recordsProcessed)
    }

    @Deprecated("Use failRun(phase, runId, errorMessage) instead")
    fun failRun(runId: String, errorMessage: String) {
        val phase = inferPhaseFromCurrentRun(runId) ?: PipelinePhase.RANKING_FETCH
        failRun(phase, runId, errorMessage)
    }

    private fun inferPhaseFromCurrentRun(runId: String): PipelinePhase? {
        return slots.entries.firstOrNull { it.value.get()?.runId == runId }?.key
    }
}
