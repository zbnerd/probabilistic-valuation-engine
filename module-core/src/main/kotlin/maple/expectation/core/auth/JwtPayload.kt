package maple.expectation.core.auth

import java.time.Instant

data class JwtPayload(
    val sessionId: String,
    val fingerprint: String,
    val role: String,
    val userIgn: String = "",
    val issuedAt: Instant,
    val expiration: Instant,
) {
    companion object {
        fun of(
            sessionId: String,
            fingerprint: String,
            role: String,
            ttlSeconds: Long,
            userIgn: String = "",
        ): JwtPayload {
            val now = Instant.now()
            return JwtPayload(
                sessionId = sessionId,
                fingerprint = fingerprint,
                role = role,
                userIgn = userIgn,
                issuedAt = now,
                expiration = now.plusSeconds(ttlSeconds),
            )
        }
    }
}
