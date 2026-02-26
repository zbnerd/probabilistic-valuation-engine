package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

/**
 * Thread Pool 설정 외부화 (P1-1)
 *
 * <h3>설정 경로</h3>
 *
 * <pre>
 * executor:
 *   equipment:
 *     core-pool-size: 8
 *     max-pool-size: 16
 *     queue-capacity: 200
 *   preset:
 *     core-pool-size: 12
 *     max-pool-size: 24
 *     queue-capacity: 100
 * </pre>
 *
 * <h3>프로필별 설정</h3>
 *
 * <ul>
 *   <li>local: 개발 환경 기본값
 *   <li>prod: t3.small 2vCPU 기준 최적화
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "executor")
data class ExecutorProperties(
  @DefaultValue val equipment: PoolConfig = PoolConfig(),
  @DefaultValue val preset: PoolConfig = PoolConfig()
) {
  /**
   * 개별 Thread Pool 설정
   *
   * @property corePoolSize 코어 스레드 수 (기본값: equipment=8, preset=12)
   * @property maxPoolSize 최대 스레드 수 (기본값: equipment=16, preset=24)
   * @property queueCapacity 큐 용량 (기본값: equipment=200, preset=100)
   */
  data class PoolConfig(
    @DefaultValue("8") @Min(1) @Max(64) var corePoolSize: Int = 8,
    @DefaultValue("16") @Min(1) @Max(128) var maxPoolSize: Int = 16,
    @DefaultValue("200") @Min(10) @Max(5000) var queueCapacity: Int = 200
  )
}
