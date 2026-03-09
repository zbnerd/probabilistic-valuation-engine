package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import maple.expectation.core.domain.model.like.CharacterLike
import org.hibernate.annotations.CreationTimestamp

@Entity
@Table(
    name = "character_like",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_target_liker",
            columnNames = ["target_ocid", "liker_account_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_target_ocid", columnList = "target_ocid"),
        Index(name = "idx_liker_account_id", columnList = "liker_account_id"),
    ],
)
open class CharacterLikeJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "target_ocid", nullable = false, length = 64)
    open var targetOcid: String? = null

    @Column(name = "liker_account_id", nullable = false, length = 64)
    open var likerAccountId: String? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: LocalDateTime? = null

    protected constructor()

    constructor(targetOcid: String?, likerAccountId: String?) {
        this.targetOcid = targetOcid
        this.likerAccountId = likerAccountId
        this.createdAt = LocalDateTime.now()
    }

    open fun toDomain(): CharacterLike = CharacterLike.restore(
        id ?: 0L,
        targetOcid ?: "",
        likerAccountId ?: "",
        createdAt ?: LocalDateTime.now(),
    )

    companion object {
        fun fromDomain(domain: CharacterLike?): CharacterLikeJpaEntity {
            if (domain == null) {
                throw IllegalArgumentException("Domain cannot be null")
            }
            return if (domain.id == null) {
                CharacterLikeJpaEntity(domain.targetOcid, domain.likerAccountId)
            } else {
                val entity = CharacterLikeJpaEntity()
                entity.id = domain.id
                entity.targetOcid = domain.targetOcid
                entity.likerAccountId = domain.likerAccountId
                entity.createdAt = domain.createdAt
                entity
            }
        }
    }
}
