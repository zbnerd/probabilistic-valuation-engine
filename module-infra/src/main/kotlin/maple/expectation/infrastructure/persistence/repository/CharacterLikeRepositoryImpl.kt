package maple.expectation.infrastructure.persistence.repository

import maple.expectation.core.domain.model.like.CharacterLike
import maple.expectation.domain.repository.CharacterLikeRepository as DomainCharacterLikeRepository
import maple.expectation.infrastructure.persistence.entity.CharacterLikeJpaEntity
import maple.expectation.infrastructure.persistence.jpa.CharacterLikeJpaRepository
import org.springframework.lang.Nullable
import org.springframework.stereotype.Repository

/**
 * CharacterLike JPA Repository Implementation (P1-11: Multi-DataSource Support)
 *
 * <p><strong>Transaction Management:</strong> Uses explicit `"transactionManager"` qualifier
 * to prevent ambiguity in multi-datasource environments. When MongoDB read replicas are added,
 * this repository will continue using the MySQL transaction manager exclusively.
 *
 * @see <a href="../../../../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
@Repository
open class CharacterLikeRepositoryImpl(
    private val jpaRepo: CharacterLikeJpaRepository,
) : DomainCharacterLikeRepository {

    @Nullable
    override fun findByTargetOcidAndLikerAccountId(
        targetOcid: String,
        likerAccountId: String,
    ): CharacterLike? = jpaRepo.findByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)
        .map { it.toDomain() }
        .orElseGet { null }

    override fun findByLikerAccountId(likerAccountId: String): List<CharacterLike> = jpaRepo.findByLikerAccountIdOrderByCreatedAtDesc(likerAccountId).stream()
        .map { it.toDomain() }
        .toList()

    override fun findByTargetOcid(targetOcid: String): List<CharacterLike> = jpaRepo.findByTargetOcidOrderByCreatedAtDesc(targetOcid).stream()
        .map { it.toDomain() }
        .toList()

    override fun save(like: CharacterLike): CharacterLike {
        requireNotNull(like) { "Like cannot be null" }
        val jpaEntity = CharacterLikeJpaEntity.fromDomain(like)
        val saved = jpaRepo.save(jpaEntity)
        return saved.toDomain()
    }

    override fun delete(like: CharacterLike) {
        requireNotNull(like) { "Like cannot be null" }
        val jpaEntity = CharacterLikeJpaEntity.fromDomain(like)
        jpaRepo.delete(jpaEntity)
    }

    override fun deleteByTargetOcidAndLikerAccountId(
        targetOcid: String,
        likerAccountId: String,
    ): Long = jpaRepo.deleteByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)

    override fun insertIfAbsent(targetOcid: String, likerAccountId: String): Int = jpaRepo.insertIfAbsent(targetOcid, likerAccountId)

    override fun countByTargetOcid(targetOcid: String): Long = jpaRepo.countByTargetOcid(targetOcid)

    override fun countByLikerAccountId(likerAccountId: String): Long = jpaRepo.countByLikerAccountId(likerAccountId)

    override fun existsByTargetOcidAndLikerAccountId(
        targetOcid: String,
        likerAccountId: String,
    ): Boolean = jpaRepo.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)
}
