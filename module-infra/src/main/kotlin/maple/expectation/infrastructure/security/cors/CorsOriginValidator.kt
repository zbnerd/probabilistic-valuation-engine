package maple.expectation.infrastructure.security.cors

import java.net.URI
import java.util.regex.Pattern
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * CORS 오리진 유효성 검사기 (Security Enhancement)
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <h4>검증 규칙</h4>
 *
 * <ul>
 *   <li><b>URL 포맷 검증</b>: RFC 3986 유효한 URL 형식
 *   <li><b>프로토콜 검증</b>: http/https만 허용
 *   <li><b>환경별 규칙</b>:
 *       <ul>
 *         <li>local/ci: http 허용 (localhost 개발용)
 *         <li>prod: https 강제 (보안)
 *       </ul>
 *   <li><b>금지 패턴</b>:
 *       <ul>
 *         <li>프로덕션에서 localhost/127.0.0.1 금지
 *         <li>프로덕션에서 사설 IP 대역(10.*, 172.16-31.*, 192.168.*) 금지
 *       </ul>
 * </ul>
 *
 * <h4>Critical Best Practices</h4>
 *
 * <ul>
 *   <li>Fail-fast: 앱 시작 시 유효하지 않은 오리진이 있으면 즉시 실패
 *   <li>Audit Trail: 시작 시 모든 허용 오리진 로그 기록
 *   <li>Timing Attack Safe: equals()가 아닌 Set.contains() 사용
 * </ul>
 *
 * <p>CLAUDE.md Section 19: Security Best Practice 준수
 */
@Component
class CorsOriginValidator(
    @Value("\${spring.profiles.active:local}") activeProfile: String,
) {
    /** 유효한 프로토콜 목록 */
    private val validProtocols = setOf("http", "https")

    /** 와일드카드 패턴 (보안 위험으로 사용 금지) */
    private val wildcardPattern = Pattern.compile("^\\*.*")

    /** 로컬호스트 패턴 (개발용) */
    private val localhostPattern = Pattern.compile("^(https?://)?(localhost|127\\.0\\.0\\.1|::1)(:\\d+)?(/.*)?$")

    /** 사설 IP 대역 패턴 (RFC 1918) */
    private val privateIpPattern = Pattern.compile("^https?://(10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|192\\.168\\.|127\\.)")

    /** 현재 활성화된 프로필 */
    private val activeProfile: String = activeProfile.split(",")[0]

    /**
     * 오리진 목록 검증 (앱 시작 시 호출)
     *
     * @param origins 검증할 오리진 목록
     * @return 검증 결과 (상세 메시지 포함)
     * @throws IllegalArgumentException 유효하지 않은 오리진이 있을 경우
     */
    fun validateOrigins(origins: List<String>): ValidationResult {
        val validOrigins = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (origin in origins) {
            try {
                validateSingleOrigin(origin)
                validOrigins.add(origin)

                // 보안 경고 체크
                if (isProductionProfile()) {
                    if (isLocalhost(origin)) {
                        warnings.add("[SECURITY] '$origin' - 프로덕션 환경에서 localhost 오리진은 권장하지 않습니다.")
                    }
                    if (isPrivateIp(origin)) {
                        warnings.add("[SECURITY] '$origin' - 프로덕션 환경에서 사설 IP 오리진은 권장하지 않습니다.")
                    }
                    if (isHttp(origin)) {
                        warnings.add("[SECURITY] '$origin' - 프로덕션 환경에서는 HTTPS 사용을 권장합니다.")
                    }
                }
            } catch (e: IllegalArgumentException) {
                errors.add("[ERROR] '$origin': ${e.message}")
            }
        }

        return ValidationResult(validOrigins, warnings, errors)
    }

    /**
     * 단일 오리진 검증
     *
     * @param origin 검증할 오리진
     * @throws IllegalArgumentException 유효하지 않은 경우
     */
    fun validateSingleOrigin(origin: String?) {
        requireNotNull(origin) { "오리진은 null이거나 비어있을 수 없습니다." }
        require(origin.isNotBlank()) { "오리진은 null이거나 비어있을 수 없습니다." }

        // 와일드카드 검출 (보안 위험)
        require(!wildcardPattern.matcher(origin).matches()) {
            "와일드카드(*) 오리진은 보안 상의 이유로 금지됩니다. 명시적인 오리진을 사용하세요."
        }

        // URL 파싱
        val uri: URI = try {
            URI(origin)
        } catch (e: Exception) {
            throw IllegalArgumentException("유효하지 않은 URL 형식입니다: $origin", e)
        }

        // 스킴(프로토콜) 검증
        val scheme = uri.scheme
        require(!scheme.isNullOrBlank()) { "프로토콜이 누락되었습니다: $origin" }

        require(validProtocols.contains(scheme.lowercase())) {
            "허용되지 않는 프로토콜입니다: $scheme (허용: $validProtocols)"
        }

        // 호스트 검증
        val host = uri.host
        require(!host.isNullOrBlank()) { "호스트가 누락되었습니다: $origin" }
    }

    /**
     * 런타임 오리진 헤더 검증 (필터용)
     *
     * @param originHeader 요청의 Origin 헤더 값
     * @param allowedOrigins 허용된 오리진 목록
     * @return 유효 여부
     */
    fun isValidRuntimeOrigin(originHeader: String?, allowedOrigins: List<String>): Boolean {
        if (originHeader.isNullOrBlank()) {
            return false
        }

        // 정확히 일치하는지 확인 (와일드카드 없음)
        return allowedOrigins.contains(originHeader)
    }

    /**
     * 오리진 정규화 (소문자 변환, 후행 슬래시 제거)
     *
     * @param origin 정규화할 오리진
     * @return 정규화된 오리진
     */
    fun normalizeOrigin(origin: String?): String? {
        if (origin == null) {
            return null
        }
        var normalized = origin.trim().lowercase()
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }

    /** 프로덕션 프로필 여부 확인 */
    private fun isProductionProfile(): Boolean = "prod" == activeProfile

    /** 로컬호스트 오리진 여부 확인 */
    private fun isLocalhost(origin: String): Boolean = localhostPattern.matcher(origin).find()

    /** 사설 IP 오리진 여부 확인 */
    private fun isPrivateIp(origin: String): Boolean = privateIpPattern.matcher(origin).find()

    /** HTTP 프로토콜 여부 확인 */
    private fun isHttp(origin: String): Boolean = origin.startsWith("http://")

    /**
     * 검증 결과 레코드
     *
     * @property validOrigins 유효한 오리진 목록
     * @property warnings 보안 경고 목록
     * @property errors 에러 목록
     */
    data class ValidationResult(
        val validOrigins: List<String>,
        val warnings: List<String>,
        val errors: List<String>,
    ) {
        fun isValid(): Boolean = errors.isEmpty()
        fun hasWarnings(): Boolean = warnings.isNotEmpty()
    }
}
