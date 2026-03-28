package maple.expectation.infrastructure.security.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import maple.expectation.domain.repository.GameCharacterRepository
import maple.expectation.infrastructure.security.AuthenticatedUser
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT Authentication Filter (ADR-005, ADR-029)
 *
 * Authorization 헤더에서 Bearer 토큰을 추출하여 SecurityContext에 AuthenticatedUser를 설정합니다.
 *
 * 흐름:
 * 1. Authorization: Bearer {token} 추출
 * 2. JwtTokenProvider로 토큰 파싱
 * 3. JWT claims로 AuthenticatedUser 생성
 * 4. SecurityContext 설정
 *
 * accountId는 fingerprint(SHA-256 of apiKey)를 사용하여
 * 동일 API Key = 동일 accountId를 보장합니다.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val gameCharacterRepository: GameCharacterRepository,
) : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)

        if (token != null) {
            val payload = jwtTokenProvider.parseToken(token)

            if (payload.isPresent) {
                val jwt = payload.get()
                val user = resolveAuthenticatedUser(jwt)

                if (user != null) {
                    val authorities = listOf(SimpleGrantedAuthority("ROLE_${user.role}"))
                    val authentication = UsernamePasswordAuthenticationToken(user, null, authorities)
                    SecurityContextHolder.getContext().authentication = authentication
                    log.debug("[JWT] Authenticated: userIgn={}, role={}", user.userIgn, user.role)
                }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7).trim()
        } else {
            null
        }
    }

    private fun resolveAuthenticatedUser(jwt: maple.expectation.infrastructure.security.jwt.JwtPayload): AuthenticatedUser? {
        val userIgn = jwt.userIgn
        val fingerprint = jwt.fingerprint

        // Resolve OCID from DB for self-like prevention
        val character = gameCharacterRepository.findByUserIgn(userIgn)
        val myOcids = if (character != null) setOf(character.characterId.value) else emptySet()

        // accountId = fingerprint (HMAC of apiKey, unique per Nexon account)
        val accountId = fingerprint

        return AuthenticatedUser(
            sessionId = jwt.sessionId,
            fingerprint = fingerprint,
            userIgn = userIgn,
            accountId = accountId,
            apiKey = "",
            myOcids = myOcids,
            role = jwt.role,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)
    }
}
