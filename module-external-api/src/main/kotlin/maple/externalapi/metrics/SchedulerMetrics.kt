package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class SchedulerMetrics(private val registry: MeterRegistry) {

    private val lockTimeoutCounters = mutableMapOf<String, Counter>()
    private val lockAcquiredCounters = mutableMapOf<String, Counter>()

    fun incrementLockTimeout(phase: String) {
        lockTimeoutCounters
            .getOrPut(phase) { registry.counter("external_api_scheduler_lock_timeout_total", "phase", phase) }
            .increment()
    }

    fun incrementLockAcquired(phase: String) {
        lockAcquiredCounters
            .getOrPut(phase) { registry.counter("external_api_scheduler_lock_acquired_total", "phase", phase) }
            .increment()
    }
}
