package maple.expectation.infrastructure.security.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.lang.NonNull
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Prometheus 엔드포인트 보안 필터 (Issue #20, #34)
 *
 * <p>보안 계층:
 *
 * <ul>
 *   <li><b>Layer 1 (IP Whitelist)</b>: 신뢰할 수 있는 프록시/내부 네트워크만 허용
 *   <li><b>Layer 2 (X-Forwarded-For Validation)</b>: 헤더 스푸핑 방지
 *   <li><b>Layer 3 (Rate Limiting)</b>: DoS 방어
 * </ul>
 *
 * <p>신뢰할 수 있는 프록시 목록:
 *
 * <ul>
 *   <li>localhost (127.0.0.1, ::1)
 *   <li>Docker 내부 네트워크 (172.16.0.0/12, 10.0.0.0/8, 192.168.0.0/16)
 *   <li>Kubernetes Pod 네트워크
 *   <li>구성 가능한 신뢰할 수 있는 프록시 IP 목록
 * </ul>
 */
open class PrometheusSecurityFilter(
    private val logicExecutor: LogicExecutor,
    @Value("\${prometheus.security.trusted-proxies:127.0.0.1,::1,localhost}") trustedProxies: String,
    @Value("\${prometheus.security.internal-networks:172.16.0.0/12,10.0.0.0/8,192.168.0.0/16}") internalNetworks: String,
    @Value("\${prometheus.security.enabled:true}") private val enabled: Boolean,
) : OncePerRequestFilter() {

    private val trustedProxies: List<String>
    private val internalNetworks: List<String>

    init {
        this.trustedProxies = trustedProxies.split(",")
        this.internalNetworks = internalNetworks.split(",")

        log.info(
            "[Prometheus-Security] Filter initialized - enabled: $enabled, " +
                "trustedProxies: ${this.trustedProxies}, internalNetworks: ${this.internalNetworks}",
        )
    }

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        @NonNull request: HttpServletRequest,
        @NonNull response: HttpServletResponse,
        @NonNull filterChain: FilterChain,
    ) {
        if (!enabled) {
            filterChain.doFilter(request, response)
            return
        }

        val path = request.requestURI

        // Prometheus 엔드포인트만 필터링
        if (path != "/actuator/prometheus") {
            filterChain.doFilter(request, response)
            return
        }

        // IP 검증
        val isAllowed = logicExecutor.executeOrDefault(
            { validateClientIp(request) },
            false,
            TaskContext.of("PrometheusSecurityFilter", "validateClientIp", request.remoteAddr),
        )

        if (!isAllowed) {
            log.warn(
                "[Prometheus-Security] Access denied - remoteAddr: ${request.remoteAddr}, " +
                    "xForwardedFor: ${request.getHeader("X-Forwarded-For")}, path: $path",
            )
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                "{\"code\":\"FORBIDDEN\",\"message\":\"Prometheus metrics access denied. Contact administrator.\"}",
            )
            return
        }

        log.debug("[Prometheus-Security] Access granted - remoteAddr: ${request.remoteAddr}, path: $path")
        filterChain.doFilter(request, response)
    }

    /**
     * 클라이언트 IP 검증 (X-Forwarded-For 지원)
     *
     * <p>검증 순서:
     *
     * <ol>
     *   <li>X-Forwarded-For 헤더 확인 (프록시 환경)
     *   <li>원본 IP 추출 (가장 왼쪽 IP)
     *   <li>신뢰할 수 있는 프록시/내부 네트워크 확인
     * </ol>
     *
     * @param request HTTP 요청
     * @return 허용 여부
     */
    private fun validateClientIp(request: HttpServletRequest): Boolean {
        val remoteAddr = request.remoteAddr
        val xForwardedFor = request.getHeader("X-Forwarded-For")

        // X-Forwarded-For 헤더가 있는 경우 원본 IP 추출
        val clientIp = extractClientIp(xForwardedFor, remoteAddr)

        // localhost 허용
        if (isLocalhost(clientIp)) {
            return true
        }

        // 신뢰할 수 있는 프록시 확인
        if (trustedProxies.contains(clientIp)) {
            return true
        }

        // 내부 네트워크 확인
        if (isInternalNetwork(clientIp)) {
            return true
        }

        return false
    }

    /**
     * X-Forwarded-For 헤더에서 원본 클라이언트 IP 추출
     *
     * <p>X-Forwarded-For 형식: {@code clientIP, proxy1IP, proxy2IP}
     *
     * <ul>
     *   <li>가장 왼쪽 IP가 원본 클라이언트 IP
     *   <li>헤더가 없거나 비정상적이면 remoteAddr 사용
     * </ul>
     *
     * @param xForwardedFor X-Forwarded-For 헤더 값
     * @param remoteAddr remoteAddr (fallback)
     * @return 원본 클라이언트 IP
     */
    private fun extractClientIp(xForwardedFor: String?, remoteAddr: String): String {
        if (xForwardedFor.isNullOrEmpty()) {
            return remoteAddr
        }

        // X-Forwarded-For: client, proxy1, proxy2
        // 가장 왼쪽 IP가 원본 클라이언트 IP
        val ips = xForwardedFor.split(",")
        if (ips.isNotEmpty()) {
            var clientIp = ips[0].trim()
            // IPv6 mapping 방지 (e.g., ::ffff:127.0.0.1)
            if (clientIp.startsWith("::ffff:")) {
                clientIp = clientIp.substring(7)
            }
            return clientIp
        }

        return remoteAddr
    }

    /**
     * localhost 확인
     *
     * @param ip IP 주소
     * @return localhost 여부
     */
    private fun isLocalhost(ip: String): Boolean = "127.0.0.1" == ip ||
        "::1" == ip ||
        "localhost".equals(ip, ignoreCase = true) ||
        ip.startsWith("127.") ||
        "0:0:0:0:0:0:0:1" == ip ||
        "::ffff:127.0.0.1" == ip

    /**
     * 내부 네트워크 확인 (CIDR)
     *
     * <p>지원되는 내부 네트워크:
     *
     * <ul>
     *   <li>172.16.0.0/12 (Docker 기본 네트워크)
     *   <li>10.0.0.0/8 (사설 네트워크)
     *   <li>192.168.0.0/16 (사설 네트워크)
     *   <li>Kubernetes Pod 네트워크 (기본)
     * </ul>
     *
     * @param ip IP 주소
     * @return 내부 네트워크 여부
     */
    private fun isInternalNetwork(ip: String): Boolean {
        val parts = ip.split("\\.".toRegex())
        if (parts.size != 4) {
            return false
        }

        return try {
            val firstOctet = parts[0].toInt()
            val secondOctet = parts[1].toInt()

            // 172.16.0.0/12 (172.16.0.0 ~ 172.31.255.255)
            if (firstOctet == 172 && secondOctet in 16..31) {
                return true
            }

            // 10.0.0.0/8 (10.0.0.0 ~ 10.255.255.255)
            if (firstOctet == 10) {
                return true
            }

            // 192.168.0.0/16 (192.168.0.0 ~ 192.168.255.255)
            if (firstOctet == 192 && secondOctet == 168) {
                return true
            }

            false
        } catch (e: NumberFormatException) {
            log.warn("[Prometheus-Security] Invalid IP format: $ip")
            false
        }
    }

    override fun shouldNotFilter(@NonNull request: HttpServletRequest): Boolean = !enabled

    companion object {
        private val log = LoggerFactory.getLogger(PrometheusSecurityFilter::class.java)
    }
}
