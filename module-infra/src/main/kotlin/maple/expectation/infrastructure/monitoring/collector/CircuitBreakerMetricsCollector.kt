package maple.expectation.infrastructure.monitoring.collector

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.stereotype.Component
import kotlin.math.round

@Component
class CircuitBreakerMetricsCollector(
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) : MetricsCollectorStrategy {

  override fun getCategoryName(): String = MetricCategory.CIRCUIT_BREAKER.key

  override fun collect(): Map<String, Any> = buildMap {
    circuitBreakerRegistry.getAllCircuitBreakers().forEach { cb ->
      val name = cb.name
      val cbMetrics = cb.metrics

      val cbData = linkedMapOf<String, Any>(
          "state" to cb.state.name,
          "failure_rate" to formatDouble(cbMetrics.failureRate.toFloat()),
          "slow_call_rate" to formatDouble(cbMetrics.slowCallRate.toFloat()),
          "buffered_calls" to cbMetrics.numberOfBufferedCalls,
          "failed_calls" to cbMetrics.numberOfFailedCalls,
          "successful_calls" to cbMetrics.numberOfSuccessfulCalls,
          "not_permitted_calls" to cbMetrics.numberOfNotPermittedCalls
      )

      this[name] = cbData
    }

    val openCount = circuitBreakerRegistry.getAllCircuitBreakers()
        .count { it.state == CircuitBreaker.State.OPEN }
    val halfOpenCount = circuitBreakerRegistry.getAllCircuitBreakers()
        .count { it.state == CircuitBreaker.State.HALF_OPEN }

    this["summary_open_count"] = openCount.toLong()
    this["summary_half_open_count"] = halfOpenCount.toLong()
    this["summary_total_count"] = circuitBreakerRegistry.getAllCircuitBreakers().size.toLong()
  }

  override fun supports(category: MetricCategory): Boolean =
      MetricCategory.CIRCUIT_BREAKER == category

  override fun getOrder(): Int = 3

  private fun formatDouble(value: Float): Double {
    if (value.isNaN() || value.isInfinite()) {
      return -1.0
    }
    return round(value * 100.0) / 100.0
  }
}
