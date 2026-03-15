package maple.expectation.infrastructure.metrics

import io.micrometer.core.instrument.*
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

/**
 * Event Outbox Pattern Metrics (Issue #490)
 *
 * <h3>Metrics Exposed</h3>
 *
 * <ul>
 *   <li>Gauges: pending_count, processing_count (current state)
 *   <li>Counters: processed_total, failed_total, published_total, stalled_recovered_total
 *   <li>Timer: processing_time (distribution)
 * </ul>
 *
 * @see maple.expectation.infrastructure.scheduler.EventOutboxScheduler
 * @see maple.expectation.infrastructure.event.outbox.EventOutboxProcessor
 */
@Component
class EventOutboxMetrics(private val registry: MeterRegistry) {

    private val pendingCount = AtomicLong(0)
    private val processingCount = AtomicLong(0)

    private val processedCounter: Counter
    private val failedCounter: Counter
    private val publishedCounter: Counter
    private val stalledRecoveredCounter: Counter
    private val integrityFailureCounter: Counter
    private val pollFailureCounter: Counter
    private val processingTimer: Timer

    init {
        // Gauges (current state)
        Gauge.builder("event_outbox_pending_count", pendingCount) { it.get().toDouble() }
            .description("Number of pending events in outbox")
            .register(registry)

        Gauge.builder("event_outbox_processing_count", processingCount) { it.get().toDouble() }
            .description("Number of events currently being processed")
            .register(registry)

        // Counters (cumulative)
        processedCounter = Counter.builder("event_outbox_processed_total")
            .description("Total number of processed events")
            .register(registry)

        failedCounter = Counter.builder("event_outbox_failed_total")
            .description("Total number of failed events")
            .register(registry)

        publishedCounter = Counter.builder("event_outbox_published_total")
            .description("Total number of events published to Redis Stream")
            .register(registry)

        stalledRecoveredCounter = Counter.builder("event_outbox_stalled_recovered_total")
            .description("Total number of stalled events recovered")
            .register(registry)

        integrityFailureCounter = Counter.builder("event_outbox_integrity_failure_total")
            .description("Total number of integrity verification failures")
            .register(registry)

        pollFailureCounter = Counter.builder("event_outbox_poll_failure_total")
            .description("Total number of polling failures")
            .register(registry)

        // Timer (processing time distribution)
        processingTimer = Timer.builder("event_outbox_processing_time")
            .description("Event processing time distribution")
            .register(registry)
    }

    /** Set pending count gauge */
    fun setPendingCount(count: Long) {
        pendingCount.set(count)
    }

    /** Set processing count gauge */
    fun setProcessingCount(count: Long) {
        processingCount.set(count)
    }

    /** Increment processed counter */
    fun incrementProcessed() {
        processedCounter.increment()
    }

    /** Increment processed counter by amount */
    fun incrementProcessed(amount: Int) {
        processedCounter.increment(amount.toDouble())
    }

    /** Increment failed counter */
    fun incrementFailed() {
        failedCounter.increment()
    }

    /** Increment failed counter by amount */
    fun incrementFailed(amount: Int) {
        failedCounter.increment(amount.toDouble())
    }

    /** Increment published counter */
    fun incrementPublished() {
        publishedCounter.increment()
    }

    /** Increment stalled recovered counter */
    fun incrementStalledRecovered(amount: Int) {
        stalledRecoveredCounter.increment(amount.toDouble())
    }

    /** Increment integrity failure counter */
    fun incrementIntegrityFailure() {
        integrityFailureCounter.increment()
    }

    /** Increment poll failure counter */
    fun incrementPollFailure() {
        pollFailureCounter.increment()
    }

    /** Increment completed counter (alias for processed) */
    fun incrementCompleted(amount: Int) {
        processedCounter.increment(amount.toDouble())
    }

    /** Record processing time */
    fun recordProcessingTime(task: Runnable) {
        processingTimer.record(task)
    }
}
