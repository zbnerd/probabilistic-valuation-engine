package maple.expectation.infrastructure.bulk

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Real-time progress logging with ETA calculation for bulk operations.
 *
 * <h3>Metrics Exposed</h3>
 * <ul>
 *   <li>Gauges: loaded_count, total_count, error_count, rate_per_second, eta_minutes
 * </ul>
 *
 * @see maple.expectation.infrastructure.batch.GameCharacterMicroBatchAdapter
 * @see maple.expectation.infrastructure.batch.L2CacheMicroBatchAdapter
 */
@Component
class ProgressLogger(private val meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(ProgressLogger::class.java)

    private val loadedCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val ratePerSecond = AtomicLong(0)
    private val etaMinutes = AtomicLong(0)

    init {
        // Gauges for current progress state
        Gauge.builder("bulk_progress_loaded_count", loadedCount) { it.get().toDouble() }
            .description("Number of items successfully loaded in bulk operation")
            .register(meterRegistry)

        Gauge.builder("bulk_progress_total_count", totalCount) { it.get().toDouble() }
            .description("Total number of items to load in bulk operation")
            .register(meterRegistry)

        Gauge.builder("bulk_progress_error_count", errorCount) { it.get().toDouble() }
            .description("Number of errors encountered in bulk operation")
            .register(meterRegistry)

        Gauge.builder("bulk_progress_rate_per_second", ratePerSecond) { it.get().toDouble() }
            .description("Current processing rate in items per second")
            .register(meterRegistry)

        Gauge.builder("bulk_progress_eta_minutes", etaMinutes) { it.get().toDouble() }
            .description("Estimated time remaining in minutes")
            .register(meterRegistry)
    }

    /**
     * Progress data class representing the current state of a bulk operation.
     *
     * @property loaded Number of items successfully loaded
     * @property total Total number of items to load
     * @property errors Number of errors encountered
     * @property ratePerSecond Current processing rate
     * @property etaMinutes Estimated time remaining in minutes
     */
    data class Progress(
        val loaded: Int,
        val total: Int,
        val errors: Int,
        val ratePerSecond: Double,
        val etaMinutes: Int,
    )

    /**
     * Log progress with formatted output and update Micrometer gauges.
     *
     * Format: "Loaded 15000/300000 (5.0%) | ETA: 45min | Errors: 12 | Rate: 100/sec"
     *
     * @param progress Current progress state
     */
    fun logProgress(progress: Progress) {
        // Update gauges
        loadedCount.set(progress.loaded.toLong())
        totalCount.set(progress.total.toLong())
        errorCount.set(progress.errors.toLong())
        ratePerSecond.set(progress.ratePerSecond.toLong())
        etaMinutes.set(progress.etaMinutes.toLong())

        // Calculate percentage
        val percentage = if (progress.total > 0) {
            (progress.loaded.toDouble() / progress.total.toDouble() * 100).let {
                String.format("%.1f", it)
            }
        } else {
            "0.0"
        }

        // Format rate (show integer if it's a whole number)
        val rateFormatted = if (progress.ratePerSecond == progress.ratePerSecond.toLong().toDouble()) {
            progress.ratePerSecond.toLong()
        } else {
            String.format("%.1f", progress.ratePerSecond)
        }

        log.info(
            "Loaded {}/{} ({}%) | ETA: {}min | Errors: {} | Rate: {}/sec",
            progress.loaded,
            progress.total,
            percentage,
            progress.etaMinutes,
            progress.errors,
            rateFormatted,
        )
    }

    /**
     * Calculate ETA (Estimated Time of Arrival) based on elapsed time and progress.
     *
     * @param loaded Number of items already loaded
     * @param total Total number of items to load
     * @param startTime Start time of the operation
     * @return ETA in minutes (rounded to nearest minute)
     */
    fun calculateEta(loaded: Int, total: Int, startTime: Instant): Int {
        if (loaded <= 0 || total <= 0 || loaded >= total) {
            return 0
        }

        val elapsedMinutes = ChronoUnit.MINUTES.between(startTime, Instant.now())
        if (elapsedMinutes <= 0) {
            return 0
        }

        val remaining = total - loaded
        val ratePerMinute = loaded.toDouble() / elapsedMinutes.toDouble()
        if (ratePerMinute <= 0) {
            return 0
        }

        val etaMinutes = (remaining / ratePerMinute).toInt().coerceAtLeast(0)
        return etaMinutes
    }

    /**
     * Calculate processing rate in items per second.
     *
     * @param loaded Number of items already loaded
     * @param startTime Start time of the operation
     * @return Rate in items per second
     */
    fun calculateRate(loaded: Int, startTime: Instant): Double {
        if (loaded <= 0) {
            return 0.0
        }

        val elapsedSeconds = ChronoUnit.SECONDS.between(startTime, Instant.now())
        if (elapsedSeconds <= 0) {
            return 0.0
        }

        return loaded.toDouble() / elapsedSeconds.toDouble()
    }
}
