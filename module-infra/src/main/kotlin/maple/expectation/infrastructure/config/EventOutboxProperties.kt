package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Event Outbox Pattern 설정 프로퍼티
 *
 * <h4>설계 의도</h4>
 * <ul>
 *   <li>폴링 주기, 모니터링 주기, 복구 주기 외부화
 *   <li>배치 크기, 재시도 횟수, Staled 판정 기준 설정 가능
 *   <li>@ConfigurationProperties로 타입 안전 바인딩
 * </ul>
 *
 * <h4>application.yml 설정 예시:</h4>
 * <pre>
 * event-outbox:
 *   polling-interval: 10s        # 기본값: 10초
 *   monitoring-interval: 60s     # 기본값: 60초
 *   stalled-recovery-interval: 5m # 기본값: 5분
 *   batch-size: 10               # 기본값: 10
 *   max-retries: 3               # 기본값: 3
 *   stalled-threshold: 5m        # 기본값: 5분
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "event-outbox")
data class EventOutboxProperties(

    /** 폴링 주기 (PENDING -> PROCESSING -> COMPLETED) */
    @NotNull
    var pollingInterval: Duration = Duration.ofSeconds(10),

    /** 메트릭 모니터링 주기 */
    @NotNull
    var monitoringInterval: Duration = Duration.ofSeconds(60),

    /** Stalled 상태 복구 주기 (JVM 크래시 대응) */
    @NotNull
    var stalledRecoveryInterval: Duration = Duration.ofMinutes(5),

    /** 배치 처리 크기 (SKIP LOCKED 조회 단위) */
    @Min(1)
    @Max(1000)
    var batchSize: Int = 10,

    /** 최대 재시도 횟수 */
    @Min(0)
    @Max(10)
    var maxRetries: Int = 3,

    /** Stalled 판정 기준 시간 (PROCESSING 상태 유지 시간) */
    @NotNull
    var stalledThreshold: Duration = Duration.ofMinutes(5),

    /** 인스턴스 식별자 (Scale-out 환경에서 식별용) */
    @NotBlank
    var instanceId: String = "default-instance"
) {
    companion object {
        /**
         * Factory method for default values.
         *
         * <p>Used in tests or when default configuration is needed.
         */
        fun defaults() = EventOutboxProperties()
    }
}
