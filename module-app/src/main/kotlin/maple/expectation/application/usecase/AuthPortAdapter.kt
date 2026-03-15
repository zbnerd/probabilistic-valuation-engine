package maple.expectation.application.usecase

import maple.expectation.core.port.inbound.AuthCommand
import maple.expectation.core.port.inbound.AuthPort
import maple.expectation.core.port.inbound.AuthResult
import maple.expectation.core.port.inbound.TokenResult
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * AuthPort 구현체 (ADR-005)
 *
 * 책임: 인증 관련 유스케이스 구현
 *
 * V5 Migration (Issue #589): Redis 기반 세션/리프레시 토큰 저장소 제거 후 JWT-only 방식으로 단순화.
 *
 * 현재 상태: Refresh Token 기능 미지원 (Stateless JWT만 사용)
 */
@Component
class AuthPortAdapter(
    private val jwtTokenProvider: JwtTokenProvider,
) : AuthPort {

    override fun login(command: AuthCommand): AuthResult {
        log.info("[AuthPort] Login attempt: userIgn={}", command.userIgn)

        // Generate session ID and fingerprint from apiKey
        val sessionId = generateSessionId(command.apiKey)
        val fingerprint = generateFingerprint(command.apiKey)

        // Determine role (check if admin)
        val role = determineRole(fingerprint)

        // Generate JWT token
        val accessToken = jwtTokenProvider.generateToken(sessionId, fingerprint, role)
        val expiresIn = jwtTokenProvider.getExpirationSeconds()

        log.info("[AuthPort] Login successful: sessionId={}, role={}", sessionId, role)

        return AuthResult.of(
            accessToken,
            expiresIn,
            role,
            fingerprint,
            "refresh-$sessionId", // Placeholder refresh token
            REFRESH_EXPIRES_IN,
        )
    }

    override fun logout(sessionId: String) {
        log.info("[AuthPort] Logout: sessionId={}", sessionId)
        // Stateless JWT - no server-side session to invalidate
        // Token will expire naturally
    }

    override fun refresh(refreshTokenId: String): TokenResult {
        log.warn("[AuthPort] Token refresh not implemented yet. refreshTokenId={}", refreshTokenId)
        throw UnsupportedOperationException(
            "Token refresh is not implemented in this version. Please login again.",
        )
    }

    private fun generateSessionId(apiKey: String): String = "session-${kotlin.math.abs(apiKey.hashCode())}-${System.currentTimeMillis()}"

    private fun generateFingerprint(apiKey: String): String = "fp-${Integer.toHexString(apiKey.hashCode())}"

    private fun determineRole(fingerprint: String): String {
        // TODO: Check against admin allowlist
        return DEFAULT_ROLE
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthPortAdapter::class.java)
        private const val DEFAULT_ROLE = "USER"
        private const val REFRESH_EXPIRES_IN = 604800L // 7 days (not implemented yet)
    }
}
