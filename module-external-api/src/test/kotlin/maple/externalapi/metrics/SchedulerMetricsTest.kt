package maple.externalapi.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SchedulerMetricsTest {

    @Test
    fun `lock timeout counter increments per phase`() {
        val registry = SimpleMeterRegistry()
        val metrics = SchedulerMetrics(registry)

        metrics.incrementLockTimeout("daily_refresh")
        metrics.incrementLockTimeout("daily_refresh")
        metrics.incrementLockTimeout("item_equipment")
        metrics.incrementLockAcquired("daily_refresh")

        assertThat(registry.find("external_api_scheduler_lock_timeout_total").tag("phase", "daily_refresh").counter()?.count()).isEqualTo(2.0)
        assertThat(registry.find("external_api_scheduler_lock_timeout_total").tag("phase", "item_equipment").counter()?.count()).isEqualTo(1.0)
        assertThat(registry.find("external_api_scheduler_lock_acquired_total").tag("phase", "daily_refresh").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `run-local chunk and record counters accumulate and drain`() {
        val registry = SimpleMeterRegistry()
        val metrics = SchedulerMetrics(registry)

        metrics.recordChunkPublished(records = 500)
        metrics.recordChunkPublished(records = 500)
        metrics.recordChunkPublished(records = 250)

        assertThat(metrics.drainRunChunks()).isEqualTo(3L)
        assertThat(metrics.drainRunRecords()).isEqualTo(1250L)

        // drain resets — second drain returns 0
        assertThat(metrics.drainRunChunks()).isEqualTo(0L)
        assertThat(metrics.drainRunRecords()).isEqualTo(0L)
    }

    @Test
    fun `lifecycle counters use only closed start and stop operations`() {
        val registry = SimpleMeterRegistry()
        val metrics = SchedulerMetrics(registry)

        metrics.recordLifecycleFailure("start")
        metrics.recordLifecycleFailure("stop")
        metrics.recordForcedShutdown()

        assertThat(
            registry.find("external_api_scheduler_lifecycle_failures_total")
                .tag("operation", "start")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.find("external_api_scheduler_lifecycle_failures_total")
                .tag("operation", "stop")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.find("external_api_scheduler_forced_shutdown_total").counter()?.count(),
        ).isEqualTo(1.0)
        assertThatThrownBy { metrics.recordLifecycleFailure("dynamic-operation") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
