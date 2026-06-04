package maple.externalapi.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
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
}
