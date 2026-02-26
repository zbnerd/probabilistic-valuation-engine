package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.model.character.GameCharacter
import maple.expectation.domain.repository.GameCharacterRepository as DomainGameCharacterRepository
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepositoryCustom
import org.springframework.lang.Nullable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
open class GameCharacterRepositoryImpl(
    private val jpaRepo: GameCharacterJpaRepository,
    private val jpaCustomRepo: GameCharacterJpaRepositoryCustom,
) : DomainGameCharacterRepository {

    @Nullable
    override fun findByOcid(ocid: String): GameCharacter? {
        return jpaRepo.findByOcid(ocid)?.toDomain()
    }

    @Nullable
    override fun findByUserIgn(userIgn: String): GameCharacter? {
        return jpaRepo.findByUserIgn(userIgn)?.toDomain()
    }

    override fun findAll(): List<GameCharacter> {
        return jpaRepo.findAll().stream().map { it.toDomain() }.toList()
    }

    override fun findAll(pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<GameCharacter> {
        return jpaRepo.findAll(pageable).map { it.toDomain() }
    }

    override fun findActiveCharacters(): List<GameCharacter> {
        return jpaCustomRepo.findActiveCharacters().stream().map { it.toDomain() }.toList()
    }

    override fun save(character: GameCharacter): GameCharacter {
        requireNotNull(character) { "Character cannot be null" }
        val jpaEntity = GameCharacterJpaEntity.fromDomain(character)
        val saved = jpaRepo.save(jpaEntity)
        return saved.toDomain()
    }

    override fun deleteByOcid(ocid: String) {
        jpaRepo.deleteByOcid(ocid)
    }

    override fun existsByOcid(ocid: String): Boolean {
        return jpaRepo.existsByOcid(ocid)
    }

    override fun incrementLikeCount(userIgn: String, count: Long) {
        jpaRepo.incrementLikeCount(userIgn, count)
    }
}
