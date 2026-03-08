package maple.expectation.infrastructure.persistence.repository

import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.equipment.CharacterEquipment
import maple.expectation.domain.repository.CharacterEquipmentRepository as DomainCharacterEquipmentRepository
import maple.expectation.infrastructure.jdbc.JdbcBatchUpsertRepository
import maple.expectation.infrastructure.persistence.CharacterEquipmentJpaRepository
import maple.expectation.infrastructure.persistence.mapper.CharacterEquipmentMapper
import org.springframework.lang.Nullable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * CharacterEquipment JPA Repository Implementation (P1-11: Multi-DataSource Support)
 *
 * <p><strong>Transaction Management:</strong> Uses explicit `"transactionManager"` qualifier
 * to prevent ambiguity in multi-datasource environments. When MongoDB read replicas are added,
 * this repository will continue using the MySQL transaction manager exclusively.
 *
 * @see <a href="../../../../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
@Repository
@Transactional("transactionManager")
open class CharacterEquipmentRepositoryImpl(
    private val jpaRepo: CharacterEquipmentJpaRepository,
    private val jdbcBatchUpsertRepository: JdbcBatchUpsertRepository,
) : DomainCharacterEquipmentRepository {

    @Transactional("transactionManager", readOnly = true)
    @Nullable
    override fun findById(characterId: CharacterId): CharacterEquipment? {
        requireNotNull(characterId) { "CharacterId cannot be null" }
        return jpaRepo.findById(characterId.value)
            .map { CharacterEquipmentMapper.toDomain(it) }
            .orElse(null)
    }

    @Deprecated(" forRemoval = true")
    override fun save(equipment: CharacterEquipment): CharacterEquipment {
        requireNotNull(equipment) { "Equipment cannot be null" }
        val id = equipment.characterId
        val jpaEntity = jpaRepo.findById(id.value).orElse(null)

        return if (jpaEntity != null) {
            CharacterEquipmentMapper.updateJpaEntity(jpaEntity, equipment)
            jpaRepo.save(jpaEntity)
            equipment
        } else {
            val newEntity = CharacterEquipmentMapper.toJpaEntity(equipment)
            jpaRepo.save(newEntity)
            equipment
        }
    }

    override fun saveAll(equipments: List<CharacterEquipment>): List<CharacterEquipment> {
        requireNotNull(equipments) { "Equipments list cannot be null" }
        if (equipments.isEmpty()) {
            return emptyList()
        }
        jdbcBatchUpsertRepository.batchUpsert(equipments)
        return equipments
    }

    override fun deleteById(characterId: CharacterId) {
        requireNotNull(characterId) { "CharacterId cannot be null" }
        jpaRepo.deleteById(characterId.value)
    }

    @Transactional("transactionManager", readOnly = true)
    override fun existsById(characterId: CharacterId): Boolean {
        requireNotNull(characterId) { "CharacterId cannot be null" }
        return jpaRepo.existsById(characterId.value)
    }
}
