package maple.restcontroller.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Optional
import javax.crypto.SecretKey
import maple.expectation.core.auth.JwtParserPort
import maple.expectation.core.auth.JwtPayload
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class JwtParserAdapter(
    @Value("\${auth.jwt.secret}") private val secret: String,
) : JwtParserPort {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private val log = LoggerFactory.getLogger(JwtParserAdapter::class.java)
        private const val CLAIM_FINGERPRINT = "fgp"
        private const val CLAIM_ROLE = "role"
        private const val CLAIM_USER_IGN = "userIgn"

        /** Default token lifetime — 1 hour, matches the legacy cookie session. */
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS: Long = 60L * 60L // 1 hour
    }

    override fun parseToken(token: String?): Optional<JwtPayload> = runCatching {
        require(token != null && token.isNotBlank()) { "Token is blank" }

        val jws = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)

        val claims = jws.payload
        val issuedAt = claims.issuedAt?.toInstant() ?: Instant.now()
        val expiration = claims.expiration?.toInstant() ?: issuedAt.plusSeconds(DEFAULT_TOKEN_LIFETIME_SECONDS)

        Optional.of(
            JwtPayload(
                sessionId = claims.subject,
                fingerprint = claims[CLAIM_FINGERPRINT, String::class.java],
                role = claims[CLAIM_ROLE, String::class.java],
                userIgn = claims[CLAIM_USER_IGN, String::class.java] ?: "",
                issuedAt = issuedAt,
                expiration = expiration,
            ),
        )
    }.getOrElse { ex ->
        log.debug("JWT parse failed: {}", ex.message)
        Optional.empty()
    }

    override fun validateToken(token: String?): Boolean = parseToken(token).isPresent
}
