package maple.expectation.infrastructure.pgmq

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * PGMQ 설정 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>PGMQ 클라이언트 및 Circuit Breaker 설정
 *
 * <h3>설정 항목</h3>
 * <ul>
 *   <li>기본 Visibility Timeout: 30초
 *   <li>기본 Batch Size: 10
 *   <li>Circuit Breaker: 장애 시 빠른 실패
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "pgmq")
class PgmqConfig {

    /** TX 활성 검증 여부 (테스트 환경에서 false 설정 가능) */
    var transactionCheckEnabled: Boolean = true

    /** 기본 Batch Size */
    var defaultBatchSize: Int = 10

    /** 기본 Visibility Timeout (초) - read 시 사용 */
    var defaultVisibilityTimeout: Int = 30

    /** Circuit Breaker 설정 */
    var circuitBreaker: CircuitBreakerSettings = CircuitBreakerSettings()

    data class CircuitBreakerSettings(
        var failureRateThreshold: Float = 50f,
        var slowCallRateThreshold: Float = 80f,
        var slowCallDurationThresholdMs: Long = 5000,
        var waitDurationInOpenStateMs: Long = 30000,
        var permittedNumberOfCallsInHalfOpenState: Int = 3,
        var slidingWindowSize: Int = 10,
    )

    /**
     * PGMQ 전용 Circuit Breaker Bean
     *
     * <p>PostgreSQL 연결 장애 시 빠른 실패로 전환
     */
    @Bean
    fun pgmqCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(circuitBreaker.failureRateThreshold)
            .slowCallRateThreshold(circuitBreaker.slowCallRateThreshold)
            .slowCallDurationThreshold(Duration.ofMillis(circuitBreaker.slowCallDurationThresholdMs))
            .waitDurationInOpenState(Duration.ofMillis(circuitBreaker.waitDurationInOpenStateMs))
            .permittedNumberOfCallsInHalfOpenState(circuitBreaker.permittedNumberOfCallsInHalfOpenState)
            .slidingWindowSize(circuitBreaker.slidingWindowSize)
            .build()

        return registry.circuitBreaker("pgmq", config)
    }
}
