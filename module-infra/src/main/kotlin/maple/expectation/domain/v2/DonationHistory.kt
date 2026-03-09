package maple.expectation.domain.v2

import jakarta.persistence.*
import java.time.LocalDateTime
import org.springframework.data.annotation.CreatedDate

/**
 * 기부 내역 엔티티
 *
 * <p>사용자 간 포인트 기부 내역을 저장합니다.
 */
@Entity
@Table(
    name = "donation_history",
    uniqueConstraints = [UniqueConstraint(name = "uk_request_id", columnNames = ["request_id"])],
)
class DonationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var senderUuid: String? = null

    /**
     * Admin(개발자)의 fingerprint
     *
     * <p>보안: fingerprint는 HMAC-SHA256 해시값이므로 저장해도 원본 API Key 노출 없음
     */
    var receiverFingerprint: String? = null

    var amount: Long? = null

    @Column(updatable = false)
    var requestId: String? = null

    @CreatedDate
    @Column(updatable = false)
    var createdAt: LocalDateTime? = null

    private constructor()

    private constructor(
        senderUuid: String?,
        receiverFingerprint: String?,
        amount: Long?,
        requestId: String?,
    ) {
        this.senderUuid = senderUuid
        this.receiverFingerprint = receiverFingerprint
        this.amount = amount
        this.requestId = requestId
    }

    companion object {
        @JvmStatic
        fun create(
            senderUuid: String?,
            receiverFingerprint: String?,
            amount: Long?,
            requestId: String?,
        ): DonationHistory = DonationHistory(senderUuid, receiverFingerprint, amount, requestId)
    }
}
