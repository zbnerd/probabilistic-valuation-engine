package maple.auth.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey
import maple.expectation.core.auth.JwtPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class JwtGeneratorService(
    @Value("\${auth.jwt.secret}") private val secret: String,
    @Value("\${auth.jwt.expiration:3600}") private val expirationSeconds: Long,
) {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val CLAIM_FINGERPRINT = "fgp"
        private const val CLAIM_ROLE = "role"
        private const val CLAIM_USER_IGN = "userIgn"
    }

    fun generateToken(
        sessionId: String,
        fingerprint: String,
        role: String,
        userIgn: String,
    ): String {
        val now = Instant.now()
        val payload = JwtPayload(
            sessionId = sessionId,
            fingerprint = fingerprint,
            role = role,
            userIgn = userIgn,
            issuedAt = now,
            expiration = now.plusSeconds(expirationSeconds),
        )
        return Jwts.builder()
            .subject(payload.sessionId)
            .claim(CLAIM_FINGERPRINT, payload.fingerprint)
            .claim(CLAIM_ROLE, payload.role)
            .claim(CLAIM_USER_IGN, payload.userIgn)
            .issuedAt(Date.from(payload.issuedAt))
            .expiration(Date.from(payload.expiration))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }
}
