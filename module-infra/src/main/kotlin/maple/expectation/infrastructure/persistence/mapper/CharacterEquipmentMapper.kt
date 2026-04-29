package maple.expectation.infrastructure.persistence.mapper

import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.equipment.CharacterEquipment
import maple.expectation.core.domain.model.equipment.EquipmentData
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity

/**
 * Mapper for converting between JPA entity and domain entity.
 *
 * <p>This mapper is responsible for:
 *
 * <ul>
 *   <li>Converting JPA entity → Domain entity (for read operations)
 *   <li>Converting Domain entity → JPA entity (for write operations)
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Stateless</b>: All methods are thread-safe
 *   <li><b>Null Safety</b>: Validates input and throws {@link IllegalArgumentException} for null
 *       values
 *   <li><b>Complete Mapping</b>: All fields including {@code updatedAt} are preserved
 *   <li><b>Separation of Concerns</b>: Infrastructure concern isolated from domain layer
 * </ul>
 *
 * @see CharacterEquipmentJpaEntity
 * @see maple.expectation.domain.model.equipment.CharacterEquipment
 */
object CharacterEquipmentMapper {

    /**
     * Converts JPA entity to domain entity.
     *
     * <p>Use this method when loading data from database or cache. Preserves the original {@code
     * updatedAt} timestamp.
     *
     * @param jpaEntity the JPA entity (must not be null)
     * @return domain entity with restored state
     * @throws IllegalArgumentException if jpaEntity is null
     */
    fun toDomain(jpaEntity: CharacterEquipmentJpaEntity): CharacterEquipment {
        requireNotNull(jpaEntity) { "JPA entity cannot be null" }

        return CharacterEquipment.restore(
            CharacterId.of(requireNotNull(jpaEntity.ocid) { "JPA entity ocid must not be null" }),
            EquipmentData.of(requireNotNull(jpaEntity.jsonContent) { "JPA entity jsonContent must not be null" }),
            requireNotNull(jpaEntity.updatedAt) { "JPA entity updatedAt must not be null" },
        )
    }

    /**
     * Converts domain entity to JPA entity for new insertion.
     *
     * <p>Use this method when saving a new entity to database. The JPA entity will have {@code
     * updatedAt} set from the domain entity.
     *
     * <p>ADR-084: Preserves domain entity's updatedAt timestamp instead of using current time. This
     * maintains temporal consistency when mapping domain → JPA.
     *
     * @param domainEntity the domain entity (must not be null)
     * @return JPA entity ready for persistence
     * @throws IllegalArgumentException if domainEntity is null
     */
    fun toJpaEntity(domainEntity: CharacterEquipment): CharacterEquipmentJpaEntity {
        requireNotNull(domainEntity) { "Domain entity cannot be null" }

        val ocid = domainEntity.characterId.value
        val jsonContent = domainEntity.equipmentData.jsonContent()

        // ADR-084: Use factory method to preserve domain timestamp
        return CharacterEquipmentJpaEntity.of(ocid, jsonContent, domainEntity.updatedAt)
    }

    /**
     * Updates existing JPA entity from domain entity.
     *
     * <p>Use this method when updating an existing entity in database. Preserves the JPA entity's
     * identity and updates only the data fields.
     *
     * @param jpaEntity the existing JPA entity to update (must not be null)
     * @param domainEntity the domain entity with new data (must not be null)
     * @throws IllegalArgumentException if either parameter is null
     */
    fun updateJpaEntity(jpaEntity: CharacterEquipmentJpaEntity, domainEntity: CharacterEquipment) {
        requireNotNull(jpaEntity) { "JPA entity cannot be null" }
        requireNotNull(domainEntity) { "Domain entity cannot be null" }

        // Use JPA entity's updateData method to preserve entity lifecycle
        jpaEntity.updateData(domainEntity.equipmentData.jsonContent())
    }

    /**
     * Converts domain entity to JPA entity, reusing existing JPA entity instance.
     *
     * <p>This is an optimized version for batch updates where you want to reuse existing JPA entity
     * instances.
     *
     * @param jpaEntity the existing JPA entity to update
     * @param domainEntity the domain entity with new data
     * @return the updated JPA entity (same instance as jpaEntity parameter)
     */
    fun toJpaEntity(jpaEntity: CharacterEquipmentJpaEntity, domainEntity: CharacterEquipment): CharacterEquipmentJpaEntity {
        updateJpaEntity(jpaEntity, domainEntity)
        return jpaEntity
    }
}
