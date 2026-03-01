package maple.expectation.web.dto

import jakarta.validation.constraints.NotBlank

/**
 * Token Refresh 요청 DTO (Issue #279)
 *
 * @param refreshToken Refresh Token ID
 */
data class RefreshRequest(
    @field:NotBlank(message = "refreshToken은 필수입니다.")
    val refreshToken: String
) {
    // Java Record 호환 메서드 (기존 Java 코드와의 호환성 유지)
    /** @return Refresh Token ID (Java record-style accessor) */
    fun refreshToken(): String = refreshToken
}
