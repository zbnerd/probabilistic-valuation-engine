package maple.expectation.infrastructure.ratelimit

import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties
import maple.expectation.infrastructure.ratelimit.strategy.IpBasedRateLimiter
import maple.expectation.infrastructure.ratelimit.strategy.UserBasedRateLimiter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RateLimitingService(
    private val ipRateLimiter: IpBasedRateLimiter,
    private val userRateLimiter: UserBasedRateLimiter,
    private val properties: RateLimitProperties,
) {
    fun checkRateLimit(context: RateLimitContext): ConsumeResult {
        if (context.isAuthenticated() && userRateLimiter.isEnabled()) {
            val fingerprint = context.authenticatedUser
                .map { it.fingerprint }
                .orElse(context.clientIp)

            log.debug("[RateLimit] Using user strategy: fingerprint={}", maskKey(fingerprint))
            return userRateLimiter.tryConsume(fingerprint)
        }

        if (ipRateLimiter.isEnabled()) {
            log.debug("[RateLimit] Using IP strategy: ip={}", maskIp(context.clientIp))
            return ipRateLimiter.tryConsume(context.clientIp)
        }

        log.debug("[RateLimit] All strategies disabled, allowing request")
        return ConsumeResult.allowed(Long.MAX_VALUE)
    }

    fun isEnabled(): Boolean = properties.enabled

    private fun maskKey(key: String?): String {
        if (key.isNullOrEmpty() || key.length <= 4) {
            return "****"
        }
        return "****" + key.substring(key.length - 4)
    }

    private fun maskIp(ip: String?): String {
        if (ip.isNullOrEmpty()) {
            return "null"
        }
        val parts = ip.split("\\.".toRegex())
        if (parts.size != 4) {
            return "***"
        }
        return "${parts[0]}.${parts[1]}.***.***"
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(RateLimitingService::class.java)
    }
}
