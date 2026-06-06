package maple.restcontroller.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import maple.expectation.core.auth.JwtParserPort
import maple.expectation.core.domain.model.security.AuthenticatedUser
import maple.expectation.core.port.out.CharacterOcidPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class JwtAuthInterceptor(
    private val jwtParserPort: JwtParserPort,
    private val characterOcidPort: CharacterOcidPort,
) : HandlerInterceptor {

    companion object {
        private val log = LoggerFactory.getLogger(JwtAuthInterceptor::class.java)
        const val USER_ATTRIBUTE = "authenticatedUser"
        private const val BEARER_PREFIX: String = "Bearer "
        private const val BEARER_PREFIX_LENGTH: Int = BEARER_PREFIX.length
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val token = extractBearerToken(request)
        if (token == null) {
            sendUnauthorized(response, "Missing Authorization header")
            return false
        }

        val payload = jwtParserPort.parseToken(token)
        if (payload.isEmpty) {
            sendUnauthorized(response, "Invalid or expired token")
            return false
        }

        val jwt = payload.get()
        val myOcids = runCatching {
            characterOcidPort.resolveOcidsByFingerprint(jwt.fingerprint)
        }.getOrElse { emptySet() }

        val user = AuthenticatedUser(
            sessionId = jwt.sessionId,
            fingerprint = jwt.fingerprint,
            userIgn = jwt.userIgn,
            accountId = jwt.fingerprint,
            apiKey = "",
            myOcids = myOcids,
            role = jwt.role,
        )

        request.setAttribute(USER_ATTRIBUTE, user)
        return true
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            header.substring(BEARER_PREFIX_LENGTH).trim().takeIf { it.isNotBlank() }
        } else null
    }

    private fun sendUnauthorized(response: HttpServletResponse, message: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"$message","status":401}""")
    }
}
