package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class OrphanCleanupMetrics(
    registry: MeterRegistry,
) {
    private val outcomes: Map<OrphanCleanupResult, Counter> =
        OrphanCleanupResult.entries.associateWith { result ->
            registry.counter("external_api_orphan_cleanup_total", "result", result.tagValue)
        }
    private val scanned = registry.counter("external_api_orphan_cleanup_scanned_total")
    private val deleted = registry.counter("external_api_orphan_cleanup_deleted_total")
    private val bytesFreed = registry.counter("external_api_orphan_cleanup_bytes_freed_total")
    private val failed = registry.counter("external_api_orphan_cleanup_failed_total")

    fun record(
        result: OrphanCleanupResult,
        summary: OrphanCleanupSummary?,
    ) {
        outcomes.getValue(result).increment()
        summary?.let {
            scanned.increment(it.scanned.toDouble())
            deleted.increment(it.deleted.toDouble())
            bytesFreed.increment(it.bytesFreed.toDouble())
            failed.increment(it.failed.toDouble())
        }
    }
}

enum class OrphanCleanupResult(
    val tagValue: String,
) {
    SUCCESS("success"),
    SUBMIT_FAILED("submit_failed"),
    TIMEOUT("timeout"),
    FAILED("failed"),
}

data class OrphanCleanupSummary(
    val scanned: Long,
    val deleted: Long,
    val bytesFreed: Long,
    val failed: Long,
)
