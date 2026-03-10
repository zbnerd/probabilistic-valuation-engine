package maple.expectation.infrastructure.ratelimit

import java.util.Optional
import maple.expectation.infrastructure.security.AuthenticatedUser

data class RateLimitContext(
    val clientIp: String,
    val authenticatedUser: Optional<AuthenticatedUser>,
    val requestUri: String,
) {
    override fun toString(): String = "RateLimitContext[" +
        "clientIp=${maskIp(clientIp)}, " +
        "authenticatedUser=${authenticatedUser.map { it.toString() }.orElse("anonymous")}, " +
        "requestUri=$requestUri" +
        "]"

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

    fun isAuthenticated(): Boolean = authenticatedUser.isPresent

    fun isAdmin(): Boolean = authenticatedUser.map { it.isAdmin() }.orElse(false)

    fun getRateLimitKey(): String = authenticatedUser.map { it.fingerprint }.orElse(clientIp)

    companion object {
        fun of(clientIp: String, user: AuthenticatedUser?, requestUri: String): RateLimitContext = RateLimitContext(clientIp, Optional.ofNullable(user), requestUri)
    }
}
