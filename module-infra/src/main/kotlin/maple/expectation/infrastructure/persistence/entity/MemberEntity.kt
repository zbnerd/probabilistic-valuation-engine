package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.util.UUID

/**
 * Member 엔티티 (JPA 매핑 전용)
 *
 * <p>비즈니스 로직(포인트 차감/검증)은 port 인터페이스의 원자적 쿼리(`decreasePointByUuid`)로 처리.
 *
 * <p>Issue #896: v2 패키지에서 infrastructure/persistence/entity/로 이관.
 */
@Entity
@Table(indexes = [Index(name = "idx_uuid", columnList = "uuid", unique = true)])
class MemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long? = null

    @Column(nullable = false, unique = true, length = 36)
    var uuid: String? = null

    var point: Long = 0L

    private constructor()

    private constructor(uuid: String, initialPoint: Long) {
        this.uuid = uuid
        this.point = initialPoint
    }

    companion object {
        @JvmStatic
        fun createSystemAdmin(uuid: String, initialPoint: Long): MemberEntity = MemberEntity(uuid, initialPoint)

        @JvmStatic
        fun createGuest(initialPoint: Long): MemberEntity = MemberEntity(UUID.randomUUID().toString(), initialPoint)
    }
}
