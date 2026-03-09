package maple.expectation.domain.v2

import jakarta.persistence.*
import java.util.UUID
import maple.expectation.error.exception.InsufficientPointException

/**
 * Member 엔티티 (Rich Domain Model)
 *
 * <p>Issue #120: Anemic → Rich Domain Model 전환
 *
 * <p>포인트 관련 비즈니스 로직을 엔티티 내부에 캡슐화합니다.
 */
@Entity
@Table(indexes = [Index(name = "idx_uuid", columnList = "uuid", unique = true)])
class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /**
     * 낙관적 락 버전 (Issue #120 Rich Domain 동시성 보호)
     *
     * <p>Rich Domain Model에서 메모리 연산 후 DB 반영 시 동시 요청에 의한 Lost Update 방지
     */
    @Version
    var version: Long? = null

    @Column(nullable = false, unique = true, length = 36)
    var uuid: String? = null

    var point: Long = 0L

    // ==================== Factory Methods ====================

    private constructor()

    private constructor(uuid: String, initialPoint: Long) {
        this.uuid = uuid
        this.point = initialPoint
    }

    /** 시스템 관리자용 팩토리 메서드 (고정 UUID) */
    companion object {
        @JvmStatic
        fun createSystemAdmin(uuid: String, initialPoint: Long): Member = Member(uuid, initialPoint)

        /** 게스트용 팩토리 메서드 (랜덤 UUID) */
        @JvmStatic
        fun createGuest(initialPoint: Long): Member = Member(UUID.randomUUID().toString(), initialPoint)
    }

    // ==================== Business Logic (Issue #120) ====================

    /**
     * 포인트 잔액 확인
     *
     * @param amount 확인할 금액
     * @return 잔액이 충분하면 true
     */
    fun hasEnoughPoint(amount: Long): Boolean = this.point >= amount

    /**
     * 포인트 차감 (Rich Domain Model)
     *
     * <p>잔액 부족 시 InsufficientPointException 발생
     *
     * @param amount 차감할 금액
     * @throws InsufficientPointException 잔액 부족
     * @throws IllegalArgumentException 금액이 0 이하
     */
    fun deductPoints(amount: Long) {
        validatePositiveAmount(amount)
        if (!hasEnoughPoint(amount)) {
            // InsufficientPointException은 (보유, 필요) 2개 인자를 받음
            throw InsufficientPointException(this.point, amount)
        }
        this.point -= amount
    }

    // ==================== Private Helpers ====================

    private fun validatePositiveAmount(amount: Long) {
        if (amount <= 0L) {
            throw IllegalArgumentException("금액은 양수여야 합니다: $amount")
        }
    }

    private fun maskUuid(): String = if (this.uuid == null || this.uuid!!.length < 8) {
        "****"
    } else {
        this.uuid!!.substring(0, 4) + "****"
    }
}
