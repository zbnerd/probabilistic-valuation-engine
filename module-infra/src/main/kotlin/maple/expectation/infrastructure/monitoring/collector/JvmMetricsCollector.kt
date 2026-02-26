package maple.expectation.infrastructure.monitoring.collector

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import kotlin.math.round

@Component
class JvmMetricsCollector(
    private val meterRegistry: MeterRegistry
) : MetricsCollectorStrategy {

  override fun getCategoryName(): String = MetricCategory.JVM.key

  override fun collect(): Map<String, Any> = buildMap {
    collectHeapMetrics(this)
    collectGcMetrics(this)
    collectThreadMetrics(this)
  }

  override fun supports(category: MetricCategory): Boolean =
      MetricCategory.JVM == category

  override fun getOrder(): Int = 2

  private fun collectHeapMetrics(metrics: MutableMap<String, Any>) {
    val heapUsed = meterRegistry.find("jvm.memory.used").tag("area", "heap").gauge()
    val heapMax = meterRegistry.find("jvm.memory.max").tag("area", "heap").gauge()
    val heapCommitted = meterRegistry.find("jvm.memory.committed").tag("area", "heap").gauge()

    heapUsed?.let {
      metrics["heap_used_mb"] = toMb(it.value())
    }

    heapMax?.let {
      if (it.value() > 0) {
        metrics["heap_max_mb"] = toMb(it.value())
        heapUsed?.let { used ->
          val usagePercent = (used.value() / it.value()) * 100
          metrics["heap_usage_percent"] = formatDouble(usagePercent)
        }
      }
    }

    heapCommitted?.let {
      metrics["heap_committed_mb"] = toMb(it.value())
    }
  }

  private fun collectGcMetrics(metrics: MutableMap<String, Any>) {
    val gcPauseTime = meterRegistry.find("jvm.gc.pause").timer()

    gcPauseTime?.let {
      metrics["gc_pause_count"] = it.count()
      metrics["gc_pause_total_ms"] = formatDouble(it.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    val gcAlloc = meterRegistry.find("jvm.gc.memory.allocated").counter()

    gcAlloc?.let {
      metrics["gc_memory_allocated_mb"] = toMb(it.count())
    }
  }

  private fun collectThreadMetrics(metrics: MutableMap<String, Any>) {
    val liveThreads = meterRegistry.find("jvm.threads.live").gauge()
    val peakThreads = meterRegistry.find("jvm.threads.peak").gauge()
    val daemonThreads = meterRegistry.find("jvm.threads.daemon").gauge()

    liveThreads?.let {
      metrics["threads_live"] = it.value().toInt()
    }

    peakThreads?.let {
      metrics["threads_peak"] = it.value().toInt()
    }

    daemonThreads?.let {
      metrics["threads_daemon"] = it.value().toInt()
    }
  }

  private fun toMb(bytes: Double): Long = (bytes / (1024 * 1024)).toLong()

  private fun formatDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
      return 0.0
    }
    return round(value * 100.0) / 100.0
  }
}
