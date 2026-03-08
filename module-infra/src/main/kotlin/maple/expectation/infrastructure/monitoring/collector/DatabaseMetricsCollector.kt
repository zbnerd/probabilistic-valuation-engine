package maple.expectation.infrastructure.monitoring.collector

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.round

/**
 * HikariCP Database Connection Pool Metrics Collector
 *
 * <p>Collects comprehensive metrics for monitoring HikariCP connection pool health
 * in a virtual thread environment (ADR-048, ADR-088).
 *
 * <h4>Key Metrics Collected:</h4>
 * <ul>
 *   <li><b>Pool Utilization</b>: active/max ratio, idle count, pending threads</li>
 *   <li><b>Wait Times</b>: acquire time (mean, max), usage time (mean, max)</li>
 *   <li><b>Timeout Tracking</b>: total timeouts, timeout rate</li>
 *   <li><b>Connection Lifecycle</b>: total connections, creation rate</li>
 * </ul>
 *
 * <h4>Virtual Thread Considerations (ADR-088):</h4>
 * <p>Virtual threads increase request concurrency but NOT database connection capacity.
 * Pool size should be based on:
 * <pre>
 * L = λ × W × buffer
 * Where: L = pool size, λ = request rate, W = query latency, buffer = 1.5-2x
 * </pre>
 *
 * <h4>Alert Thresholds:</h4>
 * <ul>
 *   <li><b>Warning</b>: 70% utilization (hikaricp.connections.active / hikaricp.connections.max)</li>
 *   <li><b>Critical</b>: 90% utilization</li>
 *   <li><b>Timeout Alert</b>: rate(hikaricp.connections.timeout) > 0.1/sec</li>
 * </ul>
 *
 * @see ADR-088 HikariCP tuning for virtual threads
 * @see ADR-048 Java 21 Virtual Threads adoption
 */
@Component
class DatabaseMetricsCollector(
  private val meterRegistry: MeterRegistry
) : MetricsCollectorStrategy {

  private val log = LoggerFactory.getLogger(DatabaseMetricsCollector::class.java)

  override fun getCategoryName(): String = MetricCategory.DATABASE.key

  override fun collect(): Map<String, Any> = buildMap {
    collectHikariMetrics(this)
    collectHikariPoolUtilization(this)
    collectHikariWaitTimePercentiles(this)
    collectHikariTimeoutMetrics(this)
  }

  override fun supports(category: MetricCategory): Boolean =
      MetricCategory.DATABASE == category

  override fun getOrder(): Int = 4

  /**
   * Collects basic HikariCP connection metrics
   */
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
      // Alert on pending threads (sign of pool exhaustion)
      if (it.value() > 5) {
        log.warn("[HikariCP] High pending threads: {}", it.value())
      }
    }

    val total = meterRegistry.find("hikaricp.connections").gauge()
    total?.let {
      metrics["connections_total"] = it.value().toInt()
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

  /**
   * Collects pool utilization ratios for capacity planning (ADR-088)
   */
  private fun collectHikariPoolUtilization(metrics: MutableMap<String, Any>) {
    val active = meterRegistry.find("hikaricp.connections.active").gauge()
    val max = meterRegistry.find("hikaricp.connections.max").gauge()
    val idle = meterRegistry.find("hikaricp.connections.idle").gauge()

    if (active != null && max != null && max.value() > 0) {
      // Utilization ratio (0.0 - 1.0)
      val utilizationRatio = active.value() / max.value()
      metrics["utilization_ratio"] = formatDouble(utilizationRatio)

      // Utilization percentage
      metrics["utilization_percent"] = formatDouble(utilizationRatio * 100)

      // Log warning at 70%, error at 90% (ADR-088 thresholds)
      when {
        utilizationRatio > 0.9 -> log.error(
          "[HikariCP] CRITICAL utilization: {}% ({} / {})",
          formatDouble(utilizationRatio * 100),
          active.value().toInt(),
          max.value().toInt()
        )
        utilizationRatio > 0.7 -> log.warn(
          "[HikariCP] High utilization: {}% ({} / {})",
          formatDouble(utilizationRatio * 100),
          active.value().toInt(),
          max.value().toInt()
        )
      }
    }

    if (idle != null && max != null && max.value() > 0) {
      // Idle ratio (excessive idle means pool is oversized)
      val idleRatio = idle.value() / max.value()
      metrics["idle_ratio"] = formatDouble(idleRatio)
    }
  }

  /**
   * Collects wait time percentiles for performance analysis
   */
  private fun collectHikariWaitTimePercentiles(metrics: MutableMap<String, Any>) {
    val acquireTimer = meterRegistry.find("hikaricp.connections.acquire").timer()
    acquireTimer?.let {
      // Percentiles for latency analysis
      metrics["acquire_p50_ms"] = formatDouble(it.percentile(0.5, java.util.concurrent.TimeUnit.MILLISECONDS))
      metrics["acquire_p95_ms"] = formatDouble(it.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS))
      metrics["acquire_p99_ms"] = formatDouble(it.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS))

      // Warn on high P99 acquire time (sign of pool pressure)
      val p99 = it.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS)
      if (p99 > 100) {  // > 100ms is concerning
        log.warn("[HikariCP] High P99 acquire time: {}ms", formatDouble(p99))
      }
    }

    val usageTimer = meterRegistry.find("hikaricp.connections.usage").timer()
    usageTimer?.let {
      metrics["usage_p50_ms"] = formatDouble(it.percentile(0.5, java.util.concurrent.TimeUnit.MILLISECONDS))
      metrics["usage_p95_ms"] = formatDouble(it.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS))
      metrics["usage_p99_ms"] = formatDouble(it.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS))
    }
  }

  /**
   * Collects timeout metrics for pool exhaustion detection
   */
  private fun collectHikariTimeoutMetrics(metrics: MutableMap<String, Any>) {
    val timeoutCounter = meterRegistry.find("hikaricp.connections.timeout").counter()
    timeoutCounter?.let {
      val timeoutCount = it.count()
      metrics["timeout_total"] = timeoutCount.toLong()

      // Calculate timeout rate (per second, based on timer count if available)
      val acquireTimer = meterRegistry.find("hikaricp.connections.acquire").timer()
      acquireTimer?.let { timer ->
        if (timer.count() > 0) {
          val timeoutRate = timeoutCount / timer.count()
          metrics["timeout_rate"] = formatDouble(timeoutRate)

          // Alert on high timeout rate (> 1% timeout rate is critical)
          if (timeoutRate > 0.01) {
            log.error(
              "[HikariCP] High timeout rate: {}% ({} / {})",
              formatDouble(timeoutRate * 100),
              timeoutCount,
              timer.count()
            )
          }
        }
      }

      // Alert on any timeouts
      if (timeoutCount > 0) {
        log.warn("[HikariCP] Connection timeouts detected: {}", timeoutCount)
      }
    }

    // Connection creation metrics (detect pool sizing issues)
    val creationCounter = meterRegistry.find("hikaricp.connections.creation").counter()
    creationCounter?.let {
      metrics["creation_total"] = it.count().toLong()
    }
  }

  private fun formatDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
      return 0.0
    }
    return round(value * 100.0) / 100.0
  }
}
