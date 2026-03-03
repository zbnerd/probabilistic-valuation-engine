package maple.expectation.web.dto.donation

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

/**
 * 커피 후원 요청 DTO
 *
 * @param adminFingerprint 수신자 Admin fingerprint (로그인 응답에서 확인 가능)
 * @param amount 후원 금액 (양수)
 */
data class SendCoffeeRequest(
    @field:NotBlank(message = "Admin fingerprint는 필수입니다")
    val adminFingerprint: String,
    @field:NotNull(message = "금액은 필수입니다")
    @field:Positive(message = "금액은 양수여야 합니다")
    val amount: Long
) {
    /** 보안: toString()에서 fingerprint 마스킹 */
    override fun toString(): String {
        val masked = if (adminFingerprint.length >= 8) {
            adminFingerprint.substring(0, 4) + "****"
        } else {
            "****"
        }
        return "SendCoffeeRequest[adminFingerprint=$masked, amount=$amount]"
    }
}
