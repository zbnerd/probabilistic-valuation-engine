package maple.expectation.controller.dto.admin

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Admin 추가 요청 DTO
 *
 * **Issue #151: Bean Validation 적용**
 * - @NotBlank: 빈 문자열 및 null 방지
 * - @Size: 정확히 64자 검증 (SHA-256 hex digest)
 * - @Pattern: SQL Injection/XSS 패턴 차단 (16진수만 허용)
 *
 * **5-Agent Council Round 2 결정**
 * - Blue Agent: SRP 준수 - Controller 내부에서 분리
 * - Purple Agent: toString() 마스킹으로 PII 보호
 * - Yellow Agent: 경계값 테스트 (63자, 65자, 비hex) 필수
 *
 * CLAUDE.md 섹션 19 준수: toString() 마스킹 필수
 *
 * @param fingerprint Admin fingerprint (64자 SHA-256 hex digest)
 */
data class AddAdminRequest(
    @field:NotBlank(message = "fingerprint는 필수입니다")
    @field:Size(min = 64, max = 64, message = "fingerprint는 64자여야 합니다")
    @field:Pattern(regexp = "^[a-fA-F0-9]+$", message = "fingerprint는 16진수만 허용됩니다")
    val fingerprint: String
) {
    /**
     * 마스킹된 fingerprint 반환
     * 로그 및 응답 메시지에서 사용
     * @return 앞 4자리 + **** + 뒤 4자리 형식 (예: "abcd****efgh")
     */
    fun maskedFingerprint(): String {
        if (fingerprint.length < 8) {
            return "****"
        }
        return fingerprint.substring(0, 4) + "****" + fingerprint.substring(fingerprint.length - 4)
    }

    /**
     * 민감정보 마스킹된 toString
     * CRITICAL: TraceAspect 로깅 시 fingerprint 노출 방지
     */
    override fun toString(): String {
        return "AddAdminRequest[fingerprint=${maskedFingerprint()}]"
    }
}
