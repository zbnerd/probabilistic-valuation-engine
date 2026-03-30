package maple.expectation.application.usecase

import maple.expectation.core.port.inbound.AuthCommand
import maple.expectation.core.port.inbound.AuthPort
import maple.expectation.core.port.inbound.AuthResult
import maple.expectation.core.port.inbound.TokenResult
import maple.expectation.application.service.auth.ApiKeyValidator
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
 * #667: Nexon API를 통한 실제 계정 검증. fingerprint = Nexon account_id.
 * 동일 계정의 다른 API Key라도 동일 account_id가 반환되어 identity가 보장됨.
 *
 * 현재 상태: Refresh Token 기능 미지원 (Stateless JWT만 사용)
 */
@Component
class AuthPortAdapter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val apiKeyValidator: ApiKeyValidator,
) : AuthPort {

    override fun login(command: AuthCommand): AuthResult {
        log.info("[AuthPort] Login attempt: userIgn={}", command.userIgn)

        // #667: Nexon API 검증 → account_id + 소유 캐릭터 OCID 확보
        val validationResult = apiKeyValidator.validateAndVerifyOwnership(
            command.apiKey, command.userIgn,
        )
        val accountId = validationResult.accountId

        val sessionId = generateSessionId(accountId)
        val role = determineRole(accountId)

        // fingerprint = Nexon account_id (동일 계정 = 동일 ID, API Key 무관)
        val accessToken = jwtTokenProvider.generateToken(sessionId, accountId, role, command.userIgn)
        val expiresIn = jwtTokenProvider.getExpirationSeconds()

        log.info("[AuthPort] Login successful: sessionId={}, role={}, accountId={}", sessionId, role, accountId)

        return AuthResult.of(
            accessToken,
            expiresIn,
            role,
            accountId,
            "refresh-$sessionId",
            REFRESH_EXPIRES_IN,
        )
    }

    override fun logout(sessionId: String) {
        log.info("[AuthPort] Logout: sessionId={}", sessionId)
    }

    override fun refresh(refreshTokenId: String): TokenResult {
        log.warn("[AuthPort] Token refresh not implemented yet. refreshTokenId={}", refreshTokenId)
        throw UnsupportedOperationException(
            "Token refresh is not implemented in this version. Please login again.",
        )
    }

    private fun generateSessionId(accountId: String): String =
        "session-${kotlin.math.abs(accountId.hashCode())}-${System.currentTimeMillis()}"

    private fun determineRole(accountId: String): String {
        // TODO: Check against admin allowlist
        return DEFAULT_ROLE
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthPortAdapter::class.java)
        private const val DEFAULT_ROLE = "USER"
        private const val REFRESH_EXPIRES_IN = 604800L // 7 days (not implemented yet)
    }
}
