package maple.expectation.infrastructure.ratelimit

import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher

/**
 * Rate Limiting Facade (오케스트레이션 레이어) - Issue #152
 *
 * <p>CLAUDE.md 섹션 7 준수: Facade 패턴
 *
 * <ul>
 *   <li>Admin 바이패스 판단
 *   <li>바이패스 경로 체크
 *   <li>RateLimitingService 호출 조율
 * </ul>
 *
 * <h4>5-Agent Council 합의</h4>
 *
 * <ul>
 *   <li><b>Blue Agent</b>: Facade → Service 책임 분리
 *   <li><b>Purple Agent</b>: Admin 바이패스 로직
 *   <li><b>Red Agent</b>: 조건부 활성화/비활성화
 * </ul>
 *
 * @since Issue #152
 */
@Component
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RateLimitingFacade(
    private val rateLimitingService: RateLimitingService,
    private val properties: RateLimitProperties,
) {
    private val pathMatcher = AntPathMatcher()

    /**
     * Rate Limit 확인 (오케스트레이션)
     *
     * <h4>처리 순서</h4>
     *
     * <ol>
     *   <li>전체 Rate Limiting 비활성화 체크
     *   <li>바이패스 경로 체크 (Swagger, Actuator 등)
     *   <li>Admin 바이패스 체크
     *   <li>RateLimitingService 호출
     * </ol>
     *
     * @param context Rate Limit 컨텍스트
     * @return ConsumeResult 토큰 소비 결과
     */
    fun checkRateLimit(context: RateLimitContext): ConsumeResult {
        // 1. 전체 Rate Limiting 비활성화
        if (!rateLimitingService.isEnabled()) {
            log.trace("[RateLimit-Bypass] Rate limiting disabled globally")
            return ConsumeResult.allowed(Long.MAX_VALUE)
        }

        // 2. 바이패스 경로 체크 (Swagger, Actuator 등)
        if (isBypassPath(context.requestUri)) {
            log.trace("[RateLimit-Bypass] Bypass path: {}", context.requestUri)
            return ConsumeResult.allowed(Long.MAX_VALUE)
        }

        // 3. Admin 바이패스 (Purple Agent P1-1)
        if (context.isAdmin()) {
            log.debug("[RateLimit-Bypass] Admin user: {}", context)
            return ConsumeResult.allowed(Long.MAX_VALUE)
        }

        // 4. Rate Limit 확인 위임
        return rateLimitingService.checkRateLimit(context)
    }

    /**
     * 바이패스 경로 여부 확인 (AntPathMatcher 사용)
     *
     * @param requestUri 요청 URI
     * @return 바이패스 여부
     */
    private fun isBypassPath(requestUri: String?): Boolean {
        if (requestUri.isNullOrEmpty()) {
            return false
        }

        return properties.bypassPaths.stream()
            .anyMatch { pattern: String -> pathMatcher.match(pattern, requestUri) }
    }

    /**
     * 현재 설정된 바이패스 경로 목록 반환 (테스트용)
     *
     * @return 바이패스 경로 목록
     */
    fun getBypassPaths(): List<String> = properties.bypassPaths

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(RateLimitingFacade::class.java)
    }
}
