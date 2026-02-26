package maple.expectation.infrastructure.monitoring.context

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.monitoring.collector.MetricCategory
import maple.expectation.infrastructure.monitoring.collector.MetricsCollectorStrategy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Comparator

/**
 * 시스템 컨텍스트 제공자 (Facade 패턴)
 */
@Component
class SystemContextProvider(
    private val collectors: List<MetricsCollectorStrategy>,
    private val executor: LogicExecutor
) {

  private val log = LoggerFactory.getLogger(SystemContextProvider::class.java)

  fun collectAllMetrics(): Map<MetricCategory, Map<String, Any>> {
    val result = mutableMapOf<MetricCategory, Map<String, Any>>()
    
    collectors.stream()
        .sorted(Comparator.comparingInt { obj: MetricsCollectorStrategy -> obj.getOrder() })
        .forEach { collector -> collectSafely(collector, result) }
    
    return result
  }

  fun collectMetrics(vararg categories: MetricCategory): Map<MetricCategory, Map<String, Any>> {
    val result = mutableMapOf<MetricCategory, Map<String, Any>>()
    
    for (category in categories) {
      collectors.stream()
          .filter { c: MetricsCollectorStrategy -> c.supports(category) }
          .findFirst()
          .ifPresent { collector -> collectSafely(collector, result) }
    }
    
    return result
  }

  fun buildContextForAi(): String {
    val allMetrics = collectAllMetrics()
    val sb = StringBuilder()
    
    sb.append("=== System Context at ").append(Instant.now()).append(" ===\n\n")
    
    allMetrics.forEach { (category, metrics) ->
      sb.append("[").append(category.displayName).append("]\n")
      metrics.forEach { (key, value) ->
        if (value is Map<*, *>) {
          sb.append("  ").append(key).append(":\n")
          @Suppress("UNCHECKED_CAST")
          val nested = value as Map<String, Any>
          nested.forEach { (k, v) -> sb.append("    ").append(k).append(": ").append(v).append("\n") }
        } else {
          sb.append("  ").append(key).append(": ").append(value).append("\n")
        }
      }
      sb.append("\n")
    }
    
    return sb.toString()
  }

  fun buildSummary(): String {
    val metrics = collectMetrics(MetricCategory.GOLDEN_SIGNALS, MetricCategory.CIRCUIT_BREAKER)
    val summary = linkedMapOf<String, Any>()
    
    metrics[MetricCategory.GOLDEN_SIGNALS]?.forEach { (key, value) ->
      if (key.contains("latency_p95") || key.contains("error_rate") || key.contains("saturation")) {
        summary[key] = value
      }
    }
    
    val cbMetrics = metrics[MetricCategory.CIRCUIT_BREAKER] ?: emptyMap()
    summary["cb_open_count"] = cbMetrics.getOrElse("summary_open_count") { 0L }
    
    val sb = StringBuilder()
    summary.forEach { (k, v) -> sb.append(k).append(": ").append(v).append(" | ") }
    
    return sb.toString()
  }

  private fun collectSafely(
      collector: MetricsCollectorStrategy,
      result: MutableMap<MetricCategory, Map<String, Any>>
  ) {
    val context = TaskContext.of("Monitoring", "Collect", collector.getCategoryName())
    
    val metrics = executor.executeOrDefault(
        { collector.collect() },
        mapOf("error" to "Collection failed"),
        context
    )
    
    for (category in MetricCategory.entries) {
      if (collector.supports(category)) {
        result[category] = metrics
        break
      }
    }
  }
}
