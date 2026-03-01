package maple.expectation.infrastructure.monitoring.collector

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.BufferStatusQuery
import maple.expectation.infrastructure.config.MonitoringThresholdProperties
import org.springframework.stereotype.Component
import kotlin.math.min
import kotlin.math.round

/**
 * Redis 메트릭 수집기
 *
 * <p>버퍼 상태와 캐시 성능 메트릭을 수집합니다.
 *
 * <h2>DIP 준수</h2>
 *
 * <p>{@link BufferStatusQuery} Port를 통해 버퍼 상태를 조회하므로 Repository 직접 참조를 방지합니다.
 *
 * @see BufferStatusQuery 버퍼 상태 조회 Port
 */
@Component
class RedisMetricsCollector(
    private val meterRegistry: MeterRegistry,
    private val bufferStatus: BufferStatusQuery,
    private val thresholdProperties: MonitoringThresholdProperties
) : MetricsCollectorStrategy {

  override fun getCategoryName(): String = MetricCategory.REDIS.key

  override fun collect(): Map<String, Any> = buildMap {
    collectBufferMetrics(this)
    collectCacheMetrics(this)
  }

  override fun supports(category: MetricCategory): Boolean =
      MetricCategory.REDIS == category

  override fun getOrder(): Int = 5

  private fun collectBufferMetrics(metrics: MutableMap<String, Any>) {
    val pendingCount = bufferStatus.getTotalPendingCount()
    metrics["buffer_pending_count"] = pendingCount

    val saturation = (pendingCount.toDouble() / thresholdProperties.bufferSaturationDouble) * 100
    metrics["buffer_saturation_percent"] = min(formatDouble(saturation), 100.0)

    val bufferGauge = meterRegistry.find("redis.buffer.pending").gauge()
    bufferGauge?.let {
      metrics["buffer_gauge_value"] = it.value().toLong()
    }
  }

  private fun collectCacheMetrics(metrics: MutableMap<String, Any>) {
    val caffeineHits = meterRegistry.find("cache.gets").tag("result", "hit").gauge()
    val caffeineMisses = meterRegistry.find("cache.gets").tag("result", "miss").gauge()

    if (caffeineHits != null && caffeineMisses != null) {
      val hits = caffeineHits.value()
      val misses = caffeineMisses.value()
      val total = hits + misses
      if (total > 0) {
        metrics["l1_cache_hit_rate"] = formatDouble((hits / total) * 100)
      }
    }

    val l2HitCounter = meterRegistry.find("tiered.cache.hit").tag("layer", "L2").counter()
    val l2MissCounter = meterRegistry.find("tiered.cache.miss").tag("layer", "L2").counter()

    if (l2HitCounter != null && l2MissCounter != null) {
      val l2Hits = l2HitCounter.count()
      val l2Misses = l2MissCounter.count()
      val l2Total = l2Hits + l2Misses
      if (l2Total > 0) {
        metrics["l2_cache_hit_rate"] = formatDouble((l2Hits / l2Total) * 100)
      }
    }
  }

  private fun formatDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) {
      return 0.0
    }
    return round(value * 100.0) / 100.0
  }
}
