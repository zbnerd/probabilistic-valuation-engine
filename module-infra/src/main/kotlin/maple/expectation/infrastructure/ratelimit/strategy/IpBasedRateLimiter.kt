package maple.expectation.infrastructure.ratelimit.strategy

import io.github.bucket4j.distributed.proxy.ProxyManager
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * IP 기반 Rate Limiter (Strategy 구현체)
 *
 * <p>비인증 사용자의 Rate Limiting을 담당
 *
 * <h4>설정</h4>
 *
 * <ul>
 *   <li>기본 용량: 100 요청/분
 *   <li>리필: 6초마다 10 토큰
 *   <li>Redis 키: {ratelimit}:ip:{clientIp}
 * </ul>
 *
 * <h4>PR #192: Conditional Bean 등록</h4>
 *
 * <p>{@code ratelimit.enabled=true} 설정 시에만 Bean 등록됨 (기본값: true)
 *
 * @since Issue #152
 */
@Component
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class IpBasedRateLimiter(
    proxyManager: ProxyManager<String>,
    properties: RateLimitProperties,
    executor: LogicExecutor,
    meterRegistry: MeterRegistry
) : AbstractBucket4jRateLimiter(proxyManager, properties, executor, meterRegistry) {

    companion object {
        private const val STRATEGY_NAME = "ip"
        private const val KEY_PREFIX = "ip"
    }

    override fun getStrategyName(): String = STRATEGY_NAME

    override fun getKeyPrefix(): String = KEY_PREFIX

    override fun getCapacity(): Int = properties.ip.capacity

    override fun getRefillTokens(): Int = properties.ip.refillTokens

    override fun getRefillPeriod(): Duration = properties.ip.refillPeriod

    /**
     * IP 기반 Rate Limiting 활성화 여부 확인
     *
     * @return 활성화 여부
     */
    fun isEnabled(): Boolean = properties.ip.enabled
}
