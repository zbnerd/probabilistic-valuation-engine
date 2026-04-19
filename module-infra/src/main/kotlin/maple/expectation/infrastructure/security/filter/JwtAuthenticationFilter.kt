package maple.expectation.infrastructure.security.filter

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.infrastructure.security.AuthenticatedUser
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

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
 * <h3>Self-Like 방지 (#662 해결)</h3>
 * <p>fingerprint 기반으로 사용자가 소유한 모든 캐릭터 OCID를 myOcids에 포함하여
 * 자신의 캐릭터에 좋아요를 누르지 못하게 합니다.
 * Lazy backfill로 fingerprint 미배정 캐릭터를 최초 인증 시 stamp합니다.
 *
 * <h3>accountId 정체성 (#667)</h3>
 * <p>accountId = fingerprint = Nexon account_id.
 * 동일 Nexon 계정의 다른 API Key라도 동일 account_id 반환.
 * 1 계정 = 1 좋아요, 동일 계정 내 캐릭터끼리 좋아요 불가.
 *
 * <h3>P1 Invalid Token Silent Pass-Through 수정</h3>
 * <p>Bearer 토큰이 존재하지만 JWT 파싱/검증에 실패하면 더 이상 silently continue하지 않고
 * 즉시 401 Unauthorized 응답을 반환합니다.
 *
 * <h3>P1 DIP 준수</h3>
 * <p>GameCharacterRepository(도메인 포트)에 직접 의존하는 것을 피하고 CharacterOcidPort(코어 포트)를 통해
 * OCID를 조회합니다. ObjectMapper는 Bean을 주입받아 매번 새 인스턴스 생성을 방지합니다.
 *
 * @property jwtTokenProvider JWT 토큰 제공자
 * @property characterOcidPort 코어 포트 (DIP 준수)
 * @property objectMapper JSON 직렬화용 ObjectMapper Bean
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val characterOcidPort: CharacterOcidPort,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {

    private val unauthorizedCounter = Counter.builder("auth.failure.unauthorized")
        .description("Count of 401 unauthorized responses from JWT filter")
        .register(meterRegistry)

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
     * AuthenticatedUser 생성
     *
     * <p>Self-Like 방지 (#662 해결): fingerprint 기반으로 사용자가 소유한 모든 캐릭터 OCID를
     * myOcids에 포함하여 자신의 캐릭터에 좋아요를 누르지 못하게 합니다.
     *
     * <p>accountId 정체성 (#667): accountId = fingerprint = Nexon account_id.
     * 동일 Nexon 계정 = 동일 account_id. API Key 무관.
     *
     * <p>Lazy backfill: fingerprint 미배정 캐릭터는 최초 인증 시 stamp (idempotent).
     */
    private fun resolveAuthenticatedUser(jwt: maple.expectation.infrastructure.security.jwt.JwtPayload): AuthenticatedUser? {
        val userIgn = jwt.userIgn
        val fingerprint = jwt.fingerprint

        // #662: fingerprint 기반 모든 OCID 조회
        val fingerprintOcids = if (fingerprint.isNotBlank()) {
            characterOcidPort.resolveOcidsByFingerprint(fingerprint)
        } else {
            emptySet()
        }

        // 현재 캐릭터 OCID (fingerprint 미배정 경우 fallback)
        val myOcid = if (userIgn.isNotBlank()) {
            characterOcidPort.resolveOcid(userIgn)
        } else {
            null
        }
        val allMyOcids = if (myOcid != null) fingerprintOcids + myOcid else fingerprintOcids

        // Defensive: 기존 캐릭터가 fingerprint에 매핑되지 않은 경우 모니터링
        if (allMyOcids.isEmpty() && myOcid != null) {
            log.warn("[JWT] No OCIDs resolved for fingerprint despite existing character. Self-like protection may be incomplete: userIgn={}", userIgn)
        }

        // Lazy backfill: fingerprint NULL인 캐릭터에만 stamp (idempotent)
        if (myOcid != null && !fingerprintOcids.contains(myOcid) && fingerprint.isNotBlank()) {
            try {
                characterOcidPort.updateFingerprint(myOcid, fingerprint, fingerprint)
            } catch (e: DuplicateKeyException) {
                // uk_account_user_ign 위반: 다른 계정이 이미 해당 조합을 소유
                log.warn("[JWT] Unique index violation during backfill. Character already registered by different account.")
            }
        }

        val accountId = fingerprint

        log.debug("[JWT] Resolved myOcids: userIgn={}, ocidCount={}, accountId={}", userIgn, allMyOcids.size, accountId)

        return AuthenticatedUser(
            sessionId = jwt.sessionId,
            fingerprint = fingerprint,
            userIgn = userIgn,
            accountId = accountId,
            apiKey = "",
            myOcids = allMyOcids,
            role = jwt.role,
        )
    }

    /**
     * 401 Unauthorized 응답 전송 (P1: silent pass-through 제거)
     */
    private fun sendUnauthorizedResponse(response: HttpServletResponse, message: String) {
        unauthorizedCounter.increment()
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"

        val errorResponse = mapOf(
            "error" to "Unauthorized",
            "message" to message,
            "status" to 401,
        )

        try {
            response.writer.write(objectMapper.writeValueAsString(errorResponse))
        } catch (e: IOException) {
            log.error("[JWT] Failed to write error response", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)
    }
}
