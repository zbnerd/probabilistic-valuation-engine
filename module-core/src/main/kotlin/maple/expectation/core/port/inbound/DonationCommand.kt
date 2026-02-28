package maple.expectation.core.port.inbound

/**
 * 도네이션 명령 DTO (ADR-005)
 *
 * @param guestUuid 발신자 UUID
 * @param adminFingerprint 수신자 Admin fingerprint
 * @param amount 후원 금액
 * @param requestId 멱등성 키
 */
data class DonationCommand(
    val guestUuid: String,
    val adminFingerprint: String,
    val amount: Long,
    val requestId: String
)
