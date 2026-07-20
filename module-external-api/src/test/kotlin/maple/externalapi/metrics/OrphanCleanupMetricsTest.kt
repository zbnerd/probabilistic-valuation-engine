package maple.externalapi.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrphanCleanupMetricsTest {
    @Test
    fun `records closed outcomes and successful cleanup counts`() {
        val registry = SimpleMeterRegistry()
        val metrics = OrphanCleanupMetrics(registry)
        val summary = OrphanCleanupSummary(
            scanned = 3,
            deleted = 2,
            bytesFreed = 1_024,
            failed = 0,
        )

        metrics.record(OrphanCleanupResult.SUCCESS, summary)
        metrics.record(OrphanCleanupResult.SUBMIT_FAILED, null)
        metrics.record(OrphanCleanupResult.TIMEOUT, null)
        metrics.record(OrphanCleanupResult.FAILED, null)

        assertThat(outcomeCount(registry, "success")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "submit_failed")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "timeout")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "failed")).isEqualTo(1.0)
        assertThat(registry.find("external_api_orphan_cleanup_scanned_total").counter()?.count())
            .isEqualTo(3.0)
        assertThat(registry.find("external_api_orphan_cleanup_deleted_total").counter()?.count())
            .isEqualTo(2.0)
        assertThat(registry.find("external_api_orphan_cleanup_bytes_freed_total").counter()?.count())
            .isEqualTo(1_024.0)
        assertThat(registry.find("external_api_orphan_cleanup_failed_total").counter()?.count())
            .isEqualTo(0.0)
        assertThat(
            registry.find("external_api_orphan_cleanup_total").meters()
                .flatMap { meter -> meter.id.tags }
                .filter { tag -> tag.key == "result" }
                .map { tag -> tag.value }
                .toSet(),
        ).containsExactlyInAnyOrder("success", "submit_failed", "timeout", "failed")
    }

    private fun outcomeCount(registry: SimpleMeterRegistry, result: String): Double =
        registry.find("external_api_orphan_cleanup_total")
            .tag("result", result)
            .counter()
            ?.count()
            ?: 0.0
}
