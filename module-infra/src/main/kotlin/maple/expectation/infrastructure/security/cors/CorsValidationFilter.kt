package maple.expectation.infrastructure.security.cors

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.filter.OncePerRequestFilter

/**
 * CORS 오리진 런타임 검증 필터
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <h4>동작 방식</h4>
 *
 * <ul>
 *   <li>요청의 Origin 헤더를 검증하여 허용된 오리진인지 확인
 *   <li>허용되지 않은 오리진인 경우 403 응답
 *   <li>OPTIONS 요청(preflight)은 Spring Security CORS 처리에 위임
 * </ul>
 *
 * <h4>CRITICAL (Spring Security 6.x Best Practice - Context7)</h4>
 *
 * <ul>
 *   <li>@Component 사용 금지 (CGLIB 프록시 → logger NPE)
 *   <li>SecurityConfig에서 @Bean으로 수동 등록
 *   <li>FilterRegistrationBean으로 서블릿 컨테이너 중복 등록 방지
 * </ul>
 *
 * <h4>보안 메트릭</h4>
 *
 * <ul>
 *   <li>거부된 요청 수를 로그에 기록
 *   <li>거부된 오리진을 마스킹하여 로그 (민감 정보 보호)
 * </ul>
 */
open class CorsValidationFilter(
    private val validator: CorsOriginValidator,
    private val executor: LogicExecutor,
    private val allowedOrigins: List<String>,
) : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // OPTIONS 요청은 Spring Security CORS 처리에 위임 (preflight)
        if (HttpMethod.OPTIONS.matches(request.method)) {
            filterChain.doFilter(request, response)
            return
        }

        // Origin 헤더 검증
        val originHeader = request.getHeader(HttpHeaders.ORIGIN)

        // Origin 헤더가 없는 요청은 통과 (same-origin 요청)
        if (originHeader.isNullOrEmpty()) {
            filterChain.doFilter(request, response)
            return
        }

        // 런타임 오리진 검증
        val isValid = executor.executeOrDefault(
            { validator.isValidRuntimeOrigin(originHeader, allowedOrigins) },
            false,
            TaskContext.of("CorsValidation", "ValidateOrigin", "***"),
        )

        if (!isValid) {
            handleInvalidOrigin(response, originHeader)
            return
        }

        filterChain.doFilter(request, response)
    }

    /**
     * 유효하지 않은 오리진 처리
     *
     * @param response HTTP 응답
     * @param originHeader 거부된 오리진
     */
    @Throws(IOException::class)
    private fun handleInvalidOrigin(response: HttpServletResponse, originHeader: String) {
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "application/json;charset=UTF-8"

        val maskedOrigin = maskOrigin(originHeader)
        log.warn("[CorsValidation-Rejected] Origin '$maskedOrigin' is not in allowed list")

        val errorResponse = "{\"code\":\"CORS_FORBIDDEN\",\"message\":\"허용되지 않는 오리진입니다.\"}"

        response.writer.write(errorResponse)
    }

    /**
     * 오리진 마스킹 (로깅용)
     *
     * <p>CLAUDE.md 섹션 19 준수: 민감 데이터 마스킹
     *
     * @param origin 오리진
     * @return 마스킹된 오리진
     */
    private fun maskOrigin(origin: String?): String {
        if (origin.isNullOrBlank()) {
            return "null"
        }

        // 프로토콜 제거
        val withoutProtocol = origin.replaceFirst("^https?://".toRegex(), "")

        // 도메인의 첫 부분만 노출, 나머지 마스킹
        val dotIndex = withoutProtocol.indexOf('.')
        return if (dotIndex > 0) {
            val firstPart = withoutProtocol.substring(0, dotIndex)
            val remaining = withoutProtocol.substring(dotIndex)
            // 첫 2글자만 노출
            val maskedFirst = if (firstPart.length > 2) {
                firstPart.substring(0, 2) + "***"
            } else {
                firstPart + "***"
            }
            maskedFirst + remaining
        } else {
            // 포트가 있는 경우
            val portIndex = withoutProtocol.indexOf(':')
            if (portIndex > 0) {
                val host = withoutProtocol.substring(0, portIndex)
                val port = withoutProtocol.substring(portIndex)
                maskDomain(host) + port
            } else {
                maskDomain(withoutProtocol)
            }
        }
    }

    private fun maskDomain(domain: String): String = if (domain.length <= 4) {
        "***"
    } else {
        domain.substring(0, 2) + "***" + domain.substring(domain.length - 2)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CorsValidationFilter::class.java)
    }
}
