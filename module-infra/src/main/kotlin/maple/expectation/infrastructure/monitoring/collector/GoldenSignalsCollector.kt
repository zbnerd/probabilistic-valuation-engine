package maple.expectation.infrastructure.monitoring.collector

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.math.round

@Component
class GoldenSignalsCollector(
    private val meterRegistry: MeterRegistry
) : MetricsCollectorStrategy {

  override fun getCategoryName(): String = MetricCategory.GOLDEN_SIGNALS.key

  override fun collect(): Map<String, Any> = buildMap {
    collectLatencyMetrics(this)
    collectTrafficMetrics(this)
    collectErrorMetrics(this)
    collectSaturationMetrics(this)
  }

  override fun supports(category: MetricCategory): Boolean =
      MetricCategory.GOLDEN_SIGNALS == category

  override fun getOrder(): Int = 1

  private fun collectLatencyMetrics(metrics: MutableMap<String, Any>) {
    val httpTimer = meterRegistry.find("http.server.requests").timer()

    if (httpTimer != null) {
      metrics["latency_p50_ms"] = formatDouble(httpTimer.percentile(0.5, TimeUnit.MILLISECONDS))
      metrics["latency_p95_ms"] = formatDouble(httpTimer.percentile(0.95, TimeUnit.MILLISECONDS))
      metrics["latency_p99_ms"] = formatDouble(httpTimer.percentile(0.99, TimeUnit.MILLISECONDS))
      metrics["latency_mean_ms"] = formatDouble(httpTimer.mean(TimeUnit.MILLISECONDS))
      metrics["latency_max_ms"] = formatDouble(httpTimer.max(TimeUnit.MILLISECONDS))
    } else {
      metrics["latency_status"] = "NO_DATA"
    }
  }

  private fun collectTrafficMetrics(metrics: MutableMap<String, Any>) {
    val requestCounter = meterRegistry.find("http.server.requests").counter()
    requestCounter?.let {
      metrics["total_requests"] = it.count().toLong()
    }

    val nexonTimer = meterRegistry.find("nexon.api.performance").timer()
    nexonTimer?.let {
      metrics["nexon_api_calls"] = it.count()
      metrics["nexon_api_mean_ms"] = formatDouble(it.mean(TimeUnit.MILLISECONDS))
    }
  }

  private fun collectErrorMetrics(metrics: MutableMap<String, Any>) {
    val errorCounter = meterRegistry.find("http.server.requests")
        .tag("status", "5xx")
        .counter()

    val totalCounter = meterRegistry.find("http.server.requests").counter()

    if (errorCounter != null && totalCounter != null && totalCounter.count() > 0) {
      val errorRate = (errorCounter.count() / totalCounter.count()) * 100
      metrics["error_rate_percent"] = formatDouble(errorRate)
      metrics["error_count_5xx"] = errorCounter.count().toLong()
    } else {
      metrics["error_rate_percent"] = 0.0
    }
  }

  private fun collectSaturationMetrics(metrics: MutableMap<String, Any>) {
    val activeConnections = meterRegistry.find("hikaricp.connections.active").gauge()
    val maxConnections = meterRegistry.find("hikaricp.connections.max").gauge()

    if (activeConnections != null && maxConnections != null && maxConnections.value() > 0) {
      val saturation = (activeConnections.value() / maxConnections.value()) * 100
      metrics["db_pool_saturation_percent"] = formatDouble(saturation)
      metrics["db_active_connections"] = activeConnections.value().toInt()
      metrics["db_max_connections"] = maxConnections.value().toInt()
    }

    val bufferPending = meterRegistry.find("buffer.pending").gauge()
    bufferPending?.let {
      metrics["buffer_pending_count"] = it.value().toLong()
    }
  }

  private fun formatDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
      return 0.0
    }
    return round(value * 100.0) / 100.0
  }
}
