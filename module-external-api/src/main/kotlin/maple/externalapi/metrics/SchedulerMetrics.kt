package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class SchedulerMetrics(private val registry: MeterRegistry) {

    private val lockTimeoutCounters = mutableMapOf<String, Counter>()
    private val lockAcquiredCounters = mutableMapOf<String, Counter>()

    /**
     * Per-run local counters for chunk-ready events published during the current run.
     * Drain-and-reset semantics: [recordChunkPublished] accumulates; [drainRunChunks] /
     * [drainRunRecords] return the current totals and reset to 0. The next run starts fresh.
     *
     * Distinct from the Prometheus counters ([external_api_chunks_published_total] / [external_api_records_published_total])
     * which are cumulative across the JVM lifetime.
     */
    private val runChunks = AtomicLong(0)
    private val runRecords = AtomicLong(0)

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

    fun recordChunkPublished(records: Int) {
        registry.counter("external_api_chunks_published_total").increment()
        registry.counter("external_api_records_published_total").increment(records.toDouble())
        runChunks.incrementAndGet()
        runRecords.addAndGet(records.toLong())
    }

    fun drainRunChunks(): Long = runChunks.getAndSet(0L)

    fun drainRunRecords(): Long = runRecords.getAndSet(0L)
}
