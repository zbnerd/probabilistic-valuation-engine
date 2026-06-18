package maple.externalapi.scheduler

import maple.externalapi.runstatus.PipelinePhase
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-phase stop flag map. `requestStop` returns the previous state (true on first
 * call, false on idempotent repeat) so callers can tell whether their request was
 * the one that tripped the flag. `clear` is unconditional reset.
 */
@Component
class PhaseStopSignal {

    private val flags = ConcurrentHashMap<PipelinePhase, AtomicBoolean>()

    fun requestStop(phase: PipelinePhase): Boolean {
        val flag = flags.computeIfAbsent(phase) { AtomicBoolean(false) }
        return flag.compareAndSet(false, true)
    }

    fun isStopRequested(phase: PipelinePhase): Boolean =
        flags[phase]?.get() == true

    fun clear(phase: PipelinePhase) {
        flags[phase]?.set(false)
    }
}
