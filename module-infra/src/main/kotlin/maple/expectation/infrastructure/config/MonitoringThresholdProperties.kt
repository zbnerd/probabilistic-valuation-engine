package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * Monitoring threshold configuration properties for unified alert thresholds.
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): @ConfigurationProperties Record로 설정 외부화
 *   <li>Red (SRE): 환경별 오버라이드 가능한 설정
 *   <li>Green (Performance): 임계값 튜닝으로 모니터링 민감도 최적화
 * </ul>
 *
 * <h3>application.yml 설정 예시</h3>
 *
 * <pre>
 * expectation:
 *   monitoring-threshold:
 *     buffer-saturation-count: 5000
 *     buffer-saturation-double: 5000.0
 * </pre>
 *
 * @property bufferSaturationCount 버퍼 포화도 경고 임계값 (MonitoringAlertService)
 * @property bufferSaturationDouble 버퍼 포화도 퍼센트 계산 기준값 (RedisMetricsCollector)
 */
@Validated
@ConfigurationProperties(prefix = "expectation.monitoring-threshold")
data class MonitoringThresholdProperties(
  @DefaultValue("5000") @Min(1000) @Max(50000) val bufferSaturationCount: Long = 5000,
  @DefaultValue("5000.0") val bufferSaturationDouble: Double = 5000.0,
  // ADR-088: HikariCP monitoring thresholds
  @DefaultValue("0.7") val hikariCpWarningThreshold: Double = 0.7,
  @DefaultValue("0.9") val hikariCpCriticalThreshold: Double = 0.9,
  @DefaultValue("100") val hikariCpAcquireTimeThresholdMs: Double = 100.0,
  @DefaultValue("5") val hikariCpPendingThreadsThreshold: Int = 5,
  @DefaultValue("0.01") val hikariCpTimeoutRateThreshold: Double = 0.01
) {

  /**
   * 기본값을 사용하는 팩토리 메서드
   *
   * <p>테스트 또는 기본 설정 시 사용
   */
  companion object {
    fun defaults() = MonitoringThresholdProperties(
      bufferSaturationCount = 5000L,
      bufferSaturationDouble = 5000.0,
      hikariCpWarningThreshold = 0.7,
      hikariCpCriticalThreshold = 0.9,
      hikariCpAcquireTimeThresholdMs = 100.0,
      hikariCpPendingThreadsThreshold = 5,
      hikariCpTimeoutRateThreshold = 0.01
    )
  }
}
