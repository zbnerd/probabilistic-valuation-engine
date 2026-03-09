package maple.expectation.infrastructure.monitoring.collector

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
    private val meterRegistry: MeterRegistry,
) : MetricsCollectorStrategy {

    private val log = LoggerFactory.getLogger(DatabaseMetricsCollector::class.java)

    // Alert thresholds (ADR-088)
    private val warningUtilizationPercent = 70
    private val criticalUtilizationPercent = 90
    private val highPendingThreadsThreshold = 5
    private val slowAcquireP99Ms = 100
    private val highTimeoutRate = 0.01 // 1%

    override fun getCategoryName(): String = MetricCategory.DATABASE.key

    override fun collect(): Map<String, Any> = buildMap {
        // Query metrics once and reuse (efficiency optimization)
        val hikariMetrics = queryHikariMetrics()

        collectBasicMetrics(this, hikariMetrics)
        collectPoolUtilization(this, hikariMetrics)
        collectWaitTimePercentiles(this, hikariMetrics)
        collectTimeoutMetrics(this, hikariMetrics)
    }

    override fun supports(category: MetricCategory): Boolean = MetricCategory.DATABASE == category

    override fun getOrder(): Int = 4

    /**
     * Data class to hold queried HikariCP metrics (avoid repeated lookups)
     */
    private data class HikariMetrics(
        val active: Gauge?,
        val idle: Gauge?,
        val max: Gauge?,
        val pending: Gauge?,
        val total: Gauge?,
        val acquireTimer: io.micrometer.core.instrument.Timer?,
        val usageTimer: io.micrometer.core.instrument.Timer?,
        val timeoutCounter: io.micrometer.core.instrument.Counter?,
        val creationCounter: io.micrometer.core.instrument.Counter?,
    )

    /**
     * Queries all HikariCP metrics once (efficiency: reduces registry lookups)
     */
    private fun queryHikariMetrics(): HikariMetrics = HikariMetrics(
        active = meterRegistry.find("hikaricp.connections.active").gauge(),
        idle = meterRegistry.find("hikaricp.connections.idle").gauge(),
        max = meterRegistry.find("hikaricp.connections.max").gauge(),
        pending = meterRegistry.find("hikaricp.connections.pending").gauge(),
        total = meterRegistry.find("hikaricp.connections").gauge(),
        acquireTimer = meterRegistry.find("hikaricp.connections.acquire").timer(),
        usageTimer = meterRegistry.find("hikaricp.connections.usage").timer(),
        timeoutCounter = meterRegistry.find("hikaricp.connections.timeout").counter(),
        creationCounter = meterRegistry.find("hikaricp.connections.creation").counter(),
    )

    /**
     * Collects basic HikariCP connection metrics
     */
    private fun collectBasicMetrics(metrics: MutableMap<String, Any>, hikari: HikariMetrics) {
        hikari.active?.let {
            metrics["connections_active"] = it.value().toInt()
        }

        hikari.idle?.let {
            metrics["connections_idle"] = it.value().toInt()
        }

        hikari.max?.let {
            metrics["connections_max"] = it.value().toInt()
        }

        hikari.pending?.let {
            metrics["connections_pending"] = it.value().toInt()
            // Alert on pending threads (sign of pool exhaustion)
            if (it.value() > highPendingThreadsThreshold) {
                log.warn("[HikariCP] High pending threads: {}", it.value())
            }
        }

        hikari.total?.let {
            metrics["connections_total"] = it.value().toInt()
        }

        hikari.acquireTimer?.let {
            metrics["acquire_mean_ms"] = MetricsCollectorUtils.formatDouble(
                it.mean(java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            metrics["acquire_max_ms"] = MetricsCollectorUtils.formatDouble(
                it.max(java.util.concurrent.TimeUnit.MILLISECONDS),
            )
        }

        hikari.usageTimer?.let {
            metrics["usage_mean_ms"] = MetricsCollectorUtils.formatDouble(
                it.mean(java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            metrics["usage_max_ms"] = MetricsCollectorUtils.formatDouble(
                it.max(java.util.concurrent.TimeUnit.MILLISECONDS),
            )
        }

        hikari.timeoutCounter?.let {
            metrics["timeout_count"] = it.count().toLong()
        }

        if (hikari.active != null && hikari.max != null && hikari.max.value() > 0) {
            val saturation = MetricsCollectorUtils.calculatePercentage(
                hikari.active.value(),
                hikari.max.value(),
            )
            metrics["saturation_percent"] = saturation
        }
    }

    /**
     * Collects pool utilization ratios for capacity planning (ADR-088)
     */
    private fun collectPoolUtilization(metrics: MutableMap<String, Any>, hikari: HikariMetrics) {
        if (hikari.active != null && hikari.max != null && hikari.max.value() > 0) {
            // Utilization ratio (0.0 - 1.0)
            val utilizationRatio = MetricsCollectorUtils.calculateRatio(
                hikari.active.value(),
                hikari.max.value(),
            )
            metrics["utilization_ratio"] = utilizationRatio

            // Utilization percentage
            val utilizationPercent = MetricsCollectorUtils.calculatePercentage(
                hikari.active.value(),
                hikari.max.value(),
            )
            metrics["utilization_percent"] = utilizationPercent

            // Log warning at 70%, error at 90% (ADR-088 thresholds)
            when {
                utilizationPercent > criticalUtilizationPercent -> log.error(
                    "[HikariCP] CRITICAL utilization: {}% ({} / {})",
                    utilizationPercent,
                    hikari.active.value().toInt(),
                    hikari.max.value().toInt(),
                )
                utilizationPercent > warningUtilizationPercent -> log.warn(
                    "[HikariCP] High utilization: {}% ({} / {})",
                    utilizationPercent,
                    hikari.active.value().toInt(),
                    hikari.max.value().toInt(),
                )
            }
        }

        if (hikari.idle != null && hikari.max != null && hikari.max.value() > 0) {
            // Idle ratio (excessive idle means pool is oversized)
            val idleRatio = MetricsCollectorUtils.calculateRatio(
                hikari.idle.value(),
                hikari.max.value(),
            )
            metrics["idle_ratio"] = idleRatio
        }
    }

    /**
     * Collects wait time percentiles for performance analysis
     */
    private fun collectWaitTimePercentiles(metrics: MutableMap<String, Any>, hikari: HikariMetrics) {
        hikari.acquireTimer?.let {
            // Percentiles for latency analysis
            metrics["acquire_p50_ms"] = MetricsCollectorUtils.formatDouble(
                it.percentile(0.5, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            metrics["acquire_p95_ms"] = MetricsCollectorUtils.formatDouble(
                it.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            metrics["acquire_p99_ms"] = MetricsCollectorUtils.formatDouble(
                it.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS),
            )

            // Warn on high P99 acquire time (sign of pool pressure)
            val p99 = it.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (p99 > slowAcquireP99Ms) {
                log.warn("[HikariCP] High P99 acquire time: {}ms", MetricsCollectorUtils.formatDouble(p99))
            }
        }

        hikari.usageTimer?.let {
            metrics["usage_p50_ms"] = MetricsCollectorUtils.formatDouble(
                it.percentile(0.5, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            metrics["usage_p95_ms"] = MetricsCollectorUtils.formatDouble(
                it.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
            metrics["usage_p99_ms"] = MetricsCollectorUtils.formatDouble(
                it.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
        }
    }

    /**
     * Collects timeout metrics for pool exhaustion detection
     */
    private fun collectTimeoutMetrics(metrics: MutableMap<String, Any>, hikari: HikariMetrics) {
        hikari.timeoutCounter?.let {
            val timeoutCount = it.count()
            metrics["timeout_total"] = timeoutCount.toLong()

            // Calculate timeout rate (per second, based on timer count if available)
            hikari.acquireTimer?.let { timer ->
                if (timer.count() > 0) {
                    val timeoutRate = timeoutCount / timer.count()
                    metrics["timeout_rate"] = MetricsCollectorUtils.formatDouble(timeoutRate)

                    // Alert on high timeout rate (> 1% timeout rate is critical)
                    if (timeoutRate > highTimeoutRate) {
                        log.error(
                            "[HikariCP] High timeout rate: {}% ({} / {})",
                            MetricsCollectorUtils.formatDouble(timeoutRate * 100),
                            timeoutCount,
                            timer.count(),
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
        hikari.creationCounter?.let {
            metrics["creation_total"] = it.count().toLong()
        }
    }
}
