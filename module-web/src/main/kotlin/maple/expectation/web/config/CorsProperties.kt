package maple.expectation.web.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import maple.expectation.infrastructure.security.cors.ValidCorsOrigin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * CORS 설정 프로퍼티 (ADR-005 이관)
 *
 * Issue #172: CORS 와일드카드 제거 - 환경별 명시적 오리진 설정
 * Issue #21: CORS 오리진 검증 강화 - URL 포맷 검증 및 보안 규칙
 *
 * **보안 규칙**
 * - 와일드카드(*) 사용 금지 - CSRF 공격 벡터
 * - 빈 리스트 시 앱 시작 실패 (fail-fast)
 * - 프로덕션에서는 환경변수로 주입 권장
 * - 유효한 URL 형식 강제 (Issue #21)
 */
@ConfigurationProperties(prefix = "cors")
@Validated
class CorsProperties {
    /**
     * 허용할 오리진 목록 (필수)
     */
    @field:NotEmpty(message = "CORS 허용 오리진 목록은 필수입니다. cors.allowed-origins 설정을 확인하세요.")
    @field:ValidCorsOrigin(message = "CORS 오리진 형식이 유효하지 않습니다. URL 형식과 프로토콜을 확인하세요.")
    var allowedOrigins: List<String> = emptyList()

    /**
     * credentials 허용 여부 (기본: true)
     */
    @field:NotNull
    var allowCredentials: Boolean = true

    /**
     * preflight 캐시 시간 (초) (기본: 3600 = 1시간)
     */
    @field:Min(0)
    var maxAge: Long = 3600L
}
