package maple.expectation.infrastructure.ratelimit.strategy

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.ratelimit.ConsumeResult
import maple.expectation.infrastructure.ratelimit.RateLimiter
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Bucket4j 기반 Rate Limiter 추상 클래스 (Template Method Pattern)
 *
 * <p>CLAUDE.md 섹션 6 준수: Template Method 패턴으로 공통 로직 추상화
 *
 * <h4>구현체 책임</h4>
 *
 * <ul>
 *   <li>getKeyPrefix() - Redis 키 접두사 반환
 *   <li>getCapacity() - 최대 토큰 수 반환
 *   <li>getRefillTokens() - 리필 토큰 수 반환
 *   <li>getRefillPeriod() - 리필 주기 반환
 * </ul>
 *
 * @since Issue #152
 */
abstract class AbstractBucket4jRateLimiter(
    protected val proxyManager: ProxyManager<String>,
    protected val properties: RateLimitProperties,
    protected val executor: LogicExecutor,
    protected val meterRegistry: MeterRegistry
) : RateLimiter {

    /**
     * 토큰 소비 시도 (Template Method)
     *
     * <p>CLAUDE.md 섹션 12 준수: LogicExecutor 패턴
     *
     * <p>CLAUDE.md 섹션 17 준수: Graceful Degradation (Redis 장애 시 Fail-Open)
     *
     * @param key Rate Limit 키 (IP 또는 fingerprint)
     * @return ConsumeResult 토큰 소비 결과
     */
    override fun tryConsume(key: String): ConsumeResult {
        val fullKey = buildFullKey(key)
        val context = TaskContext.of("RateLimit", "Consume", "${getStrategyName()}:${maskKey(key)}")

        return executor.executeOrDefault(
            { doTryConsume(fullKey) },
            ConsumeResult.failOpen(), // Default value
            context
        )
    }

    /**
     * 실제 토큰 소비 로직
     *
     * @param fullKey Redis 전체 키
     * @return ConsumeResult 토큰 소비 결과
     */
    private fun doTryConsume(fullKey: String): ConsumeResult {
        val configSupplier = Supplier { this.buildBucketConfiguration() }

        val probe = proxyManager.builder()
            .build(fullKey, configSupplier)
            .tryConsumeAndReturnRemaining(1)

        recordMetrics(probe.isConsumed)

        return if (probe.isConsumed) {
            ConsumeResult.allowed(probe.remainingTokens)
        } else {
            val retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.nanosToWaitForRefill)
            ConsumeResult.denied(probe.remainingTokens, maxOf(retryAfterSeconds, 1))
        }
    }

    /**
     * Bucket 설정 생성
     *
     * <p>Context7 Best Practice: Greedy Refill로 균등 토큰 리필
     *
     * @return BucketConfiguration 버킷 설정
     */
    protected fun buildBucketConfiguration(): BucketConfiguration {
        val bandwidth = Bandwidth.builder()
            .capacity(getCapacity().toLong())
            .refillGreedy(getRefillTokens().toLong(), getRefillPeriod())
            .build()

        return BucketConfiguration.builder()
            .addLimit(bandwidth)
            .build()
    }

    /**
     * Redis 전체 키 생성
     *
     * <p>CLAUDE.md 섹션 8-1 준수: Cluster Hash Tag 적용
     *
     * @param key 원본 키
     * @return 전체 키 (예: {ratelimit}:ip:192.168.1.1)
     */
    protected fun buildFullKey(key: String): String {
        return "${properties.keyPrefix}:${getKeyPrefix()}:$key"
    }

    /**
     * Redis 장애 시 Fail-Open 처리
     *
     * <p>5-Agent Council 합의: 가용성 > 보안
     *
     * @param key 원본 키
     * @return Fail-Open 결과
     */
    private fun handleFailureForLogging(key: String) {
        if (properties.isFailOpen()) {
            log.warn(
                "[RateLimit-FailOpen] Redis failure, allowing request: strategy={}, key={}",
                getStrategyName(),
                maskKey(key)
            )
            meterRegistry.counter("ratelimit.failopen", "strategy", getStrategyName()).increment()
        } else {
            // Fail-Close 모드 (보안 우선) - 로깅만
            log.warn(
                "[RateLimit-FailClose] Redis failure, using default: strategy={}, key={}",
                getStrategyName(),
                maskKey(key)
            )
            meterRegistry.counter("ratelimit.failclose", "strategy", getStrategyName()).increment()
        }
    }

    /**
     * 메트릭 기록
     *
     * <p>CLAUDE.md 섹션 17 준수: Micrometer 소문자 점 표기법
     *
     * @param consumed 토큰 소비 성공 여부
     */
    private fun recordMetrics(consumed: Boolean) {
        val result = if (consumed) "allowed" else "denied"
        meterRegistry.counter(
            "ratelimit.consume",
            "strategy", getStrategyName(),
            "result", result
        ).increment()
    }

    /**
     * 키 마스킹 (로깅용)
     *
     * @param key 원본 키
     * @return 마스킹된 키 (마지막 4자 제외)
     */
    protected fun maskKey(key: String?): String {
        if (key.isNullOrEmpty() || key.length <= 4) {
            return "****"
        }
        return "****" + key.substring(key.length - 4)
    }

    // ===== Template Methods (구현체에서 오버라이드) =====

    /** Redis 키 접두사 (예: "ip" 또는 "user") */
    protected abstract fun getKeyPrefix(): String

    /** 최대 토큰 수 (버킷 용량) */
    protected abstract fun getCapacity(): Int

    /** 리필 토큰 수 */
    protected abstract fun getRefillTokens(): Int

    /** 리필 주기 */
    protected abstract fun getRefillPeriod(): Duration

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AbstractBucket4jRateLimiter::class.java)
    }
}
