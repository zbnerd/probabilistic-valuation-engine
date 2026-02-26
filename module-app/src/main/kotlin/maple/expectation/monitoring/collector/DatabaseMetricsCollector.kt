package maple.expectation.monitoring.collector

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import kotlin.math.round

@Component
class DatabaseMetricsCollector(
    private val meterRegistry: MeterRegistry
) : MetricsCollectorStrategy {

  override fun getCategoryName(): String = MetricCategory.DATABASE.key

  override fun collect(): Map<String, Any> = buildMap {
    collectHikariMetrics(this)
  }

  override fun supports(category: MetricCategory): Boolean =
      MetricCategory.DATABASE == category

  override fun getOrder(): Int = 4

  private fun collectHikariMetrics(metrics: MutableMap<String, Any>) {
    val active = meterRegistry.find("hikaricp.connections.active").gauge()
    active?.let {
      metrics["connections_active"] = it.value().toInt()
    }

    val idle = meterRegistry.find("hikaricp.connections.idle").gauge()
    idle?.let {
      metrics["connections_idle"] = it.value().toInt()
    }

    val max = meterRegistry.find("hikaricp.connections.max").gauge()
    max?.let {
      metrics["connections_max"] = it.value().toInt()
    }

    val pending = meterRegistry.find("hikaricp.connections.pending").gauge()
    pending?.let {
      metrics["connections_pending"] = it.value().toInt()
    }

    val acquireTimer = meterRegistry.find("hikaricp.connections.acquire").timer()
    acquireTimer?.let {
      metrics["acquire_mean_ms"] = formatDouble(it.mean(java.util.concurrent.TimeUnit.MILLISECONDS))
      metrics["acquire_max_ms"] = formatDouble(it.max(java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    val usageTimer = meterRegistry.find("hikaricp.connections.usage").timer()
    usageTimer?.let {
      metrics["usage_mean_ms"] = formatDouble(it.mean(java.util.concurrent.TimeUnit.MILLISECONDS))
      metrics["usage_max_ms"] = formatDouble(it.max(java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    val timeoutCounter = meterRegistry.find("hikaricp.connections.timeout").counter()
    timeoutCounter?.let {
      metrics["timeout_count"] = it.count().toLong()
    }

    if (active != null && max != null && max.value() > 0) {
      val saturation = (active.value() / max.value()) * 100
      metrics["saturation_percent"] = formatDouble(saturation)
    }
  }

  private fun formatDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
      return 0.0
    }
    return round(value * 100.0) / 100.0
  }
}
