package maple.expectation.infrastructure.security.filter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import maple.expectation.application.service.like.OcidResolutionService
import maple.expectation.infrastructure.security.AuthenticatedUser
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * JWT Authentication Filter (ADR-005, ADR-029, ADR-030)
 *
 * Authorization 헤더에서 Bearer 토큰을 추출하여 SecurityContext에 AuthenticatedUser를 설정합니다.
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>Authorization: Bearer {token} 추출
 *   <li>JwtTokenProvider로 토큰 파싱 및 검증
 *   <li>JWT claims로 AuthenticatedUser 생성
 *   <li>SecurityContext 설정
 * </ol>
 *
 * <h3>P0 Self-Like 방지</h3>
 * <p>모든 캐릭터 OCID를 myOcids에 포함하여 사용자가 자신의 어떤 캐릭터에도 좋아요를 누르지 못하게 합니다.
 *
 * <h3>P1 accountId 정체성 제약사항</h3>
 * <p>accountId = fingerprint (API Key의 HMAC-SHA256 해시)를 사용합니다.
 * 동일한 Nexon 계정이라도 API Key가 다르면 다른 accountId로 인식되어 중복 좋아요가 가능합니다.
 *
 * <p><b>TODO (P1):</b> game_character 테이블에 fingerprint 컬럼을 추가하여
 * Nexon 계정 단위의 정확한 accountId를 생성해야 합니다. 현재는 API Key 단위로 식별하는 제약이 있습니다.
 *
 * <h3>P1 Invalid Token Silent Pass-Through 수정</h3>
 * <p>Bearer 토큰이 존재하지만 JWT 파싱/검증에 실패하면 더 이상 silently continue하지 않고
 * 즉시 401 Unauthorized 응답을 반환합니다.
 *
 * @property jwtTokenProvider JWT 토큰 제공자
 * @property ocidResolutionService OCID 해결 서비스 (P0: 모든 캐릭터 OCID 조회)
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val ocidResolutionService: OcidResolutionService,
) : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)

        if (token != null) {
            try {
                val payload = jwtTokenProvider.parseToken(token)

                if (payload.isPresent) {
                    val jwt = payload.get()
                    val user = resolveAuthenticatedUser(jwt)

                    if (user != null) {
                        val authorities = listOf(SimpleGrantedAuthority("ROLE_${user.role}"))
                        val authentication = UsernamePasswordAuthenticationToken(user, null, authorities)
                        SecurityContextHolder.getContext().authentication = authentication
                        log.debug("[JWT] Authenticated: userIgn={}, role={}, myOcids={}", user.userIgn, user.role, user.myOcids.size)
                    }
                } else {
                    // 토큰 파싱 실패 (만료, 변조 등) - P1: silent pass-through 제거
                    log.warn("[JWT] Token parsing failed: invalid or expired token")
                    sendUnauthorizedResponse(response, "Invalid or expired token")
                    return
                }
            } catch (e: IllegalArgumentException) {
                // JWT 파싱 중 IllegalArgumentException (format error, algorithm validation 등)
                // P1: silent pass-through 제거
                log.warn("[JWT] Token validation failed: ${e.message}")
                sendUnauthorizedResponse(response, "Token validation failed: ${e.message}")
                return
            } catch (e: Exception) {
                // 예상치 못한 예외 - P1: silent pass-through 제거
                log.error("[JWT] Unexpected error during authentication", e)
                sendUnauthorizedResponse(response, "Authentication error")
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * Bearer 토큰 추출
     */
    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7).trim()
        } else {
            null
        }
    }

    /**
     * AuthenticatedUser 생성 (P0: 모든 캐릭터 OCID 포함)
     *
     * <p>P0 수정: 이전 구현에서는 현재 로그인 캐릭터의 OCID만 myOcids에 포함했으나,
     * 사용자가 여러 캐릭터를 소유한 경우 다른 캐릭터로 자신을 좋아요할 수 있는 취약점이 있었습니다.
     * 이제 모든 캐릭터 OCID를 조회하여 myOcids에 포함시킵니다.
     *
     * <p>P1 제약사항: 현재 game_character 테이블에 fingerprint 컬럼이 없어서
     * DB에 존재하는 모든 캐릭터 OCID를 조회합니다. 정확한 Self-Like 방지를 위해서는
     * fingerprint 컬럼 추가가 필요합니다.
     */
    private fun resolveAuthenticatedUser(jwt: maple.expectation.infrastructure.security.jwt.JwtPayload): AuthenticatedUser? {
        val userIgn = jwt.userIgn
        val fingerprint = jwt.fingerprint

        // P0: 모든 캐릭터 OCID를 조회하여 Self-Like 방지
        // TODO (P1): game_character 테이블에 fingerprint 컬럼 추가 후
        //            해당 fingerprint를 가진 캐릭터만 조회하도록 수정 필요
        val allOcids = ocidResolutionService.resolveAllOcids()

        // P1 제약사항: 현재는 모든 캐릭터를 myOcids에 포함
        // TODO (P1): fingerprint별 캐릭터 매핑이 가능해지면 여기서 필터링 필요
        val myOcids = allOcids

        // accountId = fingerprint (P1 제약사항: API Key별로 다른 accountId)
        // TODO (P1): Nexon 계정 단위의 accountId를 생성하도록 수정 필요
        val accountId = fingerprint

        log.debug("[JWT] Resolved myOcids: count={}, accountId={}", myOcids.size, accountId)

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

    /**
     * 401 Unauthorized 응답 전송 (P1: silent pass-through 제거)
     */
    private fun sendUnauthorizedResponse(response: HttpServletResponse, message: String) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"

        val errorResponse = mapOf(
            "error" to "Unauthorized",
            "message" to message,
            "status" to 401
        )

        try {
            response.writer.write(jacksonObjectMapper().writeValueAsString(errorResponse))
        } catch (e: IOException) {
            log.error("[JWT] Failed to write error response", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)
    }
}
