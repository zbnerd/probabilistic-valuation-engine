package maple.expectation.web.dto.donation

/**
 * 커피 후원 응답 DTO
 *
 * @param requestId 멱등성 키 (중복 요청 방지용)
 * @param message 성공 메시지
 */
data class SendCoffeeResponse(
    val requestId: String,
    val message: String,
) {
    companion object {
        fun success(requestId: String): SendCoffeeResponse = SendCoffeeResponse(requestId, "커피 후원이 완료되었습니다.")
    }
}
