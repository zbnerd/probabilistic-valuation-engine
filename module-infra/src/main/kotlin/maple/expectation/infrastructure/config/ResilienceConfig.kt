package maple.expectation.infrastructure.config

import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Resilience4j Retry Bean 등록 (P1-2: YAML 이관)
 *
 * ## 변경 사항
 *
 * - Before: 독립 RetryRegistry 생성 → Actuator 추적 불가
 * - After: Spring 관리 RetryRegistry에서 인스턴스 조회 → /actuator/retries 노출
 *
 * 설정은 application.yml의 resilience4j.retry.instances.likeSyncRetry에서 관리됩니다.
 */
@Configuration
class ResilienceConfig {

    @Bean
    fun likeSyncRetry(retryRegistry: RetryRegistry): Retry = retryRegistry.retry("likeSyncRetry")
}
