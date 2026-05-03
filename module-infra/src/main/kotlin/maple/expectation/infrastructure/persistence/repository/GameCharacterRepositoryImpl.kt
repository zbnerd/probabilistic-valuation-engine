package maple.expectation.infrastructure.persistence.repository

import maple.expectation.core.domain.model.Page
import maple.expectation.core.domain.model.PageRequest
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.domain.repository.GameCharacterRepository as DomainGameCharacterRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepositoryCustom
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.lang.Nullable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * GameCharacter JPA Repository Implementation (P1-11: Multi-DataSource Support)
 *
 * <p><strong>Transaction Management:</strong> Uses explicit `"transactionManager"` qualifier
 * to prevent ambiguity in multi-datasource environments. When MongoDB read replicas are added,
 * this repository will continue using the MySQL transaction manager exclusively.
 *
 * @see <a href="../../../../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
@Repository
@Transactional(value = "transactionManager", readOnly = true)
open class GameCharacterRepositoryImpl(
    private val jpaRepo: GameCharacterJpaRepository,
    private val jpaCustomRepo: GameCharacterJpaRepositoryCustom,
    private val logicExecutor: LogicExecutor,
) : DomainGameCharacterRepository {

    @Nullable
    override fun findByOcid(ocid: String): GameCharacter? = jpaRepo.findByOcid(ocid)?.toDomain()

    @Nullable
    override fun findByUserIgn(userIgn: String): GameCharacter? = jpaRepo.findByUserIgn(userIgn)?.toDomain()

    override fun findAll(): List<GameCharacter> = jpaRepo.findAll().stream().map { it.toDomain() }.toList()

    override fun findAll(pageRequest: PageRequest): Page<GameCharacter> {
        val springPageable = SpringPageRequest.of(pageRequest.page, pageRequest.size)
        val springPage = jpaRepo.findAll(springPageable)
        return Page(
            content = springPage.content.map { it.toDomain() },
            pageNumber = springPage.number,
            pageSize = springPage.size,
            totalElements = springPage.totalElements,
            hasNext = springPage.hasNext(),
        )
    }

    override fun findActiveCharacters(): List<GameCharacter> = jpaCustomRepo.findActiveCharacters().stream().map { it.toDomain() }.toList()

    @Transactional("transactionManager")
    override fun save(character: GameCharacter): GameCharacter {
        requireNotNull(character) { "Character cannot be null" }
        val jpaEntity = GameCharacterJpaEntity.fromDomain(character)
        val saved = jpaRepo.save(jpaEntity)
        return saved.toDomain()
    }

    @Transactional("transactionManager")
    override fun deleteByOcid(ocid: String) {
        jpaRepo.deleteByOcid(ocid)
    }

    override fun existsByOcid(ocid: String): Boolean = jpaRepo.existsByOcid(ocid)

    @Transactional("transactionManager")
    override fun incrementLikeCount(userIgn: String, count: Long) {
        jpaRepo.incrementLikeCount(userIgn, count)
    }

    override fun findByUserIgnIn(userIgns: List<String>): Map<String, GameCharacter> {
        if (userIgns.isEmpty()) return emptyMap()

        val context = TaskContext.of("GameCharacterRepository", "FindByUserIgnIn", "${userIgns.size}")

        return logicExecutor.executeOrDefault(
            { jpaRepo.findAllByUserIgnIn(userIgns).filter { it.userIgn != null }.associate { requireNotNull(it.userIgn) to it.toDomain() } },
            emptyMap(),
            context,
        )
    }
}
