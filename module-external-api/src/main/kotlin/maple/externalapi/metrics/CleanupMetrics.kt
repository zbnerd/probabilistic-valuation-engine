package maple.externalapi.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class CleanupMetrics(registry: MeterRegistry) {

    private val deletedRuns = registry.counter("artifact_cleanup_deleted_runs_total", "module", "external-api")
    private val deletedBytes = registry.counter("artifact_cleanup_deleted_bytes_total", "module", "external-api")
    private val errors = registry.counter("artifact_cleanup_errors_total", "module", "external-api")
    private val skippedActive = registry.counter("artifact_cleanup_skipped_active_runs_total", "module", "external-api")
    private val throttledRuns = registry.counter("artifact_cleanup_throttled_runs_total", "module", "external-api")

    private val durationTimer = Timer.builder("artifact_cleanup_duration_seconds")
        .description("Time spent on cleanup cycle")
        .tag("module", "external-api")
        .register(registry)

    @Volatile private var storageUsedBytes = 0L

    init {
        Gauge.builder("artifact_storage_used_bytes") { storageUsedBytes }
            .description("Current storage usage in bytes for external-api artifacts")
            .tag("module", "external-api")
            .register(registry)
    }

    fun recordDeletedRuns(count: Int) = deletedRuns.increment(count.toDouble())
    fun recordDeletedBytes(bytes: Long) = deletedBytes.increment(bytes.toDouble())
    fun recordError() = errors.increment()
    fun recordSkippedActive() = skippedActive.increment()
    fun recordThrottled(count: Int) = throttledRuns.increment(count.toDouble())
    fun timer(): Timer = durationTimer
    fun updateStorageUsed(bytes: Long) {
        storageUsedBytes = bytes
    }
}
