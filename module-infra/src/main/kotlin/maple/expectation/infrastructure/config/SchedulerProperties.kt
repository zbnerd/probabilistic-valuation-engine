package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Scheduler Thread Pool Properties
 *
 * ## 설정
 *
 * ```kotlin
 * scheduler:
 *   task-scheduler:
 *     pool-size: 3  # 기본값: 3
 * ```
 *
 * @see SchedulerConfig
 */
@ConfigurationProperties(prefix = "scheduler.task-scheduler")
data class SchedulerProperties(
    @DefaultValue("3") val poolSize: Int,
    @DefaultValue("60") val awaitTerminationSeconds: Int,
) {
    init {
        require(poolSize > 0) { "scheduler.task-scheduler.pool-size must be positive, got: $poolSize" }
        require(awaitTerminationSeconds > 0) { "await-termination-seconds must be positive" }
    }
}
