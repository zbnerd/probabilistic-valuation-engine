package maple.expectation.infra.auth

import maple.expectation.core.port.out.TokenPort
import maple.expectation.infrastructure.security.jwt.JwtPayload
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider
import org.springframework.stereotype.Component

/**
 * TokenPort implementation that adapts the existing JwtTokenProvider
 *
 * <p>This adapter bridges the legacy JwtTokenProvider API with the new
 * simplified TokenPort interface used by module-core.
 *
 * <p>API Mapping:
 * <ul>
 *   <li>TokenPort.generateToken(userId) → JwtTokenProvider.generateToken(sessionId, empty, empty)</li>
 *   <li>TokenPort.validateToken(token) → JwtTokenProvider.parseToken(token) → extract sessionId</li>
 * </ul>
 */
@Component
class TokenPortImpl(
    private val jwtTokenProvider: JwtTokenProvider,
) : TokenPort {

    override fun generateToken(userId: Long): String {
        // Use existing JwtTokenProvider with sessionId as userId
        // fingerprint and role are left empty for simple userId-only tokens
        return jwtTokenProvider.generateToken(
            sessionId = userId.toString(),
            fingerprint = "",
            role = "",
        )
    }

    override fun validateToken(token: String): Long? = try {
        val payload: JwtPayload? = jwtTokenProvider.parseToken(token).orElse(null)
        payload?.sessionId?.toLong()
    } catch (e: Exception) {
        null
    }
}
