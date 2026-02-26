package maple.expectation.infrastructure.ratelimit.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.ratelimit.ConsumeResult
import maple.expectation.infrastructure.ratelimit.RateLimitContext
import maple.expectation.infrastructure.ratelimit.RateLimitingFacade
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties
import maple.expectation.infrastructure.security.AuthenticatedUser
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

open class RateLimitingFilter(
    private val rateLimitingFacade: RateLimitingFacade,
    private val properties: RateLimitProperties,
    private val executor: LogicExecutor
) : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val context = buildContext(request)

        val result = executor.executeOrDefault(
            { rateLimitingFacade.checkRateLimit(context) },
            ConsumeResult.failOpen(),
            TaskContext.of("RateLimit", "Filter", maskIp(context.clientIp))
        )

        if (!result.allowed) {
            handleRateLimitExceeded(response, result)
            return
        }

        addRateLimitHeaders(response, result)
        filterChain.doFilter(request, response)
    }

    private fun buildContext(request: HttpServletRequest): RateLimitContext {
        val clientIp = extractClientIp(request)
        val user = extractAuthenticatedUser()
        val requestUri = request.requestURI

        return RateLimitContext.of(clientIp, user, requestUri)
    }

    private fun extractClientIp(request: HttpServletRequest): String {
        val trustedHeaders = properties.trustedHeaders

        for (header in trustedHeaders) {
            val headerValue = request.getHeader(header)
            if (!headerValue.isNullOrEmpty()) {
                val ip = headerValue.split(",")[0].trim()
                if (ip.isNotEmpty()) {
                    return ip
                }
            }
        }

        return request.remoteAddr
    }

    private fun extractAuthenticatedUser(): AuthenticatedUser? {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication

        if (authentication != null &&
            authentication.isAuthenticated &&
            authentication.principal is AuthenticatedUser
        ) {
            return authentication.principal as AuthenticatedUser?
        }

        return null
    }

    @Throws(IOException::class)
    private fun handleRateLimitExceeded(response: HttpServletResponse, result: ConsumeResult) {
        response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE + 16 // 429
        response.contentType = "application/json;charset=UTF-8"
        response.setHeader("Retry-After", result.retryAfterSeconds.toString())
        response.setHeader("X-RateLimit-Remaining", result.remainingTokens.toString())

        val errorResponse =
            "{\"code\":\"R001\",\"message\":\"요청 한도를 초과했습니다. ${result.retryAfterSeconds}초 후 다시 시도해주세요.\"}"

        response.writer.write(errorResponse)
        log.warn("[RateLimit-Exceeded] Retry-After={}s", result.retryAfterSeconds)
    }

    private fun addRateLimitHeaders(response: HttpServletResponse, result: ConsumeResult) {
        if (result.remainingTokens >= 0) {
            response.setHeader("X-RateLimit-Remaining", result.remainingTokens.toString())
        }
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
        private val log = org.slf4j.LoggerFactory.getLogger(RateLimitingFilter::class.java)
    }
}
